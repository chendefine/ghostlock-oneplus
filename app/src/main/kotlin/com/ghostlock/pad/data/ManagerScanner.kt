package com.ghostlock.pad.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

/** Finds KernelSU-family root managers and orders them for ksud resolution. */
object ManagerScanner {
    /** Built-in KernelSU-family managers, priority order. */
    val BUILTIN = listOf(
        "me.weishu.kernelsu.pr",
        "me.weishu.kernelsu",
        "com.sukisu.ultra.pr",
        "com.sukisu.ultra",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.kowx712.supermanager",
    )

    data class Candidate(
        val packageName: String,
        val label: String,
        val hasKsud: Boolean,
        val builtin: Boolean,
    )

    /**
     * Managers worth showing: KernelSU-family built-ins plus any app that
     * actually bundles libksud.so. Must be called OFF the main thread: with
     * the OEM "read app list" grant the system returns the full package
     * list, and probing it (resources + stat per package) is slow.
     *
     * Labels are only loaded for kept candidates — loading a label means
     * loading another app's resources, which is the expensive part when
     * hundreds of packages are visible.
     */
    @Suppress("DEPRECATION")
    fun scan(pm: PackageManager, selfPackage: String): List<Candidate> =
        runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { pi -> pi.applicationInfo?.let { pi to it } }
            .filter { (pi, _) -> pi.packageName != selfPackage }
            .filter { (_, ai) -> (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { (pi, ai) ->
                val pkg = pi.packageName
                // Per-package isolation: one broken entry must not drop the
                // whole scan result.
                runCatching {
                    val dir = ai.nativeLibraryDir
                    val hasKsud = dir != null && File(dir, "libksud.so").isFile
                    if (!hasKsud && pkg !in BUILTIN) return@runCatching null
                    Candidate(
                        packageName = pkg,
                        label = runCatching { ai.loadLabel(pm).toString() }
                            .getOrDefault(pkg),
                        hasKsud = hasKsud,
                        builtin = pkg in BUILTIN,
                    )
                }.getOrNull()
            }
            .sortedWith(sortComparator())
            .toList()

    /** Cheap probe of the known managers only; safe-ish but still call off-main. */
    @Suppress("DEPRECATION")
    fun probeBuiltins(pm: PackageManager): List<Candidate> =
        BUILTIN.mapNotNull { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                val dir = ai.nativeLibraryDir
                val hasKsud = dir != null && File(dir, "libksud.so").isFile
                Candidate(
                    packageName = pkg,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg),
                    hasKsud = hasKsud,
                    builtin = true,
                )
            }.getOrNull()
        }

    /** Pure sort, extracted so it is testable without a PackageManager. */
    fun sortComparator() = compareByDescending<Candidate> { it.builtin && it.hasKsud }
        .thenByDescending { it.hasKsud }
        .thenBy { it.label.lowercase() }

    /** Chosen package first, then the rest of the built-ins in priority order. */
    fun resolvePackages(chosen: String?): List<String> =
        if (chosen.isNullOrBlank()) BUILTIN
        else listOf(chosen) + BUILTIN.filter { it != chosen }
}
