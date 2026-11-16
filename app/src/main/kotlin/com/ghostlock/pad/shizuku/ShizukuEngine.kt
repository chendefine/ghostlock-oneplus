package com.ghostlock.pad.shizuku

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.ghostlock.pad.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

enum class ShizukuStatus { NOT_RUNNING, PERMISSION_REQUIRED, READY }

/** Binds the engine UserService through Shizuku and streams its callbacks. */
class ShizukuEngine(private val context: Context) {
    private var statusListener: (() -> Unit)? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { statusListener?.invoke() }
    private val binderDead = Shizuku.OnBinderDeadListener { statusListener?.invoke() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST) statusListener?.invoke()
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun status(): ShizukuStatus = try {
        if (!Shizuku.pingBinder() || Shizuku.getUid() != Process.SHELL_UID) {
            ShizukuStatus.NOT_RUNNING
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus.PERMISSION_REQUIRED
        } else {
            ShizukuStatus.READY
        }
    } catch (_: Throwable) {
        ShizukuStatus.NOT_RUNNING
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        } else {
            statusListener?.invoke()
        }
    }

    fun setStatusListener(listener: (() -> Unit)?) {
        statusListener = listener
    }

    /** One probe round-trip; returns the service's key=value report. */
    suspend fun probe(): String? {
        val statusNow = status()
        if (statusNow != ShizukuStatus.READY) return null
        return withService { service -> service.probe() }
    }

    /**
     * One bound connection. Returns the exploit's exit code via the callback
     * wrapper, or null when the UserService never connected or dropped.
     */
    suspend fun run(
        managerPackage: String?,
        onLog: (String) -> Unit,
        onPhase: (String) -> Unit,
        onComplete: (Int, String) -> Unit,
    ) {
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = connectAndRun(managerPackage, onLog, onPhase, onComplete)
            if (result != null) return
            if (attempt < MAX_ATTEMPTS) {
                onLog("<s> retrying UserService connection ($attempt/$MAX_ATTEMPTS)")
            }
        }
        onLog("<s> error: Shizuku UserService did not connect after $MAX_ATTEMPTS attempts")
        onComplete(1, "no-connection")
    }

    private suspend fun withService(block: (IEngineService) -> String): String? =
        suspendCancellableCoroutine { continuation ->
            val args = userServiceArgs()
            val handler = Handler(Looper.getMainLooper())
            val connected = AtomicBoolean(false)
            var done = false
            lateinit var connection: ServiceConnection
            val timeout = Runnable {
                if (!connected.get()) {
                    done = true
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    connected.set(true)
                    handler.removeCallbacks(timeout)
                    val reply = runCatching { block(IEngineService.Stub.asInterface(binder)) }.getOrNull()
                    done = true
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    if (continuation.isActive) continuation.resume(reply)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    handler.removeCallbacks(timeout)
                    if (done) return
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                runCatching { Shizuku.unbindUserService(args, connection, false) }
            }
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure {
                    done = true
                    if (continuation.isActive) continuation.resume(null)
                }
            handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        }

    private suspend fun connectAndRun(
        managerPackage: String?,
        onLog: (String) -> Unit,
        onPhase: (String) -> Unit,
        onComplete: (Int, String) -> Unit,
    ): Unit? = suspendCancellableCoroutine { continuation ->
        val args = userServiceArgs()
        val handler = Handler(Looper.getMainLooper())
        val connected = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        val connectTimeout = Runnable {
            if (!connected.get() && continuation.isActive) {
                completed.set(true)
                onLog("<s> error: Shizuku UserService did not connect within ${CONNECT_TIMEOUT_MS / 1000}s")
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                continuation.resume(null)
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.set(true)
                handler.removeCallbacks(connectTimeout)
                onLog("<b> Shizuku UserService connected")
                val service = IEngineService.Stub.asInterface(binder)
                val callback = object : IEngineCallback.Stub() {
                    override fun onLog(line: String) = onLog(line)

                    override fun onPhase(phase: String) = onPhase(phase)

                    override fun onComplete(exitCode: Int, summary: String) {
                        completed.set(true)
                        handler.removeCallbacks(connectTimeout)
                        runCatching { Shizuku.unbindUserService(args, connection, true) }
                        if (continuation.isActive) continuation.resume(Unit)
                        onComplete(exitCode, summary)
                    }
                }
                runCatching {
                    service.run(
                        com.ghostlock.pad.EngineContract.EXPECTED_KERNEL,
                        managerPackage,
                        callback,
                    )
                }.onFailure { error ->
                    completed.set(true)
                    handler.removeCallbacks(connectTimeout)
                    onLog("<s> error: ${error.message}")
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    if (continuation.isActive) continuation.resume(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                handler.removeCallbacks(connectTimeout)
                if (completed.get()) return
                onLog("<b> Shizuku UserService disconnected (device reboot?)")
                if (continuation.isActive) continuation.resume(null)
            }
        }
        continuation.invokeOnCancellation {
            handler.removeCallbacks(connectTimeout)
            runCatching { Shizuku.unbindUserService(args, connection, false) }
        }
        onLog("<b> binding UserService")
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { error ->
                completed.set(true)
                onLog("<s> error: cannot bind Shizuku UserService: ${error.message}")
                if (continuation.isActive) continuation.resume(null)
            }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("RestrictedApi")
    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, EngineUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("ghostlock")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)

    fun close() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        statusListener = null
    }

    companion object {
        private const val PERMISSION_REQUEST = 4816

        /** Bound on one UserService connection attempt; the exploit itself is not capped. */
        private const val CONNECT_TIMEOUT_MS = 5_000L

        private const val MAX_ATTEMPTS = 3
    }
}
