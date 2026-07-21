package com.phillipchin.webrtctunnel.viewmodel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * FIX8 P0-001-A: the setup identity draft holds replacement private bytes in a
 * non-data object, wipes the previous byte array on replace/clear, and only ever
 * hands out independent copies. These tests assert the exact byte lifecycle, not a
 * message.
 */
class SetupIdentityDraftTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun replaceWipesPreviousPrivateBytes() {
        val draft = SetupIdentityDraft()
        val first = bytes(1, 2, 3, 4)
        draft.replace(first, "public-a", "peer-a")

        // Replacing must zero the previously-held array before dropping it.
        draft.replace(bytes(9, 9, 9, 9), "public-b", "peer-b")

        assertArrayEquals("previous private bytes must be wiped on replace", ByteArray(4), first)
    }

    @Test
    fun clearWipesPrivateBytesAndDropsReplacement() {
        val draft = SetupIdentityDraft()
        val held = bytes(7, 7, 7)
        draft.replace(held, "public", "peer")

        draft.clear()

        assertArrayEquals("clear must wipe private bytes", ByteArray(3), held)
        assertNull("clear must drop the replacement", draft.copyForSave())
    }

    @Test
    fun copyForSaveReturnsIndependentCopyThatDoesNotAffectDraft() {
        val draft = SetupIdentityDraft()
        draft.replace(bytes(5, 6, 7), "public", "peer")

        val copy = draft.copyForSave()!!
        assertArrayEquals(bytes(5, 6, 7), copy.privateIdentity)
        assertEquals("public", copy.publicIdentity)
        assertEquals("peer", copy.peerId)

        // Wiping the save-owned copy must not disturb the draft's own bytes.
        copy.wipe()
        val second = draft.copyForSave()!!
        assertArrayEquals("draft bytes must survive a save-copy wipe", bytes(5, 6, 7), second.privateIdentity)
    }

    @Test
    fun copyForSaveIsNullWhenEmpty() {
        assertNull(SetupIdentityDraft().copyForSave())
    }

    @Test
    fun replaceRejectsEmptyOrBlankFields() {
        val draft = SetupIdentityDraft()
        assertThrows(IllegalArgumentException::class.java) { draft.replace(ByteArray(0), "public", "peer") }
        assertThrows(IllegalArgumentException::class.java) { draft.replace(bytes(1), "  ", "peer") }
        assertThrows(IllegalArgumentException::class.java) { draft.replace(bytes(1), "public", " ") }
    }
}
