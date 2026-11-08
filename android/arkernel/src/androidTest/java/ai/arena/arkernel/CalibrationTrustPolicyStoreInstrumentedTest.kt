package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibrationTrustPolicyStoreInstrumentedTest {
    @Test
    fun atomicallyInstallsOnlyNewerRootSignedPolicy() {
        val root = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val profileKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val store = CalibrationTrustPolicyStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mapOf("root" to root.public),
            fileName = "trust-policy-instrumented.bin",
            minimumAcceptedPolicyRevision = 1)
        store.clear()
        try {
            val first = signedPolicy(root, profileKey, 1)
            assertTrue(store.install(first).installed)
            assertFalse(store.install(first).installed)
            assertEquals(TrustPolicyInstallReason.REVISION_ROLLBACK,
                store.install(first).reason)
            val second = signedPolicy(root, profileKey, 2)
            assertTrue(store.install(second).installed)
            assertEquals(2L, requireNotNull(store.load(2_000)).revision)
            assertEquals(null, store.load(20_000))
        } finally {
            store.clear()
        }
    }

    private fun signedPolicy(root: java.security.KeyPair, profileKey: java.security.KeyPair,
        revision: Long): SignedCalibrationTrustPolicy {
        val policy = CalibrationTrustPolicy(revision, listOf(TrustedCalibrationKey(
            "profile", profileKey.public, 1_000, 9_000)),
            validFromEpochMillis = 1_000, validUntilEpochMillis = 10_000)
        val payload = CalibrationTrustPolicyCodec.encode(policy)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(root.private); update(payload); sign()
        }
        return SignedCalibrationTrustPolicy("root", payload, signature)
    }
}
