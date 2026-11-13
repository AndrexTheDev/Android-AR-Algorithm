package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ArKernelCameraImuExtrinsicsTest {

    companion object {

        private fun rotationAbout(axis: Char, angle: Double): Array<DoubleArray> {
            val c = cos(angle)
            val s = sin(angle)
            return when (axis) {
                'x' -> arrayOf(
                    doubleArrayOf(1.0, 0.0, 0.0),
                    doubleArrayOf(0.0, c, -s),
                    doubleArrayOf(0.0, s, c))
                'y' -> arrayOf(
                    doubleArrayOf(c, 0.0, s),
                    doubleArrayOf(0.0, 1.0, 0.0),
                    doubleArrayOf(-s, 0.0, c))
                else -> arrayOf(
                    doubleArrayOf(c, -s, 0.0),
                    doubleArrayOf(s, c, 0.0),
                    doubleArrayOf(0.0, 0.0, 1.0))
            }
        }

        private fun vector(
            matrix: Array<DoubleArray>,
            row: Int,
        ): ArKernelCameraImuVector = ArKernelCameraImuVector(
            matrix[row][0], matrix[row][1], matrix[row][2])

        private fun matrixOf(matrix: Array<DoubleArray>): ArKernelCameraImuMatrix =
            ArKernelCameraImuMatrix(
                vector(matrix, 0), vector(matrix, 1), vector(matrix, 2))

        private fun healthy(): ArKernelCameraImuExtrinsics {
            val r = rotationAbout('z', PI / 2.0)
            return ArKernelCameraImuExtrinsics(
                runId = "ext.test",
                deviceToken = "phone.test",
                cameraId = "0",
                cameraLevel = ArKernelCameraLevel.FULL,
                cameraFacing = ArKernelCameraFacing.BACK,
                sensorOrientationDeg = 90,
                profileWidth = 128,
                profileHeight = 72,
                sourceWidth = 1_280,
                sourceHeight = 720,
                frameCount = 240,
                gyroSampleCount = 6_000,
                durationMs = 8_000L,
                frameIntervalMedianUs = 33_333L,
                gyroRateMedianHz = 200L,
                motionRangeDps = 180.0,
                matrix = matrixOf(r),
                focalPxHorizontal = 2_800.0,
                focalPxVertical = 2_820.0,
                focalSplitPermille = 7,
                conditioningPermille = 420,
                residualPermille = 90,
                correlationPermille = 960,
            )
        }
    }

    @Test
    fun aHealthyRunRoundTrips() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
        val parsed = ArKernelCameraImuExtrinsicsAdapter.parse(text)
        assertTrue("${parsed.rejection} ${parsed.detail}", parsed.accepted)
        val record = parsed.record!!
        assertEquals("ext.test", record.runId)
        assertEquals(240, record.frameCount)
        assertEquals(2_810.0, record.focalPxMean, 1e-6)
        assertTrue(record.rotationOk)
        assertTrue(record.focalOk)
        assertTrue(record.extrinsicsSufficient)
    }

    @Test
    fun aRotationSurvivesThePermilleRoundTrip() {
        val parsed = ArKernelCameraImuExtrinsicsAdapter.parse(
            ArKernelCameraImuExtrinsicsAdapter.encode(healthy())).record!!
        // A quarter turn about z, to within a thousandth per component.
        assertEquals(0.0, parsed.matrix.rowX.x, 0.002)
        assertEquals(-1.0, parsed.matrix.rowX.y, 0.002)
        assertEquals(1.0, parsed.matrix.rowY.x, 0.002)
        assertEquals(0.0, parsed.matrix.rowY.y, 0.002)
        assertTrue(parsed.matrix.isProperRotation())
    }

    @Test
    fun aMatrixThatIsNotARotationIsRejected() {
        // A reflection: orthonormal rows but determinant -1.
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("r_zz=1000", "r_zz=-1000")
        val parsed = ArKernelCameraImuExtrinsicsAdapter.parse(text)
        assertEquals(ArKernelCameraImuExtrinsicsRejection.NOT_A_ROTATION,
            parsed.rejection)
    }

    @Test
    fun aBadlyConditionedRunIsNotSufficient() {
        val weak = healthy().copy(conditioningPermille = 10)
        val parsed = ArKernelCameraImuExtrinsicsAdapter.parse(
            ArKernelCameraImuExtrinsicsAdapter.encode(weak))
        assertTrue(parsed.accepted)
        assertFalse(parsed.record!!.extrinsicsSufficient)
        assertEquals(listOf("motion"), parsed.record!!.outstandingSteps())
    }

    @Test
    fun aLargeFocalSplitIsFlagged() {
        val split = healthy().copy(focalSplitPermille = 500)
        val parsed = ArKernelCameraImuExtrinsicsAdapter.parse(
            ArKernelCameraImuExtrinsicsAdapter.encode(split))
        assertTrue(parsed.accepted)
        assertFalse(parsed.record!!.focalOk)
        assertTrue(parsed.record!!.outstandingSteps().contains("focal"))
    }

    @Test
    fun theSourceResolutionCannotBeDropped() {
        // A focal length in pixels means nothing without the grid it belongs
        // to, so the record has to carry it.
        val record = healthy()
        assertTrue(
            ArKernelCameraImuExtrinsicsAdapter.encode(record)
                .contains("source_width=1280"))
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(record)
            .replace("source_width=1280", "source_width=1920")
            .replace("focal_px_horizontal=2800.000",
                "focal_px_horizontal=4200.000")
            .replace("focal_px_mean=2810.000", "focal_px_mean=4210.000")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun eulerAnglesCannotBeRewritten() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
        val tampered = text.replace("euler_yaw_mdeg=", "euler_yaw_mdeg=1")
        assertTrue(tampered != text)
        assertEquals(ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
            ArKernelCameraImuExtrinsicsAdapter.parse(tampered).rejection)
    }

    @Test
    fun theMeanFocalLengthCannotBeRewritten() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("focal_px_mean=", "focal_px_mean=9")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun verdictsCannotBeUpgraded() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("extrinsics_sufficient=true",
                "extrinsics_sufficient=false")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.STATISTICS_MISMATCH,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun aRaisedQualityGradeIsRejected() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("quality_grade=unverified",
                "quality_grade=centimeter-validated")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.INVALID_QUALITY,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun claimingToHaveFittedIntrinsicsIsRejected() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("intrinsics_fitted=false", "intrinsics_fitted=true")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.UNSAFE_INVARIANT,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun claimingToHaveFittedTheTranslationIsRejected() {
        // The translation is not observable by this method, so a record that
        // says otherwise is wrong on its face.
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("translation_fitted=false", "translation_fitted=true")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.UNSAFE_INVARIANT,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun claimingToKeepFramesIsRejected() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("no_frames_retained=true", "no_frames_retained=false")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.UNSAFE_INVARIANT,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun anOutOfRangeComponentIsRejected() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("r_xx=0\n", "r_xx=4000\n")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun aNonNumericComponentIsRejected() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("r_xy=-1000", "r_xy=left")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun anUnknownCameraLevelIsRejected() {
        val text = ArKernelCameraImuExtrinsicsAdapter.encode(healthy())
            .replace("camera_level=full", "camera_level=amazing")
        assertEquals(ArKernelCameraImuExtrinsicsRejection.INVALID_VALUE,
            ArKernelCameraImuExtrinsicsAdapter.parse(text).rejection)
    }

    @Test
    fun garbageIsRejected() {
        assertEquals(ArKernelCameraImuExtrinsicsRejection.INVALID_HEADER,
            ArKernelCameraImuExtrinsicsAdapter.parse("nonsense").rejection)
        assertEquals(ArKernelCameraImuExtrinsicsRejection.EMPTY_RECORD,
            ArKernelCameraImuExtrinsicsAdapter.parse("").rejection)
        assertEquals(ArKernelCameraImuExtrinsicsRejection.INPUT_TOO_LARGE,
            ArKernelCameraImuExtrinsicsAdapter.parse(
                "x".repeat(20_000)).rejection)
    }

    @Test
    fun theReportNeverGrades() {
        val report = ArKernelCameraImuExtrinsicsAdapter.report(healthy())
        assertEquals("unverified", report.qualityGrade)
        assertEquals(240, report.frameCount)
        assertTrue(report.extrinsicsSufficient)
    }
}
