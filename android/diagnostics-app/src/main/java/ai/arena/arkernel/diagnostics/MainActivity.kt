package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelFieldDiagnosticsLogEvent
import ai.arena.arkernel.ArKernelFieldDiagnosticsLogEventKind
import ai.arena.arkernel.ArKernelFieldDiagnosticsPolicy
import ai.arena.arkernel.ArKernelFieldDiagnosticsPolicyBundle
import ai.arena.arkernel.ArKernelFieldDiagnosticsRunLog
import ai.arena.arkernel.ArKernelFieldDiagnosticsTestPlan
import ai.arena.arkernel.ArKernelFieldDiagnosticsTestPlanBundle
import ai.arena.arkernel.ArKernelPhysicalSelfTestMetrics
import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ConfigurationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single installed, plan-driven host diagnostic app. It writes an opted-in
 * ARKFDPOL1 policy, an ARKFDPLAN1 test plan and a bounded ARKFDLOG1 run log as
 * text files, never images, pixels or dense geometry. A separate opted-in
 * CalibrationCaptureActivity records bounded ARKCALIB1 scale evidence and
 * orientation samples only. A host-pasted ARKFDPLAN1 plan selects implemented
 * structural test operators; it cannot add new low-level capabilities. The app
 * performs no automatic upload, automatic calibration, signature verification
 * or physical ground-truth measurement and stays explicitly unverified.
 */
class MainActivity : DiagnosticsActivity() {

    private lateinit var statusText: TextView
    private lateinit var logPreview: TextView
    private lateinit var optIn: CheckBox
    private lateinit var createButton: Button
    private lateinit var physicalButton: Button
    private lateinit var resetPlanButton: Button
    private lateinit var planInput: EditText
    private lateinit var sharePlanButton: Button
    private lateinit var writeButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var adaptiveButton: Button
    private lateinit var campaignButton: Button
    private lateinit var bindingButton: Button
    private lateinit var crossButton: Button
    private lateinit var retentionButton: Button
    private lateinit var measurementButton: Button
    private lateinit var feedButton: Button
    private lateinit var rulerButton: Button
    private lateinit var validationButton: Button
    private lateinit var guideButton: Button
    private lateinit var guideText: TextView

    private val diagnosticsPolicy = ArKernelFieldDiagnosticsPolicy(
        policyId = "diagnostics.apk.v1",
        collectionOptIn = true,
    )
    private val policyText: String =
        ArKernelFieldDiagnosticsPolicyBundle.encode(diagnosticsPolicy)
    private val defaultPlanText: String =
        ArKernelFieldDiagnosticsTestPlanBundle.defaultPlanText()
    private val runCounter = AtomicInteger(0)
    private val sessionStartMillis = System.currentTimeMillis()

    private var currentLog: String = ""
    private var currentRunId: String = "run.initial"

