package ai.arena.arkernel.diagnostics

import ai.arena.arkernel.ArKernelArtifactFile
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One file that was found and read. */
data class ArKernelScannedFile(
    val file: ArKernelArtifactFile,
    val text: String,
    val truncated: Boolean,
)

/**
 * Everything one scan produced: the files it could read, what went wrong, and
 * what it had to leave out.
 */
data class ArKernelArtifactScan(
    val files: List<ArKernelScannedFile>,
    val notes: List<String>,
    val overflow: List<String>,
)

/**
 * Finds and reads the artefact files this app has written.
 *
 * This used to live inside the artefact hub. It moved out because the wizard
 * needs the same answer - which records actually exist on this device - and a
 * second copy would eventually disagree with the first. Two screens that
 * disagree about what has been measured are worse than one screen that is
 * wrong, because the operator has no way to tell which to believe.
 *
 * The scan is deliberately bounded in three ways, and each bound is reported
 * rather than swallowed: at most `MAX_FILES` files, at most
 * `MAX_TEXT_PER_FILE` characters of text retained per file, and at most
 * `MAX_TOTAL_TEXT` characters held in total. The digest, by contrast, always
 * covers the **whole** file - a digest over a prefix could not be checked by
 * anyone who hashes the file themselves, which is the only thing a digest list
 * is for.
 */
class ArKernelArtifactScanner(private val context: Context) {

    private companion object {
        const val MAX_FILES = 200

        /** Enough for any record this app writes, and no more. */
        const val MAX_TEXT_PER_FILE = 65_536

        /** The whole scan's text budget, so a folder of large files cannot. */
        const val MAX_TOTAL_TEXT = 2_097_152
    }

    /** What was read from one file. */
    private data class Payload(
        val text: String,
        val sizeBytes: Long,
        val sha256Hex: String,
        val textTruncated: Boolean,
    )

    /** How much text the scan may still hold. */
    private class Budget(var remaining: Int) {
        fun take(size: Int): Boolean {
            if (size > remaining) return false
            remaining -= size
            return true
        }
    }

    fun scan(): ArKernelArtifactScan {
        val found = ArrayList<ArKernelScannedFile>()
        val overflow = ArrayList<String>()
        val notes = ArrayList<String>()
        val budget = Budget(MAX_TOTAL_TEXT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scanMediaStore(found, overflow, notes, budget)
        }
        scanPrivateDirectory(found, overflow, notes, budget)
        found.sortWith(
            compareByDescending<ArKernelScannedFile> { it.file.modifiedMs }
                .thenBy { it.file.name },
        )
        return ArKernelArtifactScan(
            files = found,
            notes = notes,
            overflow = overflow,
        )
    }

    /** Files the app published through MediaStore on Android 10 and later. */
    private fun scanMediaStore(
        found: ArrayList<ArKernelScannedFile>,
        overflow: ArrayList<String>,
        notes: ArrayList<String>,
        budget: Budget,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%ARKernelDiagnostics%")
        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (found.size >= MAX_FILES) {
                        overflow.add(name)
                        continue
                    }
                    val size = cursor.getLong(sizeColumn)
                    val seconds = cursor.getLong(dateColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn),
                    )
                    val payload = readPayload(uri.open(), notes) ?: continue
                    add(found, notes, budget, name, seconds * 1000L, payload, size)
                }
            }
        } catch (error: Exception) {
            notes.add("MediaStore: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Older Android versions, and the app-private fallback the other screens
     * use when MediaStore is unavailable.
     */
    private fun scanPrivateDirectory(
        found: ArrayList<ArKernelScannedFile>,
        overflow: ArrayList<String>,
        notes: ArrayList<String>,
        budget: Budget,
    ) {
        val dirs = ArrayList<File>()
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { dirs.add(it) }
        context.getExternalFilesDir(null)?.let { dirs.add(it) }
        for (dir in dirs.distinctBy { it.absolutePath }) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (!file.name.endsWith(".txt")) continue
                if (found.any { it.file.name == file.name }) continue
                if (found.size >= MAX_FILES) {
                    overflow.add(file.name)
                    continue
                }
                val payload = readPayload(
                    try {
                        file.inputStream()
                    } catch (error: Exception) {
                        notes.add("${file.name}: unlesbar")
                        continue
                    },
                    notes,
                ) ?: continue
                add(
                    found,
                    notes,
                    budget,
                    file.name,
                    file.lastModified(),
                    payload,
                    file.length(),
                )
            }
        }
    }

    private fun Uri.open(): java.io.InputStream? =
        try {
            context.contentResolver.openInputStream(this)
        } catch (error: Exception) {
            null
        }

    /**
     * Hashes the whole stream and keeps only the beginning as text.
     *
     * `declaredSize` is what the directory listing claimed; the digest is taken
     * over what was actually read, and a mismatch is reported rather than
     * silently bound.
     */
    private fun readPayload(
        stream: java.io.InputStream?,
        notes: ArrayList<String>,
    ): Payload? {
        if (stream == null) {
            notes.add("Lesefehler: keinen Stream bekommen")
            return null
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            stream.use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    digest.update(chunk, 0, read)
                    total += read
                    val room = MAX_TEXT_PER_FILE - head.size()
                    if (room > 0) head.write(chunk, 0, minOf(read, room))
                }
            }
            Payload(
                text = String(head.toByteArray(), Charsets.UTF_8),
                sizeBytes = total.toLong(),
                sha256Hex = hex(digest.digest()),
                textTruncated = total > MAX_TEXT_PER_FILE,
            )
        } catch (error: Exception) {
            notes.add("Lesefehler: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun add(
        found: ArrayList<ArKernelScannedFile>,
        notes: ArrayList<String>,
        budget: Budget,
        name: String,
        modifiedMs: Long,
        payload: Payload,
        declaredSize: Long,
    ) {
        if (declaredSize > 0L && declaredSize != payload.sizeBytes) {
            notes.add("$name: Größe weicht ab ($declaredSize/${payload.sizeBytes})")
        }
        if (!budget.take(payload.text.length)) {
            notes.add("$name: Textbudget erschöpft")
            return
        }
        try {
            found.add(
                ArKernelScannedFile(
                    file = ArKernelArtifactFile(
                        name = name,
                        sizeBytes = payload.sizeBytes,
                        modifiedMs = modifiedMs,
                        sha256Hex = payload.sha256Hex,
                    ),
                    text = payload.text,
                    truncated = payload.textTruncated,
                ),
            )
        } catch (error: IllegalArgumentException) {
            notes.add("$name: verworfen (${error.message})")
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
        }
}
