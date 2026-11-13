package ai.arena.arkernel.diagnostics

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.arena.arkernel.diagnostics.DiagnosticsActivity
import java.io.IOException

/**
 * Fragen-Sammlung für die Finalisierung des AR-Kerns.
 *
 * Diese Aktivität stellt dem Nutzer alle Fragen, deren Antworten für den
 * Abschluss des AR-Kerns benötigt werden. Die Antworten werden lokal
 * gesammelt und können als Textdatei exportiert werden (z. B. zur
 * Übermittlung an das Entwicklungsteam).
 *
 * Keine der Fragen erfordert Kamerazugriff, Sensor-Lesung oder I/O -
 * sie dienen dazu, dem Nutzer abzufragen, was das Gerät und die Umgebung
 * hergeben, und welche Erfahrungen er mit der App gemacht hat.
 */
class KernelFinalizationQuestionsActivity : DiagnosticsActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout

    // Antwort-Speicher: Frage-ID -> Antwort-Text
    private val answers = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "AR-Kernel Finalisierung – Fragen"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        val intro = TextView(this).apply {
            text =
                "Bitte beantworte so viele Fragen wie möglich. Deine Antworten " +
                "helfen dabei, den AR-Kernel für dein Gerät und deine Umgebung " +
                "zu finalisieren. Alle Antworten bleiben lokal auf deinem " +
                "Gerät und können exportiert werden."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        root.addView(intro)

        scrollView = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)
        root.addView(scrollView)

        // Alle Fragengruppen hinzufügen
        addSection("1. Gerät & Hardware")
        addQuestion("hw_01", "Welches Handy-Modell verwendest du?", EditText(this))
        addQuestion("hw_02", "Welche Android-Version ist installiert?", EditText(this))
        addQuestion("hw_03", "Wie viel RAM hat das Gerät (in GB)?", EditText(this))
        addQuestion("hw_04", "Verfügt das Gerät über einen Rotationssensor?", CheckBox(this))
        addQuestion("hw_05", "Verfügt das Gerät über ein Gyroskop?", CheckBox(this))
        addQuestion("hw_06", "Verfügt das Gerät über einen Beschleunigungssensor?", CheckBox(this))
        addQuestion("hw_07", "Gibt es bekannte Überhitzungsprobleme bei längerer Nutzung?", CheckBox(this))

        addSection("2. Kamera")
        addQuestion("cam_01", "Wie viele Kameras hat das Gerät (Haupt-, Ultraweitwinkel, Tele, Selfie)?", EditText(this))
        addQuestion("cam_02", "Welche Kamera wird hauptsächlich für AR genutzt?", EditText(this))
        addQuestion("cam_03", "Kann die Kamera manuell auf eine bestimmte Brennweite/Entfernung fokussieren?", CheckBox(this))
        addQuestion("cam_04", "Gibt es sichtbares Bildrauschen bei schlechten Lichtverhältnissen?", CheckBox(this))
        addQuestion("cam_05", "Wie ist die maximale Auflösung der Preview (z. B. 1920x1080)?", EditText(this))
        addQuestion("cam_06", "Unterstützt die Kamera Camera2 API?", CheckBox(this))

        addSection("3. Sensoren & IMU")
        addQuestion("imu_01", "Wird die IMU-Daten während der Bewegung stabil erfasst?", CheckBox(this))
        addQuestion("imu_02", "Gibt es spürbare Verzögerungen (Latenz) bei der Sensorausgabe?", CheckBox(this))
        addQuestion("imu_03", "Ist das Gerät bei der Nutzung bewegungsruhig oder wird es bewegt?", EditText(this))
        addQuestion("imu_04", "Wie oft tritt ein Sensor-Timeout oder -Ausfall auf?", EditText(this))

        addSection("4. Umgebung & Ziel")
        addQuestion("env_01", "Welche Art von Schachbrett-Ziel wird verwendet (9x7 Ecken, andere Größe)?", EditText(this))
        addQuestion("env_02", "Wie groß ist ein Kästchen des Schachbretts in Millimetern (falls gemessen)?", EditText(this))
        addQuestion("env_03", "Liegt das Brett flach auf einer ebenen Oberfläche?", CheckBox(this))
        addQuestion("env_04", "Wird das Brett unter gleichmäßiger Beleuchtung photographed?", CheckBox(this))
        addQuestion("env_05", "Gibt es reflexionsarme Oberflächen in der Umgebung?", CheckBox(this))
        addQuestion("env_06", "Welche Entfernung liegt typischerweise zwischen Kamera und Ziel (in cm)?", EditText(this))

        addSection("5. Nutzung & Erfahrung")
        addQuestion("use_01", "Wie oft wurde die App bereits gestartet?", EditText(this))
        addQuestion("use_02", "Welche Funktionen werden am häufigsten genutzt?", EditText(this))
        addQuestion("use_03", "Gab es Abstürze oder Einfrieren der App?", CheckBox(this))
        addQuestion("use_04", "Welche Schritte des Assistenten wurden bereits durchlaufen?", EditText(this))
        addQuestion("use_05", "Welche Fehlermeldungen wurden angezeigt (falls vorhanden)?", EditText(this))
        addQuestion("use_06", "Wie zufrieden bist du mit der Genauigkeit der Messungen (1-5)?", EditText(this))

        addSection("6. Kalibrierung & Messwerte")
        addQuestion("cal_01", "Wurde eine Kamera-Intrinsik-Kalibrierung durchgeführt?", CheckBox(this))
        addQuestion("cal_02", "Wurde eine Kamera-IMU-Extrinsik-Kalibrierung durchgeführt?", CheckBox(this))
        addQuestion("cal_03", "Sind die gemessenen Entfernungen plausibel?", CheckBox(this))
        addQuestion("cal_04", "Welche Abweichung wird typischerweise beobachtet (in mm oder cm)?", EditText(this))
        addQuestion("cal_05", "Wurden physische Referenzmaße mit einem Maßband überprüft?", CheckBox(this))

        addSection("7. Offene Probleme")
        addQuestion("prob_01", "Welche Funktionen funktionieren nicht wie erwartet?", EditText(this))
        addQuestion("prob_02", "Gibt es spezifische Szenarien, in denen die App versagt?", EditText(this))
        addQuestion("prob_03", "Welche Verbesserungen sind am dringendsten erforderlich?", EditText(this))

        // Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 0)
            }
        }

        val exportButton = Button(this).apply {
            text = "Antworten exportieren"
            setOnClickListener { exportAnswers() }
            contentDescription = "Gesammelte Antworten als Textdatei exportieren"
        }
        buttonRow.addView(exportButton)

        val finishButton = Button(this).apply {
            text = "Fertig – zurück zum Hauptbildschirm"
            setOnClickListener { finish() }
            contentDescription = "Fragen-Sammlung beenden und zum Hauptbildschirm zurückkehren"
        }
        buttonRow.addView(finishButton)

        root.addView(buttonRow)

        setContentView(root)

        // Window insets behandeln (edge-to-edge)
        window.setDecorFitsSystemWindows(false)
        val decor = window.decorView
        decor.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun addSection(title: String) {
        val section = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        container.addView(section)
    }

    private fun addQuestion(id: String, question: String, view: View) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val label = TextView(this).apply {
            text = question
            textSize = 14f
            setPadding(0, 0, 0, 4)
        }
        row.addView(label)

        when (view) {
            is EditText -> {
                view.hint = "Antwort hier eingeben …"
                view.maxLines = 3
                view.isSingleLine = false
                view.contentDescription = "Antwort für: $question"
                // Antwortänderung speichern
                view.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        answers[id] = s?.toString() ?: ""
                    }
                })
            }
            is CheckBox -> {
                view.text = "Ja"
                view.contentDescription = "Ja/Nein für: $question"
                view.setOnCheckedChangeListener { _, isChecked ->
                    answers[id] = isChecked.toString()
                }
            }
        }
        row.addView(view)
        container.addView(row)
    }

    private fun exportAnswers() {
        val sb = StringBuilder()
        sb.append("AR-Kernel Finalisierung – Nutzer-Antworten\n")
        sb.append("Gerät: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Android: ${android.os.Build.VERSION.RELEASE}\n")
        sb.append("==============================\n\n")

        for ((id, answer) in answers) {
            sb.append("$id: $answer\n")
        }

        sb.append("\n==============================\n")
        sb.append("Ende der Antworten\n")

        val text = sb.toString()

        // Als Datei speichern und teilen
        val fileName = "ARKFINAL_${currentRunId()}.txt"
        val saveResult = writeAnswersToDownloads(fileName, text)

        // Als Intent zum Teilen anbieten
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AR-Kernel Finalisierung – Nutzer-Antworten")
            putExtra(Intent.EXTRA_TEXT, "$text\n\n$saveResult")
        }

        startActivity(Intent.createChooser(shareIntent, "Antworten teilen/exportieren"))
    }

    /** Eindeutige Lauf-ID für die Antwortdatei (opak, gerätegebunden). */
    private fun currentRunId(): String {
        val raw = "${android.os.Build.MANUFACTURER}|${android.os.Build.MODEL}|${android.os.Build.VERSION.RELEASE}"
        return raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "_").take(64)
    }

    private fun writeAnswersToDownloads(fileName: String, text: String): String {
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

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, KernelFinalizationQuestionsActivity::class.java))
        }
    }
}
