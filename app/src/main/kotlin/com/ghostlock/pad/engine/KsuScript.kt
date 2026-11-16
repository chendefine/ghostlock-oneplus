package com.ghostlock.pad.engine

/**
 * Builds the root-side KernelSU install script executed through the
 * payload's su daemon (`su -c sh <script>`).
 *
 * Adapted from the late-load flow of JoinChang/ghostlock-oneplus and
 * YuKongA/ghostlock-app:
 *  1. skip if the kernelsu module is already loaded
 *  2. `ksud late-load --kmi <KMI>` (KMI derived from `uname -r`)
 *  3. wait for `kernelsu` in /proc/modules
 *  4. register the manager APK with the dynamic manager when available
 *  5. re-enable SELinux enforcing
 */
object KsuScript {
    /** `6.12.58-android16-6-g...` -> `android16-6.12` */
    fun kmiFromRelease(release: String): String? {
        val android = Regex("android\\d+").find(release)?.value ?: return null
        val version = release.split('.').take(2).joinToString(".")
        if (!Regex("^\\d+\\.\\d+$").matches(version)) return null
        return "$android-$version"
    }

    fun build(ksudPath: String, managerPackage: String): String {
        val safeKsud = sanitize(ksudPath)
        val safePkg = sanitize(managerPackage)
        return """
        #!/system/bin/sh
        LOG=${'$'}{LOG:-/data/local/tmp/ghostlock/ksu_script.log}
        diag() { echo "${'$'}*"; echo "${'$'}*" >>"${'$'}LOG"; }
        fail() { diag "[!] ${'$'}*"; exit 1; }
        diag "[*] KernelSU install start uid=$(id -u)"
        if grep -q kernelsu /proc/modules 2>/dev/null; then
          diag "[+] KernelSU already loaded"
        else
          KSUD="$safeKsud"
          if [ ! -f "${'$'}KSUD" ]; then
            fail "ksud not found at ${'$'}KSUD"
          fi
          chmod 755 "${'$'}KSUD" 2>/dev/null
          KVER=$(uname -r | cut -d. -f1-2)
          AVER=$(uname -r | grep -o 'android[0-9]*' | head -1)
          KMI="${'$'}AVER-${'$'}KVER"
          if [ -z "${'$'}AVER" ] || [ -z "${'$'}KVER" ]; then
            fail "cannot derive KMI from $(uname -r)"
          fi
          diag "[*] ksud: ${'$'}KSUD"
          diag "[*] ksud late-load --kmi ${'$'}KMI"
          mkdir -p /data/adb/ksu 2>/dev/null
          setsid "${'$'}KSUD" late-load --kmi "${'$'}KMI" </dev/null >>"${'$'}LOG" 2>&1 &
          KSUD_PID=${'$'}!
          KSUD_EXITED=0
          for w in $(seq 1 30); do
            if grep -q kernelsu /proc/modules 2>/dev/null; then break; fi
            if ! kill -0 "${'$'}KSUD_PID" 2>/dev/null; then KSUD_EXITED=1; break; fi
            sleep 1
          done
          if [ "${'$'}KSUD_EXITED" = 1 ]; then
            wait "${'$'}KSUD_PID"
            diag "[*] ksud exit=${'$'}?"
          fi
        fi
        if ! grep -q kernelsu /proc/modules 2>/dev/null; then
          echo 1 > /sys/fs/selinux/enforce 2>/dev/null
          diag "[!] KernelSU module not loaded; see ${'$'}LOG"
          exit 1
        fi
        diag "[+] KernelSU module loaded:"
        grep kernelsu /proc/modules
        APK=$(pm path "$safePkg" 2>/dev/null | sed 's/package://')
        if [ -n "${'$'}APK" ] && [ -x /data/adb/ksud ]; then
          /data/adb/ksud kernel dynamic-manager set-apk "${'$'}APK" 2>/dev/null \
            && diag "[+] dynamic manager set" \
            || diag "[!] dynamic manager set failed"
        else
          diag "[*] dynamic manager registration skipped"
        fi
        echo 1 > /sys/fs/selinux/enforce 2>/dev/null
        diag "[*] SELinux enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
        diag "[+] done"
        exit 0
        """.trimIndent() + "\n"
    }

    /** Shell single-quote safety for values interpolated into the script. */
    private fun sanitize(value: String): String =
        if (value.all { it.isLetterOrDigit() || it in "/._-" }) {
            value
        } else {
            "'" + value.replace("'", "'\\''") + "'"
        }
}
