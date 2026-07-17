package com.phillipchin.webrtctunnel.security

import android.content.Context
import android.util.Log
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
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

class IdentityRepository(
    private val context: Context,
    private val crypto: IdentityCrypto = AndroidKeystoreIdentityCrypto(),
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

    fun hasEncryptedIdentity(): Boolean = identityFile.exists()

    /**
     * P1-004-C: commit the encrypted-identity + public-identity pair as one logical unit.
     * Encrypt first, snapshot the prior pair, atomically replace the encrypted file, then the
     * public file; if the public replace fails, restore BOTH prior files so the pair can never
     * be left mismatched. Throws [IdentityPersistenceException] when the prior pair was restored,
     * or [IdentityRollbackIncompleteException] when restoration itself failed (so the mismatch is
     * visible, not silent). Only ciphertext ever reaches disk.
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
            throw cancelled
        } catch (error: Exception) {
            // Step 5: restore both files to the prior pair (through the same atomic replace).
            val rolledBack =
                runCatching {
                    restorePairFile(identityFile, priorEncrypted, atomicReplace)
                    restorePairFile(publicFile, priorPublic, atomicReplace)
                }
            if (rolledBack.isFailure) {
                throw IdentityRollbackIncompleteException(
                    "Failed to store identity pair and rollback was incomplete",
                    error,
                )
            }
            throw IdentityPersistenceException("Failed to store identity pair; prior pair restored", error)
        }
    }

    /**
     * FIX6 P0-003: capture the exact prior state of the identity-storage files so a failed
     * setup transaction can restore them. Serialized against mutations.
     */
    fun captureStorageSnapshot(): IdentityStorageSnapshot =
        synchronized(storageLock) {
            IdentityStorageSnapshot(
                encryptedIdentity = snapshotOfFile(identityFile),
                publicIdentity = snapshotOfFile(publicFile),
                authorizedKeys = snapshotOfFile(authorizedKeysFile),
            )
        }

    /** Restore identity storage to a captured [snapshot]. Serialized against mutations. */
    fun restoreStorageSnapshot(snapshot: IdentityStorageSnapshot) =
        synchronized(storageLock) {
            restoreFileFromSnapshot(identityFile, snapshot.encryptedIdentity)
            restoreFileFromSnapshot(publicFile, snapshot.publicIdentity)
            restoreFileFromSnapshot(authorizedKeysFile, snapshot.authorizedKeys)
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

    fun readPublicIdentity(): String = if (publicFile.exists()) publicFile.readText() else ""

    fun appendAuthorizedPublicIdentity(line: String): Result<Unit> =
        runCatching {
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
        }

    fun exportPrivateIdentity(
        outputPath: String,
        confirmRisk: Boolean,
    ): Result<Unit> =
        runCatching {
            require(confirmRisk) { "Private export requires explicit confirmation" }
            require(hasEncryptedIdentity()) { "No private identity available" }
            val output = File(outputPath)
            output.parentFile?.mkdirs()
            usePrivateIdentityPlaintext { output.writeBytes(it) }
        }

    fun exportPublicIdentity(outputPath: String): Result<Unit> =
        runCatching {
            val value = readPublicIdentity()
            require(value.isNotBlank()) { "No public identity available" }
            val output = File(outputPath)
            output.parentFile?.mkdirs()
            output.writeText(value)
        }
}

/**
 * Reads and validates a private-identity file at [path]. Stateless (touches no repository
 * state), so it lives at top level, which also keeps [IdentityRepository] under detekt's
 * TooManyFunctions threshold.
 */
fun readPrivateIdentityFile(path: String): Result<String> =
    runCatching {
        val source = File(path)
        require(source.exists()) { "Identity file not found: $path" }
        val value = source.readText()
        require(value.isNotBlank()) { "Identity file is empty" }
        value
    }

// Top-level File helpers (not IdentityRepository members) to keep that class under detekt's
// TooManyFunctions threshold. Callers hold the repository lock.
private fun snapshotOfFile(file: File): StoredFileSnapshot =
    if (file.exists()) {
        StoredFileSnapshot(existed = true, bytes = file.readBytes())
    } else {
        StoredFileSnapshot(existed = false, bytes = null)
    }

private fun restoreFileFromSnapshot(
    file: File,
    snapshot: StoredFileSnapshot,
) {
    if (snapshot.existed) {
        file.parentFile?.mkdirs()
        file.writeBytes(snapshot.bytes ?: ByteArray(0))
    } else {
        file.delete()
    }
}

// P1-004-C: restore one file of the identity pair during rollback — atomically replace it with
// its prior bytes, or delete it if it was absent. Uses the same [atomicReplace] as the forward
// write so an injected failure exercises the rollback-incomplete path.
private fun restorePairFile(
    file: File,
    snapshot: StoredFileSnapshot,
    atomicReplace: (File, ByteArray) -> Unit,
) {
    if (snapshot.existed) {
        atomicReplace(file, snapshot.bytes ?: ByteArray(0))
    } else {
        file.delete()
    }
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
    destination.parentFile?.mkdirs()
    val temp = Files.createTempFile(destination.parentFile?.toPath(), "${destination.name}.tmp-", ".partial")
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
            Log.w(IDENTITY_TAG, "Atomic identity move unavailable; using replacement", error)
            Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        runCatching { Files.deleteIfExists(temp) }
            .onFailure { cleanup ->
                Log.w(
                    IDENTITY_TAG,
                    "Identity temp cleanup failed: ${
                        SensitiveDataRedactor.redactText(cleanup.message ?: "unknown cleanup failure")
                    }",
                )
            }
    }
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
