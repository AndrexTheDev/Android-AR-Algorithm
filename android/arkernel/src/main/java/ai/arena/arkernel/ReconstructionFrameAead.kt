package ai.arena.arkernel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Strict AES-256-GCM envelope for optional authenticated encryption of
 * retained reconstruction keyframes and their frame-owned depth companions.
 * The ciphertext is bound to a domain separator, session ID, file name and
 * payload kind through AAD, so files cannot be swapped between frames,
 * sessions or colour/depth roles. The non-exportable key lives in Android
 * Keystore; deletion is an explicit host retention action. CRC inside the
 * plaintext still detects accidental corruption, while the GCM tag supplies
 * authentication. This protects bytes at rest only, not host UI consent or
 * cross-session world alignment.
 */
internal object ReconstructionFrameAeadCodec {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private const val HEADER_BYTES = 8 + 1 + 1 + 4
    private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024 + 256
    private const val MIN_ENVELOPE_BYTES = HEADER_BYTES + GCM_NONCE_BYTES + GCM_TAG_BYTES
    private const val MAX_ENVELOPE_BYTES =
        HEADER_BYTES + GCM_NONCE_BYTES + MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
    private val MAGIC = byteArrayOf(0x41, 0x52, 0x4b, 0x46, 0x45, 0x41, 0x44, 0x31) // ARKFEAD1
    private const val VERSION: Byte = 1
    private val KEY_CREATION_LOCK = Any()

