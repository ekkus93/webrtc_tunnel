package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ForwardsConfigStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = File(context.filesDir, "forwards.json")
    private lateinit var store: ForwardsConfigStore

    @Before
    fun setUp() {
        file.delete()
        store = ForwardsConfigStore(context)
    }

    private fun forward(
        id: String,
        port: Int,
    ) = ForwardConfig(id = id, name = id, localPort = port, remoteForwardId = id, enabled = true)

    @Test
    fun saveAndLoadRoundTrip() {
        val list = listOf(forward("a", 1111), forward("b", 2222))
        store.saveForwards(list)
        assertEquals(list, store.loadForwardsResult().getOrThrow())
    }

    @Test
    fun loadSeedsDefaultsWhenMissing() {
        file.delete()
        assertTrue(store.loadForwardsResult().getOrThrow().isNotEmpty())
    }

    @Test
    fun seededDefaultsUseCanonicalRemoteForwardIds() {
        // The seeded defaults must match the answer-side convention in the repo's example
        // configs (`ssh`, `web-ui`); a non-matching id (e.g. "llama") makes a clean install
        // fail with `unknown_forward` against a docs-configured answer.
        file.delete()
        val remoteIds = store.loadForwardsResult().getOrThrow().map { it.remoteForwardId }.toSet()
        assertEquals(setOf("ssh", "web-ui"), remoteIds)
    }

    @Test
    fun corruptJsonIsFailure() {
        file.writeText("{ this is not valid json")
        assertTrue(store.loadForwardsResult().isFailure)
    }

    @Test
    fun malformedJsonIsParseFailureNotReadFailure() {
        file.writeText("{ this is not valid json")
        val error = store.loadForwardsResult().exceptionOrNull()
        assertTrue(error is ForwardsParseException)
    }

    @Test
    fun unreadableFileIsReadFailureNotParseFailure() {
        file.writeText("[]")
        // Force forwardsFile.readText() itself to fail, as opposed to decoding failing on
        // malformed content, so the two distinct failure modes are provably distinguished
        // (P1-003) rather than both being reported as "corrupt".
        assertTrue(file.setReadable(false))
        try {
            val error = store.loadForwardsResult().exceptionOrNull()
            assertTrue(error is ForwardsReadException)
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun loadForwardsResultReturnsFailureWithoutThrowingWhenSeedWriteFails() {
        file.delete()
        // Force the missing-file default-seeding write inside loadForwardsResult() to fail,
        // by making its target directory read-only, instead of throwing past the Result
        // contract (P1-002).
        assertTrue(context.filesDir.setReadOnly())
        try {
            val result = store.loadForwardsResult()
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ForwardsWriteException)
        } finally {
            context.filesDir.setWritable(true)
        }
    }

    @Test
    fun saveForwardsWrapsUnderlyingFailureAsForwardsWriteException() {
        assertTrue(context.filesDir.setReadOnly())
        try {
            val error = runCatching { store.saveForwards(listOf(forward("a", 1111))) }.exceptionOrNull()
            assertTrue(error is ForwardsWriteException)
        } finally {
            context.filesDir.setWritable(true)
        }
    }

    @Test
    fun saveLeavesNoTempFilesBehind() {
        store.saveForwards(listOf(forward("a", 1111)))
        val temps = context.filesDir.listFiles { f -> f.name.startsWith("forwards") && f.name.endsWith(".tmp") }
        assertTrue(temps.isNullOrEmpty())
    }

    @Test
    fun saveReplacesExistingFileContents() {
        store.saveForwards(listOf(forward("a", 1111)))
        store.saveForwards(listOf(forward("b", 2222)))
        val loaded = store.loadForwardsResult().getOrThrow()
        assertEquals(1, loaded.size)
        assertEquals("b", loaded.first().id)
    }
}
