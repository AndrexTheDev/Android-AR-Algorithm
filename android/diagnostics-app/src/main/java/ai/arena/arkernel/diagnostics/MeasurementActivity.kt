package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelAdaptiveDeviceClass
import ai.arena.arkernel.ArKernelCampaignSceneKind
import ai.arena.arkernel.ArKernelMeasurementAdapter
import ai.arena.arkernel.ArKernelMeasurementCondition
import ai.arena.arkernel.ArKernelMeasurementEntry
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Guided, explicitly opt-in execution of the nine-scene measurement campaign.
 *
 * The user performs every physical measurement with an independent reference
 * (folding rule, surveyed target, construction site) and pastes one canonical
 * `ARKCALIB1` text per scene, separated by a line `===`. Each text is parsed,
 * canonically re-encoded and bound by SHA-256 digest into one `ARKMEAS1`
 * ledger. Scene kinds and conditions follow the fixed portable protocol;
 * calibration values are never transferred between devices.
 *
 * This screen performs no measurement by itself, captures no image, pixel,
 * frame, pose or trajectory, computes no accuracy, performs no automatic or
 * universal calibration, verifies no signature, uploads nothing, grants no
 * quality grade and does not validate centimetre accuracy. All scenes require
 * one explicit user confirmation and at least one held-out scene. The grade
 * stays `unverified`; physical validation remains host work.
 */
class MeasurementActivity : android.app.Activity() {

    private companion object {
        const val STATE_CAMPAIGN = "measurement.campaign"
        const val STATE_INPUT = "measurement.input"
        const val STATE_CONFIRM = "measurement.confirm"
        const val STATE_HELD_OUT = "measurement.held_out"
        const val STATE_CLASS = "measurement.class"
        const val STATE_LEDGER = "measurement.ledger"
        const val STATE_STATUS = "measurement.status"
    }

    private lateinit var inputText: EditText
    private lateinit var campaignInput: EditText
    private lateinit var classSpinner: Spinner
    private lateinit var confirmBox: CheckBox
    private lateinit var heldOutBox: CheckBox
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var shareButton: Button

