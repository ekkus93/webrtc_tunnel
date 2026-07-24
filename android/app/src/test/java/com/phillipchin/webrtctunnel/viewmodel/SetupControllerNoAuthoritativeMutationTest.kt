package com.phillipchin.webrtctunnel.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX8 P2-001-A: a static regression guard for P0-001's own invariant — setup-wizard
 * controllers must never commit authoritative identity or forwards state directly; every
 * mutation goes through a draft (`SetupIdentityDraft`/`WizardStateAccess.setForwards`) until
 * the final setup transaction (`SetupPersistenceCoordinator.persist`) commits it. Scans every
 * `Setup*.kt` file under `viewmodel/` for a direct call to `storeEncryptedIdentity(`,
 * `upsertWithReceipt(`, or `deleteWithReceipt(` — the three authoritative-commit APIs P0-001-B/C
 * moved out of the interactive setup-edit path (FIX8 P0-001-C, `4bbd63e`, fixed the last of
 * these — `SetupForwardsController` calling `ForwardsRepository.upsertWithReceipt`/
 * `deleteWithReceipt` directly).
 */
class SetupControllerNoAuthoritativeMutationTest {
    private fun viewmodelSourceRoot(): File {
        val path = "src/main/java/com/phillipchin/webrtctunnel/viewmodel"
        val candidates =
            listOf(
                File(path),
                File("app/$path"),
                File(System.getProperty("user.dir"), path),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("viewmodel source root not found from ${File(".").absolutePath}")
    }

    @Test
    fun setupControllersNeverCallAuthoritativeMutationApisDirectly() {
        val bannedCalls = listOf("storeEncryptedIdentity(", "upsertWithReceipt(", "deleteWithReceipt(")
        val hits = mutableListOf<String>()

        viewmodelSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.startsWith("Setup") }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val isCommentLine = trimmed.startsWith("//") || trimmed.startsWith("*")
                    if (!isCommentLine) {
                        bannedCalls.forEach { banned ->
                            if (line.contains(banned)) {
                                hits += "${file.path}:${index + 1}: ${line.trim()}"
                            }
                        }
                    }
                }
            }

        assertTrue(
            "setup controllers must never call an authoritative mutation API directly — " +
                "route through the wizard draft and commit only via SetupPersistenceCoordinator.persist " +
                "at final save — found: $hits",
            hits.isEmpty(),
        )
    }
}
