package com.hiltech.shared.core.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hiltech.shared.core.design.hiltechDateText
import com.hiltech.shared.core.design.hiltechText

/** Session-memory only. This viewer never exports a private file or opens a reusable storage URL. */
@Composable
internal fun FinancePayrollEvidenceViewer(document: FinancePayrollEvidenceDocumentDto, onClose: () -> Unit) {
    var originalText by remember(document.viewReceiptId) { mutableStateOf(false) }
    val rows = remember(document.viewReceiptId) {
        document.contentUtf8.removePrefix("\uFEFF").split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
    }
    AlertDialog(onDismissRequest = onClose,
        modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hiltechText("مستند البنك الأصلي", "Original bank document"))
            Text(document.fileName, style = MaterialTheme.typography.bodyMedium)
        } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(hiltechText("الحركة المطلوب مراجعتها: ", "Movement under review: ") + document.matchedTransactionReference,
                style = MaterialTheme.typography.titleSmall)
            Text(hiltechText("هذا هو الملف المحفوظ مع كشف البنك المعتمد. راجع المرجع والتاريخ والمبلغ والمستفيد قبل تسجيل القرار.",
                "This is the file stored with the approved bank statement. Check the reference, date, amount and beneficiary before deciding."))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = originalText, onCheckedChange = { originalText = it })
                Text(hiltechText("عرض نص الملف الأصلي", "Show original file text"))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(if (originalText) rows else rows.drop(1), key = { index, _ -> index }) { _, row ->
                    val fields = row.split(',')
                    val matched = fields.firstOrNull() == document.matchedTransactionReference
                    Surface(color = if (matched) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        if (originalText || fields.size != 5) Text(row, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (matched) Text(hiltechText("الحركة المرتبطة بهذه المطابقة", "Movement linked to this match"), style = MaterialTheme.typography.labelMedium)
                            Text(fields[0], style = MaterialTheme.typography.titleSmall)
                            Text(hiltechDateText(fields[1]) + " · " + (if (fields[2] == "DEBIT") hiltechText("مبلغ خارج من الحساب", "Account debit") else hiltechText("مبلغ وارد للحساب", "Account credit")))
                            Text(fields[4] + " " + fields[3], style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
        } },
        confirmButton = { TextButton(onClick = onClose) { Text(hiltechText("عودة للمراجعة", "Return to review")) } },
    )
}
