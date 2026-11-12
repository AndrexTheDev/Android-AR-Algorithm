package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class ArKernelSignatureManifestTest {

    companion object {
        private lateinit var keyPair: KeyPair

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            keyPair = generator.generateKeyPair()
        }

        private fun signerFor(privateKey: PrivateKey): (ByteArray) -> ByteArray =
            { data ->
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(privateKey)
                signer.update(data)
                signer.sign()
            }

        private fun publicKey(): PublicKey = keyPair.public

        private fun entries(): List<ArKernelSignatureEntryInput> = listOf(
            ArKernelSignatureEntryInput(
                artifactName = "ARKFEED1_1700000000000.txt",
                sourceText = "ARKFEED1\nversion=1\nEND\n",
            ),
            ArKernelSignatureEntryInput(
                artifactName = "ARKVALID1_1700000000001.txt",
                sourceText = "ARKVALID1\nversion=1\nEND\n",
            ),
        )

        private fun createSigned(): ArKernelSignedManifest {
            val result = ArKernelSignatureManifestAdapter.create(
                manifestId = "sign.session.v1",
                deviceToken = "phone.redmi.v1",
                keyOrigin = ArKernelSignatureKeyOrigin.ANDROID_KEYSTORE,
                publicKey = publicKey(),
                entries = entries(),
                signer = signerFor(keyPair.private),
            )
            check(result.accepted) { result.detail }
            return checkNotNull(result.signed)
        }

        private fun withValue(
            text: String,
            key: String,
            value: String,
        ): String {
            val lines = text.trimEnd('\n').split('\n').toMutableList()
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            check(index >= 0) { "missing key $key" }
            lines[index] = "$key=$value"
            return lines.joinToString("\n") + "\n"
        }

        private fun replaceEntry(
            text: String,
            entryIndex: Int,
            newLine: String,
        ): String {
            val lines = text.trimEnd('\n').split('\n').toMutableList()
            val index = lines.indexOfFirst {
                it.startsWith("entry=$entryIndex|")
            }
            check(index >= 0) { "missing entry $entryIndex" }
            lines[index] = newLine
            return lines.joinToString("\n") + "\n"
        }
    }

    @Test
    fun createSignsAndVerifyAccepts() {
        val signed = createSigned()
        val encoded = ArKernelSignatureManifestAdapter.encode(signed)
        val parsed = ArKernelSignatureManifestAdapter.parse(encoded)
        assertTrue(parsed.detail, parsed.accepted)
        val result = ArKernelSignatureManifestAdapter
            .verify(checkNotNull(parsed.signed), publicKey())
        assertTrue(result.detail, result.verified)
        assertTrue(result.keyFingerprintMatches)
        assertTrue(result.signatureValid)
        assertEquals(
            ArKernelSignatureManifestAdapter.fingerprintOf(publicKey()),
            result.publicKeyFingerprint,
        )
    }

    @Test
    fun fingerprintIsStableHexOverTheEncodedKey() {
        val first = ArKernelSignatureManifestAdapter
            .fingerprintOf(publicKey())
        assertEquals(64, first.length)
        assertEquals(first, ArKernelSignatureManifestAdapter
            .fingerprintOf(publicKey()))
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun entryDigestsMatchTheSourceTexts() {
        val signed = createSigned()
        assertEquals(2, signed.manifest.entries.size)
        assertEquals(
            ArKernelSignatureManifestAdapter
                .digestOf("ARKFEED1\nversion=1\nEND\n"),
            signed.manifest.entries[0].digest)
        assertEquals("ARKFEED1_1700000000000.txt",
            signed.manifest.entries[0].artifactName)
    }

    @Test
    fun parseRejectsTamperedEntryDigest() {
        val encoded = ArKernelSignatureManifestAdapter.encode(createSigned())
        val tampered = replaceEntry(
            encoded,
            0,
            "entry=0|ARKFEED1_1700000000000.txt|" + "a".repeat(64),
        )
        val result = ArKernelSignatureManifestAdapter.parse(tampered)
        assertEquals(
            ArKernelSignatureRejection.MANIFEST_DIGEST_MISMATCH,
            result.rejection)
    }

    @Test
    fun parseRejectsClaimedMetricAccuracy() {
        val encoded = ArKernelSignatureManifestAdapter.encode(createSigned())
        val result = ArKernelSignatureManifestAdapter
            .parse(withValue(encoded, "metric_accuracy_claimed", "true"))
        assertEquals(
            ArKernelSignatureRejection.UNSAFE_INVARIANT,
            result.rejection)
    }

    @Test
    fun parseRejectsWrongAlgorithmAndOrigin() {
        val encoded = ArKernelSignatureManifestAdapter.encode(createSigned())
        assertEquals(
            ArKernelSignatureRejection.INVALID_ALGORITHM,
            ArKernelSignatureManifestAdapter
                .parse(withValue(encoded, "signature_algorithm", "ed25519"))
                .rejection)
        assertEquals(
            ArKernelSignatureRejection.INVALID_KEY_ORIGIN,
            ArKernelSignatureManifestAdapter
                .parse(withValue(encoded, "key_origin", "hardware"))
                .rejection)
    }

    @Test
    fun parseRejectsEmptyAndMalformedInput() {
        assertEquals(
            ArKernelSignatureRejection.EMPTY_MANIFEST,
            ArKernelSignatureManifestAdapter.parse("  ").rejection)
        assertEquals(
            ArKernelSignatureRejection.INVALID_HEADER,
            ArKernelSignatureManifestAdapter
                .parse("ARKSIGN0\nEND\n").rejection)
    }

    @Test
    fun verifyFailsForADifferentKey() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val result = ArKernelSignatureManifestAdapter
            .verify(createSigned(), other.public)
        assertFalse(result.verified)
        assertFalse(result.keyFingerprintMatches)
        assertNotEquals(
            ArKernelSignatureManifestAdapter.fingerprintOf(publicKey()),
            result.publicKeyFingerprint)
    }

    @Test
    fun verifyFailsWhenTheSignatureDoesNotMatch() {
        val signed = createSigned()
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val forged = ArKernelSignatureManifestAdapter.create(
            manifestId = signed.manifest.manifestId,
            deviceToken = signed.manifest.deviceToken,
            keyOrigin = signed.manifest.keyOrigin,
            publicKey = publicKey(),
            entries = listOf(
                ArKernelSignatureEntryInput(
                    artifactName = "ARKFEED1_other.txt",
                    sourceText = "ARKFEED1\nversion=1\nEND\n",
                ),
            ),
            signer = signerFor(other.private),
        )
        check(forged.accepted) { forged.detail }
        val result = ArKernelSignatureManifestAdapter
            .verify(checkNotNull(forged.signed), publicKey())
        assertTrue(result.keyFingerprintMatches)
        assertFalse(result.signatureValid)
        assertFalse(result.verified)
    }

    @Test
    fun createRejectsDuplicateArtefactName() {
        val result = ArKernelSignatureManifestAdapter.create(
            manifestId = "sign.session.v2",
            deviceToken = "phone.redmi.v1",
            keyOrigin = ArKernelSignatureKeyOrigin.SOFTWARE,
            publicKey = publicKey(),
            entries = listOf(
                ArKernelSignatureEntryInput("same.txt", "one\n"),
                ArKernelSignatureEntryInput("same.txt", "two\n"),
            ),
            signer = signerFor(keyPair.private),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelSignatureRejection.INVALID_ROW,
            result.rejection)
    }

    @Test
    fun createRejectsEmptySourceText() {
        val result = ArKernelSignatureManifestAdapter.create(
            manifestId = "sign.session.v3",
            deviceToken = "phone.redmi.v1",
            keyOrigin = ArKernelSignatureKeyOrigin.SOFTWARE,
            publicKey = publicKey(),
            entries = listOf(
                ArKernelSignatureEntryInput("empty.txt", "   "),
            ),
            signer = signerFor(keyPair.private),
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelSignatureRejection.EMPTY_SOURCE,
            result.rejection)
    }

    @Test
    fun createRejectsFailingSigner() {
        val result = ArKernelSignatureManifestAdapter.create(
            manifestId = "sign.session.v4",
            deviceToken = "phone.redmi.v1",
            keyOrigin = ArKernelSignatureKeyOrigin.SOFTWARE,
            publicKey = publicKey(),
            entries = entries(),
            signer = { throw IllegalStateException("keystore locked") },
        )
        assertFalse(result.accepted)
        assertEquals(
            ArKernelSignatureRejection.SIGNING_FAILED,
            result.rejection)
        assertTrue(result.detail.contains("keystore locked"))
    }

    @Test
    fun createRejectsInvalidToken() {
        val result = ArKernelSignatureManifestAdapter.create(
            manifestId = "bad id",
            deviceToken = "phone.redmi.v1",
            keyOrigin = ArKernelSignatureKeyOrigin.SOFTWARE,
            publicKey = publicKey(),
            entries = entries(),
            signer = signerFor(keyPair.private),
        )
        assertEquals(
            ArKernelSignatureRejection.INVALID_TOKEN,
            result.rejection)
    }

    @Test
    fun keyOriginRoundTripsOnTheWire() {
        assertEquals("android_keystore",
            ArKernelSignatureKeyOrigin.ANDROID_KEYSTORE.wireName())
        assertEquals("software",
            ArKernelSignatureKeyOrigin.SOFTWARE.wireName())
        assertEquals(ArKernelSignatureKeyOrigin.SOFTWARE,
            ArKernelSignatureKeyOrigin.fromWire("software"))
        assertEquals(null, ArKernelSignatureKeyOrigin.fromWire("tpm"))
    }

    @Test
    fun entryRejectsInvalidDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            ArKernelSignatureEntry(
                index = 0,
                artifactName = "a.txt",
                digest = "not-a-digest",
            )
        }
    }
}
