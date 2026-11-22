package com.hiltech.shared.core.procurement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.HiltechSpace
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.LocalHiltechOperationalColors

/** The production-mounted issuer composable also renders independent native QA scenes. */
@Composable
fun PurchaseOrderIssuerLiveScreen(
    ui: IssuerLiveUi,
    onRefresh: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onToggleLifecycle: () -> Unit,
    selectedAction: @Composable (PurchaseOrderIssuerQueueItemDto) -> Unit,
    lifecycleContent: @Composable () -> Unit,
) {
    val palette = LocalHiltechOperationalColors.current
    val selected = ui.queue.firstOrNull { it.candidateId == ui.selectedCandidateId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HiltechSpace.medium)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            HiltechSectionHeading(
                index = "PROCUREMENT / ISSUER DESK",
                title = "Issue internal purchase orders",
                subtitle = "Approved candidates, authorized destinations, and canonical server receipts.",
            )
            HiltechStateTag(
                "Company authority required / online source",
                HiltechStateTone.NEUTRAL,
            )
            Text(
                "Issuing a PO records an internal company order. It does not notify a " +
                    "supplier, receive stock or authorize payment.",
                color = palette.secondaryText,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
                OutlinedButton(enabled = !ui.loading, onClick = onRefresh) {
                    Text(if (ui.loading) "Loading issuer queue" else "Refresh authorized queue")
                }
                if (ui.lifecycleAvailable) {
                    OutlinedButton(onClick = onToggleLifecycle) {
                        Text(if (ui.lifecycleVisible) "Hide PO lifecycle tools"
                            else "PO lifecycle tools")
                    }
                }
            }
            if (ui.loading) HiltechStateTag(
                "Checking current server scope...", HiltechStateTone.NEUTRAL,
            )
            ui.error?.let { HiltechStateTag(it, HiltechStateTone.BLOCKED) }
            ui.message?.let { HiltechStateTag(it, HiltechStateTone.ATTENTION) }
        }
        ui.verifiedIssuedOrder?.let { issued ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    index = "ISSUED-ORDER REGISTER / SERVER VERIFIED",
                    title = issued.code,
                    subtitle = "This order already exists in the canonical server register. No new issue was submitted.",
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
                    HiltechKeyValue("Effective state", issued.effectiveState, Modifier.weight(1f))
                    HiltechKeyValue("Amount", issued.total + " " + issued.currency, Modifier.weight(1f))
                    HiltechKeyValue("Dispatch state", issued.supplierDispatchState, Modifier.weight(1f))
                }
                HiltechStateTag("Existing source record verified", HiltechStateTone.CURRENT)
                SelectionContainer {
                    HiltechKeyValue("Issued PO ID", isolateSourceId(issued.id))
                }
            }
        }
        ui.recentReceipt?.let { receipt ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    index = "SERVER CONFIRMATION / INTERNAL PO",
                    title = receipt.code,
                    subtitle = "Confirmed by the canonical issue endpoint; not supplier dispatch.",
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
                    HiltechKeyValue("Issue state", receipt.state, Modifier.weight(1f))
                    HiltechKeyValue("Total", receipt.total + " " + receipt.currency, Modifier.weight(1f))
                    HiltechKeyValue("Supplier dispatch", receipt.supplierDispatchState, Modifier.weight(1f))
                }
                HiltechStateTag(
                    if (receipt.replayed) "Confirmed idempotent replay"
                    else "Server confirmed internal issue",
                    HiltechStateTone.CURRENT,
                )
                Text(
                    "Supplier notified: " + receipt.supplierNotified +
                        "  |  Stock received: " + receipt.stockReceived +
                        "  |  Payment authorized: " + receipt.paymentAuthorized,
                    color = palette.secondaryText, fontSize = 12.sp,
                )
                SelectionContainer {
                    HiltechKeyValue("PO source identifier", isolateSourceId(receipt.purchaseOrderId))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 920.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(HiltechSpace.medium)) {
                    IssuerQueuePanel(
                        ui = ui, onSelectCandidate = onSelectCandidate, modifier = Modifier.weight(.90f),
                    )
                    IssuerInspectorPanel(
                        candidate = selected, selectedAction = selectedAction,
                        modifier = Modifier.weight(1.10f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(HiltechSpace.medium)) {
                    IssuerQueuePanel(ui, onSelectCandidate, Modifier.fillMaxWidth())
                    IssuerInspectorPanel(selected, selectedAction, Modifier.fillMaxWidth())
                }
            }
        }
        if (ui.lifecycleAvailable && ui.lifecycleVisible) {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    index = "SEPARATE AUTHORIZED OPERATIONS",
                    title = "PO lifecycle",
                    subtitle = "Existing cancellation and destination-amendment tools; each has its own server gates.",
                )
                lifecycleContent()
            }
        }
    }
}

