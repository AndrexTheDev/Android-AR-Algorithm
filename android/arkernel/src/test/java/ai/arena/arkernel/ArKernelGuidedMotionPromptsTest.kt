package ai.arena.arkernel

import android.hardware.camera2.CameraCharacteristics
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArKernelGuidedMotionPromptsTest {
    @Test
    fun formatsEveryGuidanceStateInGermanAndEnglishWithoutQualityClaims() {
        for (state in GuidedMotionGuidanceState.entries) {
            val german = ArKernelGuidedMotionPrompts.format(state, Locale.GERMAN)
            val english = ArKernelGuidedMotionPrompts.format(state, Locale.ENGLISH)
            assertEquals(ArKernelPromptLocale.DE, german.locale)
            assertEquals(ArKernelPromptLocale.EN, english.locale)
            assertEquals(state != GuidedMotionGuidanceState.COMPLETED, german.actionable)
            assertEquals(german.tone, english.tone)
            assertTrue(german.message.isNotBlank())
            assertTrue(english.message.isNotBlank())
            assertFalse(german.message.contains("zentimetervalidiert", ignoreCase = true))
            assertFalse(english.message.contains("centimeter", ignoreCase = true))
            assertFalse(german.detail.contains("zentimetervalidiert", ignoreCase = true))
            assertFalse(english.detail.contains("centimeter", ignoreCase = true))
        }
        assertEquals("Kamera ruhig halten.", ArKernelGuidedMotionPrompts.format(
            GuidedMotionGuidanceState.HOLD_STILL, Locale.GERMAN).message)
        assertEquals("Hold the camera still.", ArKernelGuidedMotionPrompts.format(
            GuidedMotionGuidanceState.HOLD_STILL, Locale.ENGLISH).message)
        assertEquals(ArKernelPromptTone.SUCCESS, ArKernelGuidedMotionPrompts.format(
            GuidedMotionGuidanceState.COMPLETED, Locale.GERMAN).tone)
        assertFalse(ArKernelGuidedMotionPrompts.format(
            GuidedMotionGuidanceState.COMPLETED, Locale.GERMAN).actionable)
    }

    @Test
    fun unsupportedLocaleFallsBackToEnglish() {
        val prompt = ArKernelGuidedMotionPrompts.format(
            GuidedMotionGuidanceState.HOLD_STILL, Locale.FRENCH)
        assertEquals(ArKernelPromptLocale.EN, prompt.locale)
        assertEquals("Hold the camera still.", prompt.message)
        assertEquals("Guided motion test", prompt.detail)
    }

    @Test
    fun formatsGrantedAndBlockedReportsWithoutUpgradingQuality() {
        val granted = grantedReport()
        val germanGranted = ArKernelGuidedMotionPrompts.format(granted, Locale.GERMAN)
        assertEquals(ArKernelPromptTone.SUCCESS, germanGranted.tone)
        assertTrue(germanGranted.message.contains("freigegeben"))
        assertTrue(germanGranted.detail.contains("Qualität: ungeprüft"))
        assertFalse(germanGranted.actionable)

        val physicallyValidated = granted.copy(metricConsistency = healthyMetric(emptyList()))
        assertTrue(physicallyValidated.dimensionallyValidated)
        val germanPhysical = ArKernelGuidedMotionPrompts.format(
            physicallyValidated, Locale.GERMAN)
        assertTrue(germanPhysical.detail.contains("Qualität: ungeprüft"))
        assertFalse(germanPhysical.message.contains("zentimetervalidiert",
            ignoreCase = true))
        assertFalse(germanPhysical.detail.contains("zentimetervalidiert",
            ignoreCase = true))

        val blocked = blockedReport()
        val germanBlocked = ArKernelGuidedMotionPrompts.format(blocked, Locale.GERMAN)
        assertEquals(ArKernelPromptTone.BLOCKED, germanBlocked.tone)
        assertTrue(germanBlocked.actionable)
        assertTrue(germanBlocked.message.contains("nicht freigegeben"))
        assertTrue(germanBlocked.detail.contains("Aktive Kompatibilitätsprüfung"))
        assertTrue(germanBlocked.detail.contains("Rotation nicht beobachtbar"))
        assertTrue(germanBlocked.detail.contains("Zeitversatz nicht beobachtbar"))
        assertTrue(germanBlocked.detail.contains("Metrische Laufzeit inkonsistent"))
        assertTrue(germanBlocked.detail.contains("Qualität: ungeprüft"))

        val englishBlocked = ArKernelGuidedMotionPrompts.format(blocked, Locale.ENGLISH)
        assertEquals(ArKernelPromptTone.BLOCKED, englishBlocked.tone)
        assertTrue(englishBlocked.detail.contains("Active compatibility check failed"))
        assertTrue(englishBlocked.detail.contains("Rotation not observable"))
        assertTrue(englishBlocked.detail.contains("Time offset not observable"))
        assertTrue(englishBlocked.detail.contains("Metric runtime inconsistent"))
        assertTrue(englishBlocked.detail.contains("Quality: unverified"))
        assertFalse(englishBlocked.message.contains("centimeter", ignoreCase = true))
    }

    private fun grantedReport(): GuidedMotionAdmissionReport =
        GuidedMotionAdmissionReport(
            acceptedActive(), acceptedRotation(), acceptedOffset(),
            healthyMetric(listOf(MetricTargetReason.INSUFFICIENT_KNOWN_DISTANCE_OBSERVATIONS)),
            emptyList())

    private fun blockedReport(): GuidedMotionAdmissionReport =
        GuidedMotionAdmissionProbe(
            ActiveDeviceCompatibilityProbe(staticReport(), true, true),
            Rotation(1.0, 0.0, 0.0, 0.0)).finish()

    private fun acceptedActive(): ActiveCompatibilityReport {
        val stats = TimingStreamStatistics(100, true, 30.0, 33_333_333L,
            100_000L, 0.0, 1_000_000_000L, 4_300_000_000L)
        val imu = stats.copy(medianRateHz = 200.0, medianIntervalNs = 5_000_000L)
        return ActiveCompatibilityReport(ObservedCameraTimebase.REALTIME, stats, imu, imu,
            1.0, 3_000_000_000L, 1_000_000L, 1_000_000L,
            DeviceCompatibilityTier.RUNTIME_VERIFIED_METRIC, emptyList())
    }

    private fun acceptedRotation() = RotationObservabilityReport(
        18, 0.54, 0.53, 0.98, 0.99, emptyList())

    private fun acceptedOffset() = TimeOffsetObservabilityReport(
        500, 100, 3_000_000_000L, 24_000_000L, 4_000_000L,
        5.0e12, 0.98, 0.05, emptyList())

    private fun healthyMetric(targetReasons: List<MetricTargetReason>) =
        MetricConsistencyReport(100, 9.80665, 0.02, 0.003,
            16, 15, 0.94, if (targetReasons.isEmpty()) 3 else 0,
            if (targetReasons.isEmpty()) 0.001 else 0.0,
            if (targetReasons.isEmpty()) 0.002 else 0.0,
            emptyList(), targetReasons)

    private fun staticReport(): DeviceCompatibilityReport {
        val camera = CameraCapabilityFacts("0", emptyList(), 640, 480,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, 90,
            4000, 3000, 4000, 3000, 33_333_333L, listOf(4f), listOf(0), listOf(0),
            listOf(0), true, true, true)
        val signature = DeviceCapabilitySignature(apiLevel = 35,
            supportedAbis = listOf("arm64-v8a"), lowRamDevice = false,
            memoryClassMb = 256, buildFingerprintSha256 = "c".repeat(64),
            camera = camera, imu = ImuCapabilityFacts(true, true, 5_000, 5_000,
                0, 0, 0.001f, 0.001f))
        return DeviceCompatibilityEvaluator.evaluate(signature)
    }
}
