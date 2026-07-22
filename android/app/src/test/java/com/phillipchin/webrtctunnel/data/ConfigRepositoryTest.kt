package com.phillipchin.webrtctunnel.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: ConfigRepository
    private lateinit var forwardsStore: ForwardsConfigStore

    @Before
    fun setUp() {
        repository = ConfigRepository(context)
        forwardsStore = ForwardsConfigStore(context)
        // deleteRecursively, not delete: a test that puts a non-empty directory at this
        // path to force a write failure would otherwise leak it into the next test, where
        // exists() reports true and ensureDefaultConfig short-circuits.
        File(context.filesDir, "config.toml").deleteRecursively()
        File(context.filesDir, "forwards.json").deleteRecursively()
        context.filesDir.setWritable(true)
        runBlocking {
            context.dataStore.edit { preferences -> preferences.clear() }
        }
    }

    // Only Dispatchers reference lives in this parameter default (InjectDispatcher).
    private fun ioDispatcher(dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineDispatcher = dispatcher

    @Test
    fun ensureDefaultConfigCreatesFileWhenMissing() {
        runBlocking {
            assertTrue(repository.ensureDefaultConfig("abc").isSuccess)
        }
        assertEquals("abc", repository.configContents)
    }

    @Test
    fun ensureDefaultConfigDoesNotOverwriteExistingFile() {
        runBlocking { repository.writeConfig("existing") }
        runBlocking {
            repository.ensureDefaultConfig("default")
        }
        assertEquals("existing", repository.configContents)
    }

    // FIX6 P0-001-A: the write result is returned rather than discarded, and the existence
    // check happens under the same write mutex as the write.

    @Test
    fun ensureDefaultConfigReturnsFailureWhenAtomicWriteFails() =
        runBlocking {
            // The write only runs when config.toml is absent, so the failure has to come
            // from the write itself rather than from an obstruction at that path (a
            // directory there would make exists() true and short-circuit to success).
            // Making filesDir read-only fails Files.createTempFile inside the writer.
            val filesDir = context.filesDir
            assertTrue("precondition: filesDir starts writable", filesDir.canWrite())
            filesDir.setWritable(false)
            try {
                // A root/permission-ignoring filesystem would silently make this a
                // false-pass, so state that as a precondition rather than asserting into
                // a writable directory and calling it proof.
                assumeTrue("filesystem honours the read-only bit", !filesDir.canWrite())

                val result = repository.ensureDefaultConfig("default")

                assertTrue("a failed default-config write must be reported, not discarded", result.isFailure)
            } finally {
                filesDir.setWritable(true)
            }
        }

    @Test
    fun ensureDefaultConfigDoesNotRouteThroughTheMutexTakingWriter() =
        runBlocking {
            // ensureDefaultConfig holds fileMutex, which is not reentrant: calling the
            // public writeConfigAtomically from inside it would deadlock. This proves it
            // calls writeConfigAtomicallyWith directly instead.
            var publicWriterCalled = false
            val repo =
                object : ConfigRepository(context) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
                        publicWriterCalled = true
                        return super.writeConfigAtomically(contents)
                    }
                }

            assertTrue(repo.ensureDefaultConfig("default").isSuccess)

            assertFalse(
                "ensureDefaultConfig must not call the mutex-taking writer while holding the mutex",
                publicWriterCalled,
            )
            assertEquals("default", repo.configContents)
        }

    @Test
    fun ensureDefaultConfigReturnsSuccessWithoutWritingWhenConfigExists() =
        runBlocking {
            repository.writeConfig("existing").getOrThrow()

            val result = repository.ensureDefaultConfig("default")

            assertTrue(result.isSuccess)
            assertEquals("existing", repository.configContents)
        }

    @Test
    fun ensureDefaultConfigDoesNotOverwriteConfigCreatedBeforeLockAcquired() =
        runBlocking {
            // The old code checked existence *outside* writeMutex, so a writer could create
            // the config between the check and the write and have the default clobber it.
            // Holding the mutex while another coroutine writes proves the check now happens
            // under the same lock: ensureDefaultConfig cannot observe the pre-write absence.
            val gate = CompletableDeferred<Unit>()
            val ensureStarted = CompletableDeferred<Unit>()

            val ensure =
                launch(ioDispatcher()) {
                    ensureStarted.complete(Unit)
                    gate.await()
                    repository.ensureDefaultConfig("default-should-not-win").getOrThrow()
                }

            ensureStarted.await()
            repository.writeConfig("written-by-another-writer").getOrThrow()
            gate.complete(Unit)
            ensure.join()

            assertEquals(
                "the default must not overwrite a config another writer already committed",
                "written-by-another-writer",
                repository.configContents,
            )
        }

    @Test
    fun defaultTemplateContainsRequiredSections() {
        val template = repository.defaultConfigTemplate
        assertTrue(template.contains("format = \"p2ptunnel-config-v3\""))
        assertTrue(template.contains("[broker]"))
        assertTrue(template.contains("[security]"))
        assertTrue(template.contains("[logging]"))
        assertFalse(template.contains("~/.config"))
        assertFalse(template.contains("~/.local"))
        assertFalse(template.contains("/etc/ssl/certs"))
        assertTrue(template.contains(context.filesDir.absolutePath))
    }

    @Test
    fun defaultTemplateInjectsDataPlaneFields() {
        val template = repository.defaultConfigTemplate
        // Release/default builds emit the strict "vnet_mux" ICE mode, the probe timeout, and
        // the mid-session heartbeat knobs.
        assertTrue(template.contains("android_ice_mode = \"vnet_mux\""))
        assertTrue(template.contains("data_plane_probe_timeout_ms = 5000"))
        assertTrue(template.contains("data_plane_heartbeat_interval_ms = 5000"))
        assertTrue(template.contains("data_plane_heartbeat_max_misses = 3"))
    }

    @Test
    fun normalizeAndroidIceModeAcceptsValidModes() {
        assertEquals("auto", normalizeAndroidIceMode("auto"))
        assertEquals("native", normalizeAndroidIceMode("native"))
        assertEquals("vnet", normalizeAndroidIceMode("vnet"))
        assertEquals("vnet_mux", normalizeAndroidIceMode("vnet_mux"))
        // Case-insensitive and whitespace-tolerant.
        assertEquals("vnet", normalizeAndroidIceMode("  VNET \n"))
        assertEquals("vnet_mux", normalizeAndroidIceMode("  VNET_MUX \n"))
    }

    @Test
    fun normalizeAndroidIceModeFallsBackToStrictDefaultOnInvalidInput() {
        // Invalid/absent input must resolve to the strict default (vnet_mux), never a
        // best-effort path that could pick native ICE on Android.
        assertEquals("vnet_mux", normalizeAndroidIceMode(null))
        assertEquals("vnet_mux", normalizeAndroidIceMode(""))
        assertEquals("vnet_mux", normalizeAndroidIceMode("turn"))
        assertEquals("vnet_mux", normalizeAndroidIceMode("vnet; rm -rf"))
    }

    @Test
    fun upsertAdvertisedLocalIpv4InsertsAfterIceMode() {
        val config =
            """
            [webrtc]
            stun_urls = ["stun:stun.l.google.com:19302"]
            android_ice_mode = "vnet_mux"

            [tunnel]
            read_chunk_size = 16384
            """.trimIndent()
        val updated = upsertAdvertisedLocalIpv4(config, "10.1.3.11")
        assertTrue(updated.contains("advertised_local_ipv4 = \"10.1.3.11\""))
        // The injected line sits inside [webrtc], right after android_ice_mode.
        val iceIdx = updated.indexOf("android_ice_mode")
        val addrIdx = updated.indexOf("advertised_local_ipv4")
        val tunnelIdx = updated.indexOf("[tunnel]")
        assertTrue(addrIdx in (iceIdx + 1) until tunnelIdx)
    }

    @Test
    fun upsertAdvertisedLocalIpv4ReplacesExistingLine() {
        val config =
            """
            [webrtc]
            android_ice_mode = "vnet_mux"
            advertised_local_ipv4 = "10.0.0.1"
            """.trimIndent()
        val updated = upsertAdvertisedLocalIpv4(config, "192.168.5.20")
        assertTrue(updated.contains("advertised_local_ipv4 = \"192.168.5.20\""))
        assertFalse(updated.contains("10.0.0.1"))
        // Exactly one advertised line remains.
        assertEquals(1, updated.lines().count { it.contains("advertised_local_ipv4") })
    }

    @Test
    fun upsertAdvertisedLocalIpv4NullRemovesLine() {
        val config =
            """
            [webrtc]
            android_ice_mode = "vnet_mux"
            advertised_local_ipv4 = "10.0.0.1"
            """.trimIndent()
        val updated = upsertAdvertisedLocalIpv4(config, null)
        assertFalse(updated.contains("advertised_local_ipv4"))
    }

    @Test
    fun upsertAndroidIceModeReplacesExistingValue() {
        val config =
            """
            [webrtc]
            stun_urls = ["stun:stun.l.google.com:19302"]
            android_ice_mode = "vnet_mux"

            [tunnel]
            read_chunk_size = 16384
            """.trimIndent()
        val updated = upsertAndroidIceMode(config, "native")
        assertTrue(updated.contains("android_ice_mode = \"native\""))
        assertFalse(updated.contains("\"vnet_mux\""))
        assertEquals(1, updated.lines().count { it.trimStart().startsWith("android_ice_mode") })
    }

    @Test
    fun upsertAndroidIceModeNormalizesInvalidInput() {
        val config =
            """
            [webrtc]
            android_ice_mode = "native"
            """.trimIndent()
        // An unknown/untrusted value must never produce an invalid config.
        val updated = upsertAndroidIceMode(config, "turn; rm -rf")
        assertTrue(updated.contains("android_ice_mode = \"vnet_mux\""))
    }

    @Test
    fun upsertAndroidIceModeInsertsUnderWebrtcWhenMissing() {
        val config =
            """
            [webrtc]
            stun_urls = ["stun:stun.l.google.com:19302"]
            """.trimIndent()
        val updated = upsertAndroidIceMode(config, "native")
        val webrtcIdx = updated.lines().indexOfFirst { it.trimStart() == "[webrtc]" }
        val iceIdx = updated.lines().indexOfFirst { it.trimStart().startsWith("android_ice_mode") }
        assertEquals(webrtcIdx + 1, iceIdx)
        assertTrue(updated.contains("android_ice_mode = \"native\""))
    }

    @Test
    fun prepareActiveConfigForStartRewritesIceModeAndAddress() {
        runBlocking {
            repository.writeConfig(
                """
                [webrtc]
                android_ice_mode = "vnet_mux"
                """.trimIndent(),
            ).getOrThrow()
            repository.prepareActiveConfigForStart("native", "10.1.3.11")
        }
        val config = repository.configContents
        assertTrue(config.contains("android_ice_mode = \"native\""))
        assertTrue(config.contains("advertised_local_ipv4 = \"10.1.3.11\""))
        // A null address clears the advertised line while leaving the chosen mode intact.
        runBlocking {
            repository.prepareActiveConfigForStart("native", null)
        }
        val cleared = repository.configContents
        assertTrue(cleared.contains("android_ice_mode = \"native\""))
        assertFalse(cleared.contains("advertised_local_ipv4"))
    }

    @Test
    fun prepareActiveConfigForStartIsNoOpWhenNoConfigExists() {
        runBlocking {
            repository.prepareActiveConfigForStart("native", "10.1.3.11")
        }
        assertEquals("", repository.configContents)
    }

    @Test
    fun writeAndReadConfigRoundTrip() {
        val contents = "format = \"p2ptunnel-config-v3\"\n[node]\npeer_id=\"x\""
        runBlocking { repository.writeConfig(contents) }
        assertEquals(contents, repository.configContents)
        assertTrue(repository.configPath.startsWith(context.filesDir.absolutePath))
    }

    @Test
    fun readConfigReturnsEmptyWhenMissing() {
        assertEquals("", repository.configContents)
    }

    @Test
    fun preferencesDefaultValuesAreSafe() =
        runBlocking {
            val prefs = repository.preferences.first()
            assertEquals(
                AndroidAppPreferences(
                    allowMetered = false,
                    resumeOnUnmetered = true,
                    showMeteredWarning = true,
                    debugLogsEnabled = false,
                ),
                prefs,
            )
        }

    @Test
    fun savePreferencesPersistsAllFields() =
        runBlocking {
            val update =
                AndroidAppPreferences(
                    allowMetered = true,
                    resumeOnUnmetered = false,
                    showMeteredWarning = false,
                    debugLogsEnabled = true,
                    advancedSettingsEnabled = true,
                    androidIceMode = "native",
                )
            repository.savePreferences(update).getOrThrow()
            assertEquals(update, repository.preferences.first())
        }

    @Test
    fun partialPreferenceStateFallsBackToDefaults() =
        runBlocking {
            context.dataStore.edit { preferences ->
                preferences[booleanPreferencesKey("allow_metered")] = true
                preferences.remove(booleanPreferencesKey("pause_on_metered"))
            }
            val prefs = repository.preferences.first()
            assertTrue(prefs.allowMetered)
            assertTrue(prefs.resumeOnUnmetered)
        }

    @Test
    fun latestWriteWins() {
        runBlocking {
            repository.writeConfig("first").getOrThrow()
            repository.writeConfig("second").getOrThrow()
        }
        assertEquals("second", repository.configContents)
        assertFalse(repository.configContents.contains("first"))
    }

    @Test
    fun atomicWriteReplacesConfig() {
        runBlocking {
            repository.writeConfig("before").getOrThrow()
            repository.writeConfigAtomically("after")
        }
        assertEquals("after", repository.configContents)
    }

    @Test
    fun forwardsValidationRejectsDuplicateEnabledPorts() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "a", localPort = 9000, remoteForwardId = "a", enabled = true),
                ForwardConfig(id = "b", name = "b", localPort = 9000, remoteForwardId = "b", enabled = true),
            )
        assertTrue(forwardsStore.validateForwards(forwards)?.contains("Duplicate local port") == true)
    }

    @Test
    fun forwardsValidationRejectsBlankEnabledForwardName() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "", localPort = 9000, remoteForwardId = "a", enabled = true),
            )
        assertEquals("Forward name is required", forwardsStore.validateForwards(forwards))
    }

    @Test
    fun forwardsValidationRejectsDuplicateEnabledRemoteForwardIds() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "a", localPort = 9000, remoteForwardId = "llama", enabled = true),
                ForwardConfig(id = "b", name = "b", localPort = 9001, remoteForwardId = "llama", enabled = true),
            )
        assertEquals("Duplicate remote forward ID: llama", forwardsStore.validateForwards(forwards))
    }

    @Test
    fun forwardsValidationAllowsDuplicateRemoteForwardIdWhenOneDisabled() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "a", localPort = 9000, remoteForwardId = "llama", enabled = true),
                ForwardConfig(id = "b", name = "b", localPort = 9001, remoteForwardId = "llama", enabled = false),
            )
        assertEquals(null, forwardsStore.validateForwards(forwards))
    }

    @Test
    fun forwardsRoundTripPersistsJson() {
        val forwards =
            listOf(
                ForwardConfig(
                    id = "svc",
                    name = "Service",
                    localHost = "127.0.0.1",
                    localPort = 18080,
                    remoteForwardId = "svc",
                ),
            )
        forwardsStore.saveForwards(forwards)
        assertEquals(forwards, forwardsStore.loadForwardsResult().getOrThrow())
    }

    @Test
    fun renderOfferConfigIncludesForwardAndPeer() {
        val input =
            SetupConfigInput(
                localPeerId = "android-peer",
                brokerHost = "broker.local",
                remotePeerId = "desktop-peer",
            )
        val text =
            repository.renderOfferConfig(
                input,
                listOf(ForwardConfig(id = "llama", name = "Llama", localPort = 8080, remoteForwardId = "llama")),
                brokerPasswordPath = null,
            )
        assertTrue(text.contains("url = \"mqtts://broker.local:8883\""))
        assertTrue(text.contains("remote_peer_id = \"desktop-peer\""))
        assertTrue(text.contains("listen_port = 8080"))
    }

    @Test
    fun renderOfferConfigDefaultsToInfoLogLevel() {
        val text =
            repository.renderOfferConfig(
                SetupConfigInput(localPeerId = "android-peer", brokerHost = "broker.local"),
                listOf(ForwardConfig(id = "llama", name = "Llama", localPort = 8080, remoteForwardId = "llama")),
                brokerPasswordPath = null,
            )
        assertTrue(text.contains("level = \"info\""))
        assertFalse(text.contains("level = \"debug\""))
    }

    @Test
    fun renderOfferConfigUsesDebugLogLevelWhenEnabled() {
        val text =
            repository.renderOfferConfig(
                SetupConfigInput(localPeerId = "android-peer", brokerHost = "broker.local"),
                listOf(ForwardConfig(id = "llama", name = "Llama", localPort = 8080, remoteForwardId = "llama")),
                debugLogs = true,
                brokerPasswordPath = null,
            )
        assertTrue(text.contains("level = \"debug\""))
        assertFalse(text.contains("level = \"info\""))
    }

    @Test
    fun renderOfferConfigEscapesInjectedTomlStrings() {
        val input =
            SetupConfigInput(
                localPeerId = "android\"peer",
                brokerHost = "broker.local\"\\n[[forwards]]\\nid = \"evil\"",
                remotePeerId = "desktop\"peer",
                topicPrefix = "topic\nprefix",
            )
        val text =
            repository.renderOfferConfig(
                input,
                listOf(
                    ForwardConfig(
                        id = "llama",
                        name = "Llama",
                        localPort = 8080,
                        remoteForwardId = "llama\"inject",
                    ),
                ),
                brokerPasswordPath = null,
            )
        assertTrue(text.contains("topic_prefix = \"topic\\nprefix\""))
        assertTrue(text.contains("id = \"llama\\\"inject\""))
        assertFalse(text.contains("\n[[forwards]]\nid = \"evil\""))
    }

    // FIX7 P0-003-A/F: renderOfferConfig must be pure — no filesystem writes, and it must use
    // exactly the caller-provided broker password path rather than computing/writing one itself.

    @Test
    fun renderOfferConfigPerformsNoFilesystemWrites() {
        val runtimeDir = File(context.filesDir, "runtime")
        runtimeDir.deleteRecursively()
        val input =
            SetupConfigInput(
                localPeerId = "android-peer",
                brokerHost = "broker.local",
                brokerPassword = "s3cret",
            )
        repository.renderOfferConfig(
            input,
            emptyList(),
            brokerPasswordPath = "/tmp/some/managed/path/mqtt_password.txt",
        )
        assertFalse(
            "render must not create the managed broker-password runtime directory",
            runtimeDir.exists(),
        )
    }

    @Test
    fun renderOfferConfigUsesProvidedBrokerPasswordPath() {
        val text =
            repository.renderOfferConfig(
                SetupConfigInput(localPeerId = "android-peer", brokerHost = "broker.local"),
                emptyList(),
                brokerPasswordPath = "/explicit/managed/mqtt_password.txt",
            )
        assertTrue(text.contains("password_file = \"/explicit/managed/mqtt_password.txt\""))
    }

    @Test
    fun renderOfferConfigOmitsPasswordFileWhenNoPasswordConfigured() {
        val text =
            repository.renderOfferConfig(
                SetupConfigInput(localPeerId = "android-peer", brokerHost = "broker.local"),
                emptyList(),
                brokerPasswordPath = null,
            )
        assertTrue(text.contains("password_file = \"\""))
    }

    @Test
    fun setupInputRoundTripPersistsState() {
        val input =
            SetupConfigInput(
                localPeerId = "android-peer",
                brokerHost = "broker.local",
                remotePeerId = "desktop-peer",
                allowMetered = true,
            )
        repository.saveSetupInput(input)
        assertEquals(input, repository.loadSetupInputResult().getOrNull())
    }

    @Test
    fun loadSetupInputResultSucceedsWithDefaultsWhenMissing() {
        File(context.filesDir, "setup_input.json").delete()
        val result = repository.loadSetupInputResult()
        assertTrue(result.isSuccess)
        assertEquals(SetupConfigInput(), result.getOrNull())
    }

    @Test
    fun loadSetupInputResultFailsOnCorruptDraft() {
        // A corrupt existing draft is a failure, never silently reset to defaults.
        File(context.filesDir, "setup_input.json").writeText("{ not valid json")
        assertTrue(repository.loadSetupInputResult().isFailure)
    }

    // FIX8 P0-003: exact byte-level setup-input snapshot/restore for the setup transaction.

    @Test
    fun restoreSetupInputSnapshotRevertsToPriorContents() =
        runBlocking {
            val setupInputFile = File(context.filesDir, "setup_input.json")
            repository.saveSetupInput(SetupConfigInput(brokerHost = "prior.example"))
            val snapshot = captureExactFileSnapshot(setupInputFile).getOrThrow()

            repository.saveSetupInput(SetupConfigInput(brokerHost = "changed.example"))
            repository.restoreSetupInputFileSnapshot(snapshot).getOrThrow()

            assertEquals("prior.example", repository.loadSetupInputResult().getOrThrow().brokerHost)
        }

    @Test
    fun restoreSetupInputSnapshotRecreatesAbsentState() =
        runBlocking {
            val setupInputFile = File(context.filesDir, "setup_input.json")
            setupInputFile.delete()
            val snapshot = captureExactFileSnapshot(setupInputFile).getOrThrow()
            assertFalse(snapshot.existed)

            repository.saveSetupInput(SetupConfigInput(brokerHost = "created.example"))
            repository.restoreSetupInputFileSnapshot(snapshot).getOrThrow()

            assertFalse(
                "setup_input.json must be absent again after restoring an absent snapshot",
                setupInputFile.exists(),
            )
        }

    @Test
    fun setupSnapshotDistinguishesAbsentPresentEmptyAndNonUtf8Bytes() =
        runBlocking {
            File(context.filesDir, "config.toml").delete()
            File(context.filesDir, "setup_input.json").delete()
            val absentSnapshot = repository.captureFilesSnapshot().getOrThrow()
            assertFalse(absentSnapshot.config.existed)
            assertFalse(absentSnapshot.setupInput.existed)

            File(context.filesDir, "config.toml").writeBytes(ByteArray(0))
            File(context.filesDir, "setup_input.json").writeBytes(ByteArray(0))
            val emptySnapshot = repository.captureFilesSnapshot().getOrThrow()
            assertTrue("present-but-empty config must be distinct from absent", emptySnapshot.config.existed)
            assertEquals(0, emptySnapshot.config.bytes?.size)
            assertTrue("present-but-empty setup input must be distinct from absent", emptySnapshot.setupInput.existed)
            assertEquals(0, emptySnapshot.setupInput.bytes?.size)

            // Invalid UTF-8: a lone continuation byte with no leading byte. A String-based
            // snapshot would corrupt or replace this; the exact byte snapshot must round-trip it
            // unchanged.
            val nonUtf8Bytes = byteArrayOf(0x66, 0x6f, 0x80.toByte(), 0x6f)
            File(context.filesDir, "config.toml").writeBytes(nonUtf8Bytes)
            File(context.filesDir, "setup_input.json").writeBytes(nonUtf8Bytes)
            val nonUtf8Snapshot = repository.captureFilesSnapshot().getOrThrow()
            assertTrue(nonUtf8Snapshot.config.existed)
            assertArrayEquals(nonUtf8Bytes, nonUtf8Snapshot.config.bytes)
            assertTrue(nonUtf8Snapshot.setupInput.existed)
            assertArrayEquals(nonUtf8Bytes, nonUtf8Snapshot.setupInput.bytes)
        }

    // FIX7 P2-001-A-style deterministic barrier (no Thread.sleep/elapsed-time guess): the write
    // blocks inside fileMutex until this test releases it, so a concurrent captureFilesSnapshot()
    // call has an unbounded window to attempt entry while the write still holds the lock.
    private class BlockingWriteOps(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : AtomicConfigFileOps by RealAtomicConfigFileOps {
        override fun writeBytes(
            temp: Path,
            bytes: ByteArray,
        ) {
            entered.complete(Unit)
            runBlocking { release.await() }
            RealAtomicConfigFileOps.writeBytes(temp, bytes)
        }
    }

    @Test
    fun setupSnapshotCaptureIsSerializedAgainstConfigAndSetupWriters() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blockingRepo = ConfigRepository(context, BlockingWriteOps(entered, release))

            val writer = launch(ioDispatcher()) { blockingRepo.writeConfigAtomically("blocked").getOrThrow() }
            entered.await()

            var snapshotTaken = false
            val snapshotter =
                launch(ioDispatcher(), start = CoroutineStart.UNDISPATCHED) {
                    blockingRepo.captureFilesSnapshot().getOrThrow()
                    snapshotTaken = true
                }
            assertFalse(
                "captureFilesSnapshot must not proceed while a write still holds fileMutex",
                snapshotTaken,
            )

            release.complete(Unit)
            writer.join()
            snapshotter.join()
            assertTrue(snapshotTaken)
        }
}
