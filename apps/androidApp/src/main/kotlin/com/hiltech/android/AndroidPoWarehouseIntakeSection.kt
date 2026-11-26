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
import com.hiltech.shared.core.procurement.PoDeliveryNoteFile
import com.hiltech.shared.core.procurement.PoReceivingDeliveryNoteUploader
import com.hiltech.shared.core.procurement.PoWarehouseIntakeApiClient
import com.hiltech.shared.core.procurement.PoWarehouseIntakeSection
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Android Storage Access Framework picker; no file copy or offline invoice/receipt writes. */
@Composable
fun AndroidPoWarehouseIntakeSection(
    api: PoWarehouseIntakeApiClient,
    warehouseApi: WarehouseApiClient,
    deliveryNoteUploader: PoReceivingDeliveryNoteUploader,
    organizationId: String,
    installationId: String,
) {
    val context = LocalContext.current
    var pendingPicker by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val chooser = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        pendingPicker?.complete(uri)
        pendingPicker = null
    }
    PoWarehouseIntakeSection(
        api = api,
        warehouseApi = warehouseApi,
        organizationId = organizationId,
        installationId = installationId,
        newOperationId = { UUID.randomUUID().toString() },
        deliveryNoteUploader = deliveryNoteUploader,
        pickDeliveryNote = {
            check(pendingPicker == null) { "A document picker is already open." }
            val pending = CompletableDeferred<Uri?>()
            pendingPicker = pending
            chooser.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp"))
            val uri = pending.await()
            if (uri == null) null else withContext(Dispatchers.IO) {
                val contentType = context.contentResolver.getType(uri)?.lowercase()
                    ?: error("Delivery note must identify an allowed PDF or image MIME type.")
                require(contentType in setOf(
                    "application/pdf", "image/jpeg", "image/png", "image/webp",
                )) { "This document type is not permitted." }
                val name = context.contentResolver.query(uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.take(200) ?: "delivery-note"
                val data = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 16 * 1024 * 1024) {
                            "Delivery note is over the 16 MiB Evidence limit."
                        }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: error("Selected delivery note cannot be opened.")
                require(data.isNotEmpty()) { "Delivery note cannot be empty." }
                val digest = MessageDigest.getInstance("SHA-256").digest(data)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                PoDeliveryNoteFile(contentType, name, data, digest, Instant.now().toString())
            }
        },
    )
}
