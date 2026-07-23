package com.phillipchin.webrtctunnel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX8 P1-003-D: `nativeLibraryLoadNormalizesOnlyUnsatisfiedLinkError`. The plain JVM unit-test
 * process has no real `libp2p_mobile.so` on its library path, so `NativeLibLoader`'s
 * `System.loadLibrary("p2p_mobile")` call in [RustTunnelBridge.kt] genuinely fails here — this
 * is not a mock, it exercises the real load-failure path. `requireNativeLoaded()` must normalize
 * that failure into an `IllegalStateException` whose cause is specifically an
 * `UnsatisfiedLinkError` (not runCatching's blanket Throwable), proving P1-003-A's replacement of
 * `runCatching` with an explicit `catch (UnsatisfiedLinkError)` behaves identically to before.
 */
class RustTunnelBridgeNativeLoadTest {
    @Test
    fun nativeLibraryLoadNormalizesOnlyUnsatisfiedLinkError() {
        val bridge = RustValidationBridge()

        val thrown =
            try {
                bridge.validateConfig("irrelevant-path")
                null
            } catch (error: IllegalStateException) {
                error
            }

        assertTrue("expected requireNativeLoaded() to throw IllegalStateException", thrown != null)
        assertTrue(
            "expected the IllegalStateException's cause to be UnsatisfiedLinkError, was: ${thrown?.cause}",
            thrown?.cause is UnsatisfiedLinkError,
        )
    }
}
