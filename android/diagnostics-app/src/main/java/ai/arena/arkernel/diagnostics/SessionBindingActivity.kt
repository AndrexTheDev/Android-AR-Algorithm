package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelSessionBindingAdapter
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

/**
 * Opt-in host-side session binding screen (`ARKBIND1`).
 *
 * The user pastes the three canonical text files produced by the other
 * screens: `ARKADAPT1` (device profile), `ARKCALIB1` (physical evidence) and
 * `ARKCAMPAIGN1` (systematic plan). The screen parses all three, hashes their
 * canonical re-encodings with SHA-256, requires one identical device token and
 * one identical device class, and writes a fail-closed `ARKBIND1_<run>.txt`
 * file. Digests are stable across BOM/CRLF presentation of the same sources.
 *
 * Nothing is uploaded automatically, no signature is verified, no image,
 * pixel, pose or trajectory is captured, no physical target is measured, no
 * intrinsic, extrinsic or metric scale is inferred and no quality grade is
 * granted. `bindingConsistent` is a structural check, not an answer, not
 * accuracy and not an upgrade. Quality stays `unverified`.
 */
class SessionBindingActivity : DiagnosticsActivity() {

    private lateinit var profileBox: EditText
    private lateinit var evidenceBox: EditText
    private lateinit var planBox: EditText
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var buildButton: Button
    private lateinit var shareButton: Button

    private val runId = "bind." + System.currentTimeMillis()
    private var currentBinding: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Sitzungsbindung (ARKBIND1)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
        }
        val intro = TextView(this).apply {
            text = "Füge die drei Textdateien ein: ARKADAPT1 " +
                "(Geräteprofil), ARKCALIB1 (physische Evidenz) und " +
                "ARKCAMPAIGN1 (Messplan). Die App prüft, ob alle drei zum " +
                "selben Gerät und derselben Geräteklasse gehören, und " +
                "bindet sie digestgenau zu einer Sitzung."
            textSize = 13f
        }
        val safetyText = TextView(this).apply {
            text = "Nur manuelles Teilen, kein automatischer Upload, keine " +
                "Signaturprüfung, keine Bilder, Posen oder Trajektorien, " +
                "keine automatische/universelle Kalibrierung, keine " +
                "Qualitätsaufstufung. Konsistenz ist keine Genauigkeit; " +
                "Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
        }
        statusText = TextView(this).apply {
            text = "Noch keine Sitzung gebunden."
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }

        profileBox = sourceBox("ARKADAPT1-Text hier einfügen")
        evidenceBox = sourceBox("ARKCALIB1-Text hier einfügen")
        planBox = sourceBox("ARKCAMPAIGN1-Text hier einfügen")

        buildButton = Button(this).apply {
            text = "1. Drei Dateien prüfen & ARKBIND1 speichern"
            textSize = 15f
            setAllCaps(false)
        }
        shareButton = Button(this).apply {
            text = "2. ARKBIND1 teilen / hier im Chat hochladen"
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
        controls.addView(
            TextView(this).apply { text = "1) Geräteprofil (ARKADAPT1)" },
            fullWidthParams())
        controls.addView(profileBox, fullWidthParams())
        controls.addView(
            clipboardButton("Profil aus Zwischenablage", profileBox),
            fullWidthParams())
        controls.addView(
            TextView(this).apply { text = "2) Physische Evidenz (ARKCALIB1)" },
            fullWidthParams())
        controls.addView(evidenceBox, fullWidthParams())
        controls.addView(
            clipboardButton("Evidenz aus Zwischenablage", evidenceBox),
            fullWidthParams())
        controls.addView(
            TextView(this).apply { text = "3) Messplan (ARKCAMPAIGN1)" },
            fullWidthParams())
        controls.addView(planBox, fullWidthParams())
        controls.addView(
            clipboardButton("Plan aus Zwischenablage", planBox),
            fullWidthParams())
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
                1.4f,
            ),
        )
        root.addView(
            previewScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.6f,
            ),
        )
        setContentView(root)

        buildButton.setOnClickListener { buildAndSave() }
        shareButton.setOnClickListener { shareBinding() }
    }

    private fun sourceBox(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(10, 10, 10, 10)
            setSingleLine(false)
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isVerticalScrollBarEnabled = true
        }

    private fun clipboardButton(
        label: String,
        target: EditText,
    ): Button = Button(this).apply {
        text = label
        textSize = 13f
        setAllCaps(false)
        setOnClickListener {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager
            val clip = manager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasted = clip.getItemAt(0).text?.toString() ?: ""
                target.setText(pasted)
                statusText.text = "$label: ${pasted.length} Zeichen " +
                    "eingefügt. Bitte selbst prüfen."
            } else {
                statusText.text = "Zwischenablage leer oder nicht lesbar."
            }
        }
    }

    private fun buildAndSave() {
        val profile = profileBox.text.toString()
        val evidence = evidenceBox.text.toString()
        val plan = planBox.text.toString()
        if (profile.isBlank() || evidence.isBlank() || plan.isBlank()) {
            statusText.text = "Alle drei Textfelder müssen ausgefüllt sein."
            return
        }
        val result = ArKernelSessionBindingAdapter.create(
            runId, profile, evidence, plan)
        if (!result.accepted || result.bundle == null ||
            result.report == null) {
            statusText.text = "Fail-closed: ${result.rejection} – " +
                result.detail
            previewText.text = "Keine Sitzung erzeugt."
            currentBinding = ""
            shareButton.isEnabled = false
            return
        }
        val bundle = result.bundle!!
        val report = result.report!!
        val text = try {
            ArKernelSessionBindingAdapter.encode(bundle)
        } catch (error: IllegalArgumentException) {
            statusText.text = "Kodierung abgelehnt: ${error.message}"
            return
        }
        val parsed = ArKernelSessionBindingAdapter.parse(text)
        if (!parsed.accepted || parsed.bundle == null) {
            statusText.text = "Fail-closed: ${parsed.rejection} – " +
                parsed.detail
            return
        }
        currentBinding = text
        previewText.text = text
        statusText.text = "Gerät: ${bundle.deviceToken} | " +
            "Kampagne: ${bundle.campaignId} | " +
            "Klasse: ${bundle.deviceClass.wireName()}\n" +
            "Digests: ${report.digestsMatch}, Token: " +
            "${report.deviceTokenMatch}, Zähler: " +
            "${report.countsMatch}, Klasse: " +
            "${report.deviceClassMatch}\n" +
            "Bindung konsistent: ${report.bindingConsistent} | " +
            "Evidenz-Vorprüfung: ${report.evidenceReady} | " +
            "Kampagne vollständig: " +
            "${report.campaignEvidenceComplete}\n" +
            "Nur manuelles Teilen; Konsistenz ist keine Genauigkeit; " +
            "unverified."
        Thread {
            val written = writeTextToDownloads(
                "ARKBIND1_${runId}.txt", text)
            runOnUiThread {
                statusText.text = statusText.text.toString() +
                    "\nDatei: $written"
            }
        }.start()
        Toast.makeText(
            this,
            "ARKBIND1 erzeugt. Bitte Text hier im Chat hochladen.",
            Toast.LENGTH_LONG,
        ).show()
        shareButton.isEnabled = true
    }

    private fun shareBinding() {
        if (currentBinding.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentBinding)
        }
        startActivity(Intent.createChooser(intent, "ARKBIND1 teilen"))
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
