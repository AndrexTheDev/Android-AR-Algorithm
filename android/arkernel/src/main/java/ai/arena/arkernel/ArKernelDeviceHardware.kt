package ai.arena.arkernel

import java.util.Locale

/**
 * Bounded canonical `ARKDEVHW1` record of what a device has and what it can
 * sustain.
 *
 * A sixty-second static run measures the gyroscope only if the device does not
 * throttle halfway through it. Below that, the measurement is of the
 * throttling, not of the sensor - and nothing in the record says which one it
 * was unless the device is asked directly. This record asks.
 *
 * Four things, in the order they matter:
 *
 *  - **Inventory.** Which sensors exist, and what each one promises: range,
 *    resolution, fastest and slowest delivery, power, reporting mode. The
 *    kernel needs an accelerometer and a gyroscope; a device without either
 *    cannot run it, and that is a fact worth recording before a campaign
 *    rather than after one.
 *  - **Headroom.** Memory and free storage. A long capture that dies at
 *    eighty percent because the disk filled up is not a reconstruction
 *    failure, it is a bookkeeping failure.
 *  - **Sustained load.** A short CPU-bound probe comparing the work rate in
 *    its first quarter against its last. Thermal throttling shows up as the
 *    late rate collapsing, which is exactly what would silently corrupt a
 *    long inertial run.
 *  - **Clocks.** Whether both monotonic clocks stay monotonic, and how far
 *    they drift apart. `elapsedRealtime` and `uptimeMillis` differ by
 *    everything the device slept through; that offset growing *during* a run
 *    means the device slept mid-run, which is worse than a slow clock.
 *
 * No sensor sample is retained. The inventory is what the platform reports
 * about its sensors, not a measurement of them - a device can advertise a
 * gyroscope and deliver nothing. `device_sufficient` is a precondition gate,
 * not a judgement of the device, and the grade stays `unverified`.
 */

private const val DEVHW_HEADER = "ARKDEVHW1"
private const val DEVHW_VERSION = "1"
private const val DEVHW_QUALITY = "unverified"
private const val DEVHW_END = "END"
private const val DEVHW_MAX_INPUT_CHARACTERS = 16_384
private const val DEVHW_MAX_LINE_CHARACTERS = 256
private const val DEVHW_MAX_SENSORS = 64
private const val DEVHW_MAX_NAME = 64

/** Build fields are folded into this alphabet, so spaces become dots. */
private val DEVHW_NAME = Regex("[A-Za-z0-9._+-]{1,64}")
private val DEVHW_TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
private val DEVHW_UNSIGNED = Regex("0|[1-9][0-9]*")
private val DEVHW_DECIMAL = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]{1,6})?")

/** Below this, a long capture risks dying because the disk filled up. */
const val ARK_DEVICE_MIN_FREE_STORAGE_MB = 256L

/** Below this, there is not enough room to hold a session in memory. */
const val ARK_DEVICE_MIN_AVAILABLE_MEMORY_MB = 192L

/** The late work rate must still be this share of the early rate. */
const val ARK_DEVICE_MIN_SUSTAINED_PERMILLE = 800L

/** Beyond 1000 ppm the two monotonic clocks disagree materially. */
const val ARK_DEVICE_MAX_CLOCK_DRIFT_PPM = 1_000L

/** `PowerManager` thermal statuses above this mean the device is struggling. */
const val ARK_DEVICE_MAX_THERMAL_STATUS = 3

private val DEVHW_KEYS = listOf(
    "version",
    "run_id",
    "device_token",
    "api_level",
    "manufacturer",
    "model",
    "device",
    "board",
    "hardware",
    "sensor_count",
    "required_sensors_present",
    "total_memory_mb",
    "available_memory_mb",
    "low_ram_device",
    "free_storage_mb",
    "total_storage_mb",
    "battery_percent",
    "battery_charging",
    "thermal_status_start",
    "thermal_status_end",
    "load_duration_ms",
    "load_rate_early",
    "load_rate_late",
    "load_rate_ratio_permille",
    "clock_offset_ms",
    "clock_drift_ppm",
    "monotonic_violations",
    "automatic_upload_disabled",
    "manual_share_only",
    "no_samples_retained",
    "no_quality_claim",
    "sensors_ok",
    "storage_ok",
    "memory_ok",
    "thermal_ok",
    "throttling_ok",
    "clock_ok",
    "device_sufficient",
    "quality_grade",
)

