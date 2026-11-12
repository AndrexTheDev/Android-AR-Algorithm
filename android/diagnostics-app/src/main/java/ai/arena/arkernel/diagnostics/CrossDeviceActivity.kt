package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelCrossDeviceComparisonAdapter
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException

private const val CROSS_SEPARATOR = "==="

/**
 * Opt-in cross-device comparison screen (`ARKCROSS1`).
 *
 * The user pastes 1–16 `ARKBIND1` session envelopes, separated by a line
 * containing exactly `===`. The screen parses each envelope, hashes its
 * canonical re-encoding with SHA-256, requires unique device tokens and
 * binding digests and writes a bounded `ARKCROSS1_<run>.txt` file.
 *
 * The portable part is the ten-question protocol, not a calibration value:
 * `per_device_calibration` and `universal_calibration_disabled` are mandatory.
 * This screen does not re-verify the three source texts behind each binding;
 * that remains `ARKBIND1.verify()` host work. Nothing is uploaded
 * automatically, no signature is verified, no image, pixel, pose or
 * trajectory is captured, no physical target is measured and no quality grade
 * is granted. `comparisonConsistent` is a structural check, not an answer,
 * not accuracy and not an upgrade. Quality stays `unverified`.
 */
class CrossDeviceActivity : DiagnosticsActivity() {

    private lateinit var inputBox: EditText
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var buildButton: Button
    private lateinit var shareButton: Button

    private val runId = "cross." + System.currentTimeMillis()
    private var currentComparison: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Gerätevergleich (ARKCROSS1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
        }
        val intro = TextView(this).apply {
            text = "Füge 1–16 ARKBIND1-Sitzungen ein. Trenne sie mit " +
                "einer eigenen Zeile, die genau $CROSS_SEPARATOR enthält. " +
                "Jede Sitzung muss ein anderes Gerät haben; dieselben " +
                "zehn kanonischen Fragen und dasselbe portable Protokoll " +
                "gelten für alle."
            textSize = 13f
        }
        val safetyText = TextView(this).apply {
            text = "Übertragbar ist nur das Verfahren, nicht ein " +
                "Kalibrierwert. Nur manuelles Teilen, keine " +
                "Signaturprüfung, keine erneute Quelltextprüfung, keine " +
                "Bilder/Posen/Trajektorien, keine automatische/universelle " +
                "Kalibrierung, keine Qualitätsaufstufung. Konsistenz ist " +
                "keine Genauigkeit; Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
        }
        statusText = TextView(this).apply {
            text = "Noch kein Vergleich erzeugt."
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        inputBox = EditText(this).apply {
            hint = "ARKBIND1-Text, dann Zeile $CROSS_SEPARATOR, " +
                "dann nächster ARKBIND1-Text"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(10, 10, 10, 10)
            setSingleLine(false)
            minLines = 5
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isVerticalScrollBarEnabled = true
        }
        val pasteButton = Button(this).apply {
            text = "1. Aus Zwischenablage einfügen"
            textSize = 14f
            setAllCaps(false)
        }
        buildButton = Button(this).apply {
            text = "2. ARKCROSS1 prüfen & speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "3. ARKCROSS1 teilen / hier hochladen"
            textSize = 15f
            setAllCaps(false)
        }
        previewText = TextView(this).apply {
            text = "Noch keine Datei erzeugt."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        controls.addView(title, fullWidthParams())
        controls.addView(intro, fullWidthParams())
        controls.addView(safetyText, fullWidthParams())
        controls.addView(statusText, fullWidthParams())
        controls.addView(inputBox, fullWidthParams())
        controls.addView(pasteButton, fullWidthParams())
        controls.addView(buildButton, fullWidthParams())
        controls.addView(shareButton, fullWidthParams())

        val controlsScroll = ScrollView(this).apply {
            addView(
                controls,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val previewScroll = ScrollView(this).apply {
            addView(
                previewText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            controlsScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.3f,
            ),
        )
        root.addView(
            previewScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.7f,
            ),
        )
        setContentView(root)

        pasteButton.setOnClickListener {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager
            val clip = manager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasted = clip.getItemAt(0).text?.toString() ?: ""
                inputBox.setText(pasted)
                statusText.text = "${pasted.length} Zeichen eingefügt. " +
                    "Bitte Trennzeilen selbst prüfen."
            } else {
                statusText.text = "Zwischenablage leer oder nicht lesbar."
            }
        }
        buildButton.setOnClickListener { buildAndSave() }
        shareButton.setOnClickListener { shareComparison() }
    }

    private fun splitBindings(raw: String): List<String> {
        val normalized = raw.replace("\r\n", "\n")
            .replace('\r', '\n')
        return normalized.split("\n$CROSS_SEPARATOR\n")
            .map { it.trim('\n') }
            .filter { it.isNotBlank() }
    }

    private fun buildAndSave() {
        val parts = splitBindings(inputBox.text.toString())
        if (parts.isEmpty()) {
            statusText.text = "Kein ARKBIND1-Text eingefügt."
            return
        }
        if (parts.size > 16) {
            statusText.text = "Höchstens 16 Sitzungen erlaubt " +
                "(eingegeben: ${parts.size})."
            return
        }
        val result =
            ArKernelCrossDeviceComparisonAdapter.create(runId, parts)
        if (!result.accepted || result.bundle == null ||
            result.report == null) {
            statusText.text = "Fail-closed: ${result.rejection} – " +
                result.detail
            previewText.text = "Kein Vergleich erzeugt."
            currentComparison = ""
            shareButton.isEnabled = false
            return
        }
        val bundle = result.bundle!!
        val report = result.report!!
        val text = try {
            ArKernelCrossDeviceComparisonAdapter.encode(bundle)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val parsed =
            ArKernelCrossDeviceComparisonAdapter.parse(text)
        if (!parsed.accepted || parsed.bundle == null) {
            statusText.text = "Fail-closed: ${parsed.rejection} – " +
                parsed.detail
            return
        }
        val summary =
            ArKernelCrossDeviceComparisonAdapter.summarize(bundle)
        currentComparison = text
        previewText.text = text
        statusText.text = "Sitzungen: ${summary.sessionCount} | " +
            "verschiedene Geräte: ${summary.distinctDeviceTokens} | " +
            "Geräteklassen: ${summary.distinctDeviceClasses} | " +
            "Kampagnen: ${summary.distinctCampaignIds}\n" +
            "Szenen ${summary.minSceneCount}–" +
            "${summary.maxSceneCount}, Beobachtungen " +
            "${summary.minObservationCount}–" +
            "${summary.maxObservationCount}\n" +
            "Konsistent: ${report.comparisonConsistent} | " +
            "Quellen erneut geprüft: " +
            "${report.sourceReverificationPerformed} | " +
            "portables Protokoll: ${summary.portableProtocolSame}\n" +
            "Nur manuelles Teilen; Konsistenz ist keine Genauigkeit; " +
            "unverified."
        Thread {
            val written = writeTextToDownloads(
                "ARKCROSS1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $written"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKCROSS1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        shareButton.isEnabled = true
    }

    private fun shareComparison() {
        if (currentComparison.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentComparison)
        }
        startActivity(Intent.createChooser(intent, "ARKCROSS1 teilen"))
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
