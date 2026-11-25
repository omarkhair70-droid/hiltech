package com.hiltech.shared.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Displays a bounded PNG page supplied by a source-authorized reader. The caller owns
 * authentication, exact document/receipt validation and revocation clearing. Bytes are
 * remembered only while this dialog is composed; this component writes no cache or file.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun ProtectedDocumentPageViewer(
    title: String,
    pageIndex: Int,
    pageCount: Int,
    imagePng: ByteArray?,
    loading: Boolean,
    error: String?,
    onPage: (Int) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onDisplayed: () -> Unit = {},
) {
    var enlarged by remember(title) { mutableStateOf(false) }
    val bitmap = remember(imagePng) { imagePng?.let { bytes ->
        runCatching {
            requireProtectedDocumentPngSize(bytes)
            bytes.decodeToImageBitmap().also { require(it.width in 1..1800 && it.height in 1..1800) }
        }.getOrNull()
    } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1160.dp).fillMaxWidth(.96f), shape = HiltechShape.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(hiltechText("معاينة المستند الأصلي", "Original document preview"), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onClose) { Text(hiltechText("عودة", "Back")) }
                }
                Text(hiltechText("راجع المحتوى ثم عُد إلى الطلب لاتخاذ القرار. فتح المستند لا يسجّل موافقة.",
                    "Review the content, then return to the request to decide. Opening a document does not approve it."),
                    style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 220.dp, max = 680.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator()
                        error != null || bitmap == null -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(error ?: hiltechText("تعذّر عرض هذه الصفحة. أعد المحاولة.", "This page could not be displayed. Try again."))
                            TextButton(onClick = onRetry) { Text(hiltechText("إعادة المحاولة", "Try again")) }
                        }
                        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                            val fitWidth = maxWidth
                            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                                Image(bitmap, contentDescription = title + " · " + (pageIndex + 1), contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.width(if (enlarged) maxOf(fitWidth, 1200.dp) else fitWidth))
                            }
                            LaunchedEffect(bitmap, pageIndex) { onDisplayed() }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !loading && pageIndex > 0, onClick = { onPage(pageIndex - 1) }) { Text(hiltechText("السابقة", "Previous")) }
                    Text(if (pageCount > 0) "${pageIndex + 1} / $pageCount" else "—", style = MaterialTheme.typography.labelLarge)
                    TextButton(enabled = !loading && pageCount > 0 && pageIndex + 1 < pageCount, onClick = { onPage(pageIndex + 1) }) { Text(hiltechText("التالية", "Next")) }
                }
                TextButton(enabled = bitmap != null && !loading, onClick = { enlarged = !enlarged }) {
                    Text(if (enlarged) hiltechText("تصغير الصفحة", "Reduce page size") else hiltechText("تكبير التفاصيل", "Enlarge details"))
                }
            }
        }
    }
}

/** Check dimensions before asking a platform decoder to allocate a bitmap. */
internal fun requireProtectedDocumentPngSize(bytes: ByteArray) {
    require(bytes.size in 24..16_777_216)
    require(bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() })
    require(bytes.copyOfRange(12, 16).decodeToString() == "IHDR")
    fun dimension(offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255L) }
    require(dimension(16) in 1L..1800L && dimension(20) in 1L..1800L)
}
