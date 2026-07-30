package com.phillipchin.webrtctunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Fix9SetupFreshnessSourceAuditTest {
    @Test
    fun identityDraftReplacementRemainsInsideFreshPublication() {
        val identity = productionSource("viewmodel/SetupIdentityController.kt").withoutComments()
        val guardedReplacement =
            Regex(
                "token\\.publishIfFresh\\s*\\{[^}]*identityDraft\\.replace\\(",
                RegexOption.DOT_MATCHES_ALL,
            )

        assertEquals(3, Regex("identityDraft\\.replace\\(").findAll(identity).count())
        assertEquals(3, guardedReplacement.findAll(identity).count())
    }

    @Test
    fun forwardDraftMutationRemainsInsideFreshPublication() {
        val forwards = productionSource("viewmodel/SetupForwardsController.kt").withoutComments()
        val upsert =
            functionWindow(
                forwards,
                "fun upsertForward",
                "fun deleteForward",
            )
        val delete =
            functionWindow(
                forwards,
                "fun deleteForward",
                "private fun withUpsert",
            )
        val guardedMutation =
            Regex(
                "token\\.publishIfFresh\\s*\\{[^}]*access\\.setForwards\\(",
                RegexOption.DOT_MATCHES_ALL,
            )

        assertEquals(1, Regex("access\\.setForwards\\(").findAll(upsert).count())
        assertEquals(1, guardedMutation.findAll(upsert).count())
        assertEquals(1, Regex("access\\.setForwards\\(").findAll(delete).count())
        assertEquals(1, guardedMutation.findAll(delete).count())
    }

    @Test
    fun finalCommitAndTunnelStartRetainFreshnessBoundaries() {
        val save = productionSource("viewmodel/SetupSaveController.kt").withoutComments()
        val start =
            functionWindow(
                save,
                "fun startTunnelFromReview",
                "private suspend fun saveAndApplyConfigInternal",
            )
        val guardedSave =
            functionWindow(
                save,
                "private suspend fun saveAndApplyConfigInternal",
                "private suspend fun runSaveAndApply",
            )
        val validation =
            functionWindow(
                save,
                "private suspend fun validateAndCommit(",
                "private suspend fun resolveSaveIdentity",
            )
        val commit =
            functionWindow(
                save,
                "private suspend fun commitSetup(",
                "private fun buildSetupPersistenceRequest",
            )

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
        val guardedReplacement =
            Regex(
                "token\\.publishIfFresh\\s*\\{[^}]*identityDraft\\.replace\\(",
                RegexOption.DOT_MATCHES_ALL,
            )

        assertFalse(guardedReplacement.containsMatchIn(unsafe))
    }

    private fun String.withoutComments(): String =
        replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
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
