package com.phillipchin.webrtctunnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Post-FIX9 source tripwires. Android Lint's real CheckResult detector remains the
 * type-aware proof for ordinary ignored calls; this scanner closes deliberate fake-consumption
 * shapes and protects the finite mutation/setup-controller inventories below.
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
        assertTrue(violations.any { "savePreferences" in it && "also" in it })
        assertTrue(violations.any { "persist" in it && "apply" in it })
        assertTrue(violations.any { "restoreStorageSnapshot" in it && "discarded in run" in it })
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
    fun mutationSensitiveFilesRejectExecutableRunCatchingAndCatchThrowable() {
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
                val executable = executableSource(source(relative))
                buildList {
                    if (RUN_CATCHING_REGEX.containsMatchIn(executable)) {
                        add("$relative: runCatching")
                    }
                    if (CATCH_THROWABLE_REGEX.containsMatchIn(executable)) {
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
    fun commentOnlyForbiddenWordsAreIgnored() {
        val source =
            """
            // never use runCatching { around this mutation
            /** catch (error: Throwable) is forbidden here. */
            fun safe(): Result<Unit> = try {
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            """.trimIndent()
        val executable = executableSource(source)
        assertTrue(!RUN_CATCHING_REGEX.containsMatchIn(executable))
        assertTrue(!CATCH_THROWABLE_REGEX.containsMatchIn(executable))
    }

    @Test
    fun realSetupActionsRetainFreshnessTokenContracts() {
        val combined = setupProductionSource()
        val missing = missingSetupContractFragments(combined)
        assertTrue(
            "Missing setup freshness contract fragments:\n${missing.joinToString("\n")}",
            missing.isEmpty(),
        )
    }

    private fun setupProductionSource(): String =
        listOf(
            "viewmodel/SetupIdentityController.kt",
            "viewmodel/SetupForwardsController.kt",
            "viewmodel/SetupSaveController.kt",
            "viewmodel/SetupViewModel.kt",
        ).joinToString("\n") { source(it) }

    private fun missingSetupContractFragments(combined: String): List<String> =
        buildList {
            setupContractFragments().forEach { (contract, fragments) ->
                fragments.filterNot(combined::contains).forEach { add("$contract: $it") }
            }
            sharedFreshnessFragments()
                .filterNot(combined::contains)
                .forEach { add("shared: $it") }
        }

    private fun setupContractFragments(): Map<String, List<String>> =
        mapOf(
            "identity path import" to
                listOf(
                    "launchIdentityAction { token ->",
                    "publishPathIdentityImportResult(token",
                ),
            "identity URI import" to
                listOf("fun importIdentityFromUri(uri: Uri)", "replacement.wipe()"),
            "identity generation" to
                listOf("fun generateIdentity()", "identityDraft.replace"),
            "remote identity paths" to
                listOf(
                    "fun validateRemotePublicIdentity()",
                    "fun importPublicIdentityFromUri(uri: Uri)",
                ),
            "forward draft edits" to
                listOf(
                    "fun upsertForward(forward: ForwardConfig)",
                    "fun deleteForward(forwardId: String)",
                ),
            "validation navigation" to
                listOf(
                    "SetupDraftOperation.ValidationNavigation) { token ->",
                    "if (!token.isFresh()) return@runGuarded",
                ),
            "final save and start" to
                listOf(
                    "SetupDraftOperation.FinalSave) { token ->",
                    "throwIfStale(commitContext.token)",
                    "if (saved && token.isFresh())",
                ),
        )

    private fun sharedFreshnessFragments(): List<String> =
        listOf(
            "runGuarded(access, SetupDraftOperation.IdentityAction) { token ->",
            "runGuarded(access, SetupDraftOperation.ForwardEdit) { token ->",
            "token.publishIfFresh",
        )

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
            var nameAt = source.indexOf(api)
            while (nameAt >= 0) {
                val beforeOk = nameAt == 0 || !source[nameAt - 1].isJavaIdentifierPart()
                var openAt = nameAt + api.length
                while (openAt < source.length && source[openAt].isWhitespace()) {
                    openAt++
                }
                if (beforeOk && openAt < source.length && source[openAt] == '(') {
                    findMatchingParen(source, openAt)?.let(::add)
                }
                nameAt = source.indexOf(api, nameAt + api.length)
            }
        }

    private fun findMatchingParen(
        source: String,
        openAt: Int,
    ): Int? {
        val state = ParenthesisScanState()
        var index = openAt
        while (index < source.length) {
            if (advanceParenthesisScan(state, source[index])) {
                return index
            }
            index++
        }
        return null
    }

    private fun advanceParenthesisScan(
        state: ParenthesisScanState,
        char: Char,
    ): Boolean =
        when {
            state.quote != null -> {
                advanceQuotedScan(state, char)
                false
            }
            char == '"' || char == '\'' -> {
                state.quote = char
                false
            }
            char == '(' -> {
                state.depth++
                false
            }
            char == ')' -> --state.depth == 0
            else -> false
        }

    private fun advanceQuotedScan(
        state: ParenthesisScanState,
        char: Char,
    ) {
        when {
            state.escaped -> state.escaped = false
            char == '\\' -> state.escaped = true
            char == state.quote -> state.quote = null
        }
    }

    /** Removes comments before scanning for executable forbidden constructs. */
    private fun executableSource(source: String): String =
        source
            .replace(BLOCK_COMMENT_REGEX, "")
            .replace(LINE_COMMENT_REGEX, "")

    private fun productionSources(): List<File> =
        productionRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

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

    private class ParenthesisScanState(
        var depth: Int = 0,
        var quote: Char? = null,
        var escaped: Boolean = false,
    )

    private companion object {
        val RUN_CATCHING_REGEX = Regex("""\brunCatching\s*\{""")
        val CATCH_THROWABLE_REGEX = Regex("""catch\s*\(\s*\w+\s*:\s*Throwable\s*\)""")
        val BLOCK_COMMENT_REGEX = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT_REGEX = Regex("""//[^\n]*""")
    }
}
