package com.phillipchin.webrtctunnel.viewmodel

/**
 * FIX8 P0-001-A: a ViewModel-owned, non-serializable holder for a setup wizard's
 * generated/imported replacement identity. Private bytes live ONLY here — never in
 * [SetupWizardState], a [kotlinx.coroutines.flow.StateFlow], Compose state,
 * `SavedStateHandle`, logs, exceptions, or `toString()`.
 *
 * Neither this class nor [DraftIdentityReplacement] is a `data class`: a generated
 * `toString()`/`equals`/`copy` would risk leaking the private byte array. The previous
 * byte array is wiped before it is dropped on every [replace] and [clear]; a save takes
 * an owned [copyForSave] and wipes that copy itself.
 */
internal class SetupIdentityDraft {
    private val lock = Any()
    private var replacement: DraftIdentityReplacement? = null

    fun replace(
        privateIdentity: ByteArray,
        publicIdentity: String,
        peerId: String,
    ) = synchronized(lock) {
        require(privateIdentity.isNotEmpty()) { "Draft private identity must not be empty" }
        require(publicIdentity.isNotBlank()) { "Draft public identity must not be blank" }
        require(peerId.isNotBlank()) { "Draft peer id must not be blank" }
        replacement?.wipe()
        replacement = DraftIdentityReplacement(privateIdentity, publicIdentity, peerId)
    }

    /** Returns an independently-owned copy for a save attempt, or null when no draft exists. */
    fun copyForSave(): DraftIdentityReplacement? =
        synchronized(lock) {
            replacement?.copyForSave()
        }

    fun clear() =
        synchronized(lock) {
            replacement?.wipe()
            replacement = null
        }

    /**
     * Test-only observation seam (spec §8: "exact ByteArray identity observation seams for
     * wiping"). Returns the draft's *live* private byte array by reference — not a copy — so a
     * test can hold the reference and prove it was zeroed after a [clear]/[replace]. Never call
     * this from production code.
     */
    internal fun peekLivePrivateBytesForTest(): ByteArray? =
        synchronized(lock) {
            replacement?.privateIdentity
        }
}

/**
 * Owns one replacement identity's material. The [privateIdentity] array is the owner's
 * responsibility to [wipe]; a [copyForSave] produces a fresh, separately-owned array so
 * the save path can wipe its copy in `finally` without disturbing the retained draft.
 */
internal class DraftIdentityReplacement(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
) {
    fun copyForSave() =
        DraftIdentityReplacement(
            privateIdentity = privateIdentity.copyOf(),
            publicIdentity = publicIdentity,
            peerId = peerId,
        )

    fun wipe() = privateIdentity.fill(0)
}
