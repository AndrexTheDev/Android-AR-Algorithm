package ai.arena.arkernel

/** Pure, testable decision for binding a calibrated stream to one camera sensor. */
enum class CameraPhysicalRoutingFailure {
    PHYSICAL_ROUTING_REQUIRES_API_28,
    PHYSICAL_CAMERA_NOT_ADVERTISED,
}

data class CameraPhysicalRoute(
    val logicalCameraId: String,
    val outputPhysicalCameraId: String?,
)

sealed class CameraPhysicalRoutingDecision {
    data class Accepted(val route: CameraPhysicalRoute) : CameraPhysicalRoutingDecision()
    data class Rejected(val reason: CameraPhysicalRoutingFailure) : CameraPhysicalRoutingDecision()
}

object CameraPhysicalRoutingPolicy {
    fun decide(
        logicalCameraId: String,
        requestedPhysicalCameraId: String?,
        advertisedPhysicalCameraIds: Collection<String>,
        apiLevel: Int,
    ): CameraPhysicalRoutingDecision {
        require(logicalCameraId.isNotBlank())
        require(apiLevel >= 1)
        if (requestedPhysicalCameraId == null || requestedPhysicalCameraId == logicalCameraId) {
            return CameraPhysicalRoutingDecision.Accepted(
                CameraPhysicalRoute(logicalCameraId, null))
        }
        if (apiLevel < 28) return CameraPhysicalRoutingDecision.Rejected(
            CameraPhysicalRoutingFailure.PHYSICAL_ROUTING_REQUIRES_API_28)
        if (requestedPhysicalCameraId !in advertisedPhysicalCameraIds) {
            return CameraPhysicalRoutingDecision.Rejected(
                CameraPhysicalRoutingFailure.PHYSICAL_CAMERA_NOT_ADVERTISED)
        }
        return CameraPhysicalRoutingDecision.Accepted(
            CameraPhysicalRoute(logicalCameraId, requestedPhysicalCameraId))
    }
}
