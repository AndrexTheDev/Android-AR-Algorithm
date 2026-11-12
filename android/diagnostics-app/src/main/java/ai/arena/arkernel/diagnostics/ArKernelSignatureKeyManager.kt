package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelSignatureKeyOrigin
import ai.arena.arkernel.ArKernelSignatureManifestAdapter
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Holds the `ARKSIGN1` signing key.
 *
 * The private key is created inside the Android keystore when the device
 * offers one, so it cannot be exported. Only if that fails — an emulator, a
 * broken keystore or a very old build — does the app fall back to a software
 * EC key stored base64-encoded in its own private files directory, which the
 * Android sandbox protects but which is not hardware backed. The origin is
 * reported in every manifest as `key_origin`, so a weaker key is never
 * silently presented as a strong one.
 *
 * This proves integrity and key continuity, not identity: there is no
 * certificate authority, no attestation and no trust chain. A rooted device
 * or a copied software key can still produce a valid signature.
 */
object ArKernelSignatureKeyManager {

    private const val ALIAS = "arkernel.arksign1.v1"
    private const val ALGORITHM = "SHA256withECDSA"
    private const val CURVE = "secp256r1"
    private const val PUBLIC_FILE = "arksign1.public.b64"
    private const val PRIVATE_FILE = "arksign1.private.b64"

    data class KeyInfo(
        val origin: ArKernelSignatureKeyOrigin,
        val publicKeyBase64: String,
        val fingerprint: String,
    )

    /** Loads or creates the signing key; null when no key can be made. */
    fun loadOrCreate(context: Context): KeyInfo? {
        val resolved = resolve(context) ?: return null
        val encoded = resolved.first.public.encoded ?: return null
        return KeyInfo(
            origin = resolved.second,
            publicKeyBase64 = Base64.getEncoder().encodeToString(encoded),
            fingerprint = ArKernelSignatureManifestAdapter
                .fingerprintOf(resolved.first.public),
        )
    }

    /** Signs [data]; null when the signature could not be produced. */
    fun sign(context: Context, data: ByteArray): ByteArray? = try {
        val privateKey = resolve(context)?.first?.private ?: return null
        Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }
    } catch (error: Exception) {
        null
    }

    /** Parses a base64 SPKI public key such as the exported one. */
    fun publicKeyFromBase64(text: String): PublicKey? = try {
        val bytes = Base64.getDecoder().decode(text.trim())
        KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(bytes))
    } catch (error: Exception) {
        null
    }

    private fun resolve(
        context: Context,
    ): Pair<KeyPair, ArKernelSignatureKeyOrigin>? {
        val hardware = keystoreKeyPair()
        if (hardware != null) {
            return hardware to ArKernelSignatureKeyOrigin.ANDROID_KEYSTORE
        }
        val software = softwareKeyPair(context)
            ?: return null
        return software to ArKernelSignatureKeyOrigin.SOFTWARE
    }

    private fun keystoreKeyPair(): KeyPair? = try {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (!store.containsAlias(ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            ).apply { initialize(spec) }.generateKeyPair()
        }
        val entry = store.getEntry(ALIAS, null)
            as? KeyStore.PrivateKeyEntry ?: return null
        KeyPair(entry.certificate.publicKey, entry.privateKey)
    } catch (error: Exception) {
        null
    }

    private fun softwareKeyPair(context: Context): KeyPair? {
        val publicFile = File(context.filesDir, PUBLIC_FILE)
        val privateFile = File(context.filesDir, PRIVATE_FILE)
        if (publicFile.isFile && privateFile.isFile) {
            return try {
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(
                        Base64.getDecoder().decode(publicFile.readText())))
                val privateKey: PrivateKey = KeyFactory.getInstance("EC")
                    .generatePrivate(PKCS8EncodedKeySpec(
                        Base64.getDecoder().decode(privateFile.readText())))
                KeyPair(publicKey, privateKey)
            } catch (error: Exception) {
                null
            }
        }
        return try {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            val pair = generator.generateKeyPair()
            publicFile.writeText(Base64.getEncoder()
                .encodeToString(pair.public.encoded))
            privateFile.writeText(Base64.getEncoder()
                .encodeToString(pair.private.encoded))
            pair
        } catch (error: Exception) {
            null
        }
    }
}
