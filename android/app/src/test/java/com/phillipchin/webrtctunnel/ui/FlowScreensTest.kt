package com.phillipchin.webrtctunnel.ui

import com.phillipchin.webrtctunnel.model.ForwardConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowScreensTest {
    @Test
    fun defaultNewForwardUsesSafeDefaults() {
        val existing =
            listOf(
                ForwardConfig(id = "a", name = "A", localHost = "127.0.0.1", localPort = 8080, remoteForwardId = "a", enabled = true),
                ForwardConfig(id = "b", name = "B", localHost = "127.0.0.1", localPort = 8081, remoteForwardId = "b", enabled = true),
            )

        val draft = defaultNewForward(existing)

        assertEquals("", draft.name)
        assertEquals("127.0.0.1", draft.localHost)
        assertEquals("", draft.remoteForwardId)
        assertEquals(8082, draft.localPort)
    }

    @Test
    fun suggestNewForwardPortSkipsDisabledEntries() {
        val existing =
            listOf(
                ForwardConfig(id = "a", name = "A", localHost = "127.0.0.1", localPort = 8080, remoteForwardId = "a", enabled = false),
                ForwardConfig(id = "b", name = "B", localHost = "127.0.0.1", localPort = 8081, remoteForwardId = "b", enabled = true),
            )

        val port = suggestNewForwardPort(existing, startPort = 8080)

        assertEquals(8080, port)
    }

    @Test
    fun forwardEditorLabelsMatchMode() {
        assertEquals(ForwardEditorLabels("Add Forward", "Add"), forwardEditorLabels(ForwardEditorMode.Add))
        assertEquals(ForwardEditorLabels("Edit Forward", "Save"), forwardEditorLabels(ForwardEditorMode.Edit))
    }

    @Test
    fun beginAddForwardEditUsesAddModeAndDefaultDraft() {
        val existing =
            listOf(
                ForwardConfig(
                    id = "svc",
                    name = "svc",
                    localHost = "127.0.0.1",
                    localPort = 8080,
                    remoteForwardId = "svc",
                    enabled = true,
                ),
            )
        val editor = beginAddForwardEdit(existing)
        assertEquals(ForwardEditorMode.Add, editor.mode)
        assertEquals("Add Forward", forwardEditorLabels(editor.mode).title)
        assertEquals("Add", forwardEditorLabels(editor.mode).action)
        assertEquals("127.0.0.1", editor.draft.localHost)
        assertEquals("", editor.draft.name)
        assertEquals("", editor.draft.remoteForwardId)
    }

    @Test
    fun beginEditForwardUsesEditModeAndExistingDraft() {
        val existingForward =
            ForwardConfig(
                id = "svc",
                name = "svc",
                localHost = "127.0.0.1",
                localPort = 8080,
                remoteForwardId = "svc",
                enabled = true,
            )
        val editor = beginEditForward(existingForward)
        assertEquals(ForwardEditorMode.Edit, editor.mode)
        assertEquals("Edit Forward", forwardEditorLabels(editor.mode).title)
        assertEquals("Save", forwardEditorLabels(editor.mode).action)
        assertEquals(existingForward, editor.draft)
    }
}
