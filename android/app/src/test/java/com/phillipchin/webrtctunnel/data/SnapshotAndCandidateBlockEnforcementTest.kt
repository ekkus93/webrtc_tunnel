package com.phillipchin.webrtctunnel.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX8 P2-001-B: three static regression guards for invariants this codebase's production
 * source is already clean of (confirmed by audit before writing these), so this is enforcement
 * against future regression, not a fix to a current violation:
 *
 * 1. No production `snapshot.bytes ?: ByteArray(0)` (or equivalent null-coalesced empty-array
 *    fallback) — an exact snapshot's absence/presence must be handled via its own `existed`
 *    flag ([ExactFileSnapshot]), never papered over with a fabricated empty array that would
 *    make "absent" and "present-and-empty" indistinguishable on restore.
 * 2. No setup/config rollback using `.orEmpty()` on snapshot/rollback data — same reasoning:
 *    an exact byte-level snapshot must never be silently downgraded to a default/empty value.
 * 3. No authoritative config write call inside a `withCandidateFile`/`withTemporaryDirectory`
 *    block — those blocks exist specifically for candidate-only, pre-commit validation; the
 *    real write must happen only after the block returns and its cleanup has already succeeded
 *    (FIX8 P0-005-A/CRITICAL-4).
 */
class SnapshotAndCandidateBlockEnforcementTest {
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

    private fun scanLines(pattern: Regex): List<String> {
        val hits = mutableListOf<String>()
        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val isCommentLine = trimmed.startsWith("//") || trimmed.startsWith("*")
                    if (!isCommentLine && pattern.containsMatchIn(line)) {
                        hits += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        return hits
    }

    @Test
    fun noFabricatedEmptyByteArrayFallbackForAnExactSnapshot() {
        val hits = scanLines(Regex("""\.bytes\s*\?:\s*ByteArray\s*\("""))
        assertTrue(
            "an exact snapshot's absence must be handled via its own `existed` flag, never a " +
                "fabricated empty ByteArray fallback that makes absent/present-and-empty " +
                "indistinguishable on restore — found: $hits",
            hits.isEmpty(),
        )
    }

    @Test
    fun noOrEmptyFallbackOnRollbackOrSnapshotData() {
        // Scoped to files that actually deal in exact snapshot/rollback/restore data — a bare
        // textual `.orEmpty()` scan across the whole app would also catch unrelated, legitimate
        // uses (e.g. a nullable UI display string), which is not what this invariant is about.
        val snapshotFiles =
            listOf(
                "ExactFileSnapshot.kt",
                "TransactionalReset.kt",
                "SetupPersistenceCoordinator.kt",
                "ForwardsRepository.kt",
                "ForwardsConfigStore.kt",
                "IdentityRepository.kt",
                "BrokerSecretRepository.kt",
            )
        val hits = mutableListOf<String>()
        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name in snapshotFiles }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val isCommentLine = trimmed.startsWith("//") || trimmed.startsWith("*")
                    if (!isCommentLine && line.contains(".orEmpty()")) {
                        hits += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "an exact snapshot/rollback value must never be silently downgraded via `.orEmpty()` " +
                "— found: $hits",
            hits.isEmpty(),
        )
    }

    @Test
    fun noAuthoritativeConfigWriteInsideACandidateOrWorkspaceBlock() {
        val hits =
            mainSourceRoot().walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> candidateBlockViolationsIn(file).asSequence() }
                .toList()

        assertTrue(
            "an authoritative config/forwards write must never appear inside a " +
                "withCandidateFile/withTemporaryDirectory block — the real write must happen only " +
                "after the block returns and its cleanup has already succeeded — found: $hits",
            hits.isEmpty(),
        )
    }

    private fun candidateBlockViolationsIn(file: File): List<String> {
        val blockStartPattern = Regex("""(withCandidateFile|withTemporaryDirectory)\s*\(""")
        val text = file.readText()
        return blockStartPattern.findAll(text)
            // Only a genuine call site — this file's own declaration of these functions is a
            // `fun withCandidateFile(`, not a call, and must be excluded.
            .filterNot { match -> text.substring(0, match.range.first).trimEnd().endsWith("fun") }
            .mapNotNull { match -> blockBodyRange(text, match.range.last) }
            .flatMap { (braceStart, braceEnd) -> authoritativeWriteHitsIn(file, text, braceStart, braceEnd) }
            .toList()
    }

    /** The `{...}` block body range starting at or after [searchFrom], or null if unbalanced. */
    private fun blockBodyRange(
        text: String,
        searchFrom: Int,
    ): Pair<Int, Int>? {
        val braceStart = text.indexOf('{', searchFrom)
        if (braceStart < 0) return null
        val braceEnd = matchingBraceEnd(text, braceStart)
        return if (braceEnd > braceStart) braceStart to braceEnd else null
    }

    /** Index of the `}` that closes the `{` at [braceStart], or -1 if unbalanced. */
    private fun matchingBraceEnd(
        text: String,
        braceStart: Int,
    ): Int {
        var depth = 0
        for (i in braceStart until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun authoritativeWriteHitsIn(
        file: File,
        text: String,
        braceStart: Int,
        braceEnd: Int,
    ): List<String> {
        val authoritativeWritePattern =
            Regex("""\b(writeConfigAtomically|replaceConfigTransactionally|saveForwards|saveConfig)\s*\(""")
        val blockBody = text.substring(braceStart, braceEnd)
        return authoritativeWritePattern.findAll(blockBody).map { writeMatch ->
            val offset = braceStart + writeMatch.range.first
            val line = text.substring(0, offset).count { it == '\n' } + 1
            "${file.path}:$line: ${writeMatch.value}"
        }.toList()
    }
}
