package com.phillipchin.webrtctunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX8 P1-003-A/D: a static regression guard, not a runtime one. The FIX8 audit removed every
 * production `runCatching` (each was either a synchronous parse/read masquerading as safe, or a
 * `System.loadLibrary` call that should only normalize `UnsatisfiedLinkError`) in favor of an
 * explicit, cancellation-first `try/catch (Exception)`. Unlike FIX7's `RunCatchingInventoryTest`,
 * this carries no "approved" marker/comment escape hatch — zero production `runCatching` is
 * tolerated, full stop.
 */
class ProductionRunCatchingAuditTest {
    private fun mainSourceRoot(): File {
        val path = "src/main/java/com/phillipchin/webrtctunnel"
        val candidates =
            listOf(
                File(path),
                File("app/$path"),
                File(System.getProperty("user.dir"), path),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("main source root not found from ${File(".").absolutePath}")
    }

    @Test
    fun productionContainsNoRunCatchingCall() {
        val callPattern = Regex("""runCatching\s*\{""")
        val hits = mutableListOf<String>()

        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val isCommentLine = trimmed.startsWith("//") || trimmed.startsWith("*")
                    if (!isCommentLine && callPattern.containsMatchIn(line)) {
                        hits += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "production must contain zero `runCatching` calls (FIX8 P1-003-A) — found: $hits",
            hits.isEmpty(),
        )
    }
}
