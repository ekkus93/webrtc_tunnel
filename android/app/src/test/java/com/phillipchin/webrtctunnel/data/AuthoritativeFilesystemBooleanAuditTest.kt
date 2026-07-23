package com.phillipchin.webrtctunnel.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIX8 P1-003-C/D: `authoritativeFilesystemOperationsContainNoUncheckedBooleanResult`. Earlier
 * FIX7/FIX8 passes replaced every `File.mkdirs()`/`File.delete()`/`setReadable()`/`setWritable()`
 * call in production with a checked equivalent (`Files.createDirectories`,
 * `Files.deleteIfExists`, or an explicit permission-setter Result). This is a static regression
 * guard: none of these ignorable-Boolean filesystem APIs may reappear in production Kotlin.
 */
class AuthoritativeFilesystemBooleanAuditTest {
    private fun mainSourceRoot(): File {
        val path = "src/main/java/com/phillipchin/webrtctunnel"
        val candidates =
            listOf(
                File(path),
                File("app/$path"),
                File(System.getProperty("user.dir"), path),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("main source root not found from ${File(".").absolutePath}")
    }

    @Test
    fun authoritativeFilesystemOperationsContainNoUncheckedBooleanResult() {
        val callPattern = Regex("""\.(mkdirs|delete|setReadable|setWritable)\s*\(""")
        val hits = mutableListOf<String>()

        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val isCommentLine = trimmed.startsWith("//") || trimmed.startsWith("*")
                    if (!isCommentLine && callPattern.containsMatchIn(line)) {
                        hits += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "production must not call File.mkdirs()/delete()/setReadable()/setWritable() " +
                "(their Boolean result is easy to silently ignore) — use Files.createDirectories/" +
                "deleteIfExists or a checked equivalent instead — found: $hits",
            hits.isEmpty(),
        )
    }
}
