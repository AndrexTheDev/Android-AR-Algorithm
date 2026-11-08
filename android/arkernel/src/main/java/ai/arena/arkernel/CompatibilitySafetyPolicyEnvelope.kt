package ai.arena.arkernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

private const val MAX_SAFETY_ENVELOPE_BYTES = 66 * 1024
private const val MAX_SAFETY_ENVELOPE_PAYLOAD_BYTES = 64 * 1024
private const val MAX_SAFETY_ENVELOPE_SIGNATURE_BYTES = 512

internal object CompatibilitySafetyPolicyEnvelopeCodec {
    fun encode(signed: SignedCompatibilitySafetyPolicy): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(signed.rootKeyId)
                val payload = signed.payload
                val signature = signed.signature
                output.writeInt(payload.size); output.write(payload)
                output.writeInt(signature.size); output.write(signature)
            }
        }.toByteArray().also { require(it.size <= MAX_SAFETY_ENVELOPE_BYTES) }

    fun decode(bytes: ByteArray): SignedCompatibilitySafetyPolicy {
        require(bytes.size in 1..MAX_SAFETY_ENVELOPE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val root = input.readUTF()
            val payloadSize = input.readInt().also {
                require(it in 1..MAX_SAFETY_ENVELOPE_PAYLOAD_BYTES)
            }
            val payload = ByteArray(payloadSize).also(input::readFully)
            val signatureSize = input.readInt().also {
                require(it in 1..MAX_SAFETY_ENVELOPE_SIGNATURE_BYTES)
            }
            val signature = ByteArray(signatureSize).also(input::readFully)
            require(input.available() == 0)
            SignedCompatibilitySafetyPolicy(root, payload, signature)
        }
    }
}
