package com.phillipchin.webrtctunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Fix9SetupFreshnessSourceAuditTest {
    @Test
    fun identityDraftReplacementRemainsInsideFreshPublication() {
        val identity = productionSource("viewmodel/SetupIdentityController.kt")

        assertTrue(identity.countOccurrences("identityDraft.replace(") >= 3)
        assertTrue(allOccurrencesInsideBlock(identity, "identityDraft.replace(", "token.publishIfFresh"))
    }

    @Test
    fun forwardDraftMutationRemainsInsideFreshPublication() {
        val forwards = productionSource("viewmodel/SetupForwardsController.kt")
        val upsert = functionWindow(forwards, "fun upsertForward", "fun deleteForward")
        val delete = functionWindow(forwards, "fun deleteForward", "private fun withUpsert")

        assertTrue(allOccurrencesInsideBlock(upsert, "access.setForwards(", "token.publishIfFresh"))
        assertTrue(allOccurrencesInsideBlock(delete, "access.setForwards(", "token.publishIfFresh"))
    }

    @Test
    fun finalCommitAndTunnelStartRetainFreshnessBoundaries() {
        val save = productionSource("viewmodel/SetupSaveController.kt")
        val start = functionWindow(save, "fun startTunnelFromReview", "private suspend fun saveAndApplyConfigInternal")
        val guardedSave = functionWindow(save, "private suspend fun saveAndApplyConfigInternal", "private suspend fun runSaveAndApply")
        val validation = functionWindow(save, "private suspend fun validateAndCommit(", "private suspend fun resolveSaveIdentity")
        val commit = functionWindow(save, "private suspend fun commitSetup(", "private fun buildSetupPersistenceRequest")

        assertTrue(
            start.contains(
                "saveAndApplyConfigInternal {\n" +
                    "                ContextCompat.startForegroundService(",
            ),
        )
        assertTrue(
            guardedSave.contains(
                "if (saved && token.isFresh()) {\n" +
                    "                        onFreshSuccess?.invoke()",
            ),
        )
        assertTrue(
            Regex(
                "throwIfStale\\(token\\).*commitSetup\\(.*throwIfStale\\(token\\)",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(validation),
        )
        assertTrue(
            commit.contains(
                "throwIfStale(commitContext.token)\n" +
                    "        val result = withContext(ioDispatcher) { persistence.persist(request) }",
            ),
        )
    }

    @Test
    fun freshnessDetectorRejectsUnguardedMutationFixture() {
        val unsafe = "fun mutate() { identityDraft.replace(bytes, public, peer) }"

        assertFalse(allOccurrencesInsideBlock(unsafe, "identityDraft.replace(", "token.publishIfFresh"))
    }

    private fun allOccurrencesInsideBlock(
        source: String,
        mutation: String,
        guard: String,
    ): Boolean {
        val code = withoutComments(source)
        var mutationIndex = code.indexOf(mutation)
        if (mutationIndex < 0) return false
        while (mutationIndex >= 0) {
            val guardIndex = code.lastIndexOf(guard, mutationIndex)
            if (guardIndex < 0) return false
            val blockStart = code.indexOf('{', guardIndex)
            if (blockStart < 0 || blockStart >= mutationIndex) return false
            val blockEnd = matchingBrace(code, blockStart)
            if (blockEnd < mutationIndex) return false
            mutationIndex = code.indexOf(mutation, mutationIndex + mutation.length)
        }
        return true
    }

    private fun matchingBrace(
        source: String,
        start: Int,
    ): Int {
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun withoutComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    private fun functionWindow(
        source: String,
        start: String,
        end: String,
    ): String {
        val startIndex = source.indexOf(start)
        require(startIndex >= 0) { "missing function start: $start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(endIndex > startIndex) { "missing function end: $end" }
        return source.substring(startIndex, endIndex)
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var index = indexOf(value)
        while (index >= 0) {
            count += 1
            index = indexOf(value, index + value.length)
        }
        return count
    }

    private fun productionSource(relativePath: String): String {
        val root = "src/main/java/com/phillipchin/webrtctunnel"
        val candidates =
            listOf(
                File(root, relativePath),
                File("app/$root", relativePath),
                File(System.getProperty("user.dir"), "app/$root/$relativePath"),
            )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("source file not found: $relativePath")
    }
}
