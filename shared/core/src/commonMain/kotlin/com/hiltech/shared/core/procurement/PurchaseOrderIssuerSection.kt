package com.hiltech.shared.core.procurement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.HiltechSpace
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.launch

/** Only these server-backed facts, not buyer's confidential quote evidence. */
data class IssuerLiveUi(
    val queue: List<PurchaseOrderIssuerQueueItemDto> = emptyList(),
    val selectedCandidateId: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val recentReceipt: PurchaseOrderIssueReceiptDto? = null,
    val verifiedIssuedOrder: PurchaseOrderLifecycleSummaryDto? = null,
    val lifecycleVisible: Boolean = false,
    val lifecycleAvailable: Boolean = false,
)

/**
 * Production-mounted, source-bound Windows PO-issuer desk. This is only an
 * interface for existing canonical issuer APIs and distinct company authority.
 * A PO issued here is NOT supplier dispatch, stock receipt or payment.
 */
@Composable
fun PurchaseOrderIssuerSection(
    api: PurchaseOrderIssueApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    cancellationEnabled: Boolean = false,
    amendmentApi: PurchaseOrderDeliveryAmendmentApiClient? = null,
) {
    val scope = rememberCoroutineScope()
    var reload by remember(organizationId, installationId) { mutableStateOf(0) }
    var busy by remember(organizationId, installationId) { mutableStateOf(false) }
    var loaded by remember(organizationId, installationId) { mutableStateOf(false) }
    var error by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var message by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var queue by remember(organizationId, installationId) {
        mutableStateOf<List<PurchaseOrderIssuerQueueItemDto>>(emptyList())
    }
    var selectedId by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var recentReceipt by remember(organizationId, installationId) {
        mutableStateOf<PurchaseOrderIssueReceiptDto?>(null)
    }
    var verifiedIssuedOrder by remember(organizationId, installationId) {
        mutableStateOf<PurchaseOrderLifecycleSummaryDto?>(null)
    }
    var lifecycleVisible by remember(organizationId, installationId) { mutableStateOf(false) }

    // Hold the actual operation ID AND its destination for uncertain issue
    // results. Merely changing candidate UI must not silently create a new key.
    var pendingOperations by remember(organizationId, installationId) {
        mutableStateOf<Map<String, IssuerPendingOperation>>(emptyMap())
    }

    fun revokeSnapshot() {
        queue = emptyList()
        loaded = false
        selectedId = null
        recentReceipt = null
        verifiedIssuedOrder = null
        pendingOperations = emptyMap()
        lifecycleVisible = false
        error = "Issuer scope is no longer authorized. Sign in or refresh access."
    }

    LaunchedEffect(organizationId, installationId, reload) {
        busy = true
        loaded = false
        queue = emptyList()
        selectedId = null
        error = null
        message = null
        recentReceipt = null
        verifiedIssuedOrder = null
        try {
            queue = api.queue(organizationId, installationId)
            loaded = true
        } catch (failure: Exception) {
            queue = emptyList()
            error = "Cannot load authorized PO issuer queue. Check scope and connection."
            if (issuerShouldClearRestrictedQueue((failure as? HiltechApiException)?.httpStatus)) {
                pendingOperations = emptyMap()
                lifecycleVisible = false
            }
        } finally {
            busy = false
        }
    }

    fun refreshQueueAfterIssue() {
        scope.launch {
            try {
                val fresh = api.queue(organizationId, installationId)
                val confirmedCandidateIds = setOfNotNull(
                    recentReceipt?.candidateId,
                    verifiedIssuedOrder?.candidateId,
                )
                // Do not re-enable a confirmed candidate even if the issuer
                // queue briefly lags its authoritative issued-PO register.
                queue = fresh.filterNot { it.candidateId in confirmedCandidateIds }
                selectedId = issuerSelectedCandidateAfterRefresh(selectedId, queue)
                if (fresh.any { it.candidateId in confirmedCandidateIds }) {
                    message = "PO was verified in the issue record, but issuer queue is still catching up. " +
                        "Its candidate remains non-actionable locally."
                }
                loaded = true
            } catch (failure: Exception) {
                if (issuerShouldClearRestrictedQueue((failure as? HiltechApiException)?.httpStatus)) {
                    revokeSnapshot()
                } else {
                    // Local successful receipt stays visible; no unverified queue
                    // is represented as a refreshed canonical server snapshot.
                    queue = emptyList()
                    loaded = false
                    message = "PO issued, but the updated queue cannot be verified. Refresh source."
                }
            }
        }
    }

    HiltechOperationalTheme {
        PurchaseOrderIssuerLiveScreen(
            ui = IssuerLiveUi(
                queue = queue,
                selectedCandidateId = selectedId,
                loaded = loaded,
                loading = busy,
                error = error,
                message = message,
                recentReceipt = recentReceipt,
                verifiedIssuedOrder = verifiedIssuedOrder,
                lifecycleVisible = lifecycleVisible,
                lifecycleAvailable = cancellationEnabled || amendmentApi != null,
            ),
            onRefresh = { reload += 1 },
            onSelectCandidate = { selectedId = it },
            onToggleLifecycle = { lifecycleVisible = !lifecycleVisible },
            selectedAction = { candidate ->
                key(candidate.candidateId) {
                    PurchaseOrderIssuerAction(
                        api = api,
                        candidate = candidate,
                        organizationId = organizationId,
                        installationId = installationId,
                        newOperationId = newOperationId,
                        savedOperation = pendingOperations[candidate.candidateId],
                        onPendingOperation = { operation ->
                            pendingOperations = pendingOperations + (candidate.candidateId to operation)
                        },
                        onIssued = { receipt ->
                            if (issuerReceiptMatchesSource(receipt, candidate)) {
                                recentReceipt = receipt
                                verifiedIssuedOrder = null
                                pendingOperations = pendingOperations - candidate.candidateId
                                queue = issuerQueueAfterConfirmedReceipt(queue, receipt)
                                selectedId = null
                                message = "Server confirmed internal PO issue. Supplier transmission is separate."
                                error = null
                                refreshQueueAfterIssue()
                            } else {
                                // An unexpected receipt must not silently remove
                                // a different candidate from the authorized view.
                                selectedId = null
                                queue = emptyList()
                                loaded = false
                                error = "Unexpected receipt source. Reload issuer authority."
                            }
                        },
                        onVerifiedIssuedOrder = { verified ->
                            if (verified.candidateId == candidate.candidateId) {
                                verifiedIssuedOrder = verified
                                recentReceipt = null
                                pendingOperations = pendingOperations - candidate.candidateId
                                queue = queue.filterNot { it.candidateId == candidate.candidateId }
                                selectedId = null
                                error = null
                                message = "Existing internal PO confirmed from server register. No new issue was submitted."
                                refreshQueueAfterIssue()
                            } else {
                                queue = emptyList()
                                loaded = false
                                error = "Unexpected issued-order scope. Reload issuer authorization."
                            }
                        },
                        onAccessRevoked = { revokeSnapshot() },
                    )
                }
            },
            lifecycleContent = {
                if (cancellationEnabled) {
                    PurchaseOrderCancellationSection(
                        api = api,
                        organizationId = organizationId,
                        installationId = installationId,
                        newOperationId = newOperationId,
                    )
                }
                if (amendmentApi != null) {
                    PurchaseOrderDeliveryAmendmentSection(
                        issueApi = api,
                        api = amendmentApi,
                        organizationId = organizationId,
                        installationId = installationId,
                        newOperationId = newOperationId,
                    )
                }
            },
        )
    }
}

