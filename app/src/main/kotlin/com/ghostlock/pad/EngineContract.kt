package com.ghostlock.pad

/**
 * Shared constants for the engine pipeline. The app side and the Shizuku
 * UserService side both read these; they must never diverge.
 */
object EngineContract {
    /**
     * The one kernel build the bundled preload payload was generated for.
     * The payload's offsets are firmware-build-specific; running it on any
     * other kernel can panic the device, so the gate is an exact string
     * match on `uname -r`.
     *
     * Validated upstream on OPPO Find X9 Ultra (PMA110/CPH2841) and shared
     * by the OnePlus Pad 3 Pro firmware line.
     */
    const val EXPECTED_KERNEL = "6.12.58-android16-6-g7704a1ae279b-ab15213644-4k"

    /** Shell-side working directory; everything staged lives here. */
    const val WORK_DIR = "/data/local/tmp/ghostlock"

    /** Staged copy of the bundled payload (LD_PRELOAD target). */
    const val PRELOAD_PATH = "$WORK_DIR/preload.so"

    /** Embedded su client installed by the payload after gaining root. */
    const val SU_PATH = "/data/local/tmp/su"

    /** su daemon socket installed by the payload. */
    const val SU_SOCKET = "/data/local/tmp/temp_su.sock"

    /** KernelSU install script written by the engine before phase "ksu". */
    const val KSU_SCRIPT_PATH = "$WORK_DIR/ksu_install.sh"

    /** Exploit output log (payload writes unbuffered stdout/stderr). */
    const val EXPLOIT_LOG = "$WORK_DIR/exploit.log"

    /** KernelSU install console output log (script-internal log is ksu_script.log). */
    const val KSU_LOG = "$WORK_DIR/ksu_install.log"

    /** Bundled native payload name inside the APK's nativeLibraryDir. */
    const val BUNDLED_PRELOAD_LIB = "libghostlock_preload.so"

    /** Phases reported through IEngineCallback.onPhase. */
    const val PHASE_STAGE = "stage"
    const val PHASE_EXPLOIT = "exploit"
    const val PHASE_VERIFY = "verify"
    const val PHASE_KSU = "ksu"
    const val PHASE_DONE = "done"

    /** Exit codes reported through IEngineCallback.onComplete. */
    const val EXIT_OK_ROOT = 0
    const val EXIT_FAILED = 1
}
