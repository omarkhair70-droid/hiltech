@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun supplierTechnicalReviewNativeWorkspace(
    client: SupplierTechnicalApiClient, evidence: EvidenceApiClient, identityId: String, organizationId: String, installationId: String,
    newOperationId: () -> String, pickDocument: suspend () -> ServiceSelectedFile?, onReauthenticate: () -> Unit = {},
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "supplier-technical-review", label = "المراجعة الفنية للموردين", hostOwnsScroll = false,
    canReadSource = { client.queue(organizationId, installationId).let { it.canReview || it.canPrepare } },
    content = { SupplierTechnicalReviewWorkspace(client, evidence, identityId, organizationId, installationId, newOperationId, pickDocument, onReauthenticate) },
)

@Composable
fun SupplierTechnicalReviewWorkspace(
    client: SupplierTechnicalApiClient, evidence: EvidenceApiClient, identityId: String, organizationId: String, installationId: String,
    newOperationId: () -> String, pickDocument: suspend () -> ServiceSelectedFile?, onReauthenticate: () -> Unit = {},
) = key(identityId, organizationId, installationId, client) {
    val language = LocalHiltechLanguage.current
    val jobs = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf(emptyList<SupplierTechnicalQueueItemDto>()) }
    var next by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    suspend fun load(offset: Int, query: String) {
        loading = true; error = null
        try {
            val result = client.queue(organizationId, installationId, query.trim(), offset)
            if (search.trim() != query.trim()) return
            items = if (offset == 0) result.items else (items + result.items).distinctBy { it.quote.quoteId }
            next = result.nextOffset
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? HiltechApiException)?.code; if (error == null) error = "LOAD_FAILED"; items = emptyList(); next = null }
        finally { loading = false }
    }
    LaunchedEffect(search, refresh) { delay(220); load(0, search) }
    val visible = items.filter { filter == "ALL" || (filter == "PENDING" && (it.effectiveDecision != "COMPLIANT" || !it.quote.sourceCurrent || !it.quote.latestQuote)) || it.effectiveDecision == filter }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(hiltechText("المراجعة الفنية للموردين", "Supplier technical review"), style = MaterialTheme.typography.headlineSmall)
                Text(hiltechText("قارن المواصفات باحتياج الشركة وسجّل النتيجة على المصدر الفني الحالي.", "Compare specifications with the company’s requirement and record the result against the current technical source."), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text(hiltechText("تحديث", "Refresh")) }
        }
        OutlinedTextField(search, { if (it.length <= 160) search = it }, label = { Text(hiltechText("بحث بالصنف أو المورد أو مرجع العرض", "Search item, supplier or quote reference")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "PENDING", "COMPLIANT").forEach { status -> FilterChip(filter == status, { filter = status }, label = {
                Text(when (status) { "ALL" -> hiltechText("الكل", "All"); "PENDING" -> hiltechText("تحتاج متابعة", "Needs attention"); else -> supplierTechnicalLabel(status, language) })
            }) }
        }
        client.commands.pending?.let { pending ->
            HiltechPanel { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(supplierTechnicalProblem("SUPPLIER_TECHNICAL_RESULT_UNCONFIRMED", language))
                if (error == null) Text(pending.title, style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { selected = pending.quoteId }) { Text(hiltechText("متابعة نفس الطلب", "Resume the same request")) }
            } }
        }
        client.commands.upload?.let { upload ->
            OutlinedButton(onClick = { selected = upload.quoteId }) { Text(hiltechText("استكمال رفع المستند الفني", "Resume technical document upload")) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { code -> Text(supplierTechnicalProblem(code, language)); if (code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")) OutlinedButton(onClick = onReauthenticate) { Text(hiltechText("تجديد تسجيل الدخول", "Sign in again")) } }
        if (!loading && error == null && visible.isEmpty()) HiltechPanel {
            Text(hiltechText("لا توجد عروض مطابقة للبحث. تظهر هنا العروض المسجلة للمراجعة الفنية بمجرد إضافتها في المشتريات.", "No quotes match this search. Quotes appear here for technical review when purchasing records them."))
        }
        visible.forEach { row -> key(row.quote.quoteId) {
            HiltechPanel {
                BoxWithConstraints {
                    val summary: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.quote.itemName, style = MaterialTheme.typography.titleMedium)
                            Text(row.quote.supplierName, style = MaterialTheme.typography.bodyLarge)
                            Text("${row.quote.itemCode} · ${row.quote.quoteReference}", style = MaterialTheme.typography.labelMedium)
                            Text("${row.quote.offeredQuantity} ${row.quote.offeredUnit}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val action: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HiltechStateTag(supplierTechnicalLabel(row.effectiveDecision, language), if (row.effectiveDecision == "COMPLIANT") HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
                            if (!row.quote.sourceCurrent) Text(hiltechText("الاحتياج يحتاج تحديثًا", "Requirement needs refreshing"), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { selected = row.quote.quoteId }) { Text(hiltechText("فتح الملف الفني", "Open technical file")) }
                        }
                    }
                    if (maxWidth >= 720.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) { Column(Modifier.weight(1f)) { summary() }; action() }
                    else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { summary(); action() }
                }
            }
        } }
        next?.let { offset -> OutlinedButton(onClick = { jobs.launch { load(offset, search) } }, enabled = !loading) { Text(hiltechText("عرض المزيد", "Show more")) } }
    }
    selected?.let { quote -> SupplierTechnicalPacketEditor(client, evidence, quote, identityId, organizationId, installationId, newOperationId, pickDocument,
        onReauthenticate, onClose = { selected = null; refresh++ }, onChanged = { refresh++ }) }
}
