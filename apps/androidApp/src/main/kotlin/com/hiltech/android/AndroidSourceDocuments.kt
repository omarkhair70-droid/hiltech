package com.hiltech.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

internal data class AndroidSourceDocuments(
    val pickDocument: suspend () -> ServiceSelectedFile?,
    val exportDocument: suspend (String, String, ByteArray, String) -> Boolean,
)

private data class ExportDestination(val fileName: String, val contentType: String)
private class CreateSourceDocument : ActivityResultContract<ExportDestination, Uri?>() {
    override fun createIntent(context: Context, input: ExportDestination) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.contentType).putExtra(Intent.EXTRA_TITLE, input.fileName)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Call inside key(fullSessionScope), so a result cannot migrate to another account. */
@Composable
internal fun rememberAndroidSourceDocuments(
    language: HiltechLanguage,
    isSessionCurrent: () -> Boolean,
): AndroidSourceDocuments {
    val context = LocalContext.current
    val currentSession by rememberUpdatedState(isSessionCurrent)
    val currentLanguage by rememberUpdatedState(language)
    fun text(ar: String, en: String) = currentLanguage.text(ar, en)
    var pendingOpen by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    var pendingSave by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingOpen?.let { if (currentSession()) it.complete(uri) else it.cancel() }
        pendingOpen = null
    }
    val save = rememberLauncherForActivityResult(remember { CreateSourceDocument() }) { uri ->
        pendingSave?.let { if (currentSession()) it.complete(uri) else it.cancel() }
        pendingSave = null
    }
    DisposableEffect(Unit) {
        onDispose {
            pendingOpen?.cancel(); pendingOpen = null
            pendingSave?.cancel(); pendingSave = null
        }
    }
    return AndroidSourceDocuments(
        pickDocument = {
            check(currentSession())
            check(pendingOpen == null && pendingSave == null) { text("أكمل اختيار الملف المفتوح أولًا.", "Finish the open file dialog first.") }
            val request = CompletableDeferred<Uri?>()
            pendingOpen = request
            try { open.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) }
            catch (failure: Exception) { pendingOpen = null; request.cancel(); throw failure }
            val uri = request.await()
            if (uri == null) null else withContext(Dispatchers.IO) {
                check(currentSession())
                val resolver = context.contentResolver
                val type = resolver.getType(uri)?.lowercase()
                require(type in setOf("application/pdf", "image/png", "image/jpeg")) {
                    text("اختر PDF أو صورة PNG أو JPEG.", "Choose a PDF, PNG or JPEG file.")
                }
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?.substringAfterLast('/')?.substringAfterLast('\\')?.take(200)?.takeIf { it.isNotBlank() } ?: "HILTECH-document"
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16_777_216) {
                            text("حجم المستند أكبر من 16 ميجابايت.", "The document is larger than 16 MiB.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error(text("تعذّرت قراءة الملف المختار.", "The selected file could not be read."))
                require(bytes.isNotEmpty()) { text("الملف فارغ. اختر مستندًا آخر.", "The file is empty. Choose another document.") }
                check(currentSession())
                ServiceSelectedFile(requireNotNull(type), name, bytes, documentSha256(bytes), Instant.now().toString())
            }
        },
        exportDocument = { fileName, contentType, bytes, expectedSha256 ->
            check(currentSession())
            require(contentType in setOf("application/zip", "application/pdf", "image/png", "image/jpeg"))
            require(bytes.isNotEmpty() && withContext(Dispatchers.Default) { documentSha256(bytes).equals(expectedSha256, true) }) {
                text("تعذّر التحقق من المستند. أعد تحميله.", "The document could not be verified. Download it again.")
            }
            check(pendingOpen == null && pendingSave == null) { text("أكمل اختيار الملف المفتوح أولًا.", "Finish the open file dialog first.") }
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").trim().take(200).ifBlank { "HILTECH-document" }
            val request = CompletableDeferred<Uri?>()
            pendingSave = request
            try { save.launch(ExportDestination(safeName, contentType)) }
            catch (failure: Exception) { pendingSave = null; request.cancel(); throw failure }
            val uri = request.await()
            if (uri == null) false else withContext(Dispatchers.IO) {
                check(currentSession())
                val resolver = context.contentResolver
                val output = resolver.openOutputStream(uri, "wt")
                    ?: error(text("تعذّر الحفظ في المكان المختار.", "The document could not be saved to this destination."))
                output.use { it.write(bytes); it.flush() }
                // A picker or share-sheet opening is not a successful export.
                // Verify the saved bytes before reporting completion.
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        length += count
                        require(length <= bytes.size.toLong())
                        digest.update(buffer, 0, count)
                    }
                } ?: error(text("تمت الكتابة، لكن تعذّر التحقق من الملف المحفوظ.", "The file was written, but the saved copy could not be verified."))
                require(length == bytes.size.toLong() && digest.digest().toHex().equals(expectedSha256, true)) {
                    text("تعذّر تأكيد الملف المحفوظ. اختر مكانًا آخر.", "The saved file could not be confirmed. Choose another destination.")
                }
                check(currentSession())
                true
            }
        },
    )
}

private fun documentSha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