    fun encrypt(plaintext: ByteArray, aad: ByteArray, keyAlias: String): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "Authenticated frame plaintext is outside its bounded size"
        }
        require(aad.isNotEmpty() && keyAlias.isNotBlank()) {
            "Authenticated frame context is invalid"
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyAlias))
        val nonce = cipher.iv
        check(nonce.size == GCM_NONCE_BYTES) { "Unexpected AES-GCM nonce size" }
        val ciphertextBytes = cipher.getOutputSize(plaintext.size)
        val header = makeHeader(nonce.size, ciphertextBytes)
        cipher.updateAAD(header + aad)
        val ciphertext = cipher.doFinal(plaintext)
        check(ciphertext.size == ciphertextBytes) { "Unexpected AES-GCM output size" }
        return ByteBuffer.allocate(HEADER_BYTES + nonce.size + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN).put(header).put(nonce).put(ciphertext).array()
    }

    fun decrypt(envelope: ByteArray, aad: ByteArray, keyAlias: String): ByteArray {
        require(aad.isNotEmpty() && keyAlias.isNotBlank()) {
            "Authenticated frame context is invalid"
        }
        require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            "Authenticated frame envelope has an invalid size"
        }
        val input = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC)) { "Authenticated frame magic is invalid" }
        require(input.get() == VERSION) { "Unsupported authenticated frame version" }
        val nonceLength = input.get().toInt() and 0xff
        val ciphertextLength = input.int
        require(nonceLength == GCM_NONCE_BYTES) { "Authenticated frame nonce size is invalid" }
        require(ciphertextLength in GCM_TAG_BYTES..(MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES)) {
            "Authenticated frame ciphertext size is invalid"
        }
        require(envelope.size == HEADER_BYTES + nonceLength + ciphertextLength) {
            "Authenticated frame envelope length is inconsistent"
        }
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val nonce = ByteArray(nonceLength).also { input.get(it) }
        val ciphertext = ByteArray(ciphertextLength).also { input.get(it) }
        val key = existingKey(keyAlias) ?: throw SecurityException(
            "Encrypted frame exists but its Android Keystore key is unavailable")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(header + aad)
        return try {
            cipher.doFinal(ciphertext).also {
                require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES) {
                    "Decrypted frame plaintext is outside its bounded size"
                }
            }
        } catch (error: AEADBadTagException) {
            throw SecurityException("Authenticated frame decryption failed", error)
        }
    }

    fun aad(sessionId: String, fileName: String, kind: Byte): ByteArray {
        val domain = "arkf-aead-v1".toByteArray(Charsets.US_ASCII)
        val session = sessionId.toByteArray(Charsets.UTF_8)
        val name = fileName.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + domain.size + 4 + session.size + 4 + name.size + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(domain.size).put(domain)
            .putInt(session.size).put(session)
            .putInt(name.size).put(name)
            .put(kind).array()
    }

    fun deleteKey(keyAlias: String): Boolean {
        val store = keyStore()
        if (!store.containsAlias(keyAlias)) return false
        store.deleteEntry(keyAlias)
        return true
    }

    private fun makeHeader(nonceLength: Int, ciphertextLength: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(VERSION).put(nonceLength.toByte()).putInt(ciphertextLength).array()

    private fun getOrCreateKey(keyAlias: String): SecretKey = synchronized(KEY_CREATION_LOCK) {
        existingKey(keyAlias) ?: run {
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

    private fun existingKey(keyAlias: String): SecretKey? =
        keyStore().getKey(keyAlias, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    const val KIND_COLOUR: Byte = 0
    const val KIND_DEPTH: Byte = 1
}

enum class ReconstructionFrameRetentionMode {
    KEEP,
    DELETE_AFTER_PUBLICATION,
    DELETE_ON_EXPLICIT_RELEASE,
}

/** Explicit host policy; no automatic deletion happens inside the archive. */
data class ReconstructionFrameRetentionPolicy(
    val mode: ReconstructionFrameRetentionMode = ReconstructionFrameRetentionMode.KEEP,
    val deleteEncryptionKey: Boolean = false,
) {
    init {
        require(mode != ReconstructionFrameRetentionMode.KEEP || !deleteEncryptionKey) {
            "Keeping frames while deleting their encryption key would make them unreadable"
        }
    }
}

data class ReconstructionFrameRetentionAction(
    val deleteFrames: Boolean,
    val deleteEncryptionKey: Boolean,
    val reason: String,
)

/** Bounded explicit retention action sink; the host owns when to apply actions. */
interface ArKernelRetentionActionSink {
    val authenticatedEncryption: Boolean
    fun clearFrames(deleteKey: Boolean): Boolean
    fun deleteEncryptionKey(): Boolean
}

/**
 * Host-facing retention control for temporary scans. It turns a terminal
 * publication outcome or an explicit release into a bounded action; the host
 * decides when to show UI and call [apply]. A bounded action sink keeps the
 * policy logic independently testable. Localized app UI remains separate.
 */
class ReconstructionFrameRetentionController(
    private val actionSink: ArKernelRetentionActionSink,
    private val policy: ReconstructionFrameRetentionPolicy = ReconstructionFrameRetentionPolicy(),
) {
    constructor(
        archive: ReconstructionFrameArchive,
        policy: ReconstructionFrameRetentionPolicy = ReconstructionFrameRetentionPolicy(),
    ) : this(archive as ArKernelRetentionActionSink, policy)

    fun afterPublication(complete: Boolean): ReconstructionFrameRetentionAction {
        val deleteFrames = complete &&
            policy.mode == ReconstructionFrameRetentionMode.DELETE_AFTER_PUBLICATION
        return action(deleteFrames,
            if (deleteFrames) "temporary scan published successfully" else "frames retained")
    }

    fun release(): ReconstructionFrameRetentionAction {
        val deleteFrames = policy.mode != ReconstructionFrameRetentionMode.KEEP
        return action(deleteFrames,
            if (deleteFrames) "host explicitly released retained frames" else "frames retained")
    }

    fun apply(action: ReconstructionFrameRetentionAction): Boolean = when {
        action.deleteFrames -> actionSink.clearFrames(action.deleteEncryptionKey)
        action.deleteEncryptionKey -> actionSink.deleteEncryptionKey()
        else -> true
    }

    private fun action(deleteFrames: Boolean, reason: String): ReconstructionFrameRetentionAction {
        val deleteKey = deleteFrames && policy.deleteEncryptionKey &&
            actionSink.authenticatedEncryption
        return ReconstructionFrameRetentionAction(deleteFrames, deleteKey, reason)
    }
}
