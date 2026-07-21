package com.phillipchin.webrtctunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX7 P1-005-B/E: a source-level regression guard, not a runtime one — the audit found and
 * fixed every unsafe production `runCatching` (suspend orchestration, persistence/rollback/
 * native cleanup) in this codebase, converting them to an explicit cancellation-first
 * `try/catch(Exception)`. The handful that remain are deliberately synchronous, non-mutating,
 * non-native-call utility sites, each carrying a `FIX7 P1-005-B: safe as runCatching` comment
 * explaining why. This test fails if a *new*, unreviewed `runCatching` is ever added to
 * production Kotlin without that comment nearby — forcing a future author to either justify it
 * the same way or convert it to the explicit pattern, rather than silently reintroducing the
 * swallowed-cancellation/fatal-Error risk this task eliminated.
 */
class RunCatchingInventoryTest {
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
    fun retainedRunCatchingInventoryContainsOnlyApprovedSynchronousSites() {
        val marker = "P1-005-B: safe as runCatching"
        val callPattern = Regex("""runCatching\s*\{""")
        val unapproved = mutableListOf<String>()

        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val isCommentLine = trimmed.startsWith("//") || trimmed.startsWith("*")
                    if (!isCommentLine && callPattern.containsMatchIn(line)) {
                        val windowStart = (index - 6).coerceAtLeast(0)
                        val window = lines.subList(windowStart, index + 1).joinToString("\n")
                        if (!window.contains(marker)) {
                            unapproved += "${file.path}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
            }

        assertTrue(
            "every retained runCatching must carry a '$marker' comment justifying it as a " +
                "synchronous, non-mutation, non-native-call site — found unapproved: $unapproved",
            unapproved.isEmpty(),
        )
    }
}
