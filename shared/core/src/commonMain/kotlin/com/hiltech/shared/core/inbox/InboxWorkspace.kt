package com.hiltech.shared.core.inbox

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.design.hiltechText
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.approval.ApprovalApiClient
import com.hiltech.shared.core.approval.ApprovalRequestDto
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.procurement.PurchaseOrderReviewApiClient
import com.hiltech.shared.core.procurement.PurchaseOrderReviewWorkspace
import com.hiltech.shared.core.procurement.PurchaseReviewCompletedDto
import com.hiltech.shared.core.procurement.purchaseReviewState
import com.hiltech.shared.core.design.hiltechDateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class InboxFilter(val label: String, val arabic: String) {
    Attention("Needs you", "يحتاج قرارك"), Unread("Unread", "غير مقروء"), All("All", "الكل"),
}

private data class InboxDetail(
    val item: InboxItemDto,
    val approval: ApprovalRequestDto?,
    val purchaseReceipt: PurchaseReviewCompletedDto? = null,
)

@Composable
fun InboxWorkspace(
    sessionScope: String,
    inbox: InboxApiClient,
    approvals: ApprovalApiClient,
    installationId: String,
    newOperationId: () -> String,
    purchaseReviews: PurchaseOrderReviewApiClient? = null,
    onReauthenticate: () -> Unit = {},
) = key(sessionScope, installationId) {
    val jobs = rememberCoroutineScope()
    val colors = LocalHiltechOperationalColors.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(InboxFilter.Attention) }
    var items by remember { mutableStateOf<List<InboxItemDto>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<InboxDetail?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var readOperation by remember { mutableStateOf<String?>(null) }

    suspend fun loadPage(cursor: String?): InboxPageDto = when (filter) {
        InboxFilter.Attention -> inbox.workQueue(installationId, cursor = cursor)
        InboxFilter.Unread -> inbox.inbox(installationId, cursor = cursor, read = "UNREAD")
        InboxFilter.All -> inbox.inbox(installationId, cursor = cursor)
    }

    LaunchedEffect(filter, revision) {
        listing = true
        items = emptyList()
        nextCursor = null
        listError = null
        try {
            val page = loadPage(null)
            items = page.items
            nextCursor = page.nextCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listError = "LIST_FAILED"
        } finally {
            listing = false
        }
    }

    LaunchedEffect(selectedId, revision) {
        detail = null
        detailError = null
        readOperation = null
        val id = selectedId ?: return@LaunchedEffect
        opening = true
        try {
            val item = inbox.one(id, installationId)
            if (item.inboxItemId != id) error("Inbox source mismatch")
            val approval = if (item.sourceType == "APPROVAL_REQUEST") {
                try {
                    approvals.one(item.sourceId, installationId).takeIf { it.approvalRequestId == item.sourceId }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else null
            val purchaseReceipt = if (approval == null && item.sourceType == "APPROVAL_REQUEST" && purchaseReviews != null) {
                try { purchaseReviews.completed(item.sourceId, installationId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } else null
            detail = InboxDetail(item, approval, purchaseReceipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            detailError = "DETAIL_FAILED"
        } finally {
            opening = false
        }
    }

    val listPane: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InboxFilter.entries.forEach { choice ->
                    FilterChip(
                        selected = filter == choice,
                        enabled = !saving && !listing,
                        onClick = {
                            if (filter != choice) {
                                selectedId = null
                                filter = choice
                            }
                        },
                        label = { Text(hiltechText(choice.arabic, choice.label)) },
                    )
                }
            }
            if (listing) CircularProgressIndicator()
            listError?.let { Text(inboxErrorLabel(it), color = MaterialTheme.colorScheme.error) }
            if (items.isEmpty() && !listing && listError == null) {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(
                        when (filter) {
                            InboxFilter.Attention -> hiltechText("لا توجد قرارات تنتظرك", "You’re caught up")
                            InboxFilter.Unread -> hiltechText("لا توجد رسائل غير مقروءة", "No unread items")
                            InboxFilter.All -> hiltechText("الوارد فارغ", "Your inbox is empty")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(hiltechText("ابحث في الوارد المعروض", "Search visible inbox")) })
            val approvalTitle = hiltechText("طلب موافقة", "Approval request")
            val matching = items.filter { item -> query.isBlank() || (if (item.safeTitleCode == "APPROVAL_DECISION_REQUIRED") approvalTitle else humanizeInboxCode(item.safeTitleCode)).contains(query.trim(), true) || item.safeSummary?.contains(query.trim(), true) == true }
            if (matching.isEmpty() && items.isNotEmpty()) Text(hiltechText("لا توجد نتائج مطابقة.", "No matching items."))
            matching.forEach { item ->
                Surface(onClick = { selectedId = item.inboxItemId }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), shape = HiltechShape.medium,
                    color = if (selectedId == item.inboxItemId) colors.selected else colors.surface, contentColor = colors.primaryText) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(inboxTitle(item), style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold)
                            if (!item.read) Text(hiltechText("غير مقروء", "Unread"), color = colors.accent, style = MaterialTheme.typography.labelMedium)
                            item.safeSummary?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        TextButton(enabled = !saving, onClick = { selectedId = item.inboxItemId }) {
                            Text(if (selectedId == item.inboxItemId) hiltechText("مفتوح", "Selected") else hiltechText("فتح", "Open"))
                        }
                    }
                }
            }
            nextCursor?.let { cursor ->
                OutlinedButton(enabled = !listing && !saving, onClick = {
                    listing = true
                    listError = null
                    jobs.launch {
                        try {
                            val page = loadPage(cursor)
                            items = (items + page.items).distinctBy(InboxItemDto::inboxItemId)
                            nextCursor = page.nextCursor
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            listError = "PAGE_FAILED"
                        } finally {
                            listing = false
                        }
                    }
                }) { Text(hiltechText("تحميل المزيد", "Load more")) }
            }
        }
    }

    val detailPane: @Composable () -> Unit = {
        HiltechPanel(Modifier.fillMaxWidth()) {
            TextButton(enabled = !saving, onClick = { selectedId = null }) { Text(hiltechText("العودة إلى الوارد", "Back to inbox")) }
            if (opening) CircularProgressIndicator()
            detailError?.let { Text(inboxErrorLabel(it), color = MaterialTheme.colorScheme.error) }
            detail?.takeIf { it.item.inboxItemId == selectedId }?.let { entry ->
                val item = entry.item
                Text(inboxTitle(item), style = MaterialTheme.typography.titleLarge)
                HiltechStateTag(
                    when {
                        item.actionable -> hiltechText("يحتاج قرارك", "Needs your decision")
                        item.state == "RESOLVED" -> hiltechText("تم الحل", "Resolved")
                        else -> hiltechText("للاطلاع", "For your information")
                    },
                    if (item.actionable) HiltechStateTone.ATTENTION else HiltechStateTone.NEUTRAL,
                )
                item.safeSummary?.takeIf(String::isNotBlank)?.let { Text(it) }
                entry.approval?.let { approval ->
                    if (approval.subjectType == "PROCUREMENT_PO_CANDIDATE") {
                        if (purchaseReviews == null) Text(hiltechText("مراجعة المشتريات غير متاحة حاليًا في هذه الجلسة. حدّث الاتصال أو راجع مسؤول النظام.",
                            "Purchasing review is currently unavailable in this session. Refresh the connection or contact the administrator."))
                        else PurchaseOrderReviewWorkspace(
                            client = purchaseReviews, candidateId = approval.subjectId, approvalRequestId = approval.approvalRequestId,
                            installationId = installationId, newOperationId = newOperationId,
                            onReauthenticate = onReauthenticate,
                            onRecorded = { result ->
                                detail = entry.copy(item = item.copy(actionable = false, state = "RESOLVED"), approval = approval.copy(state = result.approvalState))
                                items = if (filter == InboxFilter.Attention) items.filterNot { it.inboxItemId == item.inboxItemId }
                                    else items.map { if (it.inboxItemId == item.inboxItemId) it.copy(actionable = false, state = "RESOLVED") else it }
                            },
                        )
                    } else {
                        Text(humanizeInboxCode(approval.subjectType), style = MaterialTheme.typography.titleMedium)
                        HiltechKeyValue(hiltechText("حالة الموافقة", "Approval status"), humanizeInboxCode(approval.state))
                        HiltechKeyValue(hiltechText("إصدار السجل", "Record version"), approval.subjectVersion.toString())
                        HiltechKeyValue(hiltechText("تاريخ الطلب", "Requested at"), approval.createdAt)
                        approval.safeReasonSummary?.takeIf(String::isNotBlank)?.let { Text(it) }
                    }
                }
                HiltechKeyValue(hiltechText("تاريخ آخر تحديث", "Last updated"), item.updatedAt)
                entry.purchaseReceipt?.let { recorded ->
                    HiltechStateTag(purchaseReviewState(recorded.approvalState), HiltechStateTone.CURRENT)
                    Text(if (recorded.decidedByCurrentUser) hiltechText("هذا هو القرار المسجل من حسابك على مقترح الشراء.",
                        "This is the decision recorded by your account on the purchase proposal.") else hiltechText(
                        "أُغلقت المراجعة القديمة في السجل، ولم تُسجّل موافقة على المقترح.", "The outdated review was closed in the record; no proposal approval was recorded."))
                    HiltechKeyValue(hiltechText("تاريخ الإغلاق", "Completed on"), hiltechDateText(recorded.completedAt.take(10)))
                }
                if (item.sourceType == "APPROVAL_REQUEST" && entry.approval == null && entry.purchaseReceipt == null) {
                    Text(hiltechText("تفاصيل الموافقة غير متاحة في هذه الجلسة.", "The approval details are unavailable for this session."))
                }
                OutlinedButton(enabled = !saving && !opening, onClick = {
                    val request = InboxReadStateRequestDto(readOperation ?: newOperationId().also { readOperation = it })
                    saving = true
                    detailError = null
                    jobs.launch {
                        try {
                            val response = if (item.read) inbox.markUnread(item.inboxItemId, request, installationId)
                            else inbox.markRead(item.inboxItemId, request, installationId)
                            if (response.inboxItemId != item.inboxItemId) error("Inbox source mismatch")
                            readOperation = null
                            detail = entry.copy(item = item.copy(read = response.read))
                            items = if (filter == InboxFilter.Unread && response.read) {
                                items.filterNot { it.inboxItemId == response.inboxItemId }
                            } else {
                                items.map { if (it.inboxItemId == response.inboxItemId) it.copy(read = response.read) else it }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            detailError = "READ_FAILED"
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(if (item.read) hiltechText("تحديد كغير مقروء", "Mark unread") else hiltechText("تحديد كمقروء", "Mark read")) }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { HiltechSectionHeading("INBOX", hiltechText("الوارد والقرارات", "Inbox & decisions"), hiltechText("الطلبات والتنبيهات الموجهة إليك.", "Requests and notifications assigned to you.")) }
        TextButton(enabled = !saving && !listing && !opening, onClick = { revision += 1 }) { Text(hiltechText("تحديث", "Refresh")) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 720.dp) {
            if (selectedId == null) listPane() else detailPane()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { listPane() }
                Column(Modifier.weight(1.25f)) {
                    if (selectedId != null) detailPane()
                    else HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(hiltechText("تفاصيل الوارد", "Inbox details"), style = MaterialTheme.typography.titleLarge)
                        Text(hiltechText("اختر طلبًا من القائمة لقراءة مصدره وحالته.", "Select an item to inspect its source and status."))
                    }
                }
            }
        }
    }
}

@Composable
private fun inboxTitle(item: InboxItemDto): String = when (item.safeTitleCode) {
    "APPROVAL_DECISION_REQUIRED" -> hiltechText("طلب موافقة", "Approval request")
    else -> humanizeInboxCode(item.safeTitleCode)
}

@Composable
private fun inboxErrorLabel(code: String): String = when (code) {
    "LIST_FAILED" -> hiltechText("تعذر تحميل الوارد. حدّث البيانات للمحاولة مرة أخرى.", "Couldn’t load the inbox. Refresh to try again.")
    "DETAIL_FAILED" -> hiltechText("الطلب غير متاح. حدّث البيانات للتحقق من الصلاحية والحالة.", "This item is unavailable. Refresh for current access and status.")
    "PAGE_FAILED" -> hiltechText("تعذر تحميل المزيد من الطلبات.", "Couldn’t load more items. Try again.")
    "READ_FAILED" -> hiltechText("تعذر تحديث حالة القراءة. حاول مرة أخرى.", "Couldn’t update the read status. Try again.")
    else -> code
}

private fun humanizeInboxCode(code: String): String =
    code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