@Composable
private fun IssuerQueuePanel(
    ui: IssuerLiveUi,
    onSelectCandidate: (String) -> Unit,
    modifier: Modifier,
) {
    val palette = LocalHiltechOperationalColors.current
    var search by remember { mutableStateOf("") }
    val filtered = issuerVisibleQueue(ui.queue, search)
    var requestedPage by remember(search) { mutableStateOf(0) }
    val window = buyerListWindow(filtered.size, requestedPage, 8)
    HiltechPanel(modifier) {
        HiltechSectionHeading(
            index = "01 / APPROVED QUEUE",
            title = "Ready to inspect",
            subtitle = "Only current, approved candidates returned by the issuer API.",
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search authorized supplier or candidate ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            "Showing " + filtered.size + " of " + ui.queue.size + " server-returned candidates",
            color = palette.secondaryText, fontSize = 12.sp,
        )
        when {
            ui.loading -> Text("Checking the current authorized queue.", color = palette.secondaryText)
            !ui.loaded -> Text("Refresh to verify issuer access.", color = palette.secondaryText)
            filtered.isEmpty() -> Text(
                if (search.isEmpty()) "No current approved and unissued candidates."
                else "No matches within the authorized issuer queue.",
                color = palette.secondaryText,
            )
        }
        filtered.subList(window.start, window.endExclusive).forEach { candidate ->
            val isSelected = candidate.candidateId == ui.selectedCandidateId
            Surface(
                color = if (isSelected) palette.selected else palette.surfaceElevated,
                shape = HiltechShape.small,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) palette.accent else palette.divider,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(HiltechSpace.small),
                    verticalArrangement = Arrangement.spacedBy(HiltechSpace.xs),
                ) {
                    Text(candidate.supplierName, color = palette.primaryText,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(candidate.quantity + " " + candidate.unitCode +
                        " / " + candidate.total + " " + candidate.currency,
                        color = palette.primaryText, fontSize = 13.sp)
                    Text("Approval policy v" + candidate.approvalPolicyVersion,
                        color = palette.secondaryText, fontSize = 12.sp)
                    OutlinedButton(
                        enabled = !ui.loading,
                        onClick = { onSelectCandidate(candidate.candidateId) },
                    ) {
                        Text(if (isSelected) "Viewing issue details" else "Inspect and prepare PO")
                    }
                }
            }
        }
        if (window.pageCount > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
                OutlinedButton(
                    enabled = window.hasPrevious,
                    onClick = { requestedPage = window.pageIndex - 1 },
                ) { Text("Previous") }
                Text("Page " + (window.pageIndex + 1) + " / " + window.pageCount,
                    color = palette.secondaryText,
                    modifier = Modifier.padding(top = 14.dp), fontSize = 12.sp)
                OutlinedButton(
                    enabled = window.hasNext,
                    onClick = { requestedPage = window.pageIndex + 1 },
                ) { Text("Next") }
            }
        }
    }
}

@Composable
private fun IssuerInspectorPanel(
    candidate: PurchaseOrderIssuerQueueItemDto?,
    selectedAction: @Composable (PurchaseOrderIssuerQueueItemDto) -> Unit,
    modifier: Modifier,
) {
    val palette = LocalHiltechOperationalColors.current
    HiltechPanel(modifier) {
        if (candidate == null) {
            HiltechSectionHeading(
                index = "02 / SOURCE INSPECTOR",
                title = "Choose an approved candidate",
                subtitle = "Inspect the exact supplier, amount, approval source and delivery destination before issuing.",
            )
            HiltechStateTag(
                "No PO is issued by selecting a candidate",
                HiltechStateTone.NEUTRAL,
            )
            return@HiltechPanel
        }
        HiltechSectionHeading(
            index = "02 / AUTHORITATIVE CANDIDATE",
            title = candidate.supplierName,
            subtitle = "Approval and purchasing scope still checked by the server.",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
            HiltechKeyValue("Quantity", candidate.quantity + " " + candidate.unitCode,
                Modifier.weight(1f))
            HiltechKeyValue("Approved total", candidate.total + " " + candidate.currency,
                Modifier.weight(1f))
        }
        HiltechKeyValue("Approval policy version", candidate.approvalPolicyVersion.toString())
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(HiltechSpace.xs)) {
                HiltechKeyValue("Approval request ID", isolateSourceId(candidate.approvalRequestId))
                HiltechKeyValue("Candidate ID", isolateSourceId(candidate.candidateId))
                HiltechKeyValue("Stock item ID", isolateSourceId(candidate.stockItemId))
                HiltechKeyValue("Supplier organization ID", isolateSourceId(candidate.supplierOrganizationId))
            }
        }
        selectedAction(candidate)
        Text(
            "Server checks candidate version, company authority and exact destination at issue time.",
            color = palette.secondaryText, fontSize = 12.sp,
        )
    }
}
