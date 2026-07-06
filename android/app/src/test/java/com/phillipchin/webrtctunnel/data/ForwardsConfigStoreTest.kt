package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.ForwardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun saveLeavesNoTempFilesBehind() {
        store.saveForwards(listOf(forward("a", 1111)))
        val temps = context.filesDir.listFiles { f -> f.name.startsWith("forwards") && f.name.endsWith(".tmp") }
        assertTrue(temps.isNullOrEmpty())
    }

    @Test
    fun upsertOnCorruptFileIsRejectedAndDoesNotOverwrite() {
        file.writeText("{ corrupt json")
        val result = store.upsertForward(forward("x", 1234))
        assertFalse(result.valid)
        assertTrue(file.readText().contains("corrupt"))
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
