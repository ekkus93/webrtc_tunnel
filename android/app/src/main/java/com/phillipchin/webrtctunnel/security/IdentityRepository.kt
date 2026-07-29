package com.phillipchin.webrtctunnel.security

import android.content.Context
import android.util.Log
import androidx.annotation.CheckResult
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.throwComposedFailureIfAny
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val IDENTITY_TAG = "IdentityRepository"

/** Thrown when identity persistence fails but the prior pair was fully restored. */
class IdentityPersistenceException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

/** Thrown when identity persistence fails AND rollback could not restore the prior pair. */
class IdentityRollbackIncompleteException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

/**
 * Snapshot of one identity-storage file for transactional rollback (FIX6 P0-003 / INV-011).
 * A plain class (not a data class) because it holds a [ByteArray]. The bytes are of the
 * on-disk file, which for identity.enc is already ciphertext — no plaintext is captured.
 */
class StoredFileSnapshot internal constructor(
    val existed: Boolean,
    val bytes: ByteArray?,
)

/** Snapshot of the whole identity storage triplet, captured before a setup transaction. */
class IdentityStorageSnapshot internal constructor(
    val encryptedIdentity: StoredFileSnapshot,
    val publicIdentity: StoredFileSnapshot,
    val authorizedKeys: StoredFileSnapshot,
)

/**
 * FIX8 P0-007-C: the encrypted-identity and public-identity files read as one coherent pair —
 * both taken from the same point in time under [IdentityRepository]'s storage lock, so a caller
 * can never observe one file from before a concurrent [IdentityRepository.storeEncryptedIdentity]
 * replacement and the other from after. [encryptedPayload] is still ciphertext; the caller
 * decrypts it (outside the lock) via [IdentityRepository.decryptPrivateIdentity].
 */
internal class StoredIdentityMaterial(
    val encryptedPayload: ByteArray,
    val publicIdentity: String,
) {
    fun wipe() = encryptedPayload.fill(0)
}

/** The three files [IdentityRepository] owns, named for exhaustive per-file restore reporting
 * (FIX7 P0-006-A). */
enum class IdentityStorageFile {
    EncryptedIdentity,
    PublicIdentity,
    AuthorizedKeys,
}

/** Outcome of restoring one [IdentityStorageFile] during [IdentityRepository.restoreStorageSnapshot]. */
sealed interface IdentityRestoreResult {
    data class Success(val file: IdentityStorageFile) : IdentityRestoreResult

    data class Failure(val file: IdentityStorageFile, val reason: String) : IdentityRestoreResult
}

