package com.ghostlock.pad

import com.ghostlock.pad.engine.KsuScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KsuScriptTest {
    @Test
    fun kmiDerivation() {
        assertEquals("android16-6.12", KsuScript.kmiFromRelease(EngineContract.EXPECTED_KERNEL))
        assertEquals(
            "android15-6.6",
            KsuScript.kmiFromRelease("6.6.89-android15-8-gabcdef123-ab12345678-4k"),
        )
        assertNull(KsuScript.kmiFromRelease("no-android-here"))
        assertNull(KsuScript.kmiFromRelease("android16"))
    }

    @Test
    fun scriptShape() {
        val script = KsuScript.build(
            "/data/app/~~a==/com.resukisu.resukisu-Ab==/lib/arm64/libksud.so",
            "com.resukisu.resukisu",
        )
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("late-load --kmi"))
        assertTrue(script.contains("grep -q kernelsu /proc/modules"))
        assertTrue(script.contains("/sys/fs/selinux/enforce"))
        assertTrue(script.contains("pm path"))
        // Runtime values are expanded by the device shell, not by the builder:
        // the produced script keeps its own shell defaults and no Kotlin
        // template escape source text leaks into it.
        assertTrue(script.contains("\${LOG:-"))
        assertFalse(script.contains("'\$'"))
    }

    @Test
    fun managerPackageIsSanitized() {
        val hostile = "pkg'; rm -rf /; '"
        val script = KsuScript.build("/data/local/tmp/x", hostile)
        // The hostile sequence must not survive verbatim inside a double-quoted context.
        assertFalse(script.contains("path \"$hostile\""))
    }

    @Test
    fun scriptPassesShellSyntaxCheck() {
        val sh = File("/bin/sh")
        if (!sh.exists()) return // host-only check; CI runners have bash/sh
        val script = KsuScript.build(
            "/data/app/~~a==/com.resukisu.resukisu-Ab==/lib/arm64/libksud.so",
            "com.resukisu.resukisu",
        )
        val tmp = kotlin.io.path.createTempFile("ksu", ".sh").toFile()
        try {
            tmp.writeText(script)
            val process = ProcessBuilder("sh", "-n", tmp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString()
            val code = process.waitFor()
            assertTrue("sh -n reported (code=$code): $output", code == 0)
        } finally {
            tmp.delete()
        }
    }
}
