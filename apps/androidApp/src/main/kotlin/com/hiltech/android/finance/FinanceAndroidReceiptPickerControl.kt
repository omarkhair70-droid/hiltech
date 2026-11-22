package com.hiltech.android.finance

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hiltech.shared.core.finance.FinanceChosenExpenseReceipt
import com.hiltech.shared.core.finance.FinanceExpenseReceiptUploadControl
import com.hiltech.shared.core.finance.FinanceExpenseReceiptUploader
import com.hiltech.shared.core.finance.FinanceReceiptBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Genuine Android system document picker; reads bytes privately and bounded,
 * no public copy and no inferred receipt from a filename/extension.
 * Host may mount only inside an authenticated employee Finance route.
 */
@Composable
fun FinanceAndroidReceiptPickerControl(
    expenseDraftId: String,
    installationId: String,
    uploader: FinanceExpenseReceiptUploader,
) {
    val context = LocalContext.current
    var waiting by remember(expenseDraftId) {
        mutableStateOf<CompletableDeferred<Uri?>?>(null)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        waiting?.complete(uri)
        waiting = null
    }
    DisposableEffect(expenseDraftId) {
        onDispose {
            waiting?.cancel()
            waiting = null
        }
    }
    FinanceExpenseReceiptUploadControl(
        expenseDraftId = expenseDraftId,
        installationId = installationId,
        uploader = uploader,
        chooseFile = {
            check(waiting == null) { "A document picker is already open." }
            val result = CompletableDeferred<Uri?>()
            waiting = result
            launcher.launch(arrayOf("application/pdf", "image/png", "image/jpeg"))
            val uri = result.await()
            if (uri == null) null
            else readPrivateFinanceReceipt(context, uri)
        },
        newOperationId = { UUID.randomUUID().toString() },
    )
}

private suspend fun readPrivateFinanceReceipt(
    context: Context,
    uri: Uri,
): FinanceChosenExpenseReceipt = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri)
        ?.substringBefore(';')?.trim()?.lowercase()
        ?: error("Receipt MIME type is unavailable.")
    require(mime in setOf("application/pdf", "image/png", "image/jpeg")) {
        "Only PDF, PNG or JPEG receipts are supported."
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            size += count
            require(size <= MAX_EXPENSE_RECEIPT_BYTES) {
                "Expense receipt exceeds 16 MiB."
            }
            output.write(buffer, 0, count)
        }
        require(size > 0) { "Expense receipt is empty." }
        output.toByteArray()
    } ?: error("Selected receipt cannot be read.")
    require(FinanceReceiptBytes.validSelectedReceipt(bytes, mime)) {
        "Selected receipt content does not match its declared PDF/image format."
    }
    val filename = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val sha = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    FinanceChosenExpenseReceipt(
        contentType = mime,
        originalFileName = filename,
        bytes = bytes,
        sha256 = sha,
        capturedAt = Instant.now().toString(),
    )
}

private const val MAX_EXPENSE_RECEIPT_BYTES = 16 * 1024 * 1024
