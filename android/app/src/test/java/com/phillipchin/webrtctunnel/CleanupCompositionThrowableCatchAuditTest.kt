package com.phillipchin.webrtctunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX8 P2-001-A: a static regression guard for detekt's own `TooGenericExceptionCaught`
 * config (`android/app/detekt.yml`'s `exceptionNames: [Throwable]`), which already flags any
 * `catch (Throwable)` reactively — this test is the committed, permanent proof of the current
 * count rather than relying on detekt alone (which a future config change could silently
 * loosen). Today's codebase has zero `catch (Throwable)` clauses: every composed-cleanup site
 * (`MutationHelpers.kt`'s `withCleanupComposition`) deliberately uses three explicit clauses —
 * `CancellationException`, `Error`, `Exception` — instead of one broad `Throwable` catch, so
 * each stays a normal, specific catch under detekt's own rule. If this project later needs one
 * composed cleanup catch that genuinely must span the whole `Throwable` hierarchy, it should
 * follow that same three-clause shape rather than introducing a bare `catch (Throwable)` —
 * this test's threshold should only ever be raised alongside an explicit, reviewed exception,
 * not silently.
 */
class CleanupCompositionThrowableCatchAuditTest {
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
    fun productionContainsNoCatchThrowableClause() {
        val callPattern = Regex("""catch\s*\(\s*\w+\s*:\s*Throwable\s*\)""")
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
            "production must contain zero `catch (Throwable)` clauses — use the explicit " +
                "CancellationException/Error/Exception three-clause shape instead (see " +
                "MutationHelpers.withCleanupComposition) — found: $hits",
            hits.isEmpty(),
        )
    }
}
