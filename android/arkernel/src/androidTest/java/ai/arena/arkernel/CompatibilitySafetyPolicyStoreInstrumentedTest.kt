package ai.arena.arkernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatibilitySafetyPolicyStoreInstrumentedTest {
    @Test
    fun atomicallyInstallsNewerPolicyAndFallsBackAfterExpiry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = keyPair()
        val builtIn = CompatibilitySafetyPolicy(4, 0, Long.MAX_VALUE, emptyList())
        val store = CompatibilitySafetyPolicyStore(context, builtIn,
            mapOf("root" to root.public), "safety-policy-instrumented.bin")
        store.clearRemote()
        try {
            val first = signed(root, policy(5, 1_000, 2_000))
            assertTrue(store.install(first, 1_500).installed)
            assertEquals(SafetyPolicySource.VERIFIED_REMOTE, store.load(1_500).source)
            assertFalse(store.install(first, 1_500).installed)
            assertEquals(SafetyPolicyInstallReason.REVISION_ROLLBACK,
                store.install(first, 1_500).reason)
            val tamperedUpdate = signed(root, policy(6, 1_000, 3_000)).let {
                SignedCompatibilitySafetyPolicy(it.rootKeyId, it.payload,
                    it.signature.also { bytes -> bytes[0] = (bytes[0].toInt() xor 1).toByte() })
            }
            assertEquals(SafetyPolicyInstallReason.SIGNATURE_INVALID,
                store.install(tamperedUpdate, 1_500).reason)
            assertEquals(5L, store.load(1_500).policy.revision)
            val expired = store.load(2_000)
            assertEquals(SafetyPolicySource.BUILT_IN, expired.source)
            assertEquals(SafetyPolicyVerificationReason.EXPIRED, expired.remoteRejection)

            val second = signed(root, policy(6, 2_000, 4_000))
            assertTrue(store.install(second, 2_500).installed)
            assertEquals(6L, store.load(2_500).policy.revision)
        } finally {
            store.clearRemote()
        }
    }

    @Test
    fun corruptStoredRemoteCannotReplaceFallbackOrResetRevisionHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = keyPair()
        val fileName = "safety-policy-corruption-instrumented.bin"
        val builtIn = CompatibilitySafetyPolicy(10, 0, Long.MAX_VALUE, listOf(
            CompatibilitySafetyRule("offline-semantics-off",
                actions = listOf(CompatibilitySafetyAction.DISABLE_SEMANTIC_INFERENCE))))
        val store = CompatibilitySafetyPolicyStore(context, builtIn,
            mapOf("root" to root.public), fileName)
        store.clearRemote()
        try {
            assertTrue(store.install(signed(root, policy(11, 1_000, 4_000)),
                1_500).installed)
            File(context.noBackupFilesDir, fileName).writeBytes(byteArrayOf(1, 2, 3))

            val loaded = store.load(1_500)
            assertEquals(SafetyPolicySource.BUILT_IN, loaded.source)
            assertEquals(SafetyPolicyVerificationReason.PAYLOAD_INVALID,
                loaded.remoteRejection)
            assertEquals(SafetyPolicyInstallReason.STORAGE_FAILURE,
                store.install(signed(root, policy(12, 1_000, 4_000)), 1_500).reason)
        } finally {
            store.clearRemote()
        }
    }

    private fun policy(revision: Long, from: Long, until: Long) =
        CompatibilitySafetyPolicy(revision, from, until, listOf(
            CompatibilitySafetyRule("legacy-depth-off",
                actions = listOf(CompatibilitySafetyAction.DISABLE_DENSE_DEPTH))))

    private fun signed(root: KeyPair,
        policy: CompatibilitySafetyPolicy): SignedCompatibilitySafetyPolicy {
        val payload = CompatibilitySafetyPolicyCodec.encode(policy)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(root.private); update(payload); sign()
        }
        return SignedCompatibilitySafetyPolicy("root", payload, signature)
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
}
