package com.phillipchin.webrtctunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the dependency-light shell fixture as part of ordinary Gradle `check`. */
class ProbeEvidenceShellContractTest {
    @Test
    fun healthyAndMissingProbeFixturesEnforceTheContract() {
        val script = findScript()
        val process =
            ProcessBuilder("bash", script.absolutePath)
                .directory(script.parentFile)
                .redirectErrorStream(true)
                .start()
        val completed = process.waitFor(15, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertTrue("probe evidence fixture timed out:\n$output", completed)
        assertEquals("probe evidence fixture failed:\n$output", 0, process.exitValue())
        assertTrue(
            "probe evidence fixture did not report PASS:\n$output",
            output.contains("[probe-evidence-test] PASS"),
        )
    }

    private fun findScript(): File {
        val relative = "tests/e2e/probe_evidence_test.sh"
        val workingDir = File(System.getProperty("user.dir")).canonicalFile
        val roots =
            listOfNotNull(
                System.getenv("GITHUB_WORKSPACE")?.let(::File),
                workingDir,
                workingDir.parentFile,
                workingDir.parentFile?.parentFile,
            ).map(File::getCanonicalFile).distinct()
        val candidates = roots.map { root -> File(root, relative) }
        return candidates.firstOrNull { candidate -> candidate.isFile }
            ?: error(
                "probe evidence fixture not found; checked: " +
                    candidates.joinToString { candidate -> candidate.absolutePath },
            )
    }
}
