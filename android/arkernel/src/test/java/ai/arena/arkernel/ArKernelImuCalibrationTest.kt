package ai.arena.arkernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract writes every statistic with six decimals, so six decimals is
 * also the precision these assertions check. Comparing a rounded literal
 * against the full-precision summary value with a tighter delta would fail
 * on the rounding alone.
 */
private const val DELTA = 1e-6

/**
 * Every fixture below was forward-simulated from a synthetic sensor with known
 * intrinsics and cross-checked against an independent reference model before
 * being written down, so the expected numbers are what the physics says, not
 * what the implementation happens to print.
 */
private data class ImuRows(
    val sessionId: String,
    val deviceToken: String,
    val referenceGravityMps2: Double,
    val requiredTemperatureSpanC: Double,
    val minRepeatCycles: Int,
    val minStaticDurationMs: Long,
    val maxGapMs: Long,
    val maxRateErrorPermille: Int,
    val maxTurnRateDps: Double,
    val maxGravityErrorMps2: Double,
    val maxAccelBiasMps2: Double,
    val maxAccelScaleErrorPermille: Int,
    val maxAccelMisalignmentPermille: Int,
    val maxAccelResidualMps2: Double,
    val maxAccelNoiseMgSqrtHz: Double,
    val maxGyroBiasDps: Double,
    val maxGyroScaleErrorPermille: Int,
    val maxCrossAxisPermille: Int,
    val maxGyroArwDegSqrtHr: Double,
    val maxAccelBiasStabilityMg: Double,
    val maxGyroBiasStabilityDph: Double,
    val streams: List<ArKernelImuStreamRow>,
    val statics: List<ArKernelImuStaticRow>,
    val poses: List<ArKernelImuPoseRow>,
    val turns: List<ArKernelImuTurnRow>,
) {

    fun result(): ArKernelImuCalibrationResult =
        ArKernelImuCalibrationAdapter.create(
            sessionId = sessionId,
            deviceToken = deviceToken,
            referenceGravityMps2 = referenceGravityMps2,
            requiredTemperatureSpanC = requiredTemperatureSpanC,
            minRepeatCycles = minRepeatCycles,
            minStaticDurationMs = minStaticDurationMs,
            maxGapMs = maxGapMs,
            maxRateErrorPermille = maxRateErrorPermille,
            maxTurnRateDps = maxTurnRateDps,
            maxGravityErrorMps2 = maxGravityErrorMps2,
            maxAccelBiasMps2 = maxAccelBiasMps2,
            maxAccelScaleErrorPermille = maxAccelScaleErrorPermille,
            maxAccelMisalignmentPermille = maxAccelMisalignmentPermille,
            maxAccelResidualMps2 = maxAccelResidualMps2,
            maxAccelNoiseMgSqrtHz = maxAccelNoiseMgSqrtHz,
            maxGyroBiasDps = maxGyroBiasDps,
            maxGyroScaleErrorPermille = maxGyroScaleErrorPermille,
            maxCrossAxisPermille = maxCrossAxisPermille,
            maxGyroArwDegSqrtHr = maxGyroArwDegSqrtHr,
            maxAccelBiasStabilityMg = maxAccelBiasStabilityMg,
            maxGyroBiasStabilityDph = maxGyroBiasStabilityDph,
            streams = streams,
            statics = statics,
            poses = poses,
            turns = turns,
        )

    fun build(): ArKernelImuCalibration {
        val created = result()
        assertTrue(created.detail, created.accepted)
        return created.calibration!!
    }
}

private fun streamRow(
    index: Int,
    label: String,
    nominalHz: Int,
    measuredHz: Double,
    minDtUs: Long,
    maxDtUs: Long,
    gapCount: Int,
    duplicateCount: Int,
    monotonicViolations: Int,
    domain: ArKernelImuTimestampDomain,
): ArKernelImuStreamRow = ArKernelImuStreamRow(
    index = index,
    label = label,
    nominalHz = nominalHz,
    measuredHz = measuredHz,
    minDtUs = minDtUs,
    maxDtUs = maxDtUs,
    gapCount = gapCount,
    duplicateCount = duplicateCount,
    monotonicViolations = monotonicViolations,
    domain = domain,
)

private fun staticRow(
    index: Int,
    label: String,
    durationMs: Long,
    sampleCount: Long,
    gyroSampleCount: Long,
    meanGyroXDps: Double,
    meanGyroYDps: Double,
    meanGyroZDps: Double,
    rmsAccMps2: Double,
    rmsGyroDps: Double,
    stabilityAccMps2: Double,
    stabilityGyroDps: Double,
    maxGapMs: Long,
    temperatureC: Double,
): ArKernelImuStaticRow = ArKernelImuStaticRow(
    index = index,
    label = label,
    durationMs = durationMs,
    sampleCount = sampleCount,
    gyroSampleCount = gyroSampleCount,
    meanGyroXDps = meanGyroXDps,
    meanGyroYDps = meanGyroYDps,
    meanGyroZDps = meanGyroZDps,
    rmsAccMps2 = rmsAccMps2,
    rmsGyroDps = rmsGyroDps,
    stabilityAccMps2 = stabilityAccMps2,
    stabilityGyroDps = stabilityGyroDps,
    maxGapMs = maxGapMs,
    temperatureC = temperatureC,
)

private fun poseRow(
    index: Int,
    label: String,
    direction: ArKernelImuPoseDirection,
    sampleCount: Long,
    meanXMps2: Double,
    meanYMps2: Double,
    meanZMps2: Double,
    meanGyroXDps: Double,
    meanGyroYDps: Double,
    meanGyroZDps: Double,
    temperatureC: Double,
): ArKernelImuPoseRow = ArKernelImuPoseRow(
    index = index,
    label = label,
    direction = direction,
    sampleCount = sampleCount,
    meanXMps2 = meanXMps2,
    meanYMps2 = meanYMps2,
    meanZMps2 = meanZMps2,
    meanGyroXDps = meanGyroXDps,
    meanGyroYDps = meanGyroYDps,
    meanGyroZDps = meanGyroZDps,
    temperatureC = temperatureC,
)