private val DEVHW_SAFETY_KEYS = listOf(
    "automatic_upload_disabled",
    "manual_share_only",
    "no_samples_retained",
    "no_quality_claim",
)

enum class ArKernelDeviceHardwareRejection {
    NONE,
    INPUT_TOO_LARGE,
    LINE_TOO_LONG,
    EMPTY_LINE,
    INVALID_HEADER,
    INVALID_SCHEMA,
    INVALID_VALUE,
    INVALID_TOKEN,
    INVALID_QUALITY,
    INVALID_ROW,
    INVALID_ORDER,
    DUPLICATE_SENSOR_INDEX,
    TOO_MANY_SENSORS,
    UNSAFE_INVARIANT,
    STATISTICS_MISMATCH,
}

/** Fail-closed parse result; on rejection no partially trusted record is returned. */
data class ArKernelDeviceHardwareParseResult(
    val record: ArKernelDeviceHardware?,
    val rejection: ArKernelDeviceHardwareRejection,
    val lineNumber: Int,
    val detail: String,
) {
    val accepted: Boolean
        get() = rejection == ArKernelDeviceHardwareRejection.NONE &&
            record != null
}

/** One sensor as the platform describes it. Not a measurement of it. */
data class ArKernelDeviceSensorRow(
    val index: Int,
    val kind: String,
    val name: String,
    val vendor: String,
    val maxRange: Double,
    val resolution: Double,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val powerMa: Double,
    val reportingMode: String,
    val wakeUp: Boolean,
) {
    init {
        require(index >= 0) { "Invalid device sensor index" }
        require(DEVHW_NAME.matches(kind)) { "Invalid device sensor kind" }
        require(DEVHW_NAME.matches(name) || name.isEmpty()) {
            "Invalid device sensor name"
        }
        require(DEVHW_NAME.matches(vendor) || vendor.isEmpty()) {
            "Invalid device sensor vendor"
        }
        require(maxRange.isFinite() && maxRange >= 0.0) {
            "Invalid device sensor range"
        }
        require(resolution.isFinite() && resolution >= 0.0) {
            "Invalid device sensor resolution"
        }
        require(minDelayUs >= 0 && maxDelayUs >= minDelayUs) {
            "Invalid device sensor delay"
        }
        require(powerMa.isFinite() && powerMa >= 0.0) {
            "Invalid device sensor power"
        }
        require(DEVHW_NAME.matches(reportingMode)) {
            "Invalid device sensor reporting mode"
        }
    }
}