    private val kinds = listOf(
        ArKernelCampaignSceneKind.INDOOR_SCALE,
        ArKernelCampaignSceneKind.OUTDOOR_SCALE,
        ArKernelCampaignSceneKind.RIGHT_ANGLE,
        ArKernelCampaignSceneKind.STILLNESS,
        ArKernelCampaignSceneKind.THERMAL_REPEAT,
        ArKernelCampaignSceneKind.DAY_NIGHT,
        ArKernelCampaignSceneKind.HELD_OUT,
        ArKernelCampaignSceneKind.CROSS_SESSION,
        ArKernelCampaignSceneKind.CROSS_DEVICE,
    )
    private val conditions = listOf(
        ArKernelMeasurementCondition.INDOOR,
        ArKernelMeasurementCondition.OUTDOOR,
        ArKernelMeasurementCondition.INDOOR,
        ArKernelMeasurementCondition.STILL,
        ArKernelMeasurementCondition.THERMAL,
        ArKernelMeasurementCondition.NIGHT,
        ArKernelMeasurementCondition.OUTDOOR,
        ArKernelMeasurementCondition.DAY,
        ArKernelMeasurementCondition.DAY,
    )
    private val instructions = listOf(
        "1. Innenmaß: kurze bekannte Strecke mit Zollstock messen.",
        "2. Außenmaß: lange Strecke auf der Baustelle messen.",
        "3. Rechter Winkel: orthogonalen Bezug prüfen.",
        "4. Stillstand: Gerät ruhig halten, IMU-Bezug notieren.",
        "5. Thermik: gleiche Messung mehrfach wiederholen.",
        "6. Tag/Nacht: gleiche Strecke bei anderem Licht messen.",
        "7. Held-out: eine zurückgehaltene Referenz extra messen.",
        "8. Sitzungsvergleich: gleiche Szene in neuer Sitzung.",
        "9. Gerätevergleich: gleiche Szene auf anderem Gerät.",
    )
    private val runId = "meas." + System.currentTimeMillis()
    private val deviceToken = opaqueDeviceToken()
    private var currentLedger: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "Geführte Messdurchführung (ARKMEAS1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für physische Messszenen"
        }
        val intro = TextView(this).apply {
            text = "Misse jede Szene selbst mit unabhängiger Referenz " +
                "(Zollstock, Baustelle). Für jede Szene ein ARKCALIB1-" +
                "Textfeld aus Knopf 5; mehrere Texte durch eine Zeile " +
                "===" + " trennen. Maximal neun Szenen in fester Reihenfolge."
            textSize = 13f
            contentDescription = "Anleitung zum Messprotokoll"
        }
        val guide = TextView(this).apply {
            text = instructions.joinToString("\n")
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Neun Szenen in fester Reihenfolge"
        }
        val safety = TextView(this).apply {
            text = "Keine Bilder, Posen oder Trajektorien, keine " +
                "automatische/universelle Kalibrierung, keine " +
                "Signaturprüfung, kein automatischer Upload, keine " +
                "Zentimetervalidierung, keine Qualitätsaufstufung; " +
                "Status: unverified. Jede Szene braucht deine " +
                "ausdrückliche Bestätigung und eine Held-out-Messung."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
            contentDescription = "Grenzen der Messdurchführung"
        }
        statusText = TextView(this).apply {
            text = if (savedInstanceState == null) {
                "Noch kein Messprotokoll erzeugt."
            } else {
                savedInstanceState.getString(
                    STATE_STATUS, "Zustand wiederhergestellt.")
            }
            textSize = 13f
            setPadding(0, 12, 0, 12)
            importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        campaignInput = EditText(this).apply {
            hint = "Kampagnen-ID (opak)"
            setText(savedInstanceState?.getString(
                STATE_CAMPAIGN) ?: "campaign.host.v1")
            textSize = 13f
            contentDescription = "Opake Kampagnen-ID"
        }
        classSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MeasurementActivity,
                android.R.layout.simple_spinner_item,
                listOf("full", "limited", "legacy", "unknown"),
            ).apply {
                setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item)
            }
            contentDescription = "Geräteklasse"
        }
        if (savedInstanceState != null) {
            val saved = savedInstanceState.getString(STATE_CLASS, "full")
            classSpinner.setSelection(
                listOf("full", "limited", "legacy", "unknown")
                    .indexOf(saved).coerceAtLeast(0))
        }
        inputText = EditText(this).apply {
            hint = "ARKCALIB1-Text 1\n===\nARKCALIB1-Text 2 …"
            setText(savedInstanceState?.getString(STATE_INPUT) ?: "")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            minLines = 6
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(10, 10, 10, 10)
            contentDescription = "ARKCALIB1-Texte pro Szene"
        }
        confirmBox = CheckBox(this).apply {
            text = "Ich habe alle Szenen selbst mit unabhängiger " +
                "Referenz gemessen und bestätige sie ausdrücklich."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_CONFIRM, false) ?: false
            contentDescription = "Ausdrückliche Bestätigung aller Szenen"
        }
        heldOutBox = CheckBox(this).apply {
            text = "Die letzte Szene ist eine zurückgehaltene " +
                "Held-out-Messung."
            textSize = 13f
            isChecked = savedInstanceState?.getBoolean(
                STATE_HELD_OUT, false) ?: false
            contentDescription = "Held-out-Messung vorhanden"
        }
        val writeButton = labeledButton(
            "ARKMEAS1 prüfen & speichern",
            "Erzeugt das ARKMEAS1-Messprotokoll")
        shareButton = labeledButton(
            "ARKMEAS1 teilen / hier hochladen",
            "Teilt das ARKMEAS1-Messprotokoll")
        val backButton = labeledButton(
            "Zurück zur Übersicht",
            "Kehrt zur Übersicht zurück")
        previewText = TextView(this).apply {
            text = "Noch kein Messprotokoll erzeugt."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
        }
        val scroll = ScrollView(this).apply {
            addView(
                previewText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(title, fullWidthParams())
        root.addView(intro, fullWidthParams())
        root.addView(guide, fullWidthParams())
        root.addView(safety, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(campaignInput, fullWidthParams())
        root.addView(classSpinner, fullWidthParams())
        root.addView(inputText, fullWidthParams())
        root.addView(confirmBox, fullWidthParams())
        root.addView(heldOutBox, fullWidthParams())
        root.addView(writeButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(backButton, fullWidthParams())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

        writeButton.setOnClickListener { writeLedger() }
        shareButton.setOnClickListener { shareLedger() }
        backButton.setOnClickListener { finish() }
        if (savedInstanceState != null) {
            currentLedger = savedInstanceState.getString(
                STATE_LEDGER, "")!!
            if (currentLedger.isNotEmpty()) {
                previewText.text = currentLedger
            }
        }
        shareButton.isEnabled = currentLedger.isNotEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAMPAIGN,
            campaignInput.text.toString())
        outState.putString(STATE_INPUT, inputText.text.toString())
        outState.putBoolean(STATE_CONFIRM, confirmBox.isChecked)
        outState.putBoolean(STATE_HELD_OUT, heldOutBox.isChecked)
        outState.putString(STATE_CLASS,
            classSpinner.selectedItem.toString())
        outState.putString(STATE_LEDGER, currentLedger)
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    private fun writeLedger() {
        val raw = inputText.text.toString()
        val parts = splitSources(raw)
        if (parts == null || parts.isEmpty() || parts.size > 9) {
            statusText.text = "Bitte 1–9 ARKCALIB1-Texte einfügen, " +
                "getrennt durch eine Zeile ===."
            announce("Ungültige Anzahl an ARKCALIB1-Texten")
            return
        }
        val campaignId = campaignInput.text.toString().trim()
        val deviceClass =
            ArKernelAdaptiveDeviceClass.fromWire(
                classSpinner.selectedItem.toString())
                ?: ArKernelAdaptiveDeviceClass.UNKNOWN
        val confirmed = confirmBox.isChecked
        val heldOut = heldOutBox.isChecked
        val entries = parts.mapIndexed { index, calibrationText ->
            ArKernelMeasurementEntry(
                kind = kinds[index],
                sceneId = "scene." +
                    (index + 1).toString().padStart(2, '0'),
                condition = conditions[index],
                userConfirmed = confirmed,
                heldOut = heldOut && index == parts.size - 1,
                calibrationText = calibrationText,
            )
        }
        val created = ArKernelMeasurementAdapter.create(
            measurementId = runId,
            deviceToken = deviceToken,
            deviceClass = deviceClass,
            campaignId = campaignId,
            entries = entries,
        )
        if (!created.accepted || created.bundle == null) {
            statusText.text = "Fail-closed: ${created.rejection} – " +
                created.detail
            announce("Messprotokoll abgelehnt")
            currentLedger = ""
            previewText.text = "Kein gültiges Messprotokoll."
            shareButton.isEnabled = false
            return
        }
        val text = try {
            ArKernelMeasurementAdapter.encode(created.bundle!!)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val verified = ArKernelMeasurementAdapter.verify(text)
        if (!verified.accepted || verified.bundle == null) {
            statusText.text = "Fail-closed: ${verified.rejection} – " +
                verified.detail
            return
        }
        val report = ArKernelMeasurementAdapter.summarize(
            verified.bundle!!)
        currentLedger = text
        previewText.text = text
        statusText.text = "Messprotokoll geprüft | Szenen: " +
            "${report.sceneCount}/9 | bestätigt: " +
            "${report.confirmedCount} | held-out: " +
            "${report.heldOutCount} | Digests: " +
            "${report.distinctDigests}\nVollständig: " +
            "${report.evidenceComplete} (nur Struktur, kein " +
            "Genauigkeitsurteil). Qualität: unverified."
        Thread {
            val written = writeTextToDownloads(
                "ARKMEAS1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $written"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKMEAS1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        shareButton.isEnabled = true
        announce("ARKMEAS1-Messprotokoll gespeichert")
    }

    private fun shareLedger() {
        if (currentLedger.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentLedger)
        }
        startActivity(
            Intent.createChooser(intent, "ARKMEAS1 teilen"))
    }

    private fun splitSources(raw: String): List<String>? {
        if (raw.isBlank()) return null
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val chunks = ArrayList<String>()
        val current = ArrayList<String>()
        for (line in normalized.split("\n")) {
            if (line.trim() == "===") {
                val part = current.joinToString("\n").trim()
                if (part.isEmpty()) return null
                chunks.add(part)
                current.clear()
            } else {
                current.add(line)
            }
        }
        val last = current.joinToString("\n").trim()
        if (last.isEmpty()) return null
        chunks.add(last)
        return chunks
    }

    private fun opaqueDeviceToken(): String = try {
        val raw = listOf(
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
            Build.BOARD, Build.HARDWARE,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { byte ->
            String.format(
                Locale.US, "%02x", byte.toInt() and 0xFF)
        }
    } catch (error: Exception) {
        "device.unknown"
    }

    private fun announce(text: String) {
        statusText.announceForAccessibility(text)
    }

    private fun labeledButton(
        label: String,
        description: String,
    ): Button = Button(this).apply {
        text = label
        textSize = 14f
        setAllCaps(false)
        contentDescription = description
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 6, 0, 0) }

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
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Downloads: Einfügen fehlgeschlagen"
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
                "Downloads: ${error.javaClass.simpleName}: " +
                    error.message
            }
        } else {
            try {
                val dir = getExternalFilesDir(
                    Environment.DIRECTORY_DOCUMENTS)
                    ?: getExternalFilesDir(null)
                    ?: filesDir
                if (!dir.exists() && !dir.mkdirs() &&
                    !dir.isDirectory) {
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
