package com.phillipchin.webrtctunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Post-FIX9 contract enforcement.
 *
 * Android Lint's real CheckResult detector remains the proof for ordinary bare ignored calls.
 * This focused scanner closes the shapes that CheckResult considers syntactically "used" even
 * though the authoritative outcome is still discarded (`also`, `apply`, and `run { ...; Unit }`).
 * It also keeps cancellation-first mutation files and setup-token production paths under an
 * explicit source inventory. This is a test tripwire, not a replacement for runtime regression
 * tests or the compiler/lint type system.
 */
class Fix9FollowupContractAuditTest {
    private val authoritativeApis =
        setOf(
            "savePreferences",
            "prepareActiveConfigForStart",
            "writeConfigAtomically",
            "replaceConfigTransactionally",
            "saveSetupInputAtomically",
            "restoreSetupInputFileSnapshot",
            "restoreConfigSnapshot",
            "captureFilesSnapshot",
            "persist",
            "restore",
            "captureSnapshot",
            "appendAuthorizedPublicIdentity",
            "restoreStorageSnapshot",
            "restoreIdentityPairSnapshot",
            "restoreAuthorizedKeysSnapshot",
            "upsertWithReceipt",
            "deleteWithReceipt",
            "rollbackReceipt",
            "resetForwards",
            "captureForTransaction",
            "replaceForTransaction",
            "restoreForTransaction",
        )

