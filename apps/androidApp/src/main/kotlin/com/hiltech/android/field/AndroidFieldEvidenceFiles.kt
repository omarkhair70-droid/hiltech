package com.hiltech.android.field

import android.content.Context
import android.net.Uri
import com.hiltech.shared.core.field.FieldEvidenceCapturePolicy
import com.hiltech.shared.core.local.LocalEvidenceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class PrivateFieldEvidenceFile(
    val evidenceId: String,
    val fileRef: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
)

class FieldEvidenceLocalFileException(val code: String) : RuntimeException(code)

/** Binary never resides in a public provider or Room. Backup is disabled. */
class AndroidFieldEvidenceFiles(private val context: Context) {
    private val folder: File
        get() = File(context.noBackupFilesDir, "field-evidence").also {
            if (!it.exists() && !it.mkdirs()) throw FieldEvidenceLocalFileException("EVIDENCE_DIR_UNAVAILABLE")
        }

    suspend fun importFromUri(evidenceId: String, uri: Uri): PrivateFieldEvidenceFile =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri)
                ?.lowercase()?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw FieldEvidenceLocalFileException("EVIDENCE_MIME_UNAVAILABLE")
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw FieldEvidenceLocalFileException("EVIDENCE_SOURCE_UNREADABLE")
            stream.use { save(evidenceId, mime, it) }
        }

    suspend fun saveFromFile(evidenceId: String, contentType: String, file: File): PrivateFieldEvidenceFile =
        withContext(Dispatchers.IO) {
            require(file.isFile)
            file.inputStream().use { save(evidenceId, contentType, it) }
        }

    private fun save(evidenceId: String, mime: String, input: InputStream): PrivateFieldEvidenceFile {
        require(UUID.fromString(evidenceId).toString() == evidenceId)
        val ref = "$evidenceId.bin"
        val destination = File(folder, ref)
        val partial = File(folder, "$evidenceId.pending")
        if (destination.exists() || partial.exists()) {
            throw FieldEvidenceLocalFileException("EVIDENCE_FILE_ALREADY_EXISTS")
        }
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (n == 0) continue
                    total += n
                    if (total > FieldEvidenceCapturePolicy.MAX_BYTES) {
                        throw FieldEvidenceLocalFileException("EVIDENCE_TOO_LARGE")
                    }
                    sha.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
            if (total == 0L) throw FieldEvidenceLocalFileException("EVIDENCE_EMPTY")
            if (!partial.renameTo(destination)) {
                throw FieldEvidenceLocalFileException("EVIDENCE_ATOMIC_RENAME_FAILED")
            }
            return PrivateFieldEvidenceFile(
                evidenceId = evidenceId,
                fileRef = ref,
                contentType = mime,
                sizeBytes = total,
                sha256 = sha.digest().joinToString("") { "%02x".format(it) },
            )
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    suspend fun readAndVerify(row: LocalEvidenceEntity): ByteArray =
        withContext(Dispatchers.IO) {
            if (row.localFileRef != row.evidenceId + ".bin" ||
                row.sizeBytes !in 1..FieldEvidenceCapturePolicy.MAX_BYTES ||
                !row.sha256.matches(Regex("[a-f0-9]{64}"))) {
                throw FieldEvidenceLocalFileException("EVIDENCE_LOCAL_METADATA_INVALID")
            }
            val file = File(folder, row.localFileRef)
            if (file.canonicalFile != File(folder.canonicalFile, row.localFileRef) ||
                !file.isFile || file.length() != row.sizeBytes) {
                throw FieldEvidenceLocalFileException("EVIDENCE_LOCAL_BYTES_MISSING_OR_CHANGED")
            }
            val bytes = file.readBytes()
            val calculated = MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }
            if (calculated != row.sha256) {
                throw FieldEvidenceLocalFileException("EVIDENCE_SHA256_MISMATCH")
            }
            bytes
        }

    suspend fun removeAfterFailedCapture(evidenceId: String) = withContext(Dispatchers.IO) {
        // Only after a failed insertion that did not produce a durable Room row.
        require(UUID.fromString(evidenceId).toString() == evidenceId)
        File(folder, "$evidenceId.bin").delete()
        File(folder, "$evidenceId.pending").delete()
    }
}
