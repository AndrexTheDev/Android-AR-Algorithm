package ai.arena.arkernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameRateLimiterTest {
    @Test
    fun capsOutputUsingOriginalMonotonicCameraTimestamps() {
        val limiter = CameraFrameRateLimiter(10)
        assertTrue(limiter.accept(1_000_000_000L))
        assertFalse(limiter.accept(1_050_000_000L))
        assertTrue(limiter.accept(1_100_000_000L))
        assertFalse(limiter.accept(1_199_999_999L))
        assertTrue(limiter.accept(1_200_000_000L))
    }

    @Test
    fun resetRequiresFreshAdmissionAndInvalidInputFailsClosed() {
        val limiter = CameraFrameRateLimiter(30)
        assertTrue(limiter.accept(2_000_000_000L))
        limiter.reset()
        assertTrue(limiter.accept(1_000_000_000L))
        assertThrows(IllegalArgumentException::class.java) { limiter.accept(0L) }
        assertThrows(IllegalArgumentException::class.java) { CameraFrameRateLimiter(0) }
    }
}
