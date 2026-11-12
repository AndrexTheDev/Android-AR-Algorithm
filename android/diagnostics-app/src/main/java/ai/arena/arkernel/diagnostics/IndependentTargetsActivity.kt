package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelIndependentTargetRegistry
import ai.arena.arkernel.ArKernelIndependentTargetRow
import ai.arena.arkernel.ArKernelIndependentTargetsAdapter
import ai.arena.arkernel.ArKernelTargetDimensionSource
import ai.arena.arkernel.ArKernelTargetKind
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
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
import java.util.Locale

/**
 * Registers the physical reference targets and the part each one plays.
 *
 * The operator measures a real object with a real tool and types the number
 * in. The app compares that number with the dimension the target is supposed
 * to have and works out whether the object is trustworthy at all — a sheet
 * that came out of the printer at the wrong scale is caught here instead of
 * silently ruining every measurement taken against it.
 *
 * The second half is bookkeeping, and it is the reason this screen exists: a
 * validation that checks a reconstruction against the very target that was
 * used to set its scale proves nothing. So every target declares whether it
 * fed the scale estimate and whether its value was withheld, and the screen
 * only reports "ausreichend" when enough targets did neither.
 *
 * This says nothing about how accurate the reconstruction is. The app never
 * measures a target, never sees a camera frame and never receives a pose
 * here. Every millimetre on this screen was typed in by a human.
 */
class IndependentTargetsActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_REGISTRY = "targets.registry"
        const val STATE_DEVICE = "targets.device"
        const val STATE_CAMPAIGN = "targets.campaign"
        const val STATE_REQUIRED = "targets.required"
        const val STATE_REPORT = "targets.report"
        const val STATE_STATUS = "targets.status"
        const val MAX_TARGETS = 16
        const val MAX_MILLIMETRES = 100_000L
        const val MAX_REPEATS = 64
        const val ID_PATTERN = "[A-Za-z0-9._:-]{1,64}"
        val rows = ArrayList<ArKernelIndependentTargetRow>()
    }

    private lateinit var registryInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var campaignInput: EditText
    private lateinit var requiredInput: EditText
    private lateinit var idInput: EditText
    private lateinit var kindSpinner: Spinner
    private lateinit var nominalInput: EditText
    private lateinit var measuredInput: EditText
    private lateinit var toleranceInput: EditText
    private lateinit var sourceSpinner: Spinner
    private lateinit var scaleCheck: CheckBox
    private lateinit var heldOutCheck: CheckBox
    private lateinit var repeatInput: EditText
    private lateinit var listText: TextView
    private lateinit var reportText: TextView
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    private var lastRegistry: ArKernelIndependentTargetRegistry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) rows.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Unabhängige Targets (ARKTARGET1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm für unabhängige Targets"
        }
        val version = TextView(this).apply {
            text = "Version 2.15-target"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) Oben drei Namen eintragen und festlegen, wie " +
                "viele unabhängige Prüf-Targets du mindestens " +
                "brauchst. 2) Für jedes Target: messen, eintragen, " +
                "Rolle ankreuzen, „Target hinzufügen“. " +
                "3) „Registrierung erstellen“. 4) „Datei " +
                "speichern“ und die Datei selbst hier im Chat " +
                "hochladen."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Target-Registrierung"
        }
        val safety = TextView(this).apply {
            text = "„Soll“ ist das Maß, das auf dem Target steht " +
                "oder in seinem Datenblatt. „Ist“ ist, was du am " +
                "echten Gegenstand gemessen hast. Weicht beides " +
                "stärker voneinander ab als die Toleranz, ist das " +
                "Target selbst unbrauchbar – etwa weil die " +
                "Schachbrett-Vorlage beim Drucken skaliert wurde. " +
                "„Hat den Maßstab gesetzt“ heißt: dieser Wert " +
                "steckt in der Skalierung und darf deshalb niemals " +
                "als Prüfung dienen. Die App misst kein Target, " +
                "sieht kein Kamerabild und bekommt hier keine Pose. " +
                "Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen der Target-Registrierung"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_STATUS)
                ?: "Noch kein Target hinzugefügt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

        registryInput = labelledEdit(
            savedInstanceState?.getString(STATE_REGISTRY) ?: "targets.run1",
            "Registrierungs-Name",
        )
        deviceInput = labelledEdit(
            savedInstanceState?.getString(STATE_DEVICE) ?: "phone.one",
            "Geräte-Name",
        )
        campaignInput = labelledEdit(
            savedInstanceState?.getString(STATE_CAMPAIGN) ?: "campaign.run1",
            "Kampagnen-Name",
        )
        requiredInput = labelledNumber(
            savedInstanceState?.getString(STATE_REQUIRED) ?: "2",
            "Mindestzahl unabhängiger Prüf-Targets",
            maximum = 1,
        )

        idInput = labelledEdit("", "Target-Name")
        kindSpinner = spinnerOf(
            ArKernelTargetKind.entries.map { it.wireName() }.toTypedArray())
        nominalInput = labelledNumber("", "Soll-Maß in Millimetern")
        measuredInput = labelledNumber("", "Gemessenes Maß in Millimetern")
        toleranceInput = labelledNumber("", "Toleranz in Millimetern")
        sourceSpinner = spinnerOf(
            ArKernelTargetDimensionSource.entries
                .map { it.wireName() }
                .toTypedArray())
        scaleCheck = CheckBox(this).apply {
            text = "Hat den Maßstab gesetzt"
            textSize = 13f
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    heldOutCheck.isChecked = false
                    heldOutCheck.isEnabled = false
                } else {
                    heldOutCheck.isEnabled = true
                }
            }
        }
        heldOutCheck = CheckBox(this).apply {
            text = "Zurückgehalten (nicht in der Kalibrierung)"
            textSize = 13f
        }
        repeatInput = labelledNumber(
            "3", "Anzahl der Messwiederholungen")

        listText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#EFF6FF"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Hinzugefügte Targets"
        }
        reportText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_REPORT)
                ?: "Noch keine Registrierung erstellt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            contentDescription = "Ergebnis der Registrierung"
        }

        val addButton = Button(this).apply {
            text = "Target hinzufügen"
            textSize = 15f
            setAllCaps(false)
        }
        val createButton = Button(this).apply {
            text = "Registrierung erstellen"
            textSize = 15f
            setAllCaps(false)
        }
        saveButton = Button(this).apply {
            text = "ARKTARGET1-Datei speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        shareButton = Button(this).apply {
            text = "ARKTARGET1 teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        val resetButton = Button(this).apply {
            text = "Targets zurücksetzen"
            textSize = 15f
            setAllCaps(false)
        }

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        root.addView(statusText)
        addLabelled(root, "Registrierungs-Name:", registryInput)
        addLabelled(root, "Geräte-Name:", deviceInput)
        addLabelled(root, "Kampagnen-Name:", campaignInput)
        addLabelled(
            root,
            "Mindestens nötige Prüf-Targets (1-8):",
            requiredInput,
        )
        addLabelled(root, "Target-Name:", idInput)
        addLabelled(root, "Art des Targets:", kindSpinner)
        addLabelled(root, "Soll-Maß in mm:", nominalInput)
        addLabelled(root, "Gemessenes Maß in mm:", measuredInput)
        addLabelled(root, "Toleranz in mm:", toleranceInput)
        addLabelled(root, "Woher das Soll-Maß stammt:", sourceSpinner)
        root.addView(scaleCheck)
        root.addView(heldOutCheck)
        addLabelled(root, "Messwiederholungen (1-64):", repeatInput)
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

        addButton.setOnClickListener { addTarget() }
        createButton.setOnClickListener { createRegistry() }
        saveButton.setOnClickListener { saveRegistry() }
        shareButton.setOnClickListener { shareRegistry() }
        resetButton.setOnClickListener { resetTargets() }
        renderList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_REGISTRY, registryInput.text.toString())
        outState.putString(STATE_DEVICE, deviceInput.text.toString())
        outState.putString(STATE_CAMPAIGN, campaignInput.text.toString())
        outState.putString(STATE_REQUIRED, requiredInput.text.toString())
        outState.putString(STATE_REPORT, reportText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
    }

    private fun addTarget() {
        val id = idInput.text.toString().trim()
        if (!id.matches(Regex(ID_PATTERN))) {
            status("Ungültiger Target-Name: nur A-Z, a-z, 0-9, Punkt, " +
                "Unterstrich, Doppelpunkt, Bindestrich.")
            return
        }
        if (rows.any { it.targetId == id }) {
            status("Diesen Target-Namen gibt es schon.")
            return
        }
        if (rows.size >= MAX_TARGETS) {
            status("Maximal $MAX_TARGETS Targets pro Registrierung.")
            return
        }
        val nominal = readMillimetres(nominalInput, "Soll-Maß") ?: return
        val measured = readMillimetres(measuredInput, "Ist-Maß") ?: return
        val tolerance = readMillimetres(toleranceInput, "Toleranz")
            ?: return
        if (tolerance < 1L) {
            status("Die Toleranz muss mindestens 1 mm sein.")
            return
        }
        val repeats = repeatInput.text.toString().trim().toIntOrNull()
        if (repeats == null || repeats < 1 || repeats > MAX_REPEATS) {
            status("Messwiederholungen müssen zwischen 1 und " +
                "$MAX_REPEATS liegen.")
            return
        }
        if (scaleCheck.isChecked && heldOutCheck.isChecked) {
            status("Ein Target kann nicht gleichzeitig den Maßstab " +
                "setzen und zurückgehalten sein.")
            return
        }
        rows += ArKernelIndependentTargetRow(
            index = rows.size,
            targetId = id,
            kind = ArKernelTargetKind.fromWire(
                kindSpinner.selectedItem.toString())
                ?: ArKernelTargetKind.GAUGE_BLOCK,
            nominalMillimetres = nominal,
            measuredMillimetres = measured,
            toleranceMillimetres = tolerance,
            source = ArKernelTargetDimensionSource.fromWire(
                sourceSpinner.selectedItem.toString())
                ?: ArKernelTargetDimensionSource.TAPE_MEASURE,
            usedForScale = scaleCheck.isChecked,
            heldOut = heldOutCheck.isChecked,
            repeatCount = repeats,
        )
        lastRegistry = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        idInput.setText("")
        nominalInput.setText("")
        measuredInput.setText("")
        toleranceInput.setText("")
        scaleCheck.isChecked = false
        heldOutCheck.isChecked = false
        renderList()
        status("Target $id hinzugefügt.")
    }

    private fun readMillimetres(input: EditText, label: String): Long? {
        val value = input.text.toString().trim().toLongOrNull()
        if (value == null || value < 1L || value > MAX_MILLIMETRES) {
            status("$label muss eine Zahl zwischen 1 und " +
                "$MAX_MILLIMETRES sein.")
            return null
        }
        return value
    }

    private fun createRegistry() {
        if (rows.isEmpty()) {
            status("Bitte zuerst mindestens ein Target hinzufügen.")
            return
        }
        val required = requiredInput.text.toString().trim().toIntOrNull()
        if (required == null || required < 1 || required > 8) {
            status("Die Mindestzahl muss zwischen 1 und 8 liegen.")
            return
        }
        for (name in listOf(
            registryInput.text.toString().trim(),
            deviceInput.text.toString().trim(),
            campaignInput.text.toString().trim(),
        )) {
            if (!name.matches(Regex(ID_PATTERN))) {
                status("Ungültiger Name: nur A-Z, a-z, 0-9, Punkt, " +
                    "Unterstrich, Doppelpunkt, Bindestrich.")
                return
            }
        }
        val result = ArKernelIndependentTargetsAdapter.create(
            registryId = registryInput.text.toString().trim(),
            deviceToken = deviceInput.text.toString().trim(),
            campaignId = campaignInput.text.toString().trim(),
            requiredIndependentTargets = required,
            targets = rows.toList(),
        )
        if (!result.accepted) {
            lastRegistry = null
            saveButton.isEnabled = false
            shareButton.isEnabled = false
            status("Registrierung abgelehnt: ${result.rejection} – " +
                result.detail)
            return
        }
        val registry = result.registry ?: return
        lastRegistry = registry
        saveButton.isEnabled = true
        shareButton.isEnabled = true
        reportText.text = formatRegistry(registry)
        status(if (registry.independenceSufficient) {
            "Registrierung erstellt und ausreichend."
        } else {
            "Registrierung erstellt, aber nicht ausreichend."
        })
    }

    private fun formatRegistry(
        registry: ArKernelIndependentTargetRegistry,
    ): String {
        val lines = ArrayList<String>()
        lines += "Targets: ${registry.targetCount}   " +
            "davon unabhängig: ${registry.independentCount}   " +
            "zurückgehalten: ${registry.heldOutCount}"
        lines += "Als Prüfung brauchbar: " +
            "${registry.usableCheckCount} " +
            "(nötig: ${registry.requiredIndependentTargets})"
        lines += "Größte Abweichung: " +
            "${registry.maxDeviationMillimetres} mm"
        lines += if (registry.targetsConsistent) {
            "Alle Targets stimmen mit ihrem Soll-Maß überein."
        } else {
            "Achtung: mindestens ein Target weicht stärker ab als " +
                "seine Toleranz. Es ist als Referenz unbrauchbar."
        }
        val usable = registry.usableCheckIds()
        if (usable.isEmpty()) {
            lines += "Kein einziges Target taugt als unabhängige " +
                "Prüfung."
        } else {
            lines += "Nutzbar: ${usable.joinToString(", ")}"
        }
        lines += if (registry.independenceSufficient) {
            "Unabhängigkeit: ausreichend."
        } else {
            "Unabhängigkeit: NICHT ausreichend. Bitte mehr Targets " +
                "messen, die weder den Maßstab gesetzt haben noch " +
                "in der Kalibrierung stecken."
        }
        lines += "Das sagt nichts über die Genauigkeit deiner " +
            "Rekonstruktion – nur, dass eine spätere Prüfung nicht " +
            "zirkulär wäre. Status: unverified."
        return lines.joinToString("\n")
    }

    private fun renderList() {
        if (rows.isEmpty()) {
            listText.text = "Noch kein Target hinzugefügt."
            return
        }
        val lines = ArrayList<String>(rows.size + 1)
        lines += String.format(
            Locale.US,
            "%-3s %-14s %7s %7s %6s %s",
            "Nr", "Target", "Soll", "Ist", "Abw", "Rolle")
        for (row in rows) {
            val role = when {
                row.usedForScale -> "Maßstab"
                row.heldOut -> "zurückgehalten"
                else -> "deklariert"
            }
            lines += String.format(
                Locale.US,
                "%-3d %-14s %7d %7d %6d %s%s",
                row.index,
                row.targetId,
                row.nominalMillimetres,
                row.measuredMillimetres,
                row.deviationMillimetres,
                role,
                if (row.consistent) "" else " (ABWEICHUNG)",
            )
        }
        listText.text = lines.joinToString("\n")
    }

    private fun resetTargets() {
        rows.clear()
        lastRegistry = null
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        reportText.text = "Noch keine Registrierung erstellt."
        renderList()
        status("Targets zurückgesetzt.")
    }

    private fun saveRegistry() {
        val registry = lastRegistry
        if (registry == null) {
            status("Bitte zuerst die Registrierung erstellen.")
            return
        }
        val name = "ARKTARGET1_${System.currentTimeMillis()}.txt"
        val path = writeTextToDownloads(
            name,
            ArKernelIndependentTargetsAdapter.encode(registry),
        )
        status(path)
        Toast.makeText(this, "Registrierung gespeichert",
            Toast.LENGTH_SHORT).show()
    }

    private fun shareRegistry() {
        val registry = lastRegistry ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKTARGET1")
                putExtra(
                    Intent.EXTRA_TEXT,
                    ArKernelIndependentTargetsAdapter.encode(registry),
                )
            }
            startActivity(
                Intent.createChooser(send, "ARKTARGET1 teilen"))
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

    private fun labelledEdit(
        initial: String,
        description: String,
    ): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(64))
        isSingleLine = true
        setText(initial)
        textSize = 13f
        contentDescription = description
    }

    private fun labelledNumber(
        initial: String = "",
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

    private fun spinnerOf(values: Array<String>): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@IndependentTargetsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                values,
            )
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {
                    }
                }
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
