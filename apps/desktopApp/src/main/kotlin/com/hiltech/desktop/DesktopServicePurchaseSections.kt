package com.hiltech.desktop

import androidx.compose.runtime.Composable
import com.hiltech.shared.core.procurement.ServicePurchaseNativeJourney
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Desktop-native private file chooser. No in-app simulated Evidence. */
@Composable
fun DesktopServicePurchaseSections(runtime: DesktopIdentityRuntime, organizationId: String) {
    val install = runtime.physicalWarehouseInstallationId
    val op = { UUID.randomUUID().toString() }

    suspend fun selectActualFile(): ServiceSelectedFile? = withContext(Dispatchers.IO) {
        val chosen = AtomicReference<java.io.File?>()
        SwingUtilities.invokeAndWait {
            val picker = JFileChooser()
            picker.dialogTitle = "Private supplier service document (max 16 MiB)"
            picker.fileFilter = FileNameExtensionFilter(
                "PDF or image: pdf, jpg, jpeg, png, webp", "pdf", "jpg", "jpeg", "png", "webp")
            picker.isAcceptAllFileFilterUsed = false
            if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chosen.set(picker.selectedFile)
            }
        }
        val file = chosen.get() ?: return@withContext null
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Source document must be an ordinary file, not a symlink."
        }
        val size = Files.size(path)
        require(size in 1L..(16L * 1024L * 1024L)) {
            "Protected source document must be 1 byte to 16 MiB."
        }
        val mime = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> throw IllegalArgumentException("Unsupported service document extension.")
        }
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() == size) { "Selected source changed while reading." }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        ServiceSelectedFile(mime, file.name.take(200), bytes, digest, Instant.now().toString())
    }

    ServicePurchaseNativeJourney(
        needApi = runtime.servicePurchaseNeedApi,
        sourcing = runtime.serviceSupplierSourcingApi,
        quoteUploader = runtime.serviceSupplierQuoteUploader,
        api = runtime.serviceOrderAndInvoiceApi,
        organizationId = organizationId,
        installationId = install,
        newOperationId = op,
        pickQuoteFile = { selectActualFile() },
        uploadFile = { kind, slot ->
            require(kind == slot.documentKind) { "Wrong order document kind." }
            val file = selectActualFile()
                ?: throw IllegalStateException("Document selection cancelled.")
            runtime.serviceOrderAndInvoiceApi.uploadDocument(
                slot = slot, install = install, contentType = file.contentType,
                originalFileName = file.originalFileName, bytes = file.bytes,
                expectedSha256 = file.sha256, capturedAt = file.capturedAt,
                reserveOperationId = op(), finalizeOperationId = op(),
            )
        },
    )
}
