package com.hiltech.desktop.finance

import androidx.compose.runtime.Composable
import com.hiltech.shared.core.finance.FinanceChosenExpenseReceipt
import com.hiltech.shared.core.finance.FinanceExpenseReceiptUploadControl
import com.hiltech.shared.core.finance.FinanceExpenseReceiptUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Real private Windows file chooser. No synthetic image or local copy.
 * Mount only on an authenticated employee Finance surface; approval and
 * payment are intentionally absent.
 */
@Composable
fun FinanceWindowsReceiptPickerControl(
    expenseDraftId: String,
    installationId: String,
    uploader: FinanceExpenseReceiptUploader,
) {
    FinanceExpenseReceiptUploadControl(
        expenseDraftId = expenseDraftId,
        installationId = installationId,
        uploader = uploader,
        chooseFile = { chooseWindowsFinanceReceipt() },
        newOperationId = { UUID.randomUUID().toString() },
    )
}

private suspend fun chooseWindowsFinanceReceipt(): FinanceChosenExpenseReceipt? =
    withContext(Dispatchers.IO) {
        val chosen = AtomicReference<File?>(null)
        SwingUtilities.invokeAndWait {
            val dialog = JFileChooser()
            dialog.dialogTitle = "Choose a private expense receipt"
            dialog.fileSelectionMode = JFileChooser.FILES_ONLY
            dialog.isAcceptAllFileFilterUsed = false
            dialog.fileFilter = FileNameExtensionFilter(
                "Expense receipt (PDF, PNG, JPEG)", "pdf", "png", "jpg", "jpeg",
            )
            if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chosen.set(dialog.selectedFile)
            }
        }
        val file = chosen.get() ?: return@withContext null
        require(file.isFile && file.length() in 1..MAX_EXPENSE_RECEIPT_BYTES.toLong()) {
            "Expense receipt must be a real file up to 16 MiB."
        }
        val bytes = file.readBytes()
        require(bytes.size in 1..MAX_EXPENSE_RECEIPT_BYTES) {
            "Expense receipt cannot exceed 16 MiB."
        }
        val mime = exactReceiptMime(bytes)
            ?: error("Selected file is not a valid PDF, PNG or JPEG signature.")
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        FinanceChosenExpenseReceipt(
            contentType = mime,
            originalFileName = file.name,
            bytes = bytes,
            sha256 = sha,
            capturedAt = Instant.now().toString(),
        )
    }

/** Explicit byte signature validation, never trust the extension alone. */
internal fun exactReceiptMime(bytes: ByteArray): String? {
    if (bytes.size >= 5 &&
        bytes.copyOfRange(0, 5).decodeToString() == "%PDF-") return "application/pdf"
    if (bytes.size >= 3 &&
        bytes[0] == 0xff.toByte() &&
        bytes[1] == 0xd8.toByte() &&
        bytes[2] == 0xff.toByte()) return "image/jpeg"
    val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    if (bytes.size >= png.size &&
        png.indices.all { bytes[it] == png[it] }) return "image/png"
    return null
}

private const val MAX_EXPENSE_RECEIPT_BYTES = 16 * 1024 * 1024
