package com.phillipchin.webrtctunnel.data

import java.io.File

/**
 * FIX8 P0-003-C: throwing entry point for the same-directory temp-plus-atomic-move primitive
 * ([atomicReplaceBytesWith]), used by callers that need a throwing `(File, ByteArray) -> Unit`
 * shape — [restoreExactFileSnapshot]'s `atomicReplace` callback and
 * [ConfigRepository.saveSetupInputAtomically]'s [mutationResult] block both compose this by
 * catching [Exception] broadly, so throwing here (rather than returning [Result]) is safe.
 */
internal fun atomicReplaceBytes(
    destination: File,
    bytes: ByteArray,
    ops: AtomicConfigFileOps = RealAtomicConfigFileOps,
    postMoveVerify: (File) -> Unit = {},
) {
    atomicReplaceBytesWith(destination, bytes, ops, postMoveVerify).getOrThrow()
}
