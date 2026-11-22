package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.ResourceCatalogChoiceDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import com.hiltech.shared.core.work.WorkApiClient
import com.hiltech.shared.core.work.WorkOrderDto
import kotlinx.coroutines.launch

/**
 * Android and Windows share this same online-only operator surface.
 * It is mounted by host applications ONLY under their explicit OFF-by-default
 * UI flag. Server procurement permissions and server feature flag are final.
 *
 * No offline queue, supplier workflow, approval, PO, stock or money mutation.
 */
@Composable
fun PurchaseRequirementOperatorSection(
    api: PurchaseRequirementApiClient,
    warehouseApi: WarehouseApiClient,
    workApi: WorkApiClient,
    installationId: String,
    newOperationId: () -> String,
    workChoices: List<WorkOrderDto> = emptyList(),
    selectedWorkOrderId: String? = null,
    projectIntakeId: String? = null,
) {
    val scope = rememberCoroutineScope()
    var selectedWork by remember(installationId) {
        mutableStateOf(selectedWorkOrderId ?: workChoices.firstOrNull()?.workOrderId.orEmpty())
    }
    LaunchedEffect(selectedWorkOrderId) {
        if (!selectedWorkOrderId.isNullOrBlank()) selectedWork = selectedWorkOrderId
    }
    var expandedWorks by remember { mutableStateOf(false) }
    var expandedStock by remember { mutableStateOf(false) }
    var refreshed by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<WorkOrderDto?>(null) }
    var choices by remember { mutableStateOf<List<ResourceCatalogChoiceDto>>(emptyList()) }
    var drafts by remember { mutableStateOf<List<PurchaseRequirementDraftDto>>(emptyList()) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var selectedItem by remember { mutableStateOf<StockItemDto?>(null) }
    var quantity by remember { mutableStateOf("") }
    var justification by remember { mutableStateOf("") }
    var reviewingId by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PurchaseRequirementReviewPreviewDto?>(null) }
    var confirmWithdrawId by remember { mutableStateOf<String?>(null) }
    var pendingCreateOperation by remember { mutableStateOf<String?>(null) }
    var editId by remember { mutableStateOf<String?>(null) }
    var editQuantity by remember { mutableStateOf("") }
    var editJustification by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<StockItemDto?>(null) }
    var reviewId by remember { mutableStateOf<String?>(null) }
    var reviewReason by remember { mutableStateOf("") }
    var intake by remember { mutableStateOf<List<PurchaseRequirementDraftDto>>(emptyList()) }
    var intakeLoading by remember { mutableStateOf(false) }
    LaunchedEffect(projectIntakeId) {
        intake = emptyList()
        if (!projectIntakeId.isNullOrBlank() &&
            workChoices.isNotEmpty() && workChoices.none { it.workOrderId == selectedWork }
        ) selectedWork = workChoices.first().workOrderId
    }

    LaunchedEffect(selectedWork, refreshed) {
        // Clear all work-specific or commercially relevant data before a new load.
        work = null
        choices = emptyList()
        drafts = emptyList()
        selectedItemId = null
        selectedItem = null
        preview = null
        confirmWithdrawId = null
        editId = null
        reviewId = null
        error = null
        if (selectedWork.isBlank()) return@LaunchedEffect
        loading = true
        try {
            val current = workApi.get(selectedWork, installationId)
            val catalog = warehouseApi.workResourceCatalog(selectedWork, installationId)
            val currentDrafts = api.forWorkOrder(selectedWork, installationId)
            work = current
            choices = catalog.stockItems
            drafts = currentDrafts
        } catch (failure: Exception) {
            error = failure.message ?: "Cannot load purchase requirements. Check access or connection."
        } finally {
            loading = false
        }
    }
    LaunchedEffect(selectedItemId, selectedWork) {
        selectedItem = null
        val id = selectedItemId ?: return@LaunchedEffect
        try {
            val item = warehouseApi.stockItem(id, installationId)
            if (item.active && item.id == id && item.organizationId == work?.organizationId) {
                selectedItem = item
            } else {
                error = "The selected stock item is no longer available for this work order."
            }
        } catch (failure: Exception) {
            error = failure.message ?: "Cannot read the current stock item."
        }
    }

    LaunchedEffect(editId, selectedWork) {
        editItem = null
        val selectedDraft = drafts.firstOrNull { it.id == editId } ?: return@LaunchedEffect
        try {
            val item = warehouseApi.stockItem(selectedDraft.stockItemId, installationId)
            if (item.active && item.organizationId == work?.organizationId) editItem = item
            else error = "The stock item needs refreshing before this revision."
        } catch (failure: Exception) {
            error = failure.message ?: "Cannot refresh the item for revision."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Purchase needs", style = MaterialTheme.typography.titleLarge)
        Text(
            "Internal material demand. Review warehouse availability before buying; this does not reserve stock or authorize a purchase.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (workChoices.isNotEmpty()) {
            OutlinedButton(onClick = { expandedWorks = true }, enabled = !submitting) {
                Text(workChoices.firstOrNull { it.workOrderId == selectedWork }?.let { "${it.workOrderCode} · ${it.title}" }
                    ?: "Select a work order")
            }
            DropdownMenu(expanded = expandedWorks, onDismissRequest = { expandedWorks = false }) {
                workChoices.take(100).forEach { option ->
                    DropdownMenuItem(
                        text = { Text("${option.workOrderCode} · ${option.title}") },
                        onClick = {
                            selectedWork = option.workOrderId
                            expandedWorks = false
                            quantity = ""
                            justification = ""
                            pendingCreateOperation = null
                            message = null
                        },
                    )
                }
            }
        }
        if (!projectIntakeId.isNullOrBlank()) {
            OutlinedButton(
                enabled = !intakeLoading && !submitting,
                onClick = {
                    intakeLoading = true
                    error = null
                    scope.launch {
                        try {
                            intake = api.projectIntake(projectIntakeId, installationId)
                            if (intake.isEmpty()) message = "No submitted technical requests in the current project."
                        } catch (failure: Exception) {
                            intake = emptyList()
                            error = "Technical intake unavailable for this identity or project: " +
                                (failure.message ?: "access denied")
                        } finally {
                            intakeLoading = false
                        }
                    }
                },
            ) { Text(if (intakeLoading) "Loading intake…" else "Project technical intake") }
            intake.take(100).forEach { item ->
                val workLabel = workChoices.firstOrNull { it.workOrderId == item.workOrderId }?.let {
                    "${it.workOrderCode} · ${it.title}"
                } ?: "Work order"
                Card {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("$workLabel · ${item.requestedQuantity} ${item.unitCode} · ${item.state}")
                        Text(item.justification, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            if (workChoices.any { it.workOrderId == item.workOrderId }) {
                                selectedWork = item.workOrderId
                            } else {
                                error = "Refresh the project work orders to open this request."
                            }
                        }) { Text("Open in work order") }
                    }
                }
            }
        }
        if (selectedWork.isBlank()) {
            Text("Select a work order to begin.")
            return@Column
        }
        if (loading) Text("Loading current work, stock catalogue and requests…")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it) }
        OutlinedButton(onClick = { refreshed++ }, enabled = !loading && !submitting) { Text("Refresh requirements") }
        val currentWork = work
        if (currentWork != null) {
            Text("${currentWork.workOrderCode} · ${currentWork.title}", style = MaterialTheme.typography.titleMedium)
            Text("Work version: ${currentWork.version}", style = MaterialTheme.typography.bodySmall)
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New material need — draft", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { expandedStock = true },
                        enabled = !loading && !submitting && choices.isNotEmpty(),
                    ) {
                        Text(choices.firstOrNull { it.id == selectedItemId }?.label ?: "Choose stock item")
                    }
                    DropdownMenu(expanded = expandedStock, onDismissRequest = { expandedStock = false }) {
                        choices.take(100).forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                selectedItemId = choice.id
                                pendingCreateOperation = null
                                expandedStock = false
                            })
                        }
                    }
                    if (choices.size > 100) Text("Only the first 100 items are shown. Refine the warehouse catalogue to continue.")
                    selectedItem?.let { item ->
                        Text("${item.itemCode} · ${item.name} · Unit: ${item.baseUnitCode}")
                        OutlinedTextField(
                            value = quantity,
                            onValueChange = { quantity = it; pendingCreateOperation = null },
                            label = { Text("Required quantity (${item.baseUnitCode})") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = justification,
                            onValueChange = { justification = it; pendingCreateOperation = null },
                            label = { Text("Reason for this need") },
                            minLines = 2,
                        )
                        OutlinedButton(
                            enabled = !submitting && canCreatePurchaseDraft(quantity, justification, item.serialized),
                            onClick = {
                                val currentItem = selectedItem ?: return@OutlinedButton
                                val currentOrder = work ?: return@OutlinedButton
                                submitting = true
                                error = null
                                message = null
                                scope.launch {
                                    try {
                                        val request = CreatePurchaseRequirementDraftDto(
                                            operationId = pendingCreateOperation
                                                ?: newOperationId().also { pendingCreateOperation = it },
                                            baseWorkOrderVersion = currentOrder.version,
                                            stockItemId = currentItem.id,
                                            stockItemVersion = currentItem.version,
                                            quantity = quantity.trim(),
                                            unitCode = currentItem.baseUnitCode,
                                            justification = justification.trim(),
                                        )
                                        val result = api.create(selectedWork, request, installationId)
                                        message = "Draft saved (${result.draft.assessmentCode}). No purchase has been authorized."
                                        quantity = ""
                                        justification = ""
                                        pendingCreateOperation = null
                                        refreshed++
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Failed to create purchase requirement."
                                    } finally {
                                        submitting = false
                                    }
                                }
                            },
                        ) { Text(if (submitting) "Saving…" else "Save draft") }
                    }
                }
            }
            Text("Existing internal requests", style = MaterialTheme.typography.titleMedium)
            if (drafts.isEmpty()) Text("No purchase requirements for this work order.")
            drafts.take(25).forEach { draft ->
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val label = choices.firstOrNull { it.id == draft.stockItemId }?.label ?: draft.stockItemId
                        Text("$label · ${draft.requestedQuantity} ${draft.unitCode}")
                        Text("State: ${draft.state} · Version ${draft.version}")
                        Text("Original stock check: ${draft.assessmentCode} · ${draft.assessedAt}",
                            style = MaterialTheme.typography.bodySmall)
                        if (!draft.sourceCurrent) Text("Source changed or request no longer active — refresh before acting.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                reviewingId = draft.id
                                preview = null
                                error = null
                                scope.launch {
                                    try {
                                        preview = api.reviewPreview(draft.id, installationId)
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Cannot refresh warehouse review."
                                    } finally {
                                        reviewingId = null
                                    }
                                }
                            }, enabled = reviewingId == null && !submitting) {
                                Text(if (reviewingId == draft.id) "Checking…" else "Review live stock")
                            }
                            if (draft.canWithdraw) {
                                OutlinedButton(
                                    onClick = { confirmWithdrawId = if (confirmWithdrawId == draft.id) null else draft.id },
                                    enabled = !submitting,
                                ) { Text(if (confirmWithdrawId == draft.id) "Cancel withdrawal" else "Withdraw draft") }
                            }
                        }
                        draft.lastReviewReason?.let { note ->
                            Text("Technical review note: $note", style = MaterialTheme.typography.bodySmall)
                        }
                        if (draft.canSubmit) {
                            OutlinedButton(enabled = !submitting, onClick = {
                                submitting = true
                                error = null
                                scope.launch {
                                    try {
                                        val result = api.submit(
                                            draft.id, SubmitPurchaseRequirementDraftDto(newOperationId(), draft.version),
                                            installationId,
                                        )
                                        message = "Requirement submitted for project technical validation (${result.draft.state})."
                                        refreshed++
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Submission failed. Refresh source before retry."
                                    } finally { submitting = false }
                                }
                            }) { Text("Submit need for technical review") }
                        }
                        if (draft.canRevise) {
                            OutlinedButton(enabled = !submitting, onClick = {
                                editId = if (editId == draft.id) null else draft.id
                                editQuantity = draft.requestedQuantity
                                editJustification = draft.justification
                                error = null
                            }) { Text(if (editId == draft.id) "Cancel revision" else "Revise draft") }
                        }
                        if (editId == draft.id && draft.canRevise) {
                            val currentItem = editItem
                            if (currentItem == null) Text("Loading current item/version for revision…")
                            else {
                                Text("Revising ${currentItem.itemCode} — original history remains immutable.")
                                OutlinedTextField(
                                    value = editQuantity, onValueChange = { editQuantity = it },
                                    label = { Text("Revised quantity (${currentItem.baseUnitCode})") },
                                )
                                OutlinedTextField(
                                    value = editJustification, onValueChange = { editJustification = it },
                                    label = { Text("Revised need / technical explanation") },
                                    minLines = 2,
                                )
                                OutlinedButton(
                                    enabled = !submitting && canCreatePurchaseDraft(
                                        editQuantity, editJustification, currentItem.serialized,
                                    ),
                                    onClick = {
                                        submitting = true
                                        error = null
                                        scope.launch {
                                            try {
                                                api.revise(draft.id, RevisePurchaseRequirementDraftDto(
                                                    operationId = newOperationId(),
                                                    expectedVersion = draft.version,
                                                    baseWorkOrderVersion = currentWork.version,
                                                    stockItemVersion = currentItem.version,
                                                    quantity = editQuantity.trim(),
                                                    unitCode = currentItem.baseUnitCode,
                                                    justification = editJustification.trim(),
                                                ), installationId)
                                                editId = null
                                                message = "New draft revision recorded. Submit it again when ready."
                                                refreshed++
                                            } catch (failure: Exception) {
                                                error = failure.message ?: "Revision failed. Reload current versions."
                                            } finally { submitting = false }
                                        }
                                    },
                                ) { Text("Save revised version") }
                            }
                        }
                        if (draft.canTechnicalReview) {
                            OutlinedButton(enabled = !submitting, onClick = {
                                reviewId = if (reviewId == draft.id) null else draft.id
                                reviewReason = ""
                            }) { Text(if (reviewId == draft.id) "Close technical review" else "Technical review") }
                        }
                        if (reviewId == draft.id && draft.canTechnicalReview) {
                            Text("Project technical validation only — NOT approval of purchasing, supplier or PO.")
                            OutlinedTextField(
                                value = reviewReason, onValueChange = { reviewReason = it },
                                label = { Text("Technical review reason / questions") }, minLines = 2,
                            )
                            (if (draft.state == "SUBMITTED") listOf(
                                "READY_FOR_SOURCING" to "Technically ready for sourcing",
                                "NEEDS_INFO" to "Request more information",
                                "REJECTED" to "Reject this technical need",
                            ) else listOf(
                                "NEEDS_INFO" to "Return to requester for revision",
                                "REJECTED" to "Reject technical need before sourcing",
                            )).forEach { choice ->
                                OutlinedButton(
                                    enabled = !submitting && (choice.first == "READY_FOR_SOURCING" ||
                                        reviewReason.trim().isNotEmpty()),
                                    onClick = {
                                        submitting = true
                                        error = null
                                        scope.launch {
                                            try {
                                                api.technicalReview(draft.id, ReviewPurchaseRequirementDto(
                                                    newOperationId(), draft.version, choice.first,
                                                    reviewReason.trim().takeIf { it.isNotEmpty() },
                                                ), installationId)
                                                reviewId = null
                                                message = "Technical intake decision saved: ${choice.first}. Not a purchase authorization."
                                                refreshed++
                                            } catch (failure: Exception) {
                                                error = failure.message ?: "Technical review failed."
                                            } finally { submitting = false }
                                        }
                                    },
                                ) { Text(choice.second) }
                            }
                        }
                        if (confirmWithdrawId == draft.id && draft.canWithdraw) {
                            Text("Withdraw this draft? The original evidence will remain in history.")
                            OutlinedButton(enabled = !submitting, onClick = {
                                submitting = true
                                error = null
                                scope.launch {
                                    try {
                                        api.withdraw(
                                            draft.id,
                                            WithdrawPurchaseRequirementDraftDto(newOperationId(), draft.version, "Requester withdrew draft"),
                                            installationId,
                                        )
                                        message = "Request withdrawn; its history was retained."
                                        confirmWithdrawId = null
                                        refreshed++
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Cannot withdraw requirement."
                                    } finally {
                                        submitting = false
                                    }
                                }
                            }) { Text("Confirm withdrawal") }
                        }
                        preview?.takeIf { it.requirementId == draft.id }?.let { current ->
                            Text("Live review: ${current.assessmentCode}")
                            current.visibleFreeQuantity?.let { Text("Visible free stock now: $it ${draft.unitCode}") }
                            if (current.otherOpenDraftIds.isNotEmpty()) {
                                Text("Other open requests for this work and item: ${current.otherOpenDraftIds.size}" +
                                    if (current.moreOpenDrafts) " (more exist)" else "")
                            }
                            if (!current.sourceCurrent) Text("Source must be refreshed before a purchase decision.")
                            Text("Review only — no approval or purchasing permission granted.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (drafts.size > 25) Text("Only the newest 25 requests are shown.")
        }
    }
}

/** Matches server positive-decimal and serialized-stock integer guards. */
fun canCreatePurchaseDraft(quantity: String, justification: String, serialized: Boolean): Boolean {
    val trimmed = quantity.trim()
    if (!Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(trimmed)) return false
    if (trimmed.all { it == '0' || it == '.' }) return false
    if (serialized && trimmed.substringAfter('.', "").any { it != '0' }) return false
    val reason = justification.trim()
    return reason.isNotEmpty() && reason.length <= 500
}
