package com.phillipchin.webrtctunnel.data

import java.io.File

// Shared BrokerSecretPermissionEnforcer test fakes (FIX8 P0-008-A). `android.system.Os.chmod`/
// `Os.stat` do not behave reliably under Robolectric's plain-JVM environment, so every JVM test
// exercising BrokerSecretRepository (other than one specifically targeting enforcer-failure
// propagation) must inject a fake instead of depending on RealBrokerSecretPermissionEnforcer —
// per the FIX8 RESPONSES pacing answer, real Os.chmod/Os.stat behavior is proven by an
// emulator/instrumentation test, not a JVM one. `internal` (not `private`) so every file in this
// package can reach them without an import, matching Kotlin's same-package visibility.

/** Always succeeds, recording every file it was asked to enforce — the default for tests that
 * don't care about permission enforcement specifically, and also used to prove the repository
 * actually calls the enforcer for both the temp file (before write) and the destination
 * (after move). */
internal class RecordingPermissionEnforcer : BrokerSecretPermissionEnforcer {
    val enforcedFiles = mutableListOf<String>()

    override fun enforceOwnerOnly(file: File) {
        enforcedFiles.add(file.name)
    }
}

/** Fails on demand — used to prove a permission enforcement/verification failure propagates as
 * [BrokerSecretPermissionException] and is treated like any other post-move failure (rollback,
 * not silently ignored). */
internal class FailingPermissionEnforcer(
    private val failOn: (File) -> Boolean = { true },
) : BrokerSecretPermissionEnforcer {
    override fun enforceOwnerOnly(file: File) {
        if (failOn(file)) throw BrokerSecretPermissionException()
    }
}
