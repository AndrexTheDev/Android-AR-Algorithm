package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhysicalRoutingTest {
    @Test
    fun directCameraNeedsNoPhysicalOutputRoutingOnOldApis() {
        val decision = CameraPhysicalRoutingPolicy.decide("0", "0", emptyList(), 26)
        assertTrue(decision is CameraPhysicalRoutingDecision.Accepted)
        val route = (decision as CameraPhysicalRoutingDecision.Accepted).route
        assertEquals("0", route.logicalCameraId)
        assertNull(route.outputPhysicalCameraId)
    }

    @Test
    fun logicalCameraPinsAdvertisedPhysicalCameraOnApi28() {
        val decision = CameraPhysicalRoutingPolicy.decide("0", "2", listOf("2", "3"), 28)
        assertEquals(CameraPhysicalRoute("0", "2"),
            (decision as CameraPhysicalRoutingDecision.Accepted).route)
    }

    @Test
    fun rejectsPhysicalRoutingWhenApiCannotExpressIt() {
        val decision = CameraPhysicalRoutingPolicy.decide("0", "2", listOf("2"), 27)
        assertEquals(CameraPhysicalRoutingFailure.PHYSICAL_ROUTING_REQUIRES_API_28,
            (decision as CameraPhysicalRoutingDecision.Rejected).reason)
    }

    @Test
    fun rejectsUnadvertisedPhysicalCameraInsteadOfFallingBack() {
        val decision = CameraPhysicalRoutingPolicy.decide("0", "9", listOf("2", "3"), 35)
        assertEquals(CameraPhysicalRoutingFailure.PHYSICAL_CAMERA_NOT_ADVERTISED,
            (decision as CameraPhysicalRoutingDecision.Rejected).reason)
    }
}
