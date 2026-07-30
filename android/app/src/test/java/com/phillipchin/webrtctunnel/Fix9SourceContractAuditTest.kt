package com.phillipchin.webrtctunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Fix9SourceContractAuditTest {
    @Test
    fun setupControllersRetainTokenAndDraftTruthContracts() {
        val forwards = source("viewmodel/SetupForwardsController.kt")
        val identity = source("viewmodel/SetupIdentityController.kt")
        val save = source("viewmodel/SetupSaveController.kt")
        val coordinator = source("viewmodel/SetupOperationCoordinator.kt")

        assertFalse(forwards.contains("Forward saved"))
        assertFalse(forwards.contains("Forward deleted"))
        assertTrue(forwards.contains("runGuarded(access, SetupDraftOperation.ForwardEdit) { token ->"))
        assertTrue(identity.contains("runGuarded(access, SetupDraftOperation.IdentityAction) { token ->"))
        assertTrue(save.contains("runGuarded(access, SetupDraftOperation.FinalSave) { token ->"))
        assertTrue(
            save.contains(
                "throwIfStale(commitContext.token)\n" +
                    "        val result = withContext(ioDispatcher)",
            ),
        )
        assertTrue(coordinator.contains("val job: Job"))
        assertTrue(coordinator.contains("jobToCancel?.cancel(CancellationException(reason))"))
    }

    @Test
    fun publicIdentityReadRemainsSerializedWithPairReplacement() {
        val identityRepository = source("security/IdentityRepository.kt")
        val pattern =
            Regex(
                """fun readPublicIdentity\(\): String\s*=\s*synchronized\(storageLock\)""",
                RegexOption.DOT_MATCHES_ALL,
            )

        assertTrue(pattern.containsMatchIn(identityRepository))
    }

    @Test
    fun configResultApisRetainCancellationFirstOrdinaryFailureContract() {
        val config = source("data/ConfigRepository.kt")
        val savePreferences = functionWindow(config, "open suspend fun savePreferences", "@CheckResult")
        val prepare = functionWindow(config, "open suspend fun prepareActiveConfigForStart", "@CheckResult")
        val replace =
            functionWindow(
                config,
                "internal open val replaceConfigTransactionally",
                "open fun saveSetupInput",
            )

        listOf(savePreferences, prepare, replace).forEach { body ->
            assertTrue(body.contains("catch (cancelled: CancellationException)"))
            assertTrue(body.contains("catch (error: Exception)"))
        }
    }

    @Test
    fun resultContractDetectorRejectsRequiredNegativeFixtures() {
        val selectedCatch =
            "fun bad(): Result<Unit> = try { Result.success(Unit) } " +
                "catch (error: java.io.IOException) { Result.failure(error) }"
        val earlyThrow =
            "fun bad(): Result<Unit> { value.getOrThrow(); return try { Result.success(Unit) } " +
                "catch (error: Exception) { Result.failure(error) } }"
        val fakeConsumption = "fun caller() { mutate().also { audit() } }"

        assertTrue(resultContractViolations(selectedCatch).contains("selected_catch"))
        assertTrue(resultContractViolations(earlyThrow).contains("get_or_throw_before_try"))
        assertTrue(resultContractViolations(fakeConsumption).contains("also_fake_consumption"))
    }

    private fun resultContractViolations(source: String): Set<String> =
        buildSet {
            if (source.contains("catch (error: java.io.IOException)") &&
                !source.contains("catch (error: Exception)")) {
                add("selected_catch")
            }
            val firstTry = source.indexOf("try")
            val firstThrow = source.indexOf("getOrThrow()")
            if (firstThrow >= 0 && (firstTry < 0 || firstThrow < firstTry)) {
                add("get_or_throw_before_try")
            }
            if (source.contains(".also {")) {
                add("also_fake_consumption")
            }
        }

    private fun functionWindow(
        source: String,
        start: String,
        end: String,
    ): String {
        val startIndex = source.indexOf(start)
        require(startIndex >= 0) { "missing function start: $start" }
        val nextIndex = source.indexOf(end, startIndex + start.length)
        val endIndex = if (nextIndex < 0) source.length else nextIndex
        return source.substring(startIndex, endIndex)
    }

    private fun source(relativePath: String): String {
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