/** One bounded device hardware and runtime record. */
data class ArKernelDeviceHardware(
    val runId: String,
    val deviceToken: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long,
    val lowRamDevice: Boolean,
    val freeStorageMb: Long,
    val totalStorageMb: Long,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val thermalStatusStart: Int,
    val thermalStatusEnd: Int,
    val loadDurationMs: Long,
    val loadRateEarly: Long,
    val loadRateLate: Long,
    val clockOffsetMs: Long,
    val clockDriftPpm: Long,
    val monotonicViolations: Int,
    val sensors: List<ArKernelDeviceSensorRow>,
) {
    init {
        require(DEVHW_TOKEN.matches(runId)) { "Invalid device run id" }
        require(DEVHW_TOKEN.matches(deviceToken)) {
            "Invalid device token"
        }
        require(apiLevel >= 1) { "Invalid device API level" }
        require(DEVHW_NAME.matches(manufacturer)) {
            "Invalid device manufacturer"
        }
        require(DEVHW_NAME.matches(model)) { "Invalid device model" }
        require(DEVHW_NAME.matches(device)) { "Invalid device name" }
        require(DEVHW_NAME.matches(board)) { "Invalid device board" }
        require(DEVHW_NAME.matches(hardware)) { "Invalid device hardware" }
        require(totalMemoryMb >= 0L) { "Invalid device total memory" }
        require(availableMemoryMb >= 0L) { "Invalid device available memory" }
        require(freeStorageMb >= 0L) { "Invalid device free storage" }
        require(totalStorageMb >= 0L) { "Invalid device total storage" }
        require(batteryPercent in -1..100) { "Invalid device battery level" }
        require(thermalStatusStart >= -1 && thermalStatusEnd >= -1) {
            "Invalid device thermal status"
        }
        require(loadDurationMs >= 0L) { "Invalid device load duration" }
        require(loadRateEarly >= 0L && loadRateLate >= 0L) {
            "Invalid device load rate"
        }
        require(monotonicViolations >= 0) {
            "Invalid device monotonic violation count"
        }
        require(sensors.size <= DEVHW_MAX_SENSORS) {
            "Device carries more than $DEVHW_MAX_SENSORS sensors"
        }
        require(sensors.withIndex().all { it.value.index == it.index }) {
            "Device sensor indices are not contiguous"
        }
    }

    val sensorCount: Int get() = sensors.size

    /** The kernel integrates an accelerometer and a gyroscope. Both are required. */
    val requiredSensorsPresent: Boolean
        get() = sensors.any { it.kind == "accelerometer" } &&
            sensors.any { it.kind == "gyroscope" }

    /** Work rate in the last quarter as a share of the first, in permille. */
    val loadRateRatioPermille: Long
        get() = if (loadRateEarly <= 0L) 0L
        else loadRateLate * 1_000L / loadRateEarly

    val sensorsOk: Boolean get() = requiredSensorsPresent
    val storageOk: Boolean
        get() = freeStorageMb >= ARK_DEVICE_MIN_FREE_STORAGE_MB
    val memoryOk: Boolean
        get() = availableMemoryMb >= ARK_DEVICE_MIN_AVAILABLE_MEMORY_MB

    /**
     * No evidence of thermal trouble. A device that does not report a thermal
     * status at all gives no evidence either way, so it is not failed for
     * being silent - but it is not credited with being cool.
     */
    val thermalOk: Boolean
        get() = thermalStatusStart < 0 || thermalStatusEnd < 0 ||
            (thermalStatusStart <= ARK_DEVICE_MAX_THERMAL_STATUS &&
                thermalStatusEnd <= ARK_DEVICE_MAX_THERMAL_STATUS)

    val throttlingOk: Boolean
        get() = loadRateEarly <= 0L ||
            loadRateRatioPermille >= ARK_DEVICE_MIN_SUSTAINED_PERMILLE

    val clockOk: Boolean
        get() = monotonicViolations == 0 &&
            (clockDriftPpm <= ARK_DEVICE_MAX_CLOCK_DRIFT_PPM &&
                clockDriftPpm >= -ARK_DEVICE_MAX_CLOCK_DRIFT_PPM)

    val deviceSufficient: Boolean
        get() = sensorsOk && storageOk && memoryOk && thermalOk &&
            throttlingOk && clockOk

    /** What to do next, in the order the campaign needs it. */
    fun outstandingSteps(): List<String> {
        val steps = ArrayList<String>()
        if (!sensorsOk) steps.add("sensors")
        if (!clockOk) steps.add("clock")
        if (!throttlingOk) steps.add("cooling")
        if (!thermalOk) steps.add("thermal")
        if (!memoryOk) steps.add("memory")
        if (!storageOk) steps.add("storage")
        return steps
    }
}

/** Bounded structural summary; never a quality or accuracy grade. */
data class ArKernelDeviceHardwareReport(
    val sensorCount: Int,
    val availableMemoryMb: Long,
    val freeStorageMb: Long,
    val sustainedPermille: Long,
    val clockDriftPpm: Long,
    val deviceSufficient: Boolean,
    val qualityGrade: String = DEVHW_QUALITY,
)

/** Canonical `ARKDEVHW1` text adapter; fail-closed in both directions. */
object ArKernelDeviceHardwareAdapter {

