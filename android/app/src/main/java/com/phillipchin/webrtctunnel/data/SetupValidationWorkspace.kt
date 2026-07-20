package com.phillipchin.webrtctunnel.data

import java.io.File

/**
 * Files inside one isolated setup-validation workspace (FIX7 P0-003-C / INV-001). Populated only
 * with what native validation needs; authoritative storage (`authorized_keys`, the broker secret,
 * setup input, preferences, the active config) is never touched during validation — only after
 * it succeeds does the real [SetupPersistenceCoordinator] commit run.
 *
 * There is no `identity` file here: `IdentityValidationClient.validateConfigWithIdentity` takes
 * the private identity as in-memory bytes directly, so the candidate's `[paths] identity` field
 * is never read during validation and can keep pointing at the live (not-yet-written) location.
 */
class SetupValidationWorkspace internal constructor(
    val root: File,
    val authorizedKeysFile: File,
    val brokerPasswordFile: File?,
    val candidateFile: File,
)

/**
 * Runs [block] against a freshly created, isolated setup-validation workspace. Cleanup goes
 * through [withTemporaryDirectory]'s composition rules (FIX7 P0-002), so a cleanup failure is
 * never silently discarded — including after an otherwise-successful validation, where it must
 * still block the authoritative commit (P0-003-C).
 */
suspend fun <T> withSetupValidationWorkspace(
    cacheDir: File,
    includeBrokerPassword: Boolean,
    // Exposed for tests to inject a failing cleanup (AtomicConfigFileOps-style fake) instead of
    // a flaky filesystem permission trick; production callers use the real default.
    deleteRecursively: (File) -> Result<Unit> = ::deleteDirectoryRecursivelySafely,
    block: suspend (SetupValidationWorkspace) -> T,
): T =
    withTemporaryDirectory(cacheDir, "setup-validation-", deleteRecursively) { root ->
        block(
            SetupValidationWorkspace(
                root = root,
                authorizedKeysFile = File(root, "authorized_keys"),
                brokerPasswordFile = if (includeBrokerPassword) File(root, "mqtt_password.txt") else null,
                candidateFile = File(root, "candidate.toml"),
            ),
        )
    }