private fun turnRow(
    index: Int,
    label: String,
    axis: ArKernelImuTurnAxis,
    referenceAngleDeg: Double,
    integratedAngleDeg: Double,
    offAxisAngleDeg: Double,
    durationMs: Long,
    sampleCount: Long,
    peakRateDps: Double,
): ArKernelImuTurnRow = ArKernelImuTurnRow(
    index = index,
    label = label,
    axis = axis,
    referenceAngleDeg = referenceAngleDeg,
    integratedAngleDeg = integratedAngleDeg,
    offAxisAngleDeg = offAxisAngleDeg,
    durationMs = durationMs,
    sampleCount = sampleCount,
    peakRateDps = peakRateDps,
)

private fun textOf(calibration: ArKernelImuCalibration): String =
    ArKernelImuCalibrationAdapter.encode(calibration)

private fun replaceKey(
    body: String,
    key: String,
    value: String,
): String {
    val lines = body.split("\n").toMutableList()
    for (i in lines.indices) {
        if (lines[i].startsWith("$key=")) {
            lines[i] = "$key=$value"
            break
        }
    }
    return lines.joinToString("\n")
}

private fun replaceField(
    body: String,
    prefix: String,
    oldField: String,
    newField: String,
): String {
    val lines = body.split("\n").toMutableList()
    for (i in lines.indices) {
        if (lines[i].startsWith(prefix)) {
            val fields = lines[i].split("|").toMutableList()
            for (j in fields.indices) {
                if (fields[j] == oldField) fields[j] = newField
            }
            lines[i] = fields.joinToString("|")
            break
        }
    }
    return lines.joinToString("\n")
}

class ArKernelImuCalibrationTest {

