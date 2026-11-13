package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelSignatureEntryInput
import ai.arena.arkernel.ArKernelSignatureKeyOrigin
import ai.arena.arkernel.ArKernelSignatureManifestAdapter
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.security.PublicKey

/**
 * Signing and verification for the artefacts you upload.
 *
 * The app creates an ECDSA P-256 key, binds the SHA-256 digests of one or
 * more artefacts into an `ARKSIGN1` manifest and signs that manifest. The
 * public key is exported as its own text file so anyone can check the
 * signature later — including this screen, against the key that is pinned
 * here.
 *
 * What this proves is integrity and key continuity: entries cannot be changed
 * and the digest list cannot be swapped without breaking the signature, and
 * the same fingerprint keeps appearing as long as the same key signs. It does
 * **not** prove who owns the key, there is no certificate authority and no
 * device attestation. The grade stays `unverified`.
 */
class SignatureActivity : DiagnosticsActivity() {

    private companion object {
        const val STATE_NAME = "sign.name"
        const val STATE_ARTEFACT = "sign.artefact"
        const val STATE_MANIFEST = "sign.manifest"
        const val STATE_FILE = "sign.file"
        const val STATE_KEY = "sign.key"
        const val STATE_ORIGIN = "sign.origin"
        const val STATE_VERIFY = "sign.verify"
        const val STATE_EXTERNAL = "sign.external"
        const val STATE_STATUS = "sign.status"
        const val STATE_RESULT = "sign.result"
        const val MAX_ARTEFACT_CHARACTERS = 16_384
        const val MAX_ENTRIES = 32
        val entries = ArrayList<ArKernelSignatureEntryInput>()
    }

    private lateinit var keyText: TextView
    private lateinit var nameInput: EditText
    private lateinit var artefactInput: EditText
    private lateinit var addButton: Button
    private lateinit var entriesText: TextView
    private lateinit var signButton: Button
    private lateinit var verifyInput: EditText
    private lateinit var externalKeyInput: EditText
    private lateinit var verifyButton: Button
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var writeButton: Button
    private lateinit var shareButton: Button
    private lateinit var saveKeyButton: Button

