package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelConditionEvidenceAdapter
import ai.arena.arkernel.ArKernelConditionLedger
import ai.arena.arkernel.ArKernelConditionSample
import ai.arena.arkernel.ArKernelMeasurementCondition
import ai.arena.arkernel.ArKernelRigKind
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Collects controlled-condition evidence: which rig, which light, which
 * thermal state, and whether it was repeated.
 *
 * Two of the values are read from the device instead of typed: the thermal
 * status and headroom come from the platform thermal API, and the light level
 * from the ambient light sensor. Both are readings from **this** device at
 * this moment, not a calibrated measurement of the scene — a phone in a pocket
 * reads darkness at noon, and a lamp pointed at the sensor reads daylight at
 * midnight.
 *
 * The condition label itself is always the operator's choice. The ledger does
 * not check the label against reality; it only checks it against the two lux
 * thresholds the operator also declared, so a run that was mislabelled by
 * mistake shows up as a conflict instead of passing silently.
 */
class ConditionEvidenceActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_LEDGER = "cond.ledger"
        const val STATE_DEVICE = "cond.device"
        const val STATE_CAMPAIGN = "cond.campaign"
        const val STATE_REPORT = "cond.report"
        const val STATE_STATUS = "cond.status"
        const val MAX_SAMPLES = 64
        const val MAX_POSE = 1_023
        const val MAX_REPEAT = 63
        const val MAX_THERMAL = 6
        const val MAX_HEADROOM = 1_000
        const val MAX_LUX = 200_000.0
        const val ID_PATTERN = "[A-Za-z0-9._:-]{1,64}"
        val samples = ArrayList<ArKernelConditionSample>()
    }

    private lateinit var ledgerInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var campaignInput: EditText
    private lateinit var requiredInput: EditText
    private lateinit var minSamplesInput: EditText
    private lateinit var maxThermalInput: EditText
    private lateinit var dayMinInput: EditText
    private lateinit var nightMaxInput: EditText
    private lateinit var conditionSpinner: Spinner
    private lateinit var rigIdInput: EditText
    private lateinit var rigKindSpinner: Spinner
    private lateinit var poseInput: EditText
    private lateinit var repeatInput: EditText
    private lateinit var thermalInput: EditText
    private lateinit var headroomInput: EditText
    private lateinit var luxInput: EditText
    private lateinit var listText: TextView
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private var lastLedger: ArKernelConditionLedger? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) samples.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Bedingungs-Evidenz (ARKCOND1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für Bedingungs-Evidenz"
        }
        val version = TextView(this).apply {
            text = "Version 2.16-cond"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Namen eintragen und die Schwellen festlegen " +
                "(Tageslicht ab wie viel Lux, Nacht bis wie viel Lux). " +
                "2) Für jede Aufnahme: Bedingung, Rig, Pose und " +
                "Wiederholung wählen, „Thermik & Licht messen“ " +
                "drücken, dann „Sample hinzufügen“. " +
                "3) „Auswerten“. 4) Datei speichern und selbst " +
                "hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Bedingungs-Evidenz"
        }
        val safety = TextView(this).apply {
            text = "Thermik und Licht liest die App selbst vom Gerät – " +
                "als Momentaufnahme, nicht als kalibrierte Messung " +
                "deiner Szene. Die Bedingung (Tag/Nacht/…) wählst " +
                "du; die App prüft sie nur gegen die beiden " +
                "Lux-Schwellen, die du selbst gesetzt hast. Damit " +
                "fällt ein falsch beschrifteter Lauf auf, mehr nicht. " +
                "Eine Wiederholung zählt nur, wenn dieselbe Pose am " +
                "demselben Rig mindestens zweimal kommt. Ob das deine " +
                "Genauigkeit verbessert, sagt diese Datei nicht. " +
                "Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen der Bedingungs-Evidenz"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_STATUS)
                ?: "Noch kein Sample hinzugefügt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

        ledgerInput = textEdit(
            savedInstanceState?.getString(STATE_LEDGER) ?: "cond.run1",
            "Name des Bedingungs-Ledgers",
        )
        deviceInput = textEdit(
            savedInstanceState?.getString(STATE_DEVICE) ?: "phone.one",
            "Geräte-Name",
        )
        campaignInput = textEdit(
            savedInstanceState?.getString(STATE_CAMPAIGN) ?: "campaign.run1",
            "Kampagnen-Name",
        )
        requiredInput = intEdit("2", "Nötige Bedingungen", 1)
        minSamplesInput = intEdit("2", "Mindest-Samples je Bedingung", 2)
        maxThermalInput = intEdit("2", "Erlaubte Thermikstufe", 1)
        dayMinInput = decimalEdit("50", "Tageslicht ab Lux")
        nightMaxInput = decimalEdit("10", "Nacht bis Lux")

        conditionSpinner = spinnerOf(
            ArKernelMeasurementCondition.entries
                .map { it.wireName() }
                .toTypedArray(),
            "Bedingung",
        )
        rigIdInput = textEdit("tripod.1", "Name des Rigs")
        rigKindSpinner = spinnerOf(
            ArKernelRigKind.entries.map { it.wireName() }.toTypedArray(),
            "Art der Halterung",
        )
        poseInput = intEdit("0", "Pose-Nummer", 4)
        repeatInput = intEdit("0", "Wiederholungsnummer", 2)
        thermalInput = intEdit("0", "Thermikstufe des Geräts", 1)
        headroomInput = intEdit("900", "Thermik-Reserve in Promille", 4)
        luxInput = decimalEdit("", "Gemessene Helligkeit in Lux")

        listText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#ECFDF5"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Hinzugefügte Samples"
        }
        reportText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_REPORT)
                ?: "Noch keine Auswertung erstellt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            contentDescription = "Ergebnis der Auswertung"
        }

        val measureButton = Button(this).apply {
            text = "Thermik & Licht messen"
            textSize = 15f
            setAllCaps(false)
        }
        val addButton = Button(this).apply {
            text = "Sample hinzufügen"
            textSize = 15f
            setAllCaps(false)
        }
        val createButton = Button(this).apply {
            text = "Auswerten"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "ARKCOND1-Datei speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        shareButton = Button(this).apply {
            text = "ARKCOND1 teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        val resetButton = Button(this).apply {
            text = "Samples zurücksetzen"
            textSize = 15f
            setAllCaps(false)
        }

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        root.addView(statusText)
        addLabelled(root, "Name des Ledgers:", ledgerInput)
        addLabelled(root, "Geräte-Name:", deviceInput)
        addLabelled(root, "Kampagnen-Name:", campaignInput)
        addLabelled(root, "Nötige Bedingungen (1-6):", requiredInput)
        addLabelled(
            root, "Mindest-Samples je Bedingung (1-64):", minSamplesInput)
        addLabelled(root, "Erlaubte Thermikstufe (0-6):", maxThermalInput)
        addLabelled(root, "Tageslicht ab (Lux):", dayMinInput)
        addLabelled(root, "Nacht bis (Lux):", nightMaxInput)
        addLabelled(root, "Bedingung:", conditionSpinner)
        addLabelled(root, "Name des Rigs:", rigIdInput)
        addLabelled(root, "Halterung:", rigKindSpinner)
        addLabelled(root, "Pose-Nummer (0-1023):", poseInput)
        addLabelled(root, "Wiederholung (0-63):", repeatInput)
        addLabelled(root, "Thermikstufe (0-6):", thermalInput)
        addLabelled(root, "Thermik-Reserve (0-1000):", headroomInput)
        addLabelled(root, "Helligkeit (Lux):", luxInput)
        root.addView(measureButton, fullWidthParams())
        root.addView(addButton, fullWidthParams())
        root.addView(listText, fullWidthParams())
        root.addView(createButton, fullWidthParams())
        root.addView(reportText)
        root.addView(saveButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(resetButton, fullWidthParams())

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        measureButton.setOnClickListener { measureConditions() }
        addButton.setOnClickListener { addSample() }
        createButton.setOnClickListener { createLedger() }
        saveButton.setOnClickListener { saveLedger() }
        shareButton.setOnClickListener { shareLedger() }
        resetButton.setOnClickListener { resetSamples() }
        renderList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LEDGER, ledgerInput.text.toString())
        outState.putString(STATE_DEVICE, deviceInput.text.toString())
        outState.putString(STATE_CAMPAIGN, campaignInput.text.toString())
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    private fun measureConditions() {
        status("Messe Thermik und Umgebungslicht …")
        Thread {
            val thermal = readThermal()
            val luxMilli = sampleAmbientLuxMilli()
            runOnUiThread {
                thermalInput.setText(thermal.first.toString())
                headroomInput.setText(thermal.second.toString())
                if (luxMilli == null) {
                    status("Kein Lichtsensor oder keine Messung " +
                        "möglich – bitte Helligkeit selbst eintragen.")
                } else {
                    luxInput.setText(formatLux(luxMilli))
                    status("Gemessen: Thermikstufe " +
                        "${thermal.first}, Reserve " +
                        "${thermal.second} ‰, " +
                        "${formatLux(luxMilli)} Lux.")
                }
            }
        }.start()
    }

    @SuppressLint("NewApi")
    private fun readThermal(): Pair<Int, Int> {
        val manager = getSystemService(Context.POWER_SERVICE)
            as? PowerManager
        val status = try {
            if (manager != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                manager.getCurrentThermalStatus()
            } else {
                0
            }
        } catch (error: Exception) {
            0
        }
        val headroom = try {
            if (manager != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val value = manager.getThermalHeadroom(30)
                if (!value.isNaN() && !value.isInfinite() && value >= 0f) {
                    value
                } else {
                    0f
                }
            } else {
                0f
            }
        } catch (error: Exception) {
            0f
        }
        val permille = Math.round(headroom * 1_000f).coerceIn(0, MAX_HEADROOM)
        return status.coerceIn(0, MAX_THERMAL) to permille
    }

    /** Median of up to a second of ambient light samples, in milli-lux. */
    private fun sampleAmbientLuxMilli(): Long? {
        val manager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?: return null
        val thread = HandlerThread("cond-light").apply { start() }
        val handler = Handler(thread.looper)
        val values = Collections.synchronizedList(mutableListOf<Float>())
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty() && values.size < 64) {
                    values.add(event.values[0])
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }
        return try {
            manager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_UI,
                handler,
            )
            val done = CountDownLatch(1)
            handler.postDelayed({ done.countDown() }, 1_000L)
            done.await(5_000L, TimeUnit.MILLISECONDS)
            val copy = synchronized(values) { values.toList() }
            if (copy.isEmpty()) {
                null
            } else {
                val sorted = copy.sorted()
                val median = sorted[sorted.size / 2].toDouble()
                Math.round(median * 1_000.0)
            }
        } catch (error: Exception) {
            null
        } finally {
            try {
                manager.unregisterListener(listener)
            } catch (ignored: Exception) {
            }
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    private fun addSample() {
        val rigId = rigIdInput.text.toString().trim()
        if (!rigId.matches(Regex(ID_PATTERN))) {
            status("Ungültiger Rig-Name: nur A-Z, a-z, 0-9, Punkt, " +
                "Unterstrich, Doppelpunkt, Bindestrich.")
            return
        }
        if (samples.size >= MAX_SAMPLES) {
            status("Maximal $MAX_SAMPLES Samples pro Ledger.")
            return
        }
        val pose = readInt(poseInput, "Pose-Nummer", MAX_POSE) ?: return
        val repeat = readInt(repeatInput, "Wiederholung", MAX_REPEAT)
            ?: return
        val thermal = readInt(thermalInput, "Thermikstufe", MAX_THERMAL)
            ?: return
        val headroom =
            readInt(headroomInput, "Thermik-Reserve", MAX_HEADROOM) ?: return
        val luxMilli = readLuxMilli(luxInput) ?: return
        val condition = ArKernelMeasurementCondition.fromWire(
            conditionSpinner.selectedItem.toString())
            ?: ArKernelMeasurementCondition.DAY
        val rigKind = ArKernelRigKind.fromWire(
            rigKindSpinner.selectedItem.toString())
            ?: ArKernelRigKind.HANDHELD
        var stamp = System.currentTimeMillis()
        val last = samples.lastOrNull()
        if (last != null && stamp <= last.timestampMillis) {
            stamp = last.timestampMillis + 1
        }
        val key = listOf(
            rigId,
            condition.wireName(),
            pose.toString(),
            repeat.toString(),
        ).joinToString("|")
        if (samples.any {
                listOf(
                    it.rigId,
                    it.condition.wireName(),
                    it.poseIndex.toString(),
                    it.repeatIndex.toString(),
                ).joinToString("|") == key
            }) {
            status("Diese Kombination aus Rig, Bedingung, Pose und " +
                "Wiederholung gibt es schon.")
            return
        }
        samples += ArKernelConditionSample(
            index = samples.size,
            condition = condition,
            rigId = rigId,
            rigKind = rigKind,
            poseIndex = pose,
            repeatIndex = repeat,
            thermalStatus = thermal,
            headroomPermille = headroom,
            luxMilli = luxMilli,
            timestampMillis = stamp,
        )
        lastLedger = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        renderList()
        status("Sample $key hinzugefügt.")
    }

    private fun readInt(
        input: EditText,
        label: String,
        maximum: Int,
    ): Int? {
        val value = input.text.toString().trim().toIntOrNull()
        if (value == null || value < 0 || value > maximum) {
            status("$label muss eine Zahl zwischen 0 und $maximum sein.")
            return null
        }
        return value
    }

    private fun readLuxMilli(input: EditText): Long? {
        val text = input.text.toString().trim()
        val value = text.toDoubleOrNull()
        if (value == null || value < 0.0 || value > MAX_LUX) {
            status("Helligkeit muss eine Zahl zwischen 0 und " +
                "$MAX_LUX Lux sein.")
            return null
        }
        return Math.round(value * 1_000.0)
    }

    private fun createLedger() {
        if (samples.isEmpty()) {
            status("Bitte zuerst mindestens ein Sample hinzufügen.")
            return
        }
        val required = readInt(requiredInput, "Nötige Bedingungen", 6)
            ?.coerceAtLeast(1) ?: return
        val minSamples =
            readInt(minSamplesInput, "Mindest-Samples", 64)
                ?.coerceAtLeast(1) ?: return
        val maxThermal = readInt(maxThermalInput, "Thermikstufe", 6)
            ?: return
        val dayMin = readLuxMilli(dayMinInput) ?: return
        val nightMax = readLuxMilli(nightMaxInput) ?: return
        for (name in listOf(
            ledgerInput.text.toString().trim(),
            deviceInput.text.toString().trim(),
            campaignInput.text.toString().trim(),
        )) {
            if (!name.matches(Regex(ID_PATTERN))) {
                status("Ungültiger Name: nur A-Z, a-z, 0-9, Punkt, " +
                    "Unterstrich, Doppelpunkt, Bindestrich.")
                return
            }
        }
        val result = ArKernelConditionEvidenceAdapter.create(
            ledgerId = ledgerInput.text.toString().trim(),
            deviceToken = deviceInput.text.toString().trim(),
            campaignId = campaignInput.text.toString().trim(),
            requiredConditions = required,
            minSamplesPerCondition = minSamples,
            maxThermalStatus = maxThermal,
            dayMinLuxMilli = dayMin,
            nightMaxLuxMilli = nightMax,
            samples = samples.toList(),
        )
        if (!result.accepted) {
            lastLedger = null
            saveButton.isEnabled = false
            shareButton.isEnabled = false
            status("Abgelehnt: ${result.rejection} – ${result.detail}")
            return
        }
        val ledger = result.ledger ?: return
        lastLedger = ledger
        saveButton.isEnabled = true
        shareButton.isEnabled = true
        reportText.text = formatLedger(ledger)
        status(if (ledger.evidenceSufficient) {
            "Auswertung erstellt und ausreichend."
        } else {
            "Auswertung erstellt, aber nicht ausreichend."
        })
    }

    private fun formatLedger(ledger: ArKernelConditionLedger): String {
        val lines = ArrayList<String>()
        lines += "Samples: ${ledger.sampleCount}   Bedingungen: " +
            "${ledger.conditionCount}   davon ausreichend: " +
            "${ledger.coveredConditions}"
        lines += "Tag: ${ledger.dayCount}   Nacht: " +
            "${ledger.nightCount}   Rigs: ${ledger.rigCount}   " +
            "davon aus der Hand: ${ledger.handheldCount}"
        lines += "Helligkeit: ${formatLux(ledger.minLuxMilli)} bis " +
            "${formatLux(ledger.maxLuxMilli)} Lux   höchste " +
            "Thermikstufe: ${ledger.observedMaxThermalStatus}"
        lines += if (ledger.dayNightCovered) {
            "+ Tag und Nacht sind beide ausreichend belegt."
        } else {
            "- Tag oder Nacht sind nicht ausreichend belegt."
        }
        lines += if (ledger.labelsCoherent) {
            "+ Kein Sample widerspricht deinen Lux-Schwellen."
        } else {
            "- ${ledger.labelConflicts} Sample(s) widersprechen " +
                "deinen eigenen Lux-Schwellen. Prüfe die " +
                "Beschriftung oder die Schwellen."
        }
        lines += if (ledger.thermalStable) {
            "+ Das Gerät war innerhalb der erlaubten Thermikstufe."
        } else {
            "- Thermikstufe ${ledger.observedMaxThermalStatus} liegt " +
                "über der erlaubten ${ledger.maxThermalStatus}."
        }
        lines += if (ledger.repeatabilityDocumented) {
            "+ Mindestens eine Pose wurde wiederholt."
        } else {
            "- Keine Pose wurde am selben Rig wiederholt."
        }
        lines += if (ledger.evidenceSufficient) {
            "Evidenz: ausreichend."
        } else {
            "Evidenz: NICHT ausreichend."
        }
        lines += "Das sagt nichts über die Genauigkeit deiner " +
            "Rekonstruktion – nur, dass die Bedingungen belegt sind. " +
            "Status: unverified."
        return lines.joinToString("\n")
    }

    private fun renderList() {
        if (samples.isEmpty()) {
            listText.text = "Noch kein Sample hinzugefügt."
            return
        }
        val lines = ArrayList<String>(samples.size + 1)
        lines += String.format(
            Locale.US,
            "%-3s %-8s %-12s %-10s %5s %3s %3s %10s",
            "Nr", "Bedingung", "Rig", "Halterung", "Pose", "Wh", "Th",
            "Lux")
        for (sample in samples) {
            lines += String.format(
                Locale.US,
                "%-3d %-8s %-12s %-10s %5d %3d %3d %10s",
                sample.index,
                sample.condition.wireName(),
                sample.rigId,
                sample.rigKind.wireName(),
                sample.poseIndex,
                sample.repeatIndex,
                sample.thermalStatus,
                formatLux(sample.luxMilli),
            )
        }
        listText.text = lines.joinToString("\n")
    }

    private fun formatLux(luxMilli: Long): String =
        String.format(Locale.US, "%.1f", luxMilli / 1_000.0)

    private fun resetSamples() {
        samples.clear()
        lastLedger = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        reportText.text = "Noch keine Auswertung erstellt."
        renderList()
        status("Samples zurückgesetzt.")
    }

    private fun saveLedger() {
        val ledger = lastLedger
        if (ledger == null) {
            status("Bitte zuerst auswerten.")
            return
        }
        val name = "ARKCOND1_${System.currentTimeMillis()}.txt"
        val path = writeTextToDownloads(
            name,
            ArKernelConditionEvidenceAdapter.encode(ledger),
        )
        status(path)
        Toast.makeText(this, "Ledger gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun shareLedger() {
        val ledger = lastLedger ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKCOND1")
                putExtra(
                    Intent.EXTRA_TEXT,
                    ArKernelConditionEvidenceAdapter.encode(ledger),
                )
            }
            startActivity(Intent.createChooser(send, "ARKCOND1 teilen"))
        } catch (error: Exception) {
            status("Teilen fehlgeschlagen: ${error.message}")
        }
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun addLabelled(
        root: LinearLayout,
        label: String,
        view: View,
    ) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(0, 8, 0, 4)
        })
        root.addView(view)
    }

    private fun textEdit(initial: String, description: String): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            isSingleLine = true
            setText(initial)
            textSize = 13f
            contentDescription = description
        }

    private fun intEdit(
        initial: String,
        description: String,
        maximum: Int = 9,
    ): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(maximum))
        isSingleLine = true
        setText(initial)
        textSize = 13f
        contentDescription = description
    }

    private fun decimalEdit(
        initial: String,
        description: String,
    ): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL
        filters = arrayOf(InputFilter.LengthFilter(12))
        isSingleLine = true
        setText(initial)
        textSize = 13f
        contentDescription = description
    }

    private fun spinnerOf(
        values: Array<String>,
        description: String,
    ): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@ConditionEvidenceActivity,
            android.R.layout.simple_spinner_dropdown_item,
            values,
        )
        contentDescription = description
    }

    private fun writeTextToDownloads(
        fileName: String,
        text: String,
    ): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS +
                        "/ARKernelDiagnostics",
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
                "Downloads/ARKernelDiagnostics/$fileName"
            } catch (error: Exception) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (ignored: Exception) {
                }
                "Downloads: ${error.javaClass.simpleName}: ${error.message}"
            }
        } else {
            try {
                val dir = getExternalFilesDir(
                    Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    return "App-Dateien: Verzeichnis nicht verfügbar"
                }
                val file = File(dir, fileName)
                file.writeText(text, Charsets.UTF_8)
                file.absolutePath
            } catch (error: Exception) {
                "App-Dateien: ${error.javaClass.simpleName}: " +
                    error.message
            }
        }
    }
}