@Composable
private fun PurchaseOrderIssuerAction(
    api: PurchaseOrderIssueApiClient,
    candidate: PurchaseOrderIssuerQueueItemDto,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    savedOperation: IssuerPendingOperation?,
    onPendingOperation: (IssuerPendingOperation) -> Unit,
    onIssued: (PurchaseOrderIssueReceiptDto) -> Unit,
    onVerifiedIssuedOrder: (PurchaseOrderLifecycleSummaryDto) -> Unit,
    onAccessRevoked: () -> Unit,
) {
    val palette = LocalHiltechOperationalColors.current
    val scope = rememberCoroutineScope()
    var busy by remember(candidate.candidateId, installationId) { mutableStateOf(false) }
    var destinations by remember(candidate.candidateId, installationId) {
        mutableStateOf<List<PurchaseOrderDeliveryDestinationDto>>(emptyList())
    }
    var fetched by remember(candidate.candidateId, installationId) { mutableStateOf(false) }
    var reload by remember(candidate.candidateId, installationId) { mutableStateOf(0) }
    var selected by remember(candidate.candidateId, installationId) {
        mutableStateOf<String?>(savedOperation?.destinationId)
    }
    var menu by remember(candidate.candidateId, installationId) { mutableStateOf(false) }
    var reviewConfirmed by remember(candidate.candidateId, installationId) { mutableStateOf(false) }
    var error by remember(candidate.candidateId, installationId) { mutableStateOf<String?>(null) }
    var outcomeUncertain by remember(candidate.candidateId, installationId) {
        mutableStateOf(savedOperation != null)
    }

    // Recheck destinations on each actual selection and explicit refresh. The
    // existing pending operation stays immutable even if the list changes.
    LaunchedEffect(candidate.candidateId, installationId, reload) {
        busy = true
        fetched = false
        destinations = emptyList()
        reviewConfirmed = false
        error = null
        try {
            val fresh = api.destinations(candidate.candidateId, installationId)
            destinations = fresh
            fetched = true
            val lockedId = savedOperation?.destinationId
            if (lockedId != null) {
                selected = lockedId
            } else if (!issuerDestinationIsAuthorized(selected, fresh)) {
                selected = null
            }
        } catch (failure: Exception) {
            destinations = emptyList()
            fetched = false
            if (issuerShouldClearRestrictedQueue((failure as? HiltechApiException)?.httpStatus)) {
                onAccessRevoked()
            } else {
                error = "Cannot verify current authorized destinations. Retry lookup."
            }
        } finally {
            busy = false
        }
    }

    val target = destinations.firstOrNull { it.storageLocationId == selected }
    val lockedToDifferentTarget =
        savedOperation != null && target != null &&
            savedOperation.destinationId != target.storageLocationId

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HiltechSpace.small)) {
        HiltechSectionHeading(
            index = "03 / DELIVERY & ISSUE",
            title = "Exact authorized destination",
            subtitle = "Load source destinations before any internal issue command.",
        )
        OutlinedButton(
            enabled = !busy,
            onClick = { reload += 1 },
        ) {
            Text(if (busy) "Checking destinations..." else "Refresh valid destinations")
        }
        if (fetched && destinations.isEmpty()) {
            HiltechStateTag("No authorized delivery destination for this candidate",
                HiltechStateTone.BLOCKED)
        }
        if (destinations.isNotEmpty()) {
            OutlinedButton(
                enabled = !busy && savedOperation == null,
                onClick = { menu = true },
            ) {
                Text(target?.let { it.name + " / " + it.code }
                    ?: "Choose exact warehouse or site")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                destinations.forEach { destination ->
                    DropdownMenuItem(
                        text = { Text(destination.name + " / " + destination.code +
                            " (" + destination.kind + ")") },
                        onClick = {
                            if (savedOperation == null) {
                                selected = destination.storageLocationId
                                reviewConfirmed = false
                            }
                            menu = false
                        },
                    )
                }
            }
        }
        target?.let {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechKeyValue("Location", it.name)
                HiltechKeyValue("Code / type", it.code + " / " + it.kind)
                SelectionContainer {
                    HiltechKeyValue("Authorized destination ID", isolateSourceId(it.storageLocationId))
                }
            }
        }
        if (savedOperation != null) {
            HiltechStateTag(
                "Previous issue attempt retained. Destination is locked to its idempotency key.",
                HiltechStateTone.ATTENTION,
            )
            if (outcomeUncertain) {
                Text(
                    "The previous response was not confirmed. Verify the canonical issued-PO " +
                        "register here before retrying. An uncertain operation cannot " +
                        "change its destination or generate another ID.",
                    color = palette.cautionText, fontSize = 13.sp,
                )
            }
        }
        if (savedOperation != null || outcomeUncertain) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val issued = api.issuedOrders(organizationId, installationId)
                            val existing = findConfirmedIssuerOrder(issued, candidate.candidateId)
                            if (existing != null) {
                                outcomeUncertain = false
                                onVerifiedIssuedOrder(existing)
                            } else {
                                error = "The issued-order register has no confirmed record for " +
                                    "this candidate yet. Do not infer failure. " +
                                    "If retrying, use the same operation and destination."
                            }
                        } catch (failure: Exception) {
                            if (issuerShouldClearRestrictedQueue(
                                    (failure as? HiltechApiException)?.httpStatus
                                )
                            ) {
                                onAccessRevoked()
                            } else {
                                error = "Cannot verify issued-order register right now. " +
                                    "Do not create a fresh operation ID."
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Verify issued PO register") }
        }
        if (lockedToDifferentTarget || (savedOperation != null && target == null)) {
            HiltechStateTag(
                "The previous operation destination is no longer authorized. Do not retry.",
                HiltechStateTone.BLOCKED,
            )
        }
        if (target != null && !lockedToDifferentTarget) {
            HiltechStateTag(
                "Internal PO only: no supplier dispatch, stock receipt or payment",
                HiltechStateTone.NEUTRAL,
            )
            if (!reviewConfirmed) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { reviewConfirmed = true },
                ) { Text(if (savedOperation == null) "Review issuing this PO" else "Review same-operation retry") }
            } else {
                HiltechKeyValue(
                    "Final issue source",
                    candidate.supplierName + " / " + candidate.total + " " +
                        candidate.currency + " / " + target.code,
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        reviewConfirmed = false
                        error = null
                        scope.launch {
                            try {
                                val operation = issuerPendingOperation(
                                    existing = savedOperation,
                                    destinationId = target.storageLocationId,
                                    newOperationId = newOperationId,
                                )
                                onPendingOperation(operation)
                                val receipt = api.issue(
                                    candidate.candidateId,
                                    IssuePurchaseOrderDto(
                                        operationId = operation.operationId,
                                        expectedCandidateVersion = CANONICAL_ISSUER_CANDIDATE_VERSION,
                                        deliveryStorageLocationId = operation.destinationId,
                                    ),
                                    installationId,
                                )
                                outcomeUncertain = false
                                onIssued(receipt)
                            } catch (failure: Exception) {
                                if (issuerShouldClearRestrictedQueue(
                                        (failure as? HiltechApiException)?.httpStatus
                                    )
                                ) {
                                    onAccessRevoked()
                                } else {
                                    outcomeUncertain = true
                                    error = "Issue outcome unconfirmed. Verify issued orders. " +
                                        "If retrying, retain the same operation and destination."
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(if (busy) "Issuing..." else if (savedOperation != null)
                        "Retry same internal PO operation" else "Confirm internal PO issue")
                }
            }
        }
        error?.let { HiltechStateTag(it, HiltechStateTone.BLOCKED) }
    }
}