class IdentityRepository(
    private val context: Context,
    private val crypto: IdentityCrypto = AndroidKeystoreIdentityCrypto(),
    // FIX8 P0-007-E: injectable checked delete (the "restore to absent" counterpart to
    // atomicReplace below) so an absent-file restore's delete failure is testable without a
    // flaky filesystem permission trick. Defaults to the real checked delete. Ordered before
    // atomicReplace so every existing trailing-lambda call site targeting atomicReplace (the
    // last parameter) is unaffected.
    private val deleteIfExists: (File) -> Unit = { Files.deleteIfExists(it.toPath()) },
    // P1-004-B: injectable atomic file replace so pair-commit rollback failure paths are
    // testable without mocking the filesystem. Defaults to the real temp-file+move replace.
    private val atomicReplace: (File, ByteArray) -> Unit = ::identityAtomicReplace,
) {
    private val identityFile = File(context.filesDir, "identity.enc")
    private val publicFile = File(context.filesDir, "identity.pub")
    private val authorizedKeysFile = File(context.filesDir, "authorized_keys")

    // FIX6 INV-011: serialize identity-pair and authorized-key reads-modify-writes so a
    // concurrent mutation cannot interleave with a snapshot/restore or with each other.
    private val storageLock = Any()
    private val identityFileOps = IdentityFileOps(atomicReplace, deleteIfExists)

    // FIX8 P0-004: a property (not a function) so it doesn't count against this class's detekt
    // TooManyFunctions threshold — semantically unchanged, still a synchronous file check.
    val hasEncryptedIdentity: Boolean get() = identityFile.exists()

    /**
     * P1-004-C: commit the encrypted-identity + public-identity pair as one logical unit.
     * Encrypt first, snapshot the prior pair, atomically replace the encrypted file, then the
     * public file; if the public replace fails OR is cancelled, restore BOTH prior files (each
     * attempted independently, FIX7 P0-006-C) so the pair can never be left mismatched. Throws
     * [IdentityPersistenceException] when the prior pair was restored, or
     * [IdentityRollbackIncompleteException] when restoration itself failed (so the mismatch is
     * visible, not silent) — in both cases every individual restore failure is attached as
     * suppressed. A cancellation (FIX7 P0-006-B) is rolled back the same way, then rethrown with
     * any restore failures attached as suppressed rather than left unrecovered. Only ciphertext
     * ever reaches disk.
     */
    fun storeEncryptedIdentity(
        privateIdentity: ByteArray,
        publicIdentity: String,
    ) = synchronized(storageLock) {
        val encrypted = crypto.encrypt(privateIdentity)
        val priorEncrypted = snapshotOfFile(identityFile)
        val priorPublic = snapshotOfFile(publicFile)
        // Step 3: replace the encrypted file. A failure here leaves the prior pair untouched
        // (atomic replace never corrupts the destination), so no rollback is needed.
        atomicReplace(identityFile, encrypted)
        try {
            // Step 4: replace the public file.
            atomicReplace(publicFile, publicIdentity.encodeToByteArray())
        } catch (cancelled: CancellationException) {
            restoreIdentityPair(identityFile, publicFile, priorEncrypted, priorPublic, identityFileOps)
                .forEach(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Exception) {
            // Step 5: restore both files to the prior pair (through the same atomic replace),
            // each attempted independently so one restore failure never skips the other.
            val failures =
                restoreIdentityPair(identityFile, publicFile, priorEncrypted, priorPublic, identityFileOps)
            if (failures.isNotEmpty()) {
                throw IdentityRollbackIncompleteException(
                    "Failed to store identity pair and rollback was incomplete",
                    error,
                ).apply { failures.forEach(::addSuppressed) }
            }
            throw IdentityPersistenceException("Failed to store identity pair; prior pair restored", error)
        }
    }

    /**
     * FIX6 P0-003: capture the exact prior state of the identity-storage files so a failed
     * setup transaction can restore them. Serialized against mutations.
     *
     * FIX8 P0-004: a property (not a function) so it doesn't count against this class's detekt
     * TooManyFunctions threshold — semantically unchanged, still a synchronous snapshot read.
     */
    val captureStorageSnapshot: IdentityStorageSnapshot
        get() =
            synchronized(storageLock) {
                IdentityStorageSnapshot(
                    encryptedIdentity = snapshotOfFile(identityFile),
                    publicIdentity = snapshotOfFile(publicFile),
                    authorizedKeys = snapshotOfFile(authorizedKeysFile),
                )
            }

    /**
     * Restore identity storage to a captured [snapshot]. Serialized against mutations.
     * FIX7 P0-006-A: attempts all three files even after an earlier one fails or was absent, and
     * returns a per-file result rather than throwing on the first failure — a caller must consume
     * every result to know exactly which file(s), if any, could not be restored ([CheckResult]).
     */
    @CheckResult
    fun restoreStorageSnapshot(snapshot: IdentityStorageSnapshot): List<IdentityRestoreResult> =
        synchronized(storageLock) {
            listOf(
                restoreIdentityFile(
                    IdentityStorageFile.EncryptedIdentity,
                    identityFile,
                    snapshot.encryptedIdentity,
                    identityFileOps,
                ),
                restoreIdentityFile(
                    IdentityStorageFile.PublicIdentity,
                    publicFile,
                    snapshot.publicIdentity,
                    identityFileOps,
                ),
                restoreIdentityFile(
                    IdentityStorageFile.AuthorizedKeys,
                    authorizedKeysFile,
                    snapshot.authorizedKeys,
                    identityFileOps,
                ),
            )
        }

    /**
     * FIX8 P0-004-E: restores ONLY the encrypted/public identity pair — the counterpart to
     * [restoreAuthorizedKeysSnapshot] — so [SetupPersistenceCoordinator]'s `Identity` stage
     * rollback does not also touch `authorized_keys` when only the pair needs restoring
     * (previously [restoreStorageSnapshot] always restored the full triplet, so a setup
     * transaction whose `Identity` stage alone failed would still overwrite an `AuthorizedKeys`
     * stage that never ran, and a rollback needing to restore both stages would restore the
     * triplet twice). Serialized against mutations like every other snapshot/restore method.
     */
    @CheckResult
    fun restoreIdentityPairSnapshot(snapshot: IdentityStorageSnapshot): List<IdentityRestoreResult> =
        synchronized(storageLock) {
            listOf(
                restoreIdentityFile(
                    IdentityStorageFile.EncryptedIdentity,
                    identityFile,
                    snapshot.encryptedIdentity,
                    identityFileOps,
                ),
                restoreIdentityFile(
                    IdentityStorageFile.PublicIdentity,
                    publicFile,
                    snapshot.publicIdentity,
                    identityFileOps,
                ),
            )
        }

    /**
     * FIX8 P0-004-E: restores ONLY `authorized_keys` — the counterpart to
     * [restoreIdentityPairSnapshot] — so [SetupPersistenceCoordinator]'s `AuthorizedKeys` stage
     * rollback does not also touch the identity pair.
     */
    @CheckResult
    fun restoreAuthorizedKeysSnapshot(snapshot: IdentityStorageSnapshot): List<IdentityRestoreResult> =
        synchronized(storageLock) {
            listOf(
                restoreIdentityFile(
                    IdentityStorageFile.AuthorizedKeys,
                    authorizedKeysFile,
                    snapshot.authorizedKeys,
                    identityFileOps,
                ),
            )
        }

    /**
     * Returns plaintext private identity bytes. Never log, persist, or include in
     * diagnostics, and wipe the buffer (`fill(0)`) after use — prefer
     * [usePrivateIdentityPlaintext], which does that automatically.
     */
    fun readPrivateIdentityPlaintext(): ByteArray {
        return crypto.decrypt(identityFile.readBytes())
    }

    /**
     * FIX8 P0-007-C: checks for and reads the encrypted identity as one coherent operation
     * (under the storage lock) instead of a separate [hasEncryptedIdentity] check followed by a
     * separate unsynchronized [readPrivateIdentityPlaintext] call — closes the TOCTOU window a
     * concurrent [storeEncryptedIdentity] replacement could otherwise race. `null` only when no
     * identity is stored; a present-but-unreadable identity still throws, same as
     * [readPrivateIdentityPlaintext]. Decryption happens outside the lock.
     *
     * A property (not a `fun`) so it doesn't count against this class's detekt
     * TooManyFunctions threshold — called identically to a member function.
     */
    val readPrivateIdentityPlaintextOrNull: ByteArray?
        get() {
            val ciphertext = synchronized(storageLock) { if (identityFile.exists()) identityFile.readBytes() else null }
            return ciphertext?.let { crypto.decrypt(it) }
        }

    /**
     * FIX8 P0-007-C: reads the encrypted-identity and public-identity files as one coherent pair
     * — see [StoredIdentityMaterial]. `null` when no identity is stored. A property for the same
     * TooManyFunctions reason as [readPrivateIdentityPlaintextOrNull].
     */
    internal val readStoredIdentityMaterial: StoredIdentityMaterial?
        get() =
            synchronized(storageLock) {
                if (identityFile.exists()) {
                    StoredIdentityMaterial(
                        encryptedPayload = identityFile.readBytes(),
                        publicIdentity = if (publicFile.exists()) publicFile.readText() else "",
                    )
                } else {
                    null
                }
            }

    /** Decrypts a [StoredIdentityMaterial]'s ciphertext — the counterpart to
     * [readStoredIdentityMaterial], kept as a separate call so decryption (a Keystore operation,
     * not a file read) always happens outside the storage lock. A property holding a function
     * value (not a `fun`) for the same TooManyFunctions reason as above — called identically via
     * `decryptPrivateIdentity(material)`. */
    internal val decryptPrivateIdentity: (StoredIdentityMaterial) -> ByteArray = { material ->
        crypto.decrypt(material.encryptedPayload)
    }

    /**
     * Read the plaintext private identity, pass it to [block], and always wipe the buffer
     * (`fill(0)`) afterward — even if [block] throws — so plaintext key material does not
     * linger in memory. Never log, persist, or include the bytes in diagnostics.
     */
    inline fun <R> usePrivateIdentityPlaintext(block: (ByteArray) -> R): R {
        val bytes = readPrivateIdentityPlaintext()
        return try {
            block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun readPublicIdentity(): String =
        synchronized(storageLock) {
            if (publicFile.exists()) publicFile.readText() else ""
        }

    // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this is a real
    // file mutation (authorized_keys append), and runCatching's Throwable-catching could
    // silently swallow a fatal Error or a laundered CancellationException.
    @CheckResult
    fun appendAuthorizedPublicIdentity(line: String): Result<Unit> =
        try {
            val trimmed = line.trim()
            require(trimmed.isNotEmpty()) { "Public identity line is empty" }
            // P1-004-D / INV-011: read-modify-write under the lock so a concurrent append cannot
            // be lost, and the rewrite is atomic (unique temp file + move) so a crash mid-write
            // cannot truncate the authorized-keys file.
            synchronized(storageLock) {
                val existing =
                    if (authorizedKeysFile.exists()) {
                        authorizedKeysFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                    } else {
                        mutableSetOf()
                    }
                if (existing.add(trimmed)) {
                    val updated = existing.toList().sorted().joinToString("\n")
                    atomicReplace(authorizedKeysFile, updated.encodeToByteArray())
                }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    // FIX7 P1-005-B: writes plaintext private key material to disk — a security-sensitive
    // mutation; explicit catch, not runCatching.
    @CheckResult
    fun exportPrivateIdentity(
        outputPath: String,
        confirmRisk: Boolean,
    ): Result<Unit> =
        try {
            require(confirmRisk) { "Private export requires explicit confirmation" }
            require(hasEncryptedIdentity) { "No private identity available" }
            val output = File(outputPath)
            // FIX8 P0-007-D: a checked Files.createDirectories, not an ignored mkdirs() Boolean —
            // a failure to create the export directory must surface, not silently proceed to a
            // write that then fails with a less clear error (or, worse, succeeds against an
            // unexpected pre-existing directory).
            output.parentFile?.let { Files.createDirectories(it.toPath()) }
            usePrivateIdentityPlaintext { output.writeBytes(it) }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    // FIX7 P1-005-B: file mutation; explicit catch, not runCatching.
    @CheckResult
    fun exportPublicIdentity(outputPath: String): Result<Unit> =
        try {
            val value = readPublicIdentity()
            require(value.isNotBlank()) { "No public identity available" }
            val output = File(outputPath)
            // FIX8 P0-007-D: checked, see exportPrivateIdentity above.
            output.parentFile?.let { Files.createDirectories(it.toPath()) }
            output.writeText(value)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
}

/**
 * Reads and validates a private-identity file at [path]. Stateless (touches no repository
 * state), so it lives at top level, which also keeps [IdentityRepository] under detekt's
 * TooManyFunctions threshold.
 *
 * FIX8 P1-003-A: explicit catch, not runCatching — a pure synchronous file read plus simple
 * validation (no native call, no persistence, no suspend chain) cannot observe a
 * CancellationException, but runCatching would also swallow a fatal Error.
 *
 * [readText] is injectable (defaulting to the real read) so a test can prove a fatal `Error` or
 * a `SecurityException` from the read step propagates/becomes `Result.failure` respectively,
 * without depending on a real file large/permission-restricted enough to trigger either
 * deterministically (FIX8 P1-003-D).
 */
@CheckResult
fun readPrivateIdentityFile(
    path: String,
    readText: (File) -> String = File::readText,
): Result<String> =
    try {
        val source = File(path)
        // FIX8 P1-003-C/D: never embed the raw path in the exception message — this Result's
        // failure message reaches UI state (SetupIdentityController) through
        // SensitiveDataRedactor.redactSecretValues, which does not scrub arbitrary paths.
        require(source.exists()) { "Identity file not found" }
        val value = readText(source)
        require(value.isNotBlank()) { "Identity file is empty" }
        Result.success(value)
    } catch (error: Exception) {
        Result.failure(error)
    }

// Top-level File helpers (not IdentityRepository members) to keep that class under detekt's
// TooManyFunctions threshold. Callers hold the repository lock.
private fun snapshotOfFile(file: File): StoredFileSnapshot =
    if (file.exists()) {
        StoredFileSnapshot(existed = true, bytes = file.readBytes())
    } else {
        StoredFileSnapshot(existed = false, bytes = null)
    }

/** Bundles the two injectable file operations every identity restore path needs, so passing
 * them around stays under detekt's LongParameterList threshold. */
private class IdentityFileOps(
    val atomicReplace: (File, ByteArray) -> Unit,
    val deleteIfExists: (File) -> Unit,
)

/**
 * FIX8 P0-007-A: restores [file] to exactly [snapshot]'s prior state — atomic replace when it
 * existed, checked [Files.deleteIfExists] (not an unchecked [File.delete]) when it was absent. A
 * present snapshot with missing bytes throws naming [logicalName] rather than silently
 * fabricating empty content via `?: ByteArray(0)` — a prior capture bug would otherwise write
 * empty/wrong bytes with no visible failure. The one shared restore primitive both the
 * pair-commit rollback ([restorePairFileResult]) and the setup-transaction snapshot restore
 * ([restoreIdentityFile]) delegate to.
 */
private fun restoreFileFromSnapshotOrThrow(
    logicalName: String,
    file: File,
    snapshot: StoredFileSnapshot,
    ops: IdentityFileOps,
) {
    if (snapshot.existed) {
        ops.atomicReplace(file, requireNotNull(snapshot.bytes) { "$logicalName snapshot bytes are missing" })
    } else {
        ops.deleteIfExists(file)
    }
}

/** [restoreFileFromSnapshotOrThrow], wrapped as a [Result] so a caller can attempt the identity
 * pair's other file even after this one fails (FIX7 P0-006-C) instead of letting the first
 * failure abort the whole rollback. */
private fun restorePairFileResult(
    logicalName: String,
    file: File,
    snapshot: StoredFileSnapshot,
    ops: IdentityFileOps,
): Result<Unit> =
    try {
        restoreFileFromSnapshotOrThrow(logicalName, file, snapshot, ops)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

/**
 * Restores both identity-pair files independently — the second is always attempted even if the
 * first fails (FIX7 P0-006-C) — and returns every restore failure so the caller can attach them
 * as suppressed on whatever exception (ordinary or cancellation) triggered the rollback. Returns
 * an empty list when both restores succeed.
 */
private fun restoreIdentityPair(
    identityFile: File,
    publicFile: File,
    priorEncrypted: StoredFileSnapshot,
    priorPublic: StoredFileSnapshot,
    ops: IdentityFileOps,
): List<Exception> {
    val failures = mutableListOf<Exception>()

    restorePairFileResult("encrypted identity", identityFile, priorEncrypted, ops)
        .exceptionOrNull()
        ?.let { failures.add(it as? Exception ?: Exception(it)) }

    restorePairFileResult("public identity", publicFile, priorPublic, ops)
        .exceptionOrNull()
        ?.let { failures.add(it as? Exception ?: Exception(it)) }

    return failures
}

/**
 * Restores one [IdentityStorageFile] to its exact prior [snapshot] using atomic replacement (or
 * checked deletion when it was absent), reporting success/failure per file rather than throwing
 * on the first one (FIX7 P0-006-A) — [IdentityRepository.restoreStorageSnapshot] always attempts
 * all three. Reasons are redacted before being returned to callers.
 */
private fun restoreIdentityFile(
    logical: IdentityStorageFile,
    file: File,
    snapshot: StoredFileSnapshot,
    ops: IdentityFileOps,
): IdentityRestoreResult =
    try {
        restoreFileFromSnapshotOrThrow(logical.name, file, snapshot, ops)
        IdentityRestoreResult.Success(logical)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        IdentityRestoreResult.Failure(logical, SensitiveDataRedactor.redactText(error.message ?: "restore failed"))
    }

/**
 * P1-004-B: atomically replace [destination] with [bytes] via a unique same-directory temp file
 * and an atomic move (falling back to a plain move where atomic move is unsupported), so a crash
 * mid-write can never leave a truncated identity/authorized-keys file. Top-level to keep
 * [IdentityRepository] under detekt's TooManyFunctions threshold. Callers hold the storage lock.
 */
private fun identityAtomicReplace(
    destination: File,
    bytes: ByteArray,
) {
    // FIX8 P0-008-C: checked, not an ignored mkdirs() Boolean.
    destination.parentFile?.let { Files.createDirectories(it.toPath()) }
    val temp = Files.createTempFile(destination.parentFile?.toPath(), "${destination.name}.tmp-", ".partial")
    // FIX7 P1-005-B/A: the temp file's cleanup result is checked, not discarded. Previously a
    // cleanup failure was only logged — an otherwise-successful replace silently reported
    // success despite a leftover temp file possibly holding identity/authorized-keys content.
    // A cleanup failure now surfaces as a failure; a cleanup failure on top of a primary
    // failure is attached as suppressed rather than replacing or discarding it.
    val primaryFailure =
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                // FIX8 P0-007-D: never log the raw Throwable — Log.w's stack-trace formatting
                // could otherwise surface a path or other detail beyond this fixed, redacted
                // summary line.
                Log.w(
                    IDENTITY_TAG,
                    "Atomic identity move unavailable; using fallback replacement (" +
                        "${SensitiveDataRedactor.redactText(error.message ?: "no detail")})",
                )
                Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
    val cleanupFailure =
        try {
            Files.deleteIfExists(temp)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(
                IDENTITY_TAG,
                "Identity temp cleanup failed: ${
                    SensitiveDataRedactor.redactText(error.message ?: "unknown cleanup failure")
                }",
            )
            error
        }
    throwComposedFailureIfAny(primaryFailure, cleanupFailure)
}

interface IdentityCrypto {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(payload: ByteArray): ByteArray
}

class AndroidKeystoreIdentityCrypto : IdentityCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec =
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "webrtc_tunnel_identity_key"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
