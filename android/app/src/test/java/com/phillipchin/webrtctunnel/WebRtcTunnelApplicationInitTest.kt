package com.phillipchin.webrtctunnel

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * FIX6 INV-010: `Application.onCreate()` must not run initialization inside `runBlocking`.
 *
 * The prior code did unbounded default-config file I/O on the main thread (ANR risk on slow
 * storage) and discarded the result. This is a source-level guard rather than a runtime
 * timing assertion: timing the main thread would be flaky, whereas the invariant is
 * structural — onCreate must hand off to the initialization coordinator, not block.
 */
class WebRtcTunnelApplicationInitTest {
    private fun applicationSource(): String {
        val path =
            "src/main/java/com/phillipchin/webrtctunnel/WebRtcTunnelApplication.kt"
        val candidates =
            listOf(
                File(path),
                File("app/$path"),
                File(System.getProperty("user.dir"), path),
            )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("WebRtcTunnelApplication.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun applicationOnCreateDoesNotRunBlockingFileIoOnMainThread() {
        val source = applicationSource()
        // Strip comments so the explanatory reference to the old runBlocking approach does
        // not trip the guard.
        val code =
            source.lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")

        assertFalse(
            "Application.onCreate must not use runBlocking; initialization is async via " +
                "AppInitializationCoordinator (FIX6 INV-010)",
            code.contains("runBlocking"),
        )
        assertFalse(
            "Application.onCreate must not import runBlocking",
            code.contains("import kotlinx.coroutines.runBlocking"),
        )
    }

    @Test
    fun applicationOnCreateDelegatesToInitializationCoordinator() {
        assertTrue(
            "onCreate must start the initialization coordinator",
            applicationSource().contains("appInitializationCoordinator.start()"),
        )
    }

    private fun assertTrue(
        message: String,
        condition: Boolean,
    ) = org.junit.Assert.assertTrue(message, condition)
}
