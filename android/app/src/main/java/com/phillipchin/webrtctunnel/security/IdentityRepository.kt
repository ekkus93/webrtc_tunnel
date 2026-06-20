package com.phillipchin.webrtctunnel.security

import android.content.Context
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IdentityRepository(
    private val context: Context,
    private val crypto: IdentityCrypto = AndroidKeystoreIdentityCrypto(),
) {
    private val identityFile = File(context.filesDir, "identity.enc")
    private val publicFile = File(context.filesDir, "identity.pub")
    private val authorizedKeysFile = File(context.filesDir, "authorized_keys")

    fun hasEncryptedIdentity(): Boolean = identityFile.exists()

    fun storeEncryptedIdentity(
        privateIdentity: ByteArray,
        publicIdentity: String,
    ) {
        identityFile.writeBytes(crypto.encrypt(privateIdentity))
        publicFile.writeText(publicIdentity)
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

    fun readPrivateIdentityFile(path: String): Result<String> =
        runCatching {
            val source = File(path)
            require(source.exists()) { "Identity file not found: $path" }
            val value = source.readText()
            require(value.isNotBlank()) { "Identity file is empty" }
            value
        }

    fun appendAuthorizedPublicIdentity(line: String): Result<Unit> =
        runCatching {
            val trimmed = line.trim()
            require(trimmed.isNotEmpty()) { "Public identity line is empty" }
            val existing =
                if (authorizedKeysFile.exists()) {
                    authorizedKeysFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                } else {
                    mutableSetOf()
                }
            if (existing.add(trimmed)) {
                authorizedKeysFile.parentFile?.mkdirs()
                authorizedKeysFile.writeText(existing.toList().sorted().joinToString("\n"))
            }
        }

    fun writeAuthorizedPublicIdentities(lines: List<String>) {
        val unique = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        authorizedKeysFile.parentFile?.mkdirs()
        authorizedKeysFile.writeText(unique.joinToString("\n"))
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