    fun encode(record: ArKernelDeviceHardware): String {
        val lines = ArrayList<String>(
            DEVHW_KEYS.size + record.sensors.size + 2)
        lines.add(DEVHW_HEADER)
        lines.add("version=$DEVHW_VERSION")
        lines.add("run_id=${record.runId}")
        lines.add("device_token=${record.deviceToken}")
        lines.add("api_level=${record.apiLevel}")
        lines.add("manufacturer=${record.manufacturer}")
        lines.add("model=${record.model}")
        lines.add("device=${record.device}")
        lines.add("board=${record.board}")
        lines.add("hardware=${record.hardware}")
        lines.add("sensor_count=${record.sensorCount}")
        lines.add(
            "required_sensors_present=${bool(record.requiredSensorsPresent)}")
        lines.add("total_memory_mb=${record.totalMemoryMb}")
        lines.add("available_memory_mb=${record.availableMemoryMb}")
        lines.add("low_ram_device=${bool(record.lowRamDevice)}")
        lines.add("free_storage_mb=${record.freeStorageMb}")
        lines.add("total_storage_mb=${record.totalStorageMb}")
        lines.add("battery_percent=${record.batteryPercent}")
        lines.add("battery_charging=${bool(record.batteryCharging)}")
        lines.add("thermal_status_start=${record.thermalStatusStart}")
        lines.add("thermal_status_end=${record.thermalStatusEnd}")
        lines.add("load_duration_ms=${record.loadDurationMs}")
        lines.add("load_rate_early=${record.loadRateEarly}")
        lines.add("load_rate_late=${record.loadRateLate}")
        lines.add("load_rate_ratio_permille=${record.loadRateRatioPermille}")
        lines.add("clock_offset_ms=${record.clockOffsetMs}")
        lines.add("clock_drift_ppm=${record.clockDriftPpm}")
        lines.add("monotonic_violations=${record.monotonicViolations}")
        for (key in DEVHW_SAFETY_KEYS) lines.add("$key=true")
        lines.add("sensors_ok=${bool(record.sensorsOk)}")
        lines.add("storage_ok=${bool(record.storageOk)}")
        lines.add("memory_ok=${bool(record.memoryOk)}")
        lines.add("thermal_ok=${bool(record.thermalOk)}")
        lines.add("throttling_ok=${bool(record.throttlingOk)}")
        lines.add("clock_ok=${bool(record.clockOk)}")
        lines.add("device_sufficient=${bool(record.deviceSufficient)}")
        lines.add("quality_grade=$DEVHW_QUALITY")
        for (sensor in record.sensors) {
            lines.add(
                "sensor=${sensor.index}|${sensor.kind}|${sensor.name}|" +
                    "${sensor.vendor}|${fixed(sensor.maxRange)}|" +
                    "${fixed(sensor.resolution)}|${sensor.minDelayUs}|" +
                    "${sensor.maxDelayUs}|${fixed(sensor.powerMa)}|" +
                    "${sensor.reportingMode}|${if (sensor.wakeUp) 1 else 0}")
        }
        lines.add(DEVHW_END)
        val text = lines.joinToString("\n")
        require(text.length <= DEVHW_MAX_INPUT_CHARACTERS) {
            "Device record exceeds its bounded size"
        }
        require(lines.all { it.length <= DEVHW_MAX_LINE_CHARACTERS }) {
            "Device record line exceeds its bounded length"
        }
        return text
    }

