package ai.arena.arkernel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated local storage for plaintext ARKMAP01 snapshots. Files live in
 * no-backup storage. The AES key is non-exportable and owned by Android
 * Keystore. This protects bytes at rest; cross-session world alignment still
 * requires relocalization before restored geometry may be rendered.
 */
class ArKernelMapStore(
    context: Context,
    fileName: String = "arkernel-map.aead",
    private val keyAlias: String = "ai.arena.arkernel.spatial-map.v1",
) {
    private val file: AtomicFile

    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) {
            "Map filename must be a simple local filename"
        }
        require(keyAlias.isNotBlank()) { "Keystore alias must not be blank" }
        file = AtomicFile(File(context.noBackupFilesDir, fileName))
    }

    @Synchronized
    fun save(plaintextSnapshot: ByteArray) {
        require(plaintextSnapshot.isNotEmpty()) { "Spatial snapshot must not be empty" }
        require(plaintextSnapshot.size <= MAX_SNAPSHOT_BYTES) { "Spatial snapshot is too large" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val nonce = cipher.iv
        check(nonce.size == GCM_NONCE_BYTES) { "Unexpected AES-GCM nonce size" }
        val expectedCiphertextBytes = cipher.getOutputSize(plaintextSnapshot.size)
        val header = makeHeader(nonce.size, expectedCiphertextBytes)
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(plaintextSnapshot)
        check(ciphertext.size == expectedCiphertextBytes) { "Unexpected AES-GCM output size" }

        val stream = file.startWrite()
        try {
            stream.write(header)
            stream.write(nonce)
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun load(): ByteArray? {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return null }
        val length = file.baseFile.length()
        require(length in MIN_ENVELOPE_BYTES.toLong()..MAX_ENVELOPE_BYTES.toLong()) {
            input.close()
            "Encrypted map envelope has an invalid size"
        }
        val envelope = input.use { it.readBytes() }
        require(envelope.size.toLong() == length) { "Encrypted map envelope was truncated" }
        val buffer = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        require(magic.contentEquals(MAGIC)) { "Encrypted map envelope magic is invalid" }
        require(buffer.get() == ENVELOPE_VERSION) { "Unsupported encrypted map envelope version" }
        val nonceLength = buffer.get().toInt() and 0xff
        val ciphertextLength = buffer.int
        require(nonceLength == GCM_NONCE_BYTES) { "Encrypted map nonce size is invalid" }
        require(ciphertextLength in GCM_TAG_BYTES..(MAX_SNAPSHOT_BYTES + GCM_TAG_BYTES)) {
            "Encrypted map ciphertext size is invalid"
        }
        require(envelope.size == HEADER_BYTES + nonceLength + ciphertextLength) {
            "Encrypted map envelope length is inconsistent"
        }
        val nonce = ByteArray(nonceLength).also { buffer.get(it) }
        val ciphertext = ByteArray(ciphertextLength).also { buffer.get(it) }
        val key = existingKey() ?: throw SecurityException(
            "Encrypted map exists but its Android Keystore key is unavailable")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(envelope.copyOfRange(0, HEADER_BYTES))
        return try {
            cipher.doFinal(ciphertext).also {
                require(it.size <= MAX_SNAPSHOT_BYTES) { "Decrypted spatial snapshot is too large" }
            }
        } catch (error: AEADBadTagException) {
            throw SecurityException("Encrypted map authentication failed", error)
        }
    }

    @Synchronized
    fun delete(deleteKey: Boolean = false) {
        file.delete()
        if (deleteKey) keyStore().deleteEntry(keyAlias)
    }

    private fun makeHeader(nonceLength: Int, ciphertextLength: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(ENVELOPE_VERSION).put(nonceLength.toByte()).putInt(ciphertextLength).array()

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_CREATION_LOCK) {
        existingKey() ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build())
            generator.generateKey()
        }
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(keyAlias, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        val MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x41, 0x45, 0x41, 0x44, 0x31)
        const val ENVELOPE_VERSION: Byte = 1
        const val HEADER_BYTES = 8 + 1 + 1 + 4
        const val MIN_ENVELOPE_BYTES = HEADER_BYTES + GCM_NONCE_BYTES + GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES = HEADER_BYTES + GCM_NONCE_BYTES +
            MAX_SNAPSHOT_BYTES + GCM_TAG_BYTES
        val KEY_CREATION_LOCK = Any()
    }
}
