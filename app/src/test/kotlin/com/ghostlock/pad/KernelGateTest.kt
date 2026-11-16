package com.ghostlock.pad

import com.ghostlock.pad.engine.KernelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelGateTest {
    @Test
    fun exactKernelIsSupported() {
        assertTrue(KernelGate.isSupported(EngineContract.EXPECTED_KERNEL))
    }

    @Test
    fun nearMissBuildsAreRejected() {
        // Different ab number, different g hash, prefix, suffix: all reject.
        assertFalse(KernelGate.isSupported("6.12.58-android16-6-g7704a1ae279b-ab15213645-4k"))
        assertFalse(KernelGate.isSupported("6.12.58-android16-6-g7704a1ae279c-ab15213644-4k"))
        assertFalse(KernelGate.isSupported("6.12.58-android16-6-g7704a1ae279b-ab15213644"))
        assertFalse(KernelGate.isSupported("6.12.69-android16-6-g000000000000-ab15213644-4k"))
        assertFalse(KernelGate.isSupported("6.12.58-android16-5-g7704a1ae279b-ab15213644-4k"))
        assertFalse(KernelGate.isSupported(""))
        assertFalse(KernelGate.isSupported(null))
    }

    @Test
    fun reasonMentionsTarget() {
        assertTrue(KernelGate.unsupportedReason("x").contains(EngineContract.EXPECTED_KERNEL))
    }
}