    private fun goodRows(): ImuRows = ImuRows(
        sessionId = "imu.001",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
            staticRow(
                index = 1, label = "statik-2",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.090500,
                meanGyroYDps = -0.120200,
                meanGyroZDps = 0.069800,
                rmsAccMps2 = 0.021500,
                rmsGyroDps = 0.019500,
                stabilityAccMps2 = 0.001100,
                stabilityGyroDps = 0.001900,
                maxGapMs = 5L,
                temperatureC = 29.000000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.849263,
                meanYMps2 = -0.014348,
                meanZMps2 = 0.005232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.808763,
                meanYMps2 = -0.044652,
                meanZMps2 = 0.025268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.030826,
                meanYMps2 = 9.759537,
                meanZMps2 = 0.026710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.008174,
                meanYMps2 = -9.819037,
                meanZMps2 = 0.002790,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.002887,
                meanYMps2 = -0.021713,
                meanZMps2 = 9.829457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 5, label = "display-unten-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.037613,
                meanYMps2 = -0.037787,
                meanZMps2 = -9.798957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 6, label = "links-auf-Kante-2",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.843263,
                meanYMps2 = -0.010348,
                meanZMps2 = 0.001232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 7, label = "rechts-auf-Kante-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.803763,
                meanYMps2 = -0.050652,
                meanZMps2 = 0.028268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 8, label = "aufrecht-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.026826,
                meanYMps2 = 9.754537,
                meanZMps2 = 0.032710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 9, label = "kopfueber-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.014174,
                meanYMps2 = -9.815037,
                meanZMps2 = -0.002210,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 10, label = "display-oben-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = -0.002113,
                meanYMps2 = -0.016713,
                meanZMps2 = 9.833457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 11, label = "display-unten-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.041613,
                meanYMps2 = -0.043787,
                meanZMps2 = -9.803957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 91.080000,
                offAxisAngleDeg = 0.400000,
                durationMs = 1200L,
                sampleCount = 240L,
                peakRateDps = 145.000000,
            ),
            turnRow(
                index = 1, label = "turn-x--90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -91.080000,
                offAxisAngleDeg = 0.350000,
                durationMs = 1180L,
                sampleCount = 236L,
                peakRateDps = 142.000000,
            ),
            turnRow(
                index = 2, label = "turn-y-90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 89.280000,
                offAxisAngleDeg = 0.300000,
                durationMs = 1250L,
                sampleCount = 250L,
                peakRateDps = 138.000000,
            ),
            turnRow(
                index = 3, label = "turn-y--90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -89.280000,
                offAxisAngleDeg = 0.330000,
                durationMs = 1220L,
                sampleCount = 244L,
                peakRateDps = 140.000000,
            ),
            turnRow(
                index = 4, label = "turn-z-90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 90.450000,
                offAxisAngleDeg = 0.280000,
                durationMs = 1300L,
                sampleCount = 260L,
                peakRateDps = 132.000000,
            ),
            turnRow(
                index = 5, label = "turn-z--90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -90.450000,
                offAxisAngleDeg = 0.310000,
                durationMs = 1280L,
                sampleCount = 256L,
                peakRateDps = 135.000000,
            ),
        ),
    )

    private fun cheapRows(): ImuRows = ImuRows(
        sessionId = "imu.002",
        deviceToken = "phone.b",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 100,
                measuredHz = 98.700000,
                minDtUs = 9600L,
                maxDtUs = 10800L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 100,
                measuredHz = 99.400000,
                minDtUs = 9580L,
                maxDtUs = 10500L,
                gapCount = 3,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.449000,
                meanGyroYDps = -0.600500,
                meanGyroZDps = 0.350200,
                rmsAccMps2 = 0.075000,
                rmsGyroDps = 0.060000,
                stabilityAccMps2 = 0.004000,
                stabilityGyroDps = 0.006000,
                maxGapMs = 62L,
                temperatureC = 31.500000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 10.014330,
                meanYMps2 = -0.031740,
                meanZMps2 = 0.006450,
                meanGyroXDps = 0.490000,
                meanGyroYDps = -0.630000,
                meanGyroZDps = 0.370000,
                temperatureC = 31.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.834330,
                meanYMps2 = -0.208260,
                meanZMps2 = 0.153550,
                meanGyroXDps = 0.415000,
                meanGyroYDps = -0.575000,
                meanGyroZDps = 0.332000,
                temperatureC = 31.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.168453,
                meanYMps2 = 9.549357,
                meanZMps2 = 0.143743,
                meanGyroXDps = 0.480000,
                meanGyroYDps = -0.555000,
                meanGyroZDps = 0.328000,
                temperatureC = 31.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.011547,
                meanYMps2 = -9.789357,
                meanZMps2 = 0.016257,
                meanGyroXDps = 0.422000,
                meanGyroYDps = -0.632000,
                meanGyroZDps = 0.376000,
                temperatureC = 31.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = -0.003163,
                meanYMps2 = -0.051353,
                meanZMps2 = 9.965103,
                meanGyroXDps = 0.468000,
                meanGyroYDps = -0.620000,
                meanGyroZDps = 0.388000,
                temperatureC = 31.800000,
            ),
            poseRow(
                index = 5, label = "display-unten-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.183163,
                meanYMps2 = -0.188647,
                meanZMps2 = -9.805103,
                meanGyroXDps = 0.428000,
                meanGyroYDps = -0.570000,
                meanGyroZDps = 0.316000,
                temperatureC = 31.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 97.200000,
                offAxisAngleDeg = 1.800000,
                durationMs = 1200L,
                sampleCount = 120L,
                peakRateDps = 260.000000,
            ),
            turnRow(
                index = 1, label = "turn-y-90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 83.700000,
                offAxisAngleDeg = 1.550000,
                durationMs = 1250L,
                sampleCount = 125L,
                peakRateDps = 240.000000,
            ),
            turnRow(
                index = 2, label = "turn-z-90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 95.850000,
                offAxisAngleDeg = 1.620000,
                durationMs = 1300L,
                sampleCount = 130L,
                peakRateDps = 250.000000,
            ),
            turnRow(
                index = 3, label = "turn-x--90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -97.200000,
                offAxisAngleDeg = 1.710000,
                durationMs = 1180L,
                sampleCount = 118L,
                peakRateDps = 265.000000,
            ),
        ),
    )

    private fun staticOnlyRows(): ImuRows = ImuRows(
        sessionId = "imu.003",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
            staticRow(
                index = 1, label = "statik-2",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.090500,
                meanGyroYDps = -0.120200,
                meanGyroZDps = 0.069800,
                rmsAccMps2 = 0.021500,
                rmsGyroDps = 0.019500,
                stabilityAccMps2 = 0.001100,
                stabilityGyroDps = 0.001900,
                maxGapMs = 5L,
                temperatureC = 29.000000,
            ),
        ),
        poses = listOf(
        ),
        turns = listOf(
        ),
    )

    private fun incompletePosesRows(): ImuRows = ImuRows(
        sessionId = "imu.004",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
            staticRow(
                index = 1, label = "statik-2",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.090500,
                meanGyroYDps = -0.120200,
                meanGyroZDps = 0.069800,
                rmsAccMps2 = 0.021500,
                rmsGyroDps = 0.019500,
                stabilityAccMps2 = 0.001100,
                stabilityGyroDps = 0.001900,
                maxGapMs = 5L,
                temperatureC = 29.000000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.849263,
                meanYMps2 = -0.014348,
                meanZMps2 = 0.005232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.808763,
                meanYMps2 = -0.044652,
                meanZMps2 = 0.025268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.030826,
                meanYMps2 = 9.759537,
                meanZMps2 = 0.026710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.008174,
                meanYMps2 = -9.819037,
                meanZMps2 = 0.002790,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.002887,
                meanYMps2 = -0.021713,
                meanZMps2 = 9.829457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 5, label = "links-auf-Kante-2",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.843263,
                meanYMps2 = -0.010348,
                meanZMps2 = 0.001232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 6, label = "rechts-auf-Kante-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.803763,
                meanYMps2 = -0.050652,
                meanZMps2 = 0.028268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 7, label = "aufrecht-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.026826,
                meanYMps2 = 9.754537,
                meanZMps2 = 0.032710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 8, label = "kopfueber-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.014174,
                meanYMps2 = -9.815037,
                meanZMps2 = -0.002210,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 9, label = "display-oben-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = -0.002113,
                meanYMps2 = -0.016713,
                meanZMps2 = 9.833457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 91.080000,
                offAxisAngleDeg = 0.400000,
                durationMs = 1200L,
                sampleCount = 240L,
                peakRateDps = 145.000000,
            ),
            turnRow(
                index = 1, label = "turn-x--90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -91.080000,
                offAxisAngleDeg = 0.350000,
                durationMs = 1180L,
                sampleCount = 236L,
                peakRateDps = 142.000000,
            ),
            turnRow(
                index = 2, label = "turn-y-90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 89.280000,
                offAxisAngleDeg = 0.300000,
                durationMs = 1250L,
                sampleCount = 250L,
                peakRateDps = 138.000000,
            ),
            turnRow(
                index = 3, label = "turn-y--90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -89.280000,
                offAxisAngleDeg = 0.330000,
                durationMs = 1220L,
                sampleCount = 244L,
                peakRateDps = 140.000000,
            ),
            turnRow(
                index = 4, label = "turn-z-90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 90.450000,
                offAxisAngleDeg = 0.280000,
                durationMs = 1300L,
                sampleCount = 260L,
                peakRateDps = 132.000000,
            ),
            turnRow(
                index = 5, label = "turn-z--90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -90.450000,
                offAxisAngleDeg = 0.310000,
                durationMs = 1280L,
                sampleCount = 256L,
                peakRateDps = 135.000000,
            ),
        ),
    )

    private fun oneCycleRows(): ImuRows = ImuRows(
        sessionId = "imu.005",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
            staticRow(
                index = 1, label = "statik-2",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.090500,
                meanGyroYDps = -0.120200,
                meanGyroZDps = 0.069800,
                rmsAccMps2 = 0.021500,
                rmsGyroDps = 0.019500,
                stabilityAccMps2 = 0.001100,
                stabilityGyroDps = 0.001900,
                maxGapMs = 5L,
                temperatureC = 29.000000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.849263,
                meanYMps2 = -0.014348,
                meanZMps2 = 0.005232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.808763,
                meanYMps2 = -0.044652,
                meanZMps2 = 0.025268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.030826,
                meanYMps2 = 9.759537,
                meanZMps2 = 0.026710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.008174,
                meanYMps2 = -9.819037,
                meanZMps2 = 0.002790,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.002887,
                meanYMps2 = -0.021713,
                meanZMps2 = 9.829457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 5, label = "display-unten-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.037613,
                meanYMps2 = -0.037787,
                meanZMps2 = -9.798957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 91.080000,
                offAxisAngleDeg = 0.400000,
                durationMs = 1200L,
                sampleCount = 240L,
                peakRateDps = 145.000000,
            ),
            turnRow(
                index = 1, label = "turn-x--90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -91.080000,
                offAxisAngleDeg = 0.350000,
                durationMs = 1180L,
                sampleCount = 236L,
                peakRateDps = 142.000000,
            ),
            turnRow(
                index = 2, label = "turn-y-90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 89.280000,
                offAxisAngleDeg = 0.300000,
                durationMs = 1250L,
                sampleCount = 250L,
                peakRateDps = 138.000000,
            ),
            turnRow(
                index = 3, label = "turn-y--90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -89.280000,
                offAxisAngleDeg = 0.330000,
                durationMs = 1220L,
                sampleCount = 244L,
                peakRateDps = 140.000000,
            ),
            turnRow(
                index = 4, label = "turn-z-90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 90.450000,
                offAxisAngleDeg = 0.280000,
                durationMs = 1300L,
                sampleCount = 260L,
                peakRateDps = 132.000000,
            ),
            turnRow(
                index = 5, label = "turn-z--90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -90.450000,
                offAxisAngleDeg = 0.310000,
                durationMs = 1280L,
                sampleCount = 256L,
                peakRateDps = 135.000000,
            ),
        ),
    )

    private fun clippedRows(): ImuRows = ImuRows(
        sessionId = "imu.006",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
            staticRow(
                index = 1, label = "statik-2",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.090500,
                meanGyroYDps = -0.120200,
                meanGyroZDps = 0.069800,
                rmsAccMps2 = 0.021500,
                rmsGyroDps = 0.019500,
                stabilityAccMps2 = 0.001100,
                stabilityGyroDps = 0.001900,
                maxGapMs = 5L,
                temperatureC = 29.000000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.849263,
                meanYMps2 = -0.014348,
                meanZMps2 = 0.005232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.808763,
                meanYMps2 = -0.044652,
                meanZMps2 = 0.025268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.030826,
                meanYMps2 = 9.759537,
                meanZMps2 = 0.026710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.008174,
                meanYMps2 = -9.819037,
                meanZMps2 = 0.002790,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.002887,
                meanYMps2 = -0.021713,
                meanZMps2 = 9.829457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 5, label = "display-unten-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.037613,
                meanYMps2 = -0.037787,
                meanZMps2 = -9.798957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 6, label = "links-auf-Kante-2",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.843263,
                meanYMps2 = -0.010348,
                meanZMps2 = 0.001232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 7, label = "rechts-auf-Kante-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.803763,
                meanYMps2 = -0.050652,
                meanZMps2 = 0.028268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 8, label = "aufrecht-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.026826,
                meanYMps2 = 9.754537,
                meanZMps2 = 0.032710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 9, label = "kopfueber-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.014174,
                meanYMps2 = -9.815037,
                meanZMps2 = -0.002210,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 10, label = "display-oben-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = -0.002113,
                meanYMps2 = -0.016713,
                meanZMps2 = 9.833457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 11, label = "display-unten-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.041613,
                meanYMps2 = -0.043787,
                meanZMps2 = -9.803957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 91.080000,
                offAxisAngleDeg = 0.400000,
                durationMs = 1200L,
                sampleCount = 240L,
                peakRateDps = 145.000000,
            ),
            turnRow(
                index = 1, label = "turn-fast",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 89.300000,
                offAxisAngleDeg = 0.300000,
                durationMs = 320L,
                sampleCount = 64L,
                peakRateDps = 2600.000000,
            ),
        ),
    )

    private fun thermalNeedRows(): ImuRows = ImuRows(
        sessionId = "imu.007",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 10.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
            staticRow(
                index = 1, label = "statik-2",
                durationMs = 30000L,
                sampleCount = 6000L,
                gyroSampleCount = 6000L,
                meanGyroXDps = 0.090500,
                meanGyroYDps = -0.120200,
                meanGyroZDps = 0.069800,
                rmsAccMps2 = 0.021500,
                rmsGyroDps = 0.019500,
                stabilityAccMps2 = 0.001100,
                stabilityGyroDps = 0.001900,
                maxGapMs = 5L,
                temperatureC = 29.000000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.849263,
                meanYMps2 = -0.014348,
                meanZMps2 = 0.005232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.808763,
                meanYMps2 = -0.044652,
                meanZMps2 = 0.025268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.030826,
                meanYMps2 = 9.759537,
                meanZMps2 = 0.026710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.008174,
                meanYMps2 = -9.819037,
                meanZMps2 = 0.002790,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.002887,
                meanYMps2 = -0.021713,
                meanZMps2 = 9.829457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 5, label = "display-unten-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.037613,
                meanYMps2 = -0.037787,
                meanZMps2 = -9.798957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 6, label = "links-auf-Kante-2",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.843263,
                meanYMps2 = -0.010348,
                meanZMps2 = 0.001232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 7, label = "rechts-auf-Kante-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.803763,
                meanYMps2 = -0.050652,
                meanZMps2 = 0.028268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 8, label = "aufrecht-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.026826,
                meanYMps2 = 9.754537,
                meanZMps2 = 0.032710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 9, label = "kopfueber-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.014174,
                meanYMps2 = -9.815037,
                meanZMps2 = -0.002210,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 10, label = "display-oben-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = -0.002113,
                meanYMps2 = -0.016713,
                meanZMps2 = 9.833457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 11, label = "display-unten-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.041613,
                meanYMps2 = -0.043787,
                meanZMps2 = -9.803957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 91.080000,
                offAxisAngleDeg = 0.400000,
                durationMs = 1200L,
                sampleCount = 240L,
                peakRateDps = 145.000000,
            ),
            turnRow(
                index = 1, label = "turn-x--90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -91.080000,
                offAxisAngleDeg = 0.350000,
                durationMs = 1180L,
                sampleCount = 236L,
                peakRateDps = 142.000000,
            ),
            turnRow(
                index = 2, label = "turn-y-90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 89.280000,
                offAxisAngleDeg = 0.300000,
                durationMs = 1250L,
                sampleCount = 250L,
                peakRateDps = 138.000000,
            ),
            turnRow(
                index = 3, label = "turn-y--90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -89.280000,
                offAxisAngleDeg = 0.330000,
                durationMs = 1220L,
                sampleCount = 244L,
                peakRateDps = 140.000000,
            ),
            turnRow(
                index = 4, label = "turn-z-90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 90.450000,
                offAxisAngleDeg = 0.280000,
                durationMs = 1300L,
                sampleCount = 260L,
                peakRateDps = 132.000000,
            ),
            turnRow(
                index = 5, label = "turn-z--90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -90.450000,
                offAxisAngleDeg = 0.310000,
                durationMs = 1280L,
                sampleCount = 256L,
                peakRateDps = 135.000000,
            ),
        ),
    )

    private fun shortStaticRows(): ImuRows = ImuRows(
        sessionId = "imu.008",
        deviceToken = "phone.a",
        referenceGravityMps2 = 9.806650,
        requiredTemperatureSpanC = 0.000000,
        minRepeatCycles = 2,
        minStaticDurationMs = 10000L,
        maxGapMs = 50L,
        maxRateErrorPermille = 100,
        maxTurnRateDps = 2000.000000,
        maxGravityErrorMps2 = 0.050000,
        maxAccelBiasMps2 = 0.100000,
        maxAccelScaleErrorPermille = 50,
        maxAccelMisalignmentPermille = 50,
        maxAccelResidualMps2 = 0.030000,
        maxAccelNoiseMgSqrtHz = 20.000000,
        maxGyroBiasDps = 0.500000,
        maxGyroScaleErrorPermille = 50,
        maxCrossAxisPermille = 100,
        maxGyroArwDegSqrtHr = 5.000000,
        maxAccelBiasStabilityMg = 5.000000,
        maxGyroBiasStabilityDph = 30.000000,
        streams = listOf(
            streamRow(
                index = 0, label = "accelerometer",
                nominalHz = 200,
                measuredHz = 200.400000,
                minDtUs = 4800L,
                maxDtUs = 5200L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
            streamRow(
                index = 1, label = "gyroscope",
                nominalHz = 200,
                measuredHz = 199.200000,
                minDtUs = 4760L,
                maxDtUs = 5240L,
                gapCount = 0,
                duplicateCount = 0,
                monotonicViolations = 0,
                domain = ArKernelImuTimestampDomain.ELAPSED_REALTIME,
            ),
        ),
        statics = listOf(
            staticRow(
                index = 0, label = "statik-1",
                durationMs = 2000L,
                sampleCount = 400L,
                gyroSampleCount = 400L,
                meanGyroXDps = 0.089500,
                meanGyroYDps = -0.119800,
                meanGyroZDps = 0.070200,
                rmsAccMps2 = 0.022000,
                rmsGyroDps = 0.020000,
                stabilityAccMps2 = 0.001200,
                stabilityGyroDps = 0.002000,
                maxGapMs = 6L,
                temperatureC = 28.500000,
            ),
        ),
        poses = listOf(
            poseRow(
                index = 0, label = "links-auf-Kante-1",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.849263,
                meanYMps2 = -0.014348,
                meanZMps2 = 0.005232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 1, label = "rechts-auf-Kante-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.808763,
                meanYMps2 = -0.044652,
                meanZMps2 = 0.025268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 2, label = "aufrecht-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.030826,
                meanYMps2 = 9.759537,
                meanZMps2 = 0.026710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 3, label = "kopfueber-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.008174,
                meanYMps2 = -9.819037,
                meanZMps2 = 0.002790,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 4, label = "display-oben-1",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.002887,
                meanYMps2 = -0.021713,
                meanZMps2 = 9.829457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 5, label = "display-unten-1",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.037613,
                meanYMps2 = -0.037787,
                meanZMps2 = -9.798957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 6, label = "links-auf-Kante-2",
                direction = ArKernelImuPoseDirection.POSITIVE_X,
                sampleCount = 600L,
                meanXMps2 = 9.843263,
                meanYMps2 = -0.010348,
                meanZMps2 = 0.001232,
                meanGyroXDps = 0.100000,
                meanGyroYDps = -0.126000,
                meanGyroZDps = 0.074000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 7, label = "rechts-auf-Kante-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_X,
                sampleCount = 600L,
                meanXMps2 = -9.803763,
                meanYMps2 = -0.050652,
                meanZMps2 = 0.028268,
                meanGyroXDps = 0.082000,
                meanGyroYDps = -0.115000,
                meanGyroZDps = 0.067000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 8, label = "aufrecht-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.026826,
                meanYMps2 = 9.754537,
                meanZMps2 = 0.032710,
                meanGyroXDps = 0.096000,
                meanGyroYDps = -0.111000,
                meanGyroZDps = 0.065000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 9, label = "kopfueber-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Y,
                sampleCount = 600L,
                meanXMps2 = 0.014174,
                meanYMps2 = -9.815037,
                meanZMps2 = -0.002210,
                meanGyroXDps = 0.085000,
                meanGyroYDps = -0.127000,
                meanGyroZDps = 0.076000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 10, label = "display-oben-2",
                direction = ArKernelImuPoseDirection.POSITIVE_Z,
                sampleCount = 600L,
                meanXMps2 = -0.002113,
                meanYMps2 = -0.016713,
                meanZMps2 = 9.833457,
                meanGyroXDps = 0.093000,
                meanGyroYDps = -0.124000,
                meanGyroZDps = 0.078000,
                temperatureC = 28.800000,
            ),
            poseRow(
                index = 11, label = "display-unten-2",
                direction = ArKernelImuPoseDirection.NEGATIVE_Z,
                sampleCount = 600L,
                meanXMps2 = 0.041613,
                meanYMps2 = -0.043787,
                meanZMps2 = -9.803957,
                meanGyroXDps = 0.086000,
                meanGyroYDps = -0.114000,
                meanGyroZDps = 0.063000,
                temperatureC = 28.800000,
            ),
        ),
        turns = listOf(
            turnRow(
                index = 0, label = "turn-x-90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 91.080000,
                offAxisAngleDeg = 0.400000,
                durationMs = 1200L,
                sampleCount = 240L,
                peakRateDps = 145.000000,
            ),
            turnRow(
                index = 1, label = "turn-x--90",
                axis = ArKernelImuTurnAxis.X,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -91.080000,
                offAxisAngleDeg = 0.350000,
                durationMs = 1180L,
                sampleCount = 236L,
                peakRateDps = 142.000000,
            ),
            turnRow(
                index = 2, label = "turn-y-90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 89.280000,
                offAxisAngleDeg = 0.300000,
                durationMs = 1250L,
                sampleCount = 250L,
                peakRateDps = 138.000000,
            ),
            turnRow(
                index = 3, label = "turn-y--90",
                axis = ArKernelImuTurnAxis.Y,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -89.280000,
                offAxisAngleDeg = 0.330000,
                durationMs = 1220L,
                sampleCount = 244L,
                peakRateDps = 140.000000,
            ),
            turnRow(
                index = 4, label = "turn-z-90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = 90.000000,
                integratedAngleDeg = 90.450000,
                offAxisAngleDeg = 0.280000,
                durationMs = 1300L,
                sampleCount = 260L,
                peakRateDps = 132.000000,
            ),
            turnRow(
                index = 5, label = "turn-z--90",
                axis = ArKernelImuTurnAxis.Z,
                referenceAngleDeg = -90.000000,
                integratedAngleDeg = -90.450000,
                offAxisAngleDeg = 0.310000,
                durationMs = 1280L,
                sampleCount = 256L,
                peakRateDps = 135.000000,
            ),
        ),
    )

    private fun goodBody(): String = textOf(goodRows().build())

    private fun assertRejects(
        body: String,
        rejection: ArKernelImuCalibrationRejection,
    ) {
        val parsed = ArKernelImuCalibrationAdapter.parse(body)
        assertFalse(parsed.detail, parsed.accepted)
        assertEquals(rejection, parsed.rejection)
    }

    @Test
    fun everyFixtureRoundTripsThroughEncodeAndParse() {
        val cases = listOf(
            "good" to goodRows(),
            "cheap" to cheapRows(),
            "staticOnly" to staticOnlyRows(),
            "incompletePoses" to incompletePosesRows(),
            "oneCycle" to oneCycleRows(),
            "clipped" to clippedRows(),
            "thermalNeed" to thermalNeedRows(),
            "shortStatic" to shortStaticRows(),
        )
        for ((name, rows) in cases) {
            val calibration = rows.build()
            val parsed = ArKernelImuCalibrationAdapter.parse(textOf(calibration))
            assertTrue("$name: ${parsed.detail}", parsed.accepted)
            assertEquals(
                "$name rejection",
                ArKernelImuCalibrationRejection.NONE,
                parsed.rejection,
            )
            val again = ArKernelImuCalibrationAdapter.encode(
                parsed.calibration!!)
            assertEquals("$name is not canonical", textOf(calibration), again)
        }
    }

    @Test
    fun goodFixtureRoundTripsAndIsSufficient() {
        val calibration = goodRows().build()
        val summary = ArKernelImuCalibrationAdapter.summarize(calibration)
        val parsed = ArKernelImuCalibrationAdapter.parse(textOf(calibration))
        assertTrue(parsed.detail, parsed.accepted)
        assertEquals(
            ArKernelImuCalibrationRejection.NONE, parsed.rejection)
        assertEquals(2, summary.streamCount)
        assertEquals(2, summary.staticCount)
        assertEquals(12, summary.poseCount)
        assertEquals(6, summary.turnCount)
        assertEquals(12_000L, summary.staticSampleTotal)
        assertEquals(7_200L, summary.poseSampleTotal)
        assertTrue(summary.calibrationSufficient)
        assertTrue(summary.outstanding.isEmpty())
    }

    @Test
    fun goodFixtureRecoversAccelerometerBias() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(0.020000, summary.accelBiasXMps2, DELTA)
        assertEquals(-0.030000, summary.accelBiasYMps2, DELTA)
        assertEquals(0.015000, summary.accelBiasZMps2, DELTA)
        assertEquals(0.039051, summary.accelBiasNormMps2, DELTA)
    }

    @Test
    fun goodFixtureRecoversAccelerometerScaleAndMisalignment() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(1.999969, summary.accelScaleXPermille, DELTA)
        assertEquals(-1.999969, summary.accelScaleYPermille, DELTA)
        assertEquals(1.000036, summary.accelScaleZPermille, DELTA)
        assertEquals(2, summary.accelScaleErrorMaxPermille)
        assertEquals(2, summary.accelMisalignmentMaxPermille)
    }

    @Test
    fun goodFixtureChecksGravityAgainstTheDeclaredValue() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(0.016993, summary.gravityMagnitudeErrorMps2, DELTA)
        assertTrue(summary.gravityOk)
    }

    @Test
    fun goodFixtureMeasuresTumbleRepeatability() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(2, summary.accelRepeatCycles)
        assertEquals(0.004257, summary.accelResidualRmsMps2, DELTA)
        assertTrue(summary.accelRepeatChecked)
    }

    @Test
    fun goodFixtureRecoversGyroscopeBias() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(0.090000, summary.gyroBiasXDps, DELTA)
        assertEquals(-0.120000, summary.gyroBiasYDps, DELTA)
        assertEquals(0.070000, summary.gyroBiasZDps, DELTA)
        assertEquals(0.165529, summary.gyroBiasNormDps, DELTA)
        assertEquals(0.012163, summary.gyroBiasSpreadDps, DELTA)
        assertTrue(summary.biasOk)
    }

    @Test
    fun goodFixtureReportsNoiseDensity() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(2.175000, summary.accelNoiseDensityMgSqrtHz, DELTA)
        assertEquals(0.118500, summary.gyroArwDegSqrtHr, DELTA)
        assertTrue(summary.noiseOk)
    }

    @Test
    fun goodFixtureReportsBiasStability() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(1.150000, summary.accelBiasStabilityMg, DELTA)
        assertEquals(7.020000, summary.gyroBiasStabilityDph, DELTA)
        assertTrue(summary.stabilityOk)
    }

    @Test
    fun goodFixtureMeasuresGyroscopeScaleAgainstTheJig() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(12, summary.gyroScaleErrorMaxPermille)
        assertEquals(5, summary.gyroCrossAxisMaxPermille)
        assertEquals(145.0, summary.observedPeakRateDps, DELTA)
        assertTrue(summary.gyroScaleOk)
    }

    @Test
    fun goodFixtureDerivesFreeIntegrationDrift() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(9.931779, summary.angleDriftDegPerMin, DELTA)
        assertEquals(9.632523, summary.positionDriftMPerMin, DELTA)
    }

    @Test
    fun goodFixtureReportsTemperatureSpanAndCadence() {
        val summary = ArKernelImuCalibrationAdapter.summarize(goodRows().build())
        assertEquals(0.500000, summary.temperatureSpanC, DELTA)
        assertTrue(summary.thermalCovered)
        assertEquals(6L, summary.observedMaxGapMs)
        assertEquals(4, summary.observedMaxRateErrorPermille)
        assertTrue(summary.cadenceOk)
        assertTrue(summary.durationOk)
    }

    @Test
    fun cheapPhoneFailsFiveVerdicts() {
        val summary = ArKernelImuCalibrationAdapter.summarize(cheapRows().build())
        assertFalse(summary.cadenceOk)
        assertFalse(summary.gravityOk)
        assertFalse(summary.accelFitOk)
        assertFalse(summary.accelRepeatChecked)
        assertFalse(summary.gyroScaleOk)
        assertFalse(summary.biasOk)
        assertTrue(summary.noiseOk)
        assertTrue(summary.stabilityOk)
        assertFalse(summary.calibrationSufficient)
    }

    @Test
    fun cheapPhoneReportsTheNumbersBehindTheFailures() {
        val summary = ArKernelImuCalibrationAdapter.summarize(cheapRows().build())
        assertEquals(0.113976, summary.gravityMagnitudeErrorMps2, DELTA)
        assertEquals(0.170000, summary.accelBiasNormMps2, DELTA)
        assertEquals(14, summary.accelScaleErrorMaxPermille)
        assertEquals(10, summary.accelMisalignmentMaxPermille)
        assertEquals(0.827551, summary.gyroBiasNormDps, DELTA)
        assertEquals(80, summary.gyroScaleErrorMaxPermille)
        assertEquals(20, summary.gyroCrossAxisMaxPermille)
        assertEquals(62L, summary.observedMaxGapMs)
        assertEquals(13, summary.observedMaxRateErrorPermille)
    }

    @Test
    fun cheapPhoneDriftsFaster() {
        val summary = ArKernelImuCalibrationAdapter.summarize(cheapRows().build())
        assertEquals(49.653105, summary.angleDriftDegPerMin, DELTA)
        assertEquals(20.124612, summary.positionDriftMPerMin, DELTA)
    }

    @Test
    fun staticRunsAloneCannotFitTheAccelerometer() {
        val calibration = staticOnlyRows().build()
        val summary = ArKernelImuCalibrationAdapter.summarize(calibration)
        assertEquals(0.0, summary.accelBiasNormMps2, DELTA)
        assertEquals(0, summary.accelScaleErrorMaxPermille)
        assertEquals(0, summary.accelRepeatCycles)
        assertFalse(summary.gravityOk)
        assertFalse(summary.accelFitOk)
        assertFalse(summary.gyroScaleOk)
        assertFalse(summary.durationOk)
        assertTrue(summary.biasOk)
        assertFalse(summary.calibrationSufficient)
        assertEquals(
            listOf("poses", "repeat", "turns"), summary.outstanding)
    }

    @Test
    fun incompleteTumbleIsNotAFit() {
        val summary = ArKernelImuCalibrationAdapter.summarize(
            incompletePosesRows().build())
        assertFalse(summary.gravityOk)
        assertFalse(summary.accelFitOk)
        assertEquals(0.0, summary.gravityMagnitudeErrorMps2, DELTA)
        assertEquals(2, summary.accelRepeatCycles)
        assertFalse(summary.calibrationSufficient)
    }

    @Test
    fun singleCycleIsNotRepeatChecked() {
        val summary = ArKernelImuCalibrationAdapter.summarize(
            oneCycleRows().build())
        assertEquals(1, summary.accelRepeatCycles)
        assertEquals(0.0, summary.accelResidualRmsMps2, DELTA)
        assertTrue(summary.accelFitOk)
        assertFalse(summary.accelRepeatChecked)
        assertFalse(summary.calibrationSufficient)
    }

    @Test
    fun clippedTurnRateInvalidatesTheScaleVerdict() {
        val summary = ArKernelImuCalibrationAdapter.summarize(
            clippedRows().build())
        assertEquals(2600.0, summary.observedPeakRateDps, DELTA)
        assertFalse(summary.gyroScaleOk)
        assertTrue(summary.accelFitOk)
        assertFalse(summary.calibrationSufficient)
    }

    @Test
    fun temperatureRequirementIsNotCovered() {
        val summary = ArKernelImuCalibrationAdapter.summarize(
            thermalNeedRows().build())
        assertEquals(0.500000, summary.temperatureSpanC, DELTA)
        assertFalse(summary.thermalCovered)
        assertTrue(summary.accelFitOk)
        assertFalse(summary.calibrationSufficient)
    }

    @Test
    fun shortStaticRunFailsTheDurationVerdict() {
        val summary = ArKernelImuCalibrationAdapter.summarize(
            shortStaticRows().build())
        assertEquals(400L, summary.staticSampleTotal)
        assertFalse(summary.durationOk)
        assertTrue(summary.biasOk)
        assertFalse(summary.calibrationSufficient)
    }

    @Test
    fun outstandingStepsNameTheMissingWork() {
        val summary = ArKernelImuCalibrationAdapter.summarize(
            staticOnlyRows().build())
        assertTrue(summary.outstanding.contains("poses"))
        assertTrue(summary.outstanding.contains("turns"))
        assertFalse(summary.outstanding.contains("cadence"))
    }

    @Test
    fun rejectsForeignHeader() {
        val body = goodBody().replaceFirst(
            "ARKIMUCAL1", "ARKIMUCAL2")
        assertRejects(body, ArKernelImuCalibrationRejection.INVALID_HEADER)
    }

    @Test
    fun rejectsOversizedInput() {
        assertRejects(
            "ARKIMUCAL1\n" + "x".repeat(20_000),
            ArKernelImuCalibrationRejection.INPUT_TOO_LARGE,
        )
    }

    @Test
    fun rejectsEmptyLine() {
        val lines = goodBody().split("\n").toMutableList()
        lines[3] = ""
        assertRejects(
            lines.joinToString("\n"),
            ArKernelImuCalibrationRejection.EMPTY_LINE,
        )
    }

    @Test
    fun rejectsLineLongerThanTheLimit() {
        val lines = goodBody().split("\n").toMutableList()
        val marker = lines.indexOfFirst { it.startsWith("pose=") }
        lines[marker] = "pose=" + "1|".repeat(140)
        assertRejects(
            lines.joinToString("\n"),
            ArKernelImuCalibrationRejection.LINE_TOO_LONG,
        )
    }

    @Test
    fun rejectsUnknownPoseDirection() {
        assertRejects(
            replaceField(goodBody(), "pose=0|", "px", "qq"),
            ArKernelImuCalibrationRejection.INVALID_DIRECTION,
        )
    }

    @Test
    fun rejectsUnknownTimestampDomain() {
        assertRejects(
            replaceField(goodBody(), "stream=0|", "elapsed_realtime", "mars"),
            ArKernelImuCalibrationRejection.INVALID_DOMAIN,
        )
    }

    @Test
    fun rejectsUnknownTurnAxis() {
        assertRejects(
            replaceField(goodBody(), "turn=0|", "x", "w"),
            ArKernelImuCalibrationRejection.INVALID_AXIS,
        )
    }

    @Test
    fun rejectsNonContiguousPoseRows() {
        assertRejects(
            replaceField(goodBody(), "pose=1|", "pose=1", "pose=7"),
            ArKernelImuCalibrationRejection.INVALID_ORDER,
        )
    }

    @Test
    fun rejectsStatisticsMismatch() {
        assertRejects(
            replaceKey(goodBody(), "accel_bias_x_mps2", "0.099000"),
            ArKernelImuCalibrationRejection.STATISTICS_MISMATCH,
        )
    }

    @Test
    fun rejectsVerdictMismatch() {
        assertRejects(
            replaceKey(goodBody(), "calibration_sufficient", "false"),
            ArKernelImuCalibrationRejection.CALIBRATION_MISMATCH,
        )
    }

    @Test
    fun rejectsUnsafeInvariant() {
        assertRejects(
            replaceKey(goodBody(), "images_disabled", "false"),
            ArKernelImuCalibrationRejection.UNSAFE_INVARIANT,
        )
    }

    @Test
    fun rejectsRaisedQualityGrade() {
        assertRejects(
            replaceKey(goodBody(), "quality_grade", "verified"),
            ArKernelImuCalibrationRejection.INVALID_QUALITY,
        )
    }

    @Test
    fun rejectsReferenceGravityOutOfBounds() {
        val created = goodRows().copy(referenceGravityMps2 = 20.0).result()
        assertFalse(created.accepted)
        assertEquals(
            ArKernelImuCalibrationRejection.INVALID_VALUE, created.rejection)
    }

    @Test
    fun rejectsTooManyPoseRows() {
        val rows = goodRows()
        val extra = rows.poses + rows.poses.take(1).map {
            it.copy(index = rows.poses.size)
        }
        val created = rows.copy(poses = extra).result()
        assertFalse(created.accepted)
        assertEquals(
            ArKernelImuCalibrationRejection.INVALID_COUNT, created.rejection)
    }

    @Test
    fun rejectsZeroReferenceAngle() {
        val rows = goodRows()
        val created = rows.copy(
            turns = rows.turns.take(1).map {
                it.copy(referenceAngleDeg = 0.0, integratedAngleDeg = 0.0)
            },
        ).result()
        assertFalse(created.accepted)
        assertEquals(
            ArKernelImuCalibrationRejection.INVALID_VALUE, created.rejection)
    }

    @Test
    fun rejectsTemperatureOutOfBounds() {
        val rows = goodRows()
        val created = rows.copy(
            statics = rows.statics.take(1).map {
                it.copy(temperatureC = 900.0)
            },
        ).result()
        assertFalse(created.accepted)
        assertEquals(
            ArKernelImuCalibrationRejection.INVALID_TEMPERATURE,
            created.rejection,
        )
    }
}
