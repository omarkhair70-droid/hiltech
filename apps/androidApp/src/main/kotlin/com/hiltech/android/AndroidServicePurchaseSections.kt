package com.hiltech.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hiltech.shared.core.procurement.ServicePurchaseNativeJourney
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Default OFF at the host. All mutations are online and server-authorized. */
@Composable
fun AndroidServicePurchaseSections(application: HiltechApplication, organizationId: String) {
    val install = application.installationId
    val context = LocalContext.current
    var pendingPicker by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pendingPicker?.complete(uri)
        pendingPicker = null
    }
    suspend fun pickSourceDocument(): ServiceSelectedFile? {
        check(pendingPicker == null) { "Another private document picker is already open." }
        val pending = CompletableDeferred<Uri?>()
        pendingPicker = pending
        picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp"))
        val uri = pending.await() ?: return null
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.lowercase()
                ?: throw IllegalArgumentException("An explicit PDF or image media type is required.")
            require(mime in setOf("application/pdf", "image/jpeg", "image/png", "image/webp")) {
                "Unsupported document media type."
            }
            val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?.take(200)?.takeIf { it.isNotBlank() } ?: "service-document"
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                var length = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    length += count
                    require(length <= 16 * 1024 * 1024) {
                        "Document exceeds the 16 MiB protected Evidence limit."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalArgumentException("Cannot read the selected document.")
            require(bytes.isNotEmpty()) { "Document cannot be empty." }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            ServiceSelectedFile(mime, filename, bytes, digest, Instant.now().toString())
        }
    }

    val op = { UUID.randomUUID().toString() }
    ServicePurchaseNativeJourney(
        needApi = application.servicePurchaseNeedApi,
        sourcing = application.serviceSupplierSourcingApi,
        quoteUploader = application.serviceSupplierQuoteUploader,
        api = application.serviceOrderAndInvoiceApi,
        organizationId = organizationId,
        installationId = install,
        newOperationId = op,
        pickQuoteFile = { pickSourceDocument() },
        uploadFile = { kind, slot ->
            require(slot.documentKind == kind) { "Document source/type mismatch." }
            val file = pickSourceDocument()
                ?: throw IllegalStateException("No source document selected.")
            application.serviceOrderAndInvoiceApi.uploadDocument(
                slot = slot, install = install,
                contentType = file.contentType, originalFileName = file.originalFileName,
                bytes = file.bytes, expectedSha256 = file.sha256,
                capturedAt = file.capturedAt,
                reserveOperationId = op(), finalizeOperationId = op(),
            )
        },
    )
}