    @Volatile
    private var currentPlanText: String = defaultPlanText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "AR-Kern Diagnose"
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
        }

        val versionText = TextView(this).apply {
            text = "Version 2.12-valid · Metrische Validierung in " +
                "Millimetern (Knopf 14). Wenn dieser Text fehlt, ist " +
                "noch die alte APK installiert."
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }

        val intro = TextView(this).apply {
            text = "Eine installierte App, viele zukünftige Testläufe: unten steht ein " +
                "ARKFDPLAN1-Textplan. Dauer, Reihenfolge, Wiederholungen und Parameter " +
                "lassen sich als Text ändern, ohne eine neue App zu installieren. " +
                "Kurzer Kapabilitäts-Log oder ultimativer Planlauf schreiben " +
                "ARKFDPOL1, ARKFDPLAN1 und ARKFDLOG1 (Downloads/ARKernelDiagnostics " +
                "bzw. App-Dateien), die du hier im Chat hochladen kannst."
            textSize = 14f
        }

        val safetyText = TextView(this).apply {
            text = "Der Plan wählt nur bereits implementierte Operatoren: Gerätekataloge, " +
                "Sensoren, IMU-Stillstand, Camera2-Lifecycle, Storage-Roundtrip, " +
                "Kern-Selbsttest, Thermal-Zeitreihe und Snapshots. Neue Android-APIs " +
                "würden ein Update derselben App erfordern, keine neue App. Nur opake " +
                "Skalare; keine Bilder, Pixel oder dichte Geometrie. Der separate " +
                "Kalibrierungs-Bildschirm erfasst mit eigenem Opt-in Maßstabs-Evidenz " +
                "und Orientierungs-Proben, aber keine metrische Position. Der " +
                "geräteadaptive Bildschirm wählt automatisch ein Profil, aber " +
                "kalibriert nicht automatisch. Der Kampagnen-Bildschirm " +
                "liefert nur einen festen Messplan, keine Auswertung. Die " +
                "Sitzungsbindung prüft nur digestgenaue Zusammengehörigkeit. " +
                "Der Gerätevergleich bindet nur deklarierte Sitzungen, keine " +
                "Kalibrierwerte. Der Retention-Bildschirm ist ein trockener " +
                "UX-Lauf ohne Löschung. Die Messdurchführung bindet deine " +
                "ARKCALIB1-Texte pro Szene, misst aber nichts selbst. " +
                "Der Live-Kamerafeed (Knopf 12) zeigt die Rückkamera und " +
                "erlaubt vier Messmarken; Bilder werden nicht gespeichert. " +
                "Knopf 13 erkennt die Zentimeter-Marken eines " +
                "ausgeklappten Zollstocks selbst im Bild. " +
                "Knopf 14 rechnet pro Szene die Abweichung gegen " +
                "dein Maßband aus – als gemessene Abweichung in " +
                "Millimetern, nicht als Genauigkeitsversprechen. " +
                "Kein Bodentruth im Log, kein Upload, " +
                "Qualität: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
        }

        optIn = CheckBox(this).apply {
            text = "Ich stimme der begrenzten Text-Diagnose zu (Opt-in)."
            textSize = 15f
        }

        statusText = TextView(this).apply {
            text = "Bitte Opt-in anhaken. Kamera-Berechtigung wird ggf. nur für " +
                "Camera2-Metadaten abgefragt."
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 12, 0, 12)
        }

        val planLabel = TextView(this).apply {
            text = "ARKFDPLAN1 (maximal 4096 Zeichen; fail-closed):"
            textSize = 13f
            setPadding(0, 12, 0, 4)
        }

        planInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(4_096))
            minLines = 6
            maxLines = 10
            isSingleLine = false
            setText(defaultPlanText)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
        }

        resetPlanButton = Button(this).apply {
            text = "Standard-ARKFDPLAN1 wiederherstellen"
            textSize = 14f
            setAllCaps(false)
        }

        createButton = Button(this).apply {
            text = "1. Kurzen Kapabilitäts-Log erzeugen"
            textSize = 15f
            setAllCaps(false)
        }
        physicalButton = Button(this).apply {
            text = "2. Ultimativen Planlauf starten (mit deiner Hilfe)"
            textSize = 15f
            setAllCaps(false)
        }
        writeButton = Button(this).apply {
            text = "3. Dateien speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "4. Log teilen"
            textSize = 15f
            setAllCaps(false)
        }
        sharePlanButton = Button(this).apply {
            text = "ARKFDPLAN1 teilen"
            textSize = 15f
            setAllCaps(false)
        }
        copyButton = Button(this).apply {
            text = "Log in Zwischenablage kopieren"
            textSize = 15f
            setAllCaps(false)
        }
        calibrationButton = Button(this).apply {
            text = "5. Physische Kalibrierung öffnen (ARKCALIB1)"
            textSize = 15f
            setAllCaps(false)
        }
        adaptiveButton = Button(this).apply {
            text = "6. Geräteadaptive Kalibrierung (ARKADAPT1)"
            textSize = 15f
            setAllCaps(false)
        }
        campaignButton = Button(this).apply {
            text = "7. Systematische Messkampagne (ARKCAMPAIGN1)"
            textSize = 15f
            setAllCaps(false)
        }
        bindingButton = Button(this).apply {
            text = "8. Sitzungsbindung (ARKBIND1)"
            textSize = 15f
            setAllCaps(false)
        }
        crossButton = Button(this).apply {
            text = "9. Gerätevergleich (ARKCROSS1)"
            textSize = 15f
            setAllCaps(false)
        }
        retentionButton = Button(this).apply {
            text = "10. Retention-UX (ARKRETENT1)"
            textSize = 15f
            setAllCaps(false)
        }
        measurementButton = Button(this).apply {
            text = "11. Geführte Messdurchführung (ARKMEAS1)"
            textSize = 15f
            setAllCaps(false)
        }

        feedButton = Button(this).apply {
            text = "12. Live-Kamerafeed & Messung (ARKFEED1)"
            textSize = 15f
            setAllCaps(false)
        }
        rulerButton = Button(this).apply {
            text = "13. Zollstock automatisch erkennen (ARKSCALE1)"
            textSize = 15f
            setAllCaps(false)
        }
        validationButton = Button(this).apply {
            text = "14. Metrische Validierung in mm (ARKVALID1)"
            textSize = 15f
            setAllCaps(false)
        }

        guideButton = Button(this).apply {
            text = "Checkliste: alles testen (anklopfen)"
            textSize = 14f
            setAllCaps(false)
            contentDescription = "Zeigt oder versteckt die Test-Checkliste"
        }
        guideText = TextView(this).apply {
            text = GUIDE_TEXT
            textSize = 12f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#FFF7ED"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Checkliste aller benötigten Testartefakte"
        }

        logPreview = TextView(this).apply {
            text = "Noch kein Log erzeugt."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
        }

        val scroll = ScrollView(this).apply {
            addView(
                logPreview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        root.addView(title, fullWidthParams())
        root.addView(versionText, fullWidthParams())
        root.addView(intro, fullWidthParams())
        root.addView(safetyText, fullWidthParams())
        root.addView(optIn, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(planLabel, fullWidthParams())
        root.addView(
            planInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                260,
            ),
        )
        root.addView(resetPlanButton, fullWidthParams())
        root.addView(
            sectionLabel("SCHRITT 1 · Log & Plan"),
            fullWidthParams(),
        )
        root.addView(createButton, fullWidthParams())
        root.addView(physicalButton, fullWidthParams())
        root.addView(writeButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(sharePlanButton, fullWidthParams())
        root.addView(copyButton, fullWidthParams())
        root.addView(guideButton, fullWidthParams())
        root.addView(guideText, fullWidthParams())
        root.addView(
            sectionLabel("SCHRITT 2 · Kalibrierung & Sitzungen"),
            fullWidthParams(),
        )
        root.addView(calibrationButton, fullWidthParams())
        root.addView(adaptiveButton, fullWidthParams())
        root.addView(campaignButton, fullWidthParams())
        root.addView(bindingButton, fullWidthParams())
        root.addView(crossButton, fullWidthParams())
        root.addView(retentionButton, fullWidthParams())
        root.addView(
            sectionLabel("SCHRITT 3 · Messung mit Kamera & Zollstock"),
            fullWidthParams(),
        )
        root.addView(measurementButton, fullWidthParams())
        root.addView(feedButton, fullWidthParams())
        root.addView(rulerButton, fullWidthParams())
        root.addView(validationButton, fullWidthParams())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

        optIn.setOnCheckedChangeListener { _, _ -> updateButtons() }
        guideButton.setOnClickListener {
            val visible = guideText.visibility == View.VISIBLE
            guideText.visibility = if (visible) View.GONE else View.VISIBLE
            guideButton.text = if (visible) {
                "Checkliste: alles testen (anklopfen)"
            } else {
                "Checkliste ausblenden"
            }
        }
        resetPlanButton.setOnClickListener {
            planInput.setText(defaultPlanText)
            currentPlanText = defaultPlanText
            statusText.text = "Standard-ARKFDPLAN1 wiederhergestellt. unverified."
        }
        createButton.setOnClickListener { createLog() }
        physicalButton.setOnClickListener { runPhysicalTests() }
        writeButton.setOnClickListener {
            Thread {
                val result = writeDiagnosticsFiles()
                runOnUiThread { statusText.text = result }
            }.start()
        }
        shareButton.setOnClickListener { shareLog() }
        sharePlanButton.setOnClickListener { sharePlan() }
        copyButton.setOnClickListener { copyLog() }
        calibrationButton.setOnClickListener {
            startActivity(
                Intent(this, CalibrationCaptureActivity::class.java),
            )
        }
        adaptiveButton.setOnClickListener {
            startActivity(
                Intent(this, AdaptiveCalibrationActivity::class.java),
            )
        }
        campaignButton.setOnClickListener {
            startActivity(
                Intent(this, CampaignPlanActivity::class.java),
            )
        }
        bindingButton.setOnClickListener {
            startActivity(
                Intent(this, SessionBindingActivity::class.java),
            )
        }
        crossButton.setOnClickListener {
            startActivity(
                Intent(this, CrossDeviceActivity::class.java),
            )
        }
        retentionButton.setOnClickListener {
            startActivity(
                Intent(this, RetentionActivity::class.java),
            )
        }
        measurementButton.setOnClickListener {
            startActivity(
                Intent(this, MeasurementActivity::class.java),
            )
        }
        feedButton.setOnClickListener {
            startActivity(
                Intent(this, MeasurementFeedActivity::class.java),
            )
        }
        rulerButton.setOnClickListener {
            startActivity(
                Intent(this, RulerScaleActivity::class.java),
            )
        }
        validationButton.setOnClickListener {
            startActivity(
                Intent(this, MetricValidationActivity::class.java),
            )
        }

        updateButtons()
        ensureCameraPermission()
    }

    /** Bold section header that groups the numbered action buttons. */
    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(0, 14, 0, 2)
            contentDescription = "Abschnitt " + text
        }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun updateButtons() {
        val allowed = optIn.isChecked
        resetPlanButton.isEnabled = allowed
        planInput.isEnabled = allowed
        createButton.isEnabled = allowed
        physicalButton.isEnabled = allowed
        sharePlanButton.isEnabled = allowed
        val hasLog = allowed && currentLog.isNotEmpty()
        writeButton.isEnabled = hasLog
        shareButton.isEnabled = hasLog
        copyButton.isEnabled = hasLog
    }

    private fun createLog() {
        if (!optIn.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        if (capturePlan() == null) return
        try {
            val text = buildLogText()
            publishLog(text, "Kapabilitäts-Log erzeugt.")
        } catch (error: Exception) {
            currentLog = ""
            currentRunId = "run.error"
            statusText.text = "Log konnte nicht erzeugt werden: ${error.message}"
            updateButtons()
        }
    }

    private fun runPhysicalTests() {
        if (!optIn.isChecked) {
            statusText.text = "Bitte zuerst das Opt-in anhaken."
            return
        }
        val (plan, planText) = capturePlan() ?: return
        physicalButton.isEnabled = false
        createButton.isEnabled = false
        Thread {
            val runner = PhysicalSelfTestRunner(this)
            val result = runner.runPlan(plan, planText) { progress ->
                runOnUiThread { statusText.text = progress }
            }
            runOnUiThread {
                try {
                    val text = buildLogText(
                        result.events,
                        result.passed,
                        result.total,
                        result.maxEvents,
                    )
                    publishLog(text, result.summary)
                } catch (error: Exception) {
                    currentLog = ""
                    currentRunId = "run.error"
                    statusText.text =
                        "Planlauf konnte nicht protokolliert werden: " +
                            error.message
                } finally {
                    updateButtons()
                }
            }
        }.start()
    }

    /** Canonicalises and fail-closes a host-pasted plan on the UI thread. */
    private fun capturePlan(): Pair<ArKernelFieldDiagnosticsTestPlan, String>? {
        val parsed = ArKernelFieldDiagnosticsTestPlanBundle.parse(
            planInput.text.toString())
        if (!parsed.accepted) {
            statusText.text = "ARKFDPLAN1 abgelehnt: ${parsed.rejection.name} " +
                "(Zeile ${parsed.lineNumber}). ${parsed.detail}"
            currentLog = ""
            currentRunId = "run.invalid.plan"
            updateButtons()
            return null
        }
        val plan = parsed.plan!!
        if (plan.maxEvents < 16) {
            statusText.text = "ARKFDPLAN1: max_events muss mindestens 16 sein " +
                "(Basis-Log plus Terminalevents)."
            currentLog = ""
            currentRunId = "run.invalid.plan"
            updateButtons()
            return null
        }
        val canonical = ArKernelFieldDiagnosticsTestPlanBundle.encode(plan)
        currentPlanText = canonical
        return plan to canonical
    }

    private fun publishLog(text: String, status: String) {
        val parsed = ArKernelFieldDiagnosticsRunLog.parse(text)
        logPreview.text = text
        if (!parsed.accepted) {
            currentLog = ""
            currentRunId = "run.invalid"
            statusText.text = "ARKFDLOG1 abgelehnt: ${parsed.rejection.name} " +
                "(Zeile ${parsed.lineNumber}). $status"
            updateButtons()
            return
        }
        currentLog = text
        val eventCount = parsed.record?.events?.size ?: 0
        statusText.text = "$status ARKFDLOG1 akzeptiert ($eventCount Events, " +
            "${text.length} Zeichen). Run: $currentRunId"
        updateButtons()
    }

    private fun buildLogText(
        extraEvents: List<PendingLogEvent> = emptyList(),
        passed: Int = 0,
        total: Int = 0,
        maxEvents: Int = 128,
    ): String {
        require(extraEvents.none {
            it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_START ||
                it.kind == ArKernelFieldDiagnosticsLogEventKind.RUN_END
        }) { "Plan events cannot contain terminal events" }
        val boundedExtraEvents = boundedPlanEvents(extraEvents, maxEvents)
        val sessionId = "session-" + sessionStartMillis
        val runId = "run-" + System.currentTimeMillis() + "." +
            runCounter.incrementAndGet()
        currentRunId = runId
        val baseNs = SystemClock.elapsedRealtimeNanos()
        val events = mutableListOf<ArKernelFieldDiagnosticsLogEvent>()

        fun add(
            kind: ArKernelFieldDiagnosticsLogEventKind,
            code: String?,
            value: Long?,
            detail: String?,
        ) {
            val sequence = events.size
            events += ArKernelFieldDiagnosticsLogEvent(
                sequence,
                baseNs + sequence.toLong() * 1_000_000L,
                kind,
                code,
                value,
                detail,
            )
        }

        val (cores, memoryToken) = memoryFacts()
        val (glMajor, glToken) = glEsFacts()
        val (sensorCount, gyroToken) = sensorFacts()
        val (cameraCount, cameraToken) = cameraFacts()
        val (thermalStatus, thermalToken) = thermalFacts()
        val (uptimeSeconds, storageToken) = timingFacts()

        add(
            ArKernelFieldDiagnosticsLogEventKind.RUN_START,
            "apk.open",
            null,
            "opt.in.apk",
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "android.api",
            Build.VERSION.SDK_INT.toLong(),
            opaqueToken("release.${Build.VERSION.RELEASE}"),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "abi",
            null,
            opaqueToken(Build.SUPPORTED_ABIS.firstOrNull() ?: "abi.unknown"),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "device",
            null,
            opaqueToken("${Build.MANUFACTURER}.${Build.MODEL}.${Build.DEVICE}"),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "build.fingerprint",
            null,
            opaqueToken(Build.FINGERPRINT),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "cpu.cores",
            cores,
            memoryToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "gl.es",
            glMajor.toLong(),
            glToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "sensors",
            sensorCount,
            gyroToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "arcore",
            arcorePresent(),
            "package.query",
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAPABILITY,
            "cameras",
            cameraCount,
            cameraToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.CAMERA_LIFECYCLE,
            "camera.permission",
            cameraPermissionGranted(),
            "characteristics.query",
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.THERMAL,
            "thermal.status",
            thermalStatus,
            thermalToken,
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.TIMING,
            "uptime.s",
            uptimeSeconds,
            storageToken,
        )
        for (pending in boundedExtraEvents) {
            add(pending.kind, pending.code, pending.value, pending.detailToken)
        }
        add(
            ArKernelFieldDiagnosticsLogEventKind.SESSION_TERMINAL,
            "structural.summary",
            passed.toLong(),
            ArKernelPhysicalSelfTestMetrics.opaqueToken(
                "pass.$passed.of.$total.unverified",
            ),
        )
        add(
            ArKernelFieldDiagnosticsLogEventKind.RUN_END,
            null,
            (events.size + 1).toLong(),
            null,
        )

        return ArKernelFieldDiagnosticsRunLog.encode(
            diagnosticsPolicy,
            sessionId,
            runId,
            events,
        )
    }

    /** Keeps the plan's event budget while retaining the final plan result. */
    private fun boundedPlanEvents(
        events: List<PendingLogEvent>,
        maxEvents: Int,
    ): List<PendingLogEvent> {
        val budget = maxEvents.coerceIn(16, 128) - 15
        if (events.size <= budget) return events
        val tailCount = minOf(7, budget - 1)
        val headCount = budget - 1 - tailCount
        val dropped = events.size - headCount - tailCount
        return events.take(headCount) +
            listOf(
                PendingLogEvent(
                    ArKernelFieldDiagnosticsLogEventKind.GUIDED_STEP,
                    "plan.events.truncated",
                    dropped.toLong(),
                    "unverified",
                ),
            ) +
            events.takeLast(tailCount)
    }

    private fun memoryFacts(): Pair<Long, String> {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024L * 1024L)
        val lowRam = if (manager?.isLowRamDevice() == true) 1 else 0
        val cores = Runtime.getRuntime().availableProcessors().toLong()
        return cores to opaqueToken("ram.mb.$totalMb.low.ram.$lowRam.cores.$cores")
    }

    private fun glEsFacts(): Pair<Int, String> {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info: ConfigurationInfo? = manager?.getDeviceConfigurationInfo()
        val raw = info?.reqGlEsVersion ?: 0
        val major = raw shr 16
        val minor = raw and 0xFFFF
        return major to opaqueToken("gl.es.$major.$minor")
    }

    private fun sensorFacts(): Pair<Long, String> {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val count = manager?.getSensorList(Sensor.TYPE_ALL)?.size?.toLong() ?: 0L
        val gyro = if (manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
            "gyro.present"
        } else {
            "gyro.absent"
        }
        return count to gyro
    }

    private fun arcorePresent(): Long = try {
        packageManager.getPackageInfo("com.google.ar.core", 0)
        1L
    } catch (error: Exception) {
        0L
    }

    private fun cameraFacts(): Pair<Long, String> = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return 0L to "camera.manager.unavailable"
        val ids = manager.getCameraIdList()
        val parts = ids.mapIndexed { index, id ->
            val level = try {
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                ) ?: -1
            } catch (error: Exception) {
                -1
            }
            "c$index.hw.$level"
        }
        ids.size.toLong() to opaqueToken(parts.joinToString("."))
    } catch (error: Exception) {
        0L to "camera.query.failed"
    }

    private fun thermalFacts(): Pair<Long, String> {
        var status = 0L
        var detail = "thermal.unavailable"
        try {
            val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                status = manager.getCurrentThermalStatus().toLong()
                detail = "thermal.status.$status"
            }
            if (manager != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val headroom = manager.getThermalHeadroom(30)
                detail = if (!headroom.isNaN() && !headroom.isInfinite() &&
                    headroom >= 0f
                ) {
                    val formatted = String.format(Locale.US, "%.2f", headroom)
                    "thermal.status.$status.headroom.$formatted"
                } else {
                    "thermal.status.$status.headroom.unknown"
                }
            }
        } catch (error: Exception) {
            detail = "thermal.query.failed"
        }
        return status to opaqueToken(detail)
    }

    private fun timingFacts(): Pair<Long, String> {
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000L
        val freeMb = try {
            StatFs(filesDir.absolutePath).getAvailableBytes() / (1024L * 1024L)
        } catch (error: Exception) {
            0L
        }
        return uptimeSeconds to opaqueToken("storage.mb.$freeMb")
    }

    private fun cameraPermissionGranted(): Long =
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) 1L else 0L

    /** ASCII-only opaque token; no whitespace, no sensitive free text. */
    private fun opaqueToken(raw: String): String {
        val mapped = raw.lowercase(Locale.ROOT).map { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' || char == '.' ||
                    char == '-' || char == ':' -> char
                else -> '.'
            }
        }.joinToString("")
        val collapsed = mapped.replace(Regex("\\.+"), ".").trim('.', '-', ':')
        return collapsed.take(128).ifEmpty { "unknown" }
    }

    private fun writeDiagnosticsFiles(): String {
        val policyName = "ARKFDPOL1_${currentRunId}.txt"
        val planName = "ARKFDPLAN1_${currentRunId}.txt"
        val logName = "ARKFDLOG1_${currentRunId}.txt"
        val policyResult = writeTextToDownloads(policyName, policyText)
        val planResult = writeTextToDownloads(planName, currentPlanText)
        val logResult = writeTextToDownloads(logName, currentLog)
        return "Richtlinie: $policyResult\nTestplan: $planResult\n" +
            "Lauf-Log: $logResult\n\n" +
            "Bitte $logName und $planName hier im Chat hochladen oder über " +
            "'Log teilen'/'ARKFDPLAN1 teilen' senden."
    }

    private fun writeTextToDownloads(fileName: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ARKernelDiagnostics",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return "Downloads: Einfügen fehlgeschlagen"
            try {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Kein Ausgabestream")
                stream.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                "Downloads/ARKernelDiagnostics/$fileName (MediaStore)"
            } catch (error: Exception) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (ignored: Exception) {
                }
                "Downloads: ${error.javaClass.simpleName}: ${error.message}"
            }
        } else {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    return "App-Dateien: Verzeichnis nicht verfügbar"
                }
                val file = File(dir, fileName)
                file.writeText(text, Charsets.UTF_8)
                file.absolutePath
            } catch (error: Exception) {
                "App-Dateien: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }

    private fun shareLog() {
        if (currentLog.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKFDLOG1 $currentRunId")
                putExtra(Intent.EXTRA_TEXT, currentLog)
            }
            startActivity(Intent.createChooser(send, "ARKFDLOG1 teilen"))
        } catch (error: Exception) {
            statusText.text = "Teilen fehlgeschlagen: ${error.message}"
        }
    }

    private fun sharePlan() {
        if (!optIn.isChecked || currentPlanText.isEmpty()) return
        if (capturePlan() == null) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKFDPLAN1 $currentRunId")
                putExtra(Intent.EXTRA_TEXT, currentPlanText)
            }
            startActivity(Intent.createChooser(send, "ARKFDPLAN1 teilen"))
        } catch (error: Exception) {
            statusText.text = "Plan teilen fehlgeschlagen: ${error.message}"
        }
    }

    private fun copyLog() {
        if (currentLog.isEmpty()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            statusText.text = "Zwischenablage nicht verfügbar."
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("ARKFDLOG1", currentLog))
        Toast.makeText(this, "Log kopiert", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        _permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, _permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            statusText.text = if (granted) {
                "Kamera-Berechtigung erteilt; bitte Opt-in anhaken und Log erzeugen."
            } else {
                "Kamera-Berechtigung verweigert; Camera2-Details bleiben begrenzt."
            }
        }
    }

    private companion object {
        const val REQUEST_CAMERA = 1001
        val GUIDE_TEXT = listOf(
            "CHECKLISTE – in dieser Reihenfolge testen.",
            "Die App prüft NICHT selbst, was schon vorliegt; das",
            "ist eine manuelle Anleitung, keine Verifikation.",
            "",
            "1. Knopf 1 (Log) + 3 (speichern) + 4 (teilen)",
            "   -> ARKFDPOL1, ARKFDPLAN1, ARKFDLOG1",
            "2. Knopf 2  -> ultimativer Planlauf, danach 3 + 4",
            "3. Knopf 6  -> ARKADAPT1  (Geräteprofil wählen)",
            "4. Knopf 7  -> ARKCAMPAIGN1 (Messplan, 9 Szenen)",
            "5. Knopf 5  -> ARKCALIB1 je Szene (dein Maßband)",
            "6. Knopf 13 -> ARKSCALE1 (Zollstock-Autoerkennung)",
            "   Zollstock auf 1 m oder 2 m ausklappen!",
            "7. Knopf 12 -> ARKFEED1 (Kamerafeed, 4 Marken)",
            "8. Knopf 11 -> ARKMEAS1 (alle 9 Szenen binden)",
            "9. Knopf 8  -> ARKBIND1 (Sitzungsbindung)",
            "10. Knopf 10 -> ARKRETENT1 (Aufbewahrung)",
            "11. Knopf 9 -> ARKCROSS1 (Vergleich, ab 2. Gerät)",
            "12. Knopf 14 -> ARKVALID1 (Maßband je Szene in mm)",
            "   Erst mit 12/13 messen und teilen, dann hier",
            "   einfügen und Referenz eintragen.",
            "",
            "Alle Dateien liegen in Downloads/ARKernelDiagnostics.",
            "Bitte die .txt-Dateien hier im Chat hochladen.",
            "Kein automatischer Upload. Qualität: unverified.",
        ).joinToString("\n")
    }
}