    private var currentManifest: String = ""
    private var currentFileName: String = ""
    private var currentKey: ArKernelSignatureKeyManager.KeyInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) entries.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "Signatur & Prüfung (ARKSIGN1)"
            textSize = 19f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            contentDescription = "Bildschirm zum Signieren und Prüfen"
        }
        val version = TextView(this).apply {
            text = "Version 2.13-sign"
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#B45309"))
            contentDescription = "Versionskennung der installierten APK"
        }
        val intro = TextView(this).apply {
            text = "1) „Schlüssel erzeugen“. 2) „Öffentlichen Schlüssel " +
                "speichern“ und einmal hier im Chat hochladen: damit kann " +
                "jeder Prüfer die Signatur nachrechnen. 3) Pro Artefakt " +
                "einen Namen eintragen, den ARK…-Text einfügen und " +
                "„Eintrag hinzufügen“. 4) „Manifest signieren“. " +
                "5) Zum Prüfen ein ARKSIGN1-Manifest einfügen und " +
                "„Manifest prüfen“ drücken."
            textSize = 13f
            contentDescription = "Bedienreihenfolge der Signatur"
        }
        val safety = TextView(this).apply {
            text = "Die Signatur beweist Unversehrtheit und dass " +
                "derselbe Schlüssel signiert hat – nicht, wer den " +
                "Schlüssel besitzt. Es gibt keine Zertifizierungsstelle, " +
                "keine Geräte-Attestierung und keine Vertrauenskette; " +
                "ein gerootetes Gerät oder eine kopierte Software- " +
                "Schlüsseldatei ergibt eine formal gültige Signatur. " +
                "Der Schlüsselursprung steht in jeder Datei " +
                "(`key_origin`). Kein automatischer Upload, keine " +
                "Inferenz, keine Qualitätsaussage. Status: unverified."
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(12, 12, 12, 12)
            contentDescription = "Grenzen der Signatur"
        }
        statusText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_STATUS)
                ?: "Noch kein Schlüssel erzeugt."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        keyText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#FFF7ED"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Schlüsselursprung und Fingerprint"
        }

        val nameLabel = TextView(this).apply {
            text = "Artefakt-Name (z. B. ARKFEED1_1700000000000.txt):"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        nameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            isSingleLine = true
            setText(savedInstanceState?.getString(STATE_NAME) ?: "")
            textSize = 13f
            contentDescription = "Name des Artefakts"
        }
        val artefactLabel = TextView(this).apply {
            text = "Artefakt-Text (ARKFDPOL1, ARKVALID1, …) einfügen:"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        artefactInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(
                InputFilter.LengthFilter(MAX_ARTEFACT_CHARACTERS))
            minLines = 5
            maxLines = 8
            isSingleLine = false
            setText(savedInstanceState?.getString(STATE_ARTEFACT) ?: "")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
            contentDescription = "Eingefügter Artefakt-Text"
        }
        entriesText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#FFF7ED"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
            contentDescription = "Gebundene Artefakte"
        }

        val verifyLabel = TextView(this).apply {
            text = "Zum Prüfen: ARKSIGN1-Manifest einfügen"
            textSize = 13f
            setPadding(0, 12, 0, 4)
        }
        verifyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(
                InputFilter.LengthFilter(MAX_ARTEFACT_CHARACTERS))
            minLines = 5
            maxLines = 8
            isSingleLine = false
            setText(savedInstanceState?.getString(STATE_VERIFY) ?: "")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
            contentDescription = "Eingefügtes Signaturmanifest"
        }
        val externalLabel = TextView(this).apply {
            text = "Optional: fremder öffentlicher Schlüssel (Base64)"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        externalKeyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(2_048))
            minLines = 2
            maxLines = 4
            isSingleLine = false
            setText(savedInstanceState?.getString(STATE_EXTERNAL) ?: "")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(8, 8, 8, 8)
            contentDescription = "Fremder öffentlicher Schlüssel"
        }
        resultText = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_RESULT)
                ?: "Noch nichts geprüft."
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            contentDescription = "Ergebnis der Prüfung"
        }

        val keyButton = Button(this).apply {
            text = "Schlüssel erzeugen"
            textSize = 15f
            setAllCaps(false)
        }
        saveKeyButton = Button(this).apply {
            text = "Öffentlichen Schlüssel speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = false
        }
        addButton = Button(this).apply {
            text = "Eintrag hinzufügen"
            textSize = 15f
            setAllCaps(false)
        }
        signButton = Button(this).apply {
            text = "Manifest signieren"
            textSize = 15f
            setAllCaps(false)
        }
        verifyButton = Button(this).apply {
            text = "Manifest prüfen"
            textSize = 15f
            setAllCaps(false)
        }
        writeButton = Button(this).apply {
            text = "ARKSIGN1 speichern"
            textSize = 15f
            setAllCaps(false)
            isEnabled = savedInstanceState
                ?.getString(STATE_MANIFEST)?.isNotEmpty() ?: false
        }
        shareButton = Button(this).apply {
            text = "ARKSIGN1 teilen"
            textSize = 15f
            setAllCaps(false)
            isEnabled = writeButton.isEnabled
        }

        currentManifest = savedInstanceState?.getString(STATE_MANIFEST) ?: ""
        currentFileName = savedInstanceState?.getString(STATE_FILE) ?: ""
        val savedKey = savedInstanceState?.getString(STATE_KEY) ?: ""
        val savedOrigin = savedInstanceState?.getString(STATE_ORIGIN) ?: ""

        root.addView(title)
        root.addView(version)
        root.addView(intro)
        root.addView(safety)
        root.addView(statusText)
        root.addView(keyButton, fullWidthParams())
        root.addView(keyText, fullWidthParams())
        root.addView(saveKeyButton, fullWidthParams())
        root.addView(nameLabel)
        root.addView(nameInput)
        root.addView(artefactLabel)
        root.addView(artefactInput)
        root.addView(addButton, fullWidthParams())
        root.addView(entriesText, fullWidthParams())
        root.addView(signButton, fullWidthParams())
        root.addView(writeButton, fullWidthParams())
        root.addView(shareButton, fullWidthParams())
        root.addView(verifyLabel)
        root.addView(verifyInput)
        root.addView(externalLabel)
        root.addView(externalKeyInput)
        root.addView(verifyButton, fullWidthParams())
        root.addView(resultText)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        keyButton.setOnClickListener { createKey() }
        saveKeyButton.setOnClickListener { savePublicKey() }
        addButton.setOnClickListener { addEntry() }
        signButton.setOnClickListener { signManifest() }
        verifyButton.setOnClickListener { verifyManifest() }
        writeButton.setOnClickListener { writeManifest() }
        shareButton.setOnClickListener { shareManifest() }
        if (savedKey.isNotEmpty()) {
            restoreKey(savedKey, savedOrigin)
        }
        renderEntries()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAME, nameInput.text.toString())
        outState.putString(STATE_ARTEFACT, artefactInput.text.toString())
        outState.putString(STATE_MANIFEST, currentManifest)
        outState.putString(STATE_FILE, currentFileName)
        outState.putString(STATE_KEY, currentKey?.publicKeyBase64 ?: "")
        outState.putString(
            STATE_ORIGIN,
            currentKey?.origin?.wireName() ?: "",
        )
        outState.putString(STATE_VERIFY, verifyInput.text.toString())
        outState.putString(STATE_EXTERNAL, externalKeyInput.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
        outState.putString(STATE_RESULT, resultText.text.toString())
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 8, 0, 0) }

    private fun createKey() {
        val info = ArKernelSignatureKeyManager.loadOrCreate(this)
        if (info == null) {
            status("Schlüssel konnte nicht erzeugt werden. Bitte " +
                "Gerätespeicher und Bildschirmsperre prüfen.")
            return
        }
        applyKey(info)
        status("Schlüssel bereit.")
    }

    private fun restoreKey(base64: String, origin: String) {
        val publicKey = ArKernelSignatureKeyManager.publicKeyFromBase64(base64)
        if (publicKey == null) return
        applyKey(
            ArKernelSignatureKeyManager.KeyInfo(
                origin = ArKernelSignatureKeyOrigin.fromWire(
                    origin.lowercase())
                    ?: ArKernelSignatureKeyOrigin.SOFTWARE,
                publicKeyBase64 = base64,
                fingerprint = ArKernelSignatureManifestAdapter
                    .fingerprintOf(publicKey),
            ),
        )
    }

    private fun applyKey(info: ArKernelSignatureKeyManager.KeyInfo) {
        currentKey = info
        val origin = when (info.origin) {
            ArKernelSignatureKeyOrigin.ANDROID_KEYSTORE ->
                "Android-Keystore (nicht exportierbar)"
            ArKernelSignatureKeyOrigin.SOFTWARE ->
                "Software-Schlüssel (App-privat, nicht hardware-geschützt)"
        }
        keyText.text = "Ursprung: $origin\nFingerprint:\n" +
            info.fingerprint
        saveKeyButton.isEnabled = true
    }

    private fun savePublicKey() {
        val info = currentKey
        if (info == null) {
            status("Bitte zuerst „Schlüssel erzeugen“.")
            return
        }
        val fileName =
            "ARKSIGN1_pubkey_${info.fingerprint.take(8)}.txt"
        val body = "# ARKSIGN1 public key (base64, SPKI, EC P-256)\n" +
            "fingerprint=${info.fingerprint}\n" +
            "key_origin=${info.origin.name.lowercase()}\n" +
            "public_key=${info.publicKeyBase64}\n"
        status(writeTextToDownloads(fileName, body))
        Toast.makeText(this, "Öffentlicher Schlüssel gespeichert",
            Toast.LENGTH_SHORT).show()
    }

    private fun addEntry() {
        val name = nameInput.text.toString().trim()
        val text = artefactInput.text.toString()
        if (!name.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) {
            status("Ungültiger Name: nur A-Z, a-z, 0-9, Punkt, " +
                "Unterstrich, Doppelpunkt, Bindestrich, maximal 64 Zeichen.")
            return
        }
        if (text.isBlank()) {
            status("Bitte zuerst einen Artefakt-Text einfügen.")
            return
        }
        if (entries.size >= MAX_ENTRIES) {
            status("Maximal $MAX_ENTRIES Einträge pro Manifest.")
            return
        }
        if (entries.any { it.artifactName == name }) {
            status("Dieser Artefakt-Name ist schon gebunden.")
            return
        }
        if (entries.any { it.sourceText == text }) {
            status("Genau dieser Text ist schon gebunden.")
            return
        }
        entries += ArKernelSignatureEntryInput(
            artifactName = name,
            sourceText = text,
        )
        currentManifest = ""
        writeButton.isEnabled = false
        shareButton.isEnabled = false
        artefactInput.setText("")
        nameInput.setText("")
        renderEntries()
        status("Eintrag $name gebunden.")
    }

    private fun signManifest() {
        if (entries.isEmpty()) {
            status("Bitte zuerst mindestens einen Eintrag hinzufügen.")
            return
        }
        val key = ArKernelSignatureKeyManager.loadOrCreate(this)
        if (key == null) {
            status("Kein Schlüssel verfügbar.")
            return
        }
        applyKey(key)
        val publicKey: PublicKey =
            ArKernelSignatureKeyManager.publicKeyFromBase64(
                key.publicKeyBase64) ?: run {
            status("Öffentlicher Schlüssel nicht lesbar.")
            return
        }
        val result = ArKernelSignatureManifestAdapter.create(
            manifestId = "sign.${System.currentTimeMillis()}",
            deviceToken = "phone.diagnostics",
            keyOrigin = key.origin,
            publicKey = publicKey,
            entries = entries.toList(),
        ) { data ->
            ArKernelSignatureKeyManager.sign(this, data)
                ?: throw IllegalStateException("Signatur fehlgeschlagen")
        }
        if (!result.accepted) {
            currentManifest = ""
            writeButton.isEnabled = false
            shareButton.isEnabled = false
            status("Abgelehnt: ${result.rejection} – ${result.detail}")
            return
        }
        val signed = result.signed ?: return
        val check = ArKernelSignatureManifestAdapter.verify(signed, publicKey)
        if (!check.verified) {
            status("Selbstprüfung fehlgeschlagen: ${check.detail}")
            return
        }
        currentManifest = ArKernelSignatureManifestAdapter.encode(signed)
        currentFileName = "ARKSIGN1_${System.currentTimeMillis()}.txt"
        writeButton.isEnabled = true
        shareButton.isEnabled = true
        status("Manifest signiert und selbst geprüft: " +
            "${entries.size} Einträge, Fingerprint " +
            "${key.fingerprint.take(12)}… Bitte speichern.")
    }

    private fun verifyManifest() {
        val text = verifyInput.text.toString()
        if (text.isBlank()) {
            status("Bitte ein ARKSIGN1-Manifest einfügen.")
            return
        }
        val parsed = ArKernelSignatureManifestAdapter.parse(text)
        if (!parsed.accepted) {
            resultText.text = "Nicht angenommen: ${parsed.rejection} " +
                "(Zeile ${parsed.lineNumber}: ${parsed.detail})"
            return
        }
        val external = externalKeyInput.text.toString().trim()
        val publicKey = if (external.isEmpty()) {
            val key = currentKey ?: ArKernelSignatureKeyManager
                .loadOrCreate(this)
            if (key == null) {
                resultText.text = "Kein Schlüssel zum Prüfen vorhanden. " +
                    "Bitte Schlüssel erzeugen oder einen fremden " +
                    "Schlüssel einfügen."
                return
            }
            applyKey(key)
            ArKernelSignatureKeyManager
                .publicKeyFromBase64(key.publicKeyBase64)
        } else {
            ArKernelSignatureKeyManager.publicKeyFromBase64(external)
        }
        if (publicKey == null) {
            resultText.text = "Öffentlicher Schlüssel nicht lesbar."
            return
        }
        val signed = parsed.signed ?: return
        val result = ArKernelSignatureManifestAdapter
            .verify(signed, publicKey)
        val lines = ArrayList<String>()
        lines += "Manifest-Digest: ${signed.manifestDigest.take(16)}…"
        lines += "Schlüssel-Fingerprint: " +
            result.publicKeyFingerprint.take(16) + "…"
        lines += "Fingerprint passt zum Manifest: " +
            if (result.keyFingerprintMatches) "ja" else "nein"
        lines += "Signatur gültig: " +
            if (result.signatureValid) "ja" else "nein"
        lines += "Einträge: ${signed.manifest.entries.size}"
        for (entry in signed.manifest.entries) {
            lines += "  ${entry.artifactName} " +
                entry.digest.take(16) + "…"
        }
        lines += if (result.verified) {
            "Ergebnis: verifiziert."
        } else {
            "Ergebnis: NICHT verifiziert – ${result.detail}"
        }
        lines += "Verifiziert heißt nur: unverändert und vom selben " +
            "Schlüssel. Keine Aussage über die Messqualität. " +
            "Status: unverified."
        resultText.text = lines.joinToString("\n")
        status(if (result.verified) "Manifest verifiziert."
            else "Manifest nicht verifiziert.")
    }

    private fun renderEntries() {
        if (entries.isEmpty()) {
            entriesText.text = "Noch kein Eintrag gebunden."
            return
        }
        val lines = ArrayList<String>(entries.size + 1)
        lines += "Nr  Artefakt                     SHA-256 (gekürzt)"
        for ((index, entry) in entries.withIndex()) {
            val digest = ArKernelSignatureManifestAdapter
                .digestOf(entry.sourceText)
            lines += String.format(
                java.util.Locale.US,
                "%-3d %-28s %s…",
                index,
                entry.artifactName,
                digest.take(16),
            )
        }
        entriesText.text = lines.joinToString("\n")
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun writeManifest() {
        if (currentManifest.isEmpty()) {
            status("Bitte zuerst ein Manifest signieren.")
            return
        }
        val fileName = if (currentFileName.isEmpty()) {
            "ARKSIGN1_${System.currentTimeMillis()}.txt"
        } else {
            currentFileName
        }
        status(writeTextToDownloads(fileName, currentManifest))
        Toast.makeText(this, "ARKSIGN1 gespeichert", Toast.LENGTH_SHORT)
            .show()
    }

    private fun shareManifest() {
        if (currentManifest.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARKSIGN1")
                putExtra(Intent.EXTRA_TEXT, currentManifest)
            }
            startActivity(Intent.createChooser(send, "ARKSIGN1 teilen"))
        } catch (error: Exception) {
            status("Teilen fehlgeschlagen: ${error.message}")
        }
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