    @Test
    fun productionDoesNotFakeConsumeAuthoritativeResults() {
        val violations =
            productionSources().flatMap { file ->
                fakeConsumptionViolations(file.readText(), authoritativeApis).map { violation ->
                    "${file.invariantSeparatorsPath}: $violation"
                }
            }

        assertTrue(
            "Authoritative result fake-consumption violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun fakeConsumptionNegativeFixturesAreRejected() {
        val fixture =
            """
            fun bad(prefs: Prefs, snapshot: Snapshot) {
                repository.savePreferences(prefs).also { audit() }
                broker.persist("secret").apply { audit() }
                run { identity.restoreStorageSnapshot(snapshot); Unit }
            }
            """.trimIndent()

        val violations = fakeConsumptionViolations(fixture, authoritativeApis)
        assertTrue(violations.any { it.contains("savePreferences") && it.contains("also") })
        assertTrue(violations.any { it.contains("persist") && it.contains("apply") })
        assertTrue(violations.any { it.contains("restoreStorageSnapshot") && it.contains("discarded in run") })
    }

    @Test
    fun legitimateResultConsumptionFixturesRemainAllowed() {
        val fixture =
            """
            fun good(prefs: Prefs, snapshot: Snapshot): Result<Unit> {
                repository.savePreferences(prefs).getOrThrow()
                val restored = identity.restoreStorageSnapshot(snapshot)
                check(restored.all { it is Success })
                return broker.persist("secret")
            }
            """.trimIndent()

        assertTrue(fakeConsumptionViolations(fixture, authoritativeApis).isEmpty())
    }

    @Test
    fun mutationSensitiveFilesRejectRunCatchingAndCatchThrowable() {
        val sensitiveFiles =
            listOf(
                "data/ConfigRepository.kt",
                "data/BrokerSecretRepository.kt",
                "data/SetupPersistenceCoordinator.kt",
                "data/ForwardsRepository.kt",
                "data/TransactionalReset.kt",
                "security/IdentityRepository.kt",
                "viewmodel/SetupIdentityController.kt",
                "viewmodel/SetupForwardsController.kt",
                "viewmodel/SetupSaveController.kt",
            )
        val violations =
            sensitiveFiles.flatMap { relative ->
                val source = source(relative)
                buildList {
                    if (source.contains("runCatching")) add("$relative: runCatching")
                    if (Regex("""catch\s*\(\s*\w+\s*:\s*Throwable\s*\)""").containsMatchIn(source)) {
                        add("$relative: catch(Throwable)")
                    }
                }
            }

        assertTrue(
            "Mutation error-handling violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun realSetupActionsRetainFreshnessTokenContracts() {
        val identity = source("viewmodel/SetupIdentityController.kt")
        val forwards = source("viewmodel/SetupForwardsController.kt")
        val save = source("viewmodel/SetupSaveController.kt")
        val viewModel = source("viewmodel/SetupViewModel.kt")

        val required =
            mapOf(
                "identity path import" to
                    listOf(
                        "launchIdentityAction { token ->",
                        "publishPathIdentityImportResult(token",
                        "publishImportedPathIdentity(token",
                    ),
                "identity URI import" to
                    listOf(
                        "fun importIdentityFromUri(uri: Uri)",
                        "val published =\n                    token.publishIfFresh",
                        "if (!published) {\n                    replacement.wipe()",
                    ),
                "identity generation" to
                    listOf(
                        "fun generateIdentity()",
                        "token.publishIfFresh {\n                        identityDraft.replace",
                    ),
                "remote identity paths" to
                    listOf(
                        "fun validateRemotePublicIdentity()",
                        "fun importPublicIdentityFromUri(uri: Uri)",
                        "token.publishIfFresh { access.applyState(resolved) }",
                    ),
                "forward draft edits" to
                    listOf(
                        "launchForwardEdit { token ->",
                        "fun upsertForward(forward: ForwardConfig)",
                        "fun deleteForward(forwardId: String)",
                        "token.publishIfFresh {",
                    ),
                "validation navigation" to
                    listOf(
                        "SetupDraftOperation.ValidationNavigation) { token ->",
                        "if (!token.isFresh()) return@runGuarded",
                        "token.publishIfFresh {\n                        applyState",
                    ),
                "final save and start" to
                    listOf(
                        "SetupDraftOperation.FinalSave) { token ->",
                        "throwIfStale(commitContext.token)",
                        "if (saved && token.isFresh()) {\n                        onFreshSuccess?.invoke()",
                    ),
            )

        val combined = identity + "\n" + forwards + "\n" + save + "\n" + viewModel
        val missing =
            required.flatMap { (contract, fragments) ->
                fragments.filterNot(combined::contains).map { fragment -> "$contract: $fragment" }
            }
        assertTrue(
            "Missing setup freshness contract fragments:\n${missing.joinToString("\n")}",
            missing.isEmpty(),
        )
    }

    private fun fakeConsumptionViolations(
        source: String,
        apiNames: Set<String>,
    ): List<String> =
        buildList {
            apiNames.forEach { api ->
                callClosingOffsets(source, api).forEach { closeOffset ->
                    val suffix = source.substring(closeOffset + 1, minOf(source.length, closeOffset + 120))
                    val trimmed = suffix.trimStart()
                    when {
                        trimmed.startsWith(".also") -> add("$api result discarded through also")
                        trimmed.startsWith(".apply") -> add("$api result discarded through apply")
                        Regex("""^\s*;\s*Unit\b""").containsMatchIn(suffix) &&
                            source.substring(maxOf(0, closeOffset - 300), closeOffset).contains("run {") ->
                            add("$api result discarded in run { ...; Unit }")
                    }
                }
            }
        }

    private fun callClosingOffsets(
        source: String,
        api: String,
    ): List<Int> =
        buildList {
            var searchFrom = 0
            while (searchFrom < source.length) {
                val nameAt = source.indexOf(api, searchFrom)
                if (nameAt < 0) break
                val beforeOk = nameAt == 0 || !source[nameAt - 1].isJavaIdentifierPart()
                var openAt = nameAt + api.length
                while (openAt < source.length && source[openAt].isWhitespace()) openAt++
                val afterOk = openAt < source.length && source[openAt] == '('
                if (beforeOk && afterOk) {
                    findMatchingParen(source, openAt)?.let(::add)
                }
                searchFrom = nameAt + api.length
            }
        }

    private fun findMatchingParen(
        source: String,
        openAt: Int,
    ): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in openAt until source.length) {
            val char = source[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                continue
            }
            if (char == '"' || char == '\'') {
                quote = char
                continue
            }
            when (char) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun productionSources(): List<File> {
        val root = productionRoot()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun source(relativePath: String): String =
        File(productionRoot(), relativePath).takeIf(File::exists)?.readText()
            ?: error("source file not found: $relativePath")

    private fun productionRoot(): File {
        val relative = "src/main/java/com/phillipchin/webrtctunnel"
        val candidates =
            listOf(
                File(relative),
                File("app/$relative"),
                File(System.getProperty("user.dir"), "app/$relative"),
            )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("production source root not found")
    }
}