    fun parse(text: String): ArKernelDeviceHardwareParseResult {
        if (text.length > DEVHW_MAX_INPUT_CHARACTERS) {
            return reject(
                ArKernelDeviceHardwareRejection.INPUT_TOO_LARGE, 0,
                "Device input exceeds its bounded size")
        }
        val normalized =
            if (text.startsWith("\uFEFF")) text.substring(1) else text
        val body = when {
            normalized.endsWith("\r\n") -> normalized.dropLast(2)
            normalized.endsWith("\n") || normalized.endsWith("\r") ->
                normalized.dropLast(1)
            else -> normalized
        }
        if (body.isEmpty()) {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_SCHEMA, 0,
                "Device input contains no record")
        }
        val lines = body.lineSequence().toList()
        if (lines[0] != DEVHW_HEADER) {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_HEADER, 1,
                "Device header is invalid")
        }
        if (lines.size < DEVHW_KEYS.size + 2) {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_SCHEMA, 0,
                "Device record is too short")
        }
        val values = LinkedHashMap<String, String>(DEVHW_KEYS.size)
        for (index in DEVHW_KEYS.indices) {
            val lineNumber = index + 2
            val lineText = lines[index + 1].trimEnd('\r')
            if (lineText.length > DEVHW_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelDeviceHardwareRejection.LINE_TOO_LONG,
                    lineNumber,
                    "Device line exceeds its bounded length")
            }
            val equals = lineText.indexOf('=')
            if (equals <= 0 || equals == lineText.length - 1) {
                return reject(
                    ArKernelDeviceHardwareRejection.INVALID_SCHEMA,
                    lineNumber, "Device metadata is not a key/value pair")
            }
            val key = lineText.substring(0, equals)
            if (key != DEVHW_KEYS[index]) {
                return reject(
                    ArKernelDeviceHardwareRejection.INVALID_SCHEMA,
                    lineNumber, "Device metadata key order is wrong")
            }
            values[key] = lineText.substring(equals + 1)
        }
        for (key in DEVHW_SAFETY_KEYS) {
            if (values[key] != "true") {
                return reject(
                    ArKernelDeviceHardwareRejection.UNSAFE_INVARIANT, 0,
                    "Device invariant $key must be true")
            }
        }
        if (values["version"] != DEVHW_VERSION) {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_VALUE, 2,
                "Unsupported device record version")
        }
        if (values["quality_grade"] != DEVHW_QUALITY) {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_QUALITY,
                DEVHW_KEYS.size + 1,
                "Device record cannot assert assessed quality")
        }
        val declaredCount = values["sensor_count"]!!.toIntOrNull()
            ?: return reject(
                ArKernelDeviceHardwareRejection.INVALID_VALUE, 12,
                "Device sensor count is not a number")

        val rowLines = lines.drop(DEVHW_KEYS.size + 1).dropLast(1)
            .map { it.trimEnd('\r') }
        if (rowLines.size > DEVHW_MAX_SENSORS) {
            return reject(
                ArKernelDeviceHardwareRejection.TOO_MANY_SENSORS, 0,
                "Device carries more than $DEVHW_MAX_SENSORS sensors")
        }
        if (rowLines.size != declaredCount) {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_VALUE, 12,
                "Device sensor count disagrees with its rows")
        }
        val sensors = ArrayList<ArKernelDeviceSensorRow>(rowLines.size)
        for ((offset, lineText) in rowLines.withIndex()) {
            val lineNumber = DEVHW_KEYS.size + 2 + offset
            if (lineText.isEmpty()) {
                return reject(
                    ArKernelDeviceHardwareRejection.EMPTY_LINE, lineNumber,
                    "Device record contains an empty line")
            }
            if (lineText.length > DEVHW_MAX_LINE_CHARACTERS) {
                return reject(
                    ArKernelDeviceHardwareRejection.LINE_TOO_LONG,
                    lineNumber,
                    "Device line exceeds its bounded length")
            }
            val parts = lineText.split('|')
            if (parts.size != 11) {
                return reject(
                    ArKernelDeviceHardwareRejection.INVALID_ROW, lineNumber,
                    "Device sensor row does not have eleven fields")
            }
            val index = parts[0].removePrefix("sensor=").toIntOrNull()
                ?: return reject(
                    ArKernelDeviceHardwareRejection.INVALID_ROW, lineNumber,
                    "Device sensor index is not a number")
            if (index != offset) {
                return reject(
                    ArKernelDeviceHardwareRejection.DUPLICATE_SENSOR_INDEX,
                    lineNumber,
                    "Device sensor indices are not contiguous")
            }
            val minDelay = parts[6].toIntOrNull()
                ?: return reject(
                    ArKernelDeviceHardwareRejection.INVALID_ROW, lineNumber,
                    "Device sensor minimum delay is invalid")
            val maxDelay = parts[7].toIntOrNull()
                ?: return reject(
                    ArKernelDeviceHardwareRejection.INVALID_ROW, lineNumber,
                    "Device sensor maximum delay is invalid")
            sensors.add(
                ArKernelDeviceSensorRow(
                    index = index,
                    kind = parts[1],
                    name = parts[2],
                    vendor = parts[3],
                    maxRange = decimal(parts[4])
                        ?: return reject(
                            ArKernelDeviceHardwareRejection.INVALID_ROW,
                            lineNumber, "Device sensor range is invalid"),
                    resolution = decimal(parts[5])
                        ?: return reject(
                            ArKernelDeviceHardwareRejection.INVALID_ROW,
                            lineNumber,
                            "Device sensor resolution is invalid"),
                    minDelayUs = minDelay,
                    maxDelayUs = maxDelay,
                    powerMa = decimal(parts[8])
                        ?: return reject(
                            ArKernelDeviceHardwareRejection.INVALID_ROW,
                            lineNumber, "Device sensor power is invalid"),
                    reportingMode = parts[9],
                    wakeUp = parts[10] == "1",
                ))
        }

        val record = runCatching {
            ArKernelDeviceHardware(
                runId = values["run_id"]!!,
                deviceToken = values["device_token"]!!,
                apiLevel = values["api_level"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("api"),
                manufacturer = values["manufacturer"]!!,
                model = values["model"]!!,
                device = values["device"]!!,
                board = values["board"]!!,
                hardware = values["hardware"]!!,
                totalMemoryMb = values["total_memory_mb"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("memory"),
                availableMemoryMb =
                    values["available_memory_mb"]!!.toLongOrNull()
                        ?: throw IllegalArgumentException("memory"),
                lowRamDevice = values["low_ram_device"] == "true",
                freeStorageMb = values["free_storage_mb"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("storage"),
                totalStorageMb = values["total_storage_mb"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("storage"),
                batteryPercent = values["battery_percent"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("battery"),
                batteryCharging = values["battery_charging"] == "true",
                thermalStatusStart =
                    values["thermal_status_start"]!!.toIntOrNull()
                        ?: throw IllegalArgumentException("thermal"),
                thermalStatusEnd = values["thermal_status_end"]!!.toIntOrNull()
                    ?: throw IllegalArgumentException("thermal"),
                loadDurationMs = values["load_duration_ms"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("load"),
                loadRateEarly = values["load_rate_early"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("load"),
                loadRateLate = values["load_rate_late"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("load"),
                clockOffsetMs = values["clock_offset_ms"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("clock"),
                clockDriftPpm = values["clock_drift_ppm"]!!.toLongOrNull()
                    ?: throw IllegalArgumentException("clock"),
                monotonicViolations =
                    values["monotonic_violations"]!!.toIntOrNull()
                        ?: throw IllegalArgumentException("clock"),
                sensors = sensors,
            )
        }.getOrElse {
            return reject(
                ArKernelDeviceHardwareRejection.INVALID_VALUE, 0,
                "Device metadata is invalid: ${it.message}")
        }

        val expected = mapOf(
            "required_sensors_present" to
                bool(record.requiredSensorsPresent),
            "load_rate_ratio_permille" to
                record.loadRateRatioPermille.toString(),
            "sensors_ok" to bool(record.sensorsOk),
            "storage_ok" to bool(record.storageOk),
            "memory_ok" to bool(record.memoryOk),
            "thermal_ok" to bool(record.thermalOk),
            "throttling_ok" to bool(record.throttlingOk),
            "clock_ok" to bool(record.clockOk),
            "device_sufficient" to bool(record.deviceSufficient),
        )
        for ((key, expectedValue) in expected) {
            if (values[key] != expectedValue) {
                return reject(
                    ArKernelDeviceHardwareRejection.STATISTICS_MISMATCH,
                    DEVHW_KEYS.indexOf(key) + 2,
                    "Device $key disagrees with its own values")
            }
        }
        return ArKernelDeviceHardwareParseResult(
            record, ArKernelDeviceHardwareRejection.NONE, 0, "accepted")
    }

    fun report(record: ArKernelDeviceHardware): ArKernelDeviceHardwareReport =
        ArKernelDeviceHardwareReport(
            sensorCount = record.sensorCount,
            availableMemoryMb = record.availableMemoryMb,
            freeStorageMb = record.freeStorageMb,
            sustainedPermille = record.loadRateRatioPermille,
            clockDriftPpm = record.clockDriftPpm,
            deviceSufficient = record.deviceSufficient,
        )

    /** Folds a platform string into the record alphabet. */
    fun name(value: String): String {
        val folded = buildString {
            for (character in value.trim()) {
                append(
                    if (character.isLetterOrDigit() ||
                        character == '.' || character == '_' ||
                        character == '+' || character == '-'
                    ) {
                        character
                    } else {
                        '.'
                    })
            }
        }
        val trimmed = if (folded.length <= DEVHW_MAX_NAME) {
            folded
        } else {
            folded.substring(0, DEVHW_MAX_NAME)
        }
        val cleaned = trimmed.trim('.', '-', '+', '_')
        return if (cleaned.isEmpty()) "unknown" else cleaned
    }

    private fun reject(
        rejection: ArKernelDeviceHardwareRejection,
        lineNumber: Int,
        detail: String,
    ): ArKernelDeviceHardwareParseResult =
        ArKernelDeviceHardwareParseResult(null, rejection, lineNumber, detail)
}

private fun decimal(value: String): Double? =
    if (DEVHW_DECIMAL.matches(value)) value.toDoubleOrNull() else null

private fun fixed(value: Double): String =
    String.format(Locale.US, "%.6f", value)

private fun bool(value: Boolean): String = if (value) "true" else "false"
