package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTimestampNormalizerTest {
    @Test
    fun locksRealtimeWithoutChangingTimestamp() {
        val normalizer = CameraTimestampNormalizer(minimumObservations = 3)
        assertNull(normalizer.normalize(100))
        var result: CameraTimestampNormalization? = null
        repeat(3) { index ->
            val raw = 10_000_000_000L + index * 33_000_000L
            result = normalizer.observeAndNormalize(raw, raw + 20_000_000L,
                raw - 4_980_000_000L)
        }

        assertTrue(normalizer.isReady)
        assertEquals(CameraTimestampDomain.REALTIME, normalizer.domain)
        assertEquals(0, normalizer.offsetNs)
        assertEquals(result?.rawTimestampNs, result?.normalizedTimestampNs)
    }

    @Test
    fun convertsUptimeWithOneLockedMedianOffset() {
        val normalizer = CameraTimestampNormalizer(minimumObservations = 3)
        var lastRaw = 0L
        repeat(3) { index ->
            lastRaw = 10_000_000_000L + index * 33_000_000L
            normalizer.observeAndNormalize(lastRaw,
                lastRaw + 5_020_000_000L, lastRaw + 20_000_000L)
        }

        assertEquals(CameraTimestampDomain.UPTIME, normalizer.domain)
        assertEquals(5_000_000_000L, normalizer.offsetNs)
        val capture = requireNotNull(normalizer.normalize(lastRaw))
        assertEquals(lastRaw + 5_000_000_000L, capture.normalizedTimestampNs)
        assertEquals(5_000_000_000L, capture.appliedOffsetNs)
    }

    @Test
    fun treatsNearlyEqualClocksAsEquivalent() {
        val normalizer = CameraTimestampNormalizer(minimumObservations = 3,
            equivalentClockDeltaNs = 20_000_000L)
        repeat(3) { index ->
            val raw = 1_000_000_000L + index * 10_000_000L
            normalizer.observeAndNormalize(raw, raw + 10_000_000L,
                raw + 8_000_000L)
        }

        assertEquals(CameraTimestampDomain.EQUIVALENT, normalizer.domain)
        assertEquals(0, normalizer.offsetNs)
    }

    @Test
    fun terminallyRejectsAmbiguousSeparatedClockDomains() {
        val normalizer = CameraTimestampNormalizer(minimumObservations = 3,
            equivalentClockDeltaNs = 1_000_000L)
        var result: CameraTimestampNormalization? = null
        repeat(3) { index ->
            val uptime = 10_000_000_000L + index * 10_000_000L
            val elapsed = uptime + 5_000_000_000L
            val ambiguousRaw = uptime + 2_500_000_000L
            result = normalizer.observeAndNormalize(ambiguousRaw, elapsed, uptime)
        }
        assertNull(result)
        assertTrue(normalizer.isFailed)
        assertFalse(normalizer.isReady)
    }

    @Test
    fun resetRemovesLockedSessionDecision() {
        val normalizer = CameraTimestampNormalizer(minimumObservations = 3)
        repeat(3) { index ->
            val raw = 10_000_000_000L + index
            normalizer.observeAndNormalize(raw, raw + 10_000_000L,
                raw - 4_990_000_000L)
        }
        assertTrue(normalizer.isReady)
        normalizer.reset()
        assertFalse(normalizer.isReady)
        assertNull(normalizer.normalize(1))
    }
}
