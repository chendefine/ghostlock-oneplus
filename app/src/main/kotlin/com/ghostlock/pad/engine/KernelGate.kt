package com.ghostlock.pad.engine

import com.ghostlock.pad.EngineContract

/**
 * Kernel gate: the bundled payload embeds offsets extracted from one exact
 * firmware build, so the gate is a strict `uname -r` string match.
 */
object KernelGate {
    /** True when [release] is exactly the supported kernel build. */
    fun isSupported(release: String?): Boolean =
        release == EngineContract.EXPECTED_KERNEL

    /** Short human reason shown next to an unsupported kernel. */
    fun unsupportedReason(release: String?): String =
        if (release.isNullOrBlank()) {
            "kernel unknown"
        } else {
            "offsets are firmware-specific; payload targets ${EngineContract.EXPECTED_KERNEL}"
        }
}
