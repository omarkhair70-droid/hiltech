package com.hiltech.shared.core.procurement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechSpace
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.design.procurementSourceTone
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.launch

/**
 * PRODUCTION-MOUNTED native Windows buyer experience.
 *
 * Source: SupplierSourcingApiClient (typed, scoped, current-server checks).
 * No synthetic objects, no hard-coded supplier identity or purchase authority.
 * Existing quote-document upload and PO-candidate actions remain intact.
 * The host and server feature gates continue to be OFF by default.
 */
@Composable
fun SupplierSourcingBuyerSection(
    api: SupplierSourcingApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    quoteDocumentUploader: SupplierQuoteDocumentUploader? = null,
    pickQuoteFile: suspend () -> SupplierQuotePickedFile? = { null },
    purchaseCandidateApi: PurchaseOrderCandidateApiClient? = null,
    purchaseApprovalRequestEnabled: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var reloadKey by remember(organizationId, installationId) { mutableStateOf(0) }
    var fetching by remember(organizationId, installationId) { mutableStateOf(true) }
    var busy by remember(organizationId, installationId) { mutableStateOf(false) }
    var loaded by remember(organizationId, installationId) { mutableStateOf(false) }
    var needs by remember(organizationId, installationId) {
        mutableStateOf<List<SourcingEligibleNeedDto>>(emptyList())
    }
    var suppliers by remember(organizationId, installationId) {
        mutableStateOf<List<SourcingSupplierCandidateDto>>(emptyList())
    }
    var rounds by remember(organizationId, installationId) {
        mutableStateOf<List<SupplierRfqDto>>(emptyList())
    }
    var selectedRequirementId by remember(organizationId, installationId) {
        mutableStateOf<String?>(null)
    }
    var selectedRfqId by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var quoteComparison by remember(organizationId, installationId) {
        mutableStateOf<SupplierRfqComparisonDto?>(null)
    }
    var selectedQuoteId by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var pendingOpen by remember(organizationId, installationId) {
        mutableStateOf<Pair<String, String>?>(null)
    }
    var showCapture by remember(organizationId, installationId) { mutableStateOf(false) }
    var error by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var message by remember(organizationId, installationId) { mutableStateOf<String?>(null) }

    // Native entrypoint now loads automatically. Scoped data is cleared BEFORE
    // the new request; denied/revoked identity never keeps another scope's quotes.
    LaunchedEffect(organizationId, installationId, reloadKey) {
        fetching = true
        loaded = false
        error = null
        message = null
        needs = emptyList()
        suppliers = emptyList()
        rounds = emptyList()
        selectedRequirementId = null
        selectedRfqId = null
        selectedQuoteId = null
        quoteComparison = null
        showCapture = false
        try {
            val authorizedNeeds = api.eligibleNeeds(organizationId, installationId)
            val linkedSuppliers = api.eligibleSuppliers(organizationId, installationId)
            needs = authorizedNeeds
            suppliers = linkedSuppliers
            loaded = true
        } catch (failure: Exception) {
            needs = emptyList()
            suppliers = emptyList()
            error = "Cannot load authorized sourcing. Verify your access and connection; retry."
        } finally {
            fetching = false
        }
    }

    fun clearRestrictedSnapshot() {
        loaded = false
        needs = emptyList()
        suppliers = emptyList()
        rounds = emptyList()
        selectedRequirementId = null
        selectedRfqId = null
        selectedQuoteId = null
        quoteComparison = null
        showCapture = false
    }

    fun viewRounds(need: SourcingEligibleNeedDto) {
        if (busy || fetching) return
        selectedRequirementId = need.requirementId
        selectedRfqId = null
        selectedQuoteId = null
        quoteComparison = null
        rounds = emptyList()
        showCapture = false
        busy = true
        error = null
        scope.launch {
            try {
                rounds = api.roundsForRequirement(need.requirementId, installationId)
            } catch (failure: Exception) {
                rounds = emptyList()
                if (shouldDiscardBuyerSnapshot((failure as? HiltechApiException)?.httpStatus)) {
                    clearRestrictedSnapshot()
                }
                error = "Sourcing rounds unavailable for the current identity or source."
            } finally {
                busy = false
            }
        }
    }

    fun openInternalRfq(need: SourcingEligibleNeedDto) {
        if (!canOfferNewRfq(need, busy || fetching)) return
        busy = true
        error = null
        scope.launch {
            var serverConfirmedCreation = false
            try {
                // Keep operationId on uncertain outcomes. Once the server confirms,
                // prevent a second draft even if the subsequent read request fails.
                val op = pendingOpen?.takeIf { it.first == need.requirementId }?.second
                    ?: newOperationId().also { pendingOpen = need.requirementId to it }
                api.openRfq(
                    need.requirementId,
                    OpenSupplierRfqDto(op, need.technicalVersion),
                    installationId,
                )
                serverConfirmedCreation = true
                pendingOpen = null
                needs = needsAfterConfirmedRfq(needs, need.requirementId)
                message = "Internal RFQ created; no supplier message or purchase order was issued."
                selectedRequirementId = need.requirementId
                selectedRfqId = null
                quoteComparison = null
                selectedQuoteId = null
                rounds = api.roundsForRequirement(need.requirementId, installationId)
                needs = api.eligibleNeeds(organizationId, installationId)
            } catch (failure: Exception) {
                if (shouldDiscardBuyerSnapshot((failure as? HiltechApiException)?.httpStatus)) {
                    clearRestrictedSnapshot()
                }
                error = if (serverConfirmedCreation) {
                    "RFQ was created on the server, but the refreshed list is unavailable. Reload authorized source."
                } else {
                    "RFQ creation outcome is uncertain. Retry with the same operation identifier."
                }
            } finally {
                busy = false
            }
        }
    }

    fun refreshAfterRecordedQuote(receipt: SupplierQuoteReceiptDto) {
        // The server receipt is authoritative; never keep the prior comparison
        // visible after a source mutation, including an idempotent replay.
        if (receipt.rfqId != selectedRfqId) {
            quoteComparison = null
            selectedQuoteId = null
            showCapture = false
            error = "Quote recorded, but the active RFQ changed. Refresh authorized source."
            return
        }
        val requirementId = selectedRequirementId
        quoteComparison = null
        selectedQuoteId = null
        showCapture = false
        busy = true
        error = null
        message = "Quotation revision ${receipt.revisionNumber} recorded. Refreshing live evidence."
        scope.launch {
            try {
                if (requirementId != null) {
                    rounds = api.roundsForRequirement(requirementId, installationId)
                }
                val fresh = api.comparison(receipt.rfqId, installationId)
                if (fresh.rfq.id != receipt.rfqId) {
                    error = "Quote saved, but comparison source did not match. Refresh authorization."
                    return@launch
                }
                quoteComparison = fresh
                when {
                    !fresh.rfq.sourceCurrent -> {
                        message = "Quotation saved, but the RFQ source changed. Evidence is historical; refresh before another action."
                    }
                    isRecordedQuoteVisibleInFreshComparison(receipt, fresh) -> {
                        selectedQuoteId = receipt.quoteId
                        message = "Quotation revision ${receipt.revisionNumber} saved and verified in the live comparison. No PO issued."
                    }
                    else -> {
                        message = "Quotation revision ${receipt.revisionNumber} saved; latest comparison is still catching up. Refresh to verify."
                    }
                }
            } catch (failure: Exception) {
                if (shouldDiscardBuyerSnapshot((failure as? HiltechApiException)?.httpStatus)) {
                    clearRestrictedSnapshot()
                }
                quoteComparison = null
                error = "Quote recorded, but the refreshed comparison could not be verified. Reload authorized source."
            } finally {
                busy = false
            }
        }
    }

    fun compareRfq(rfq: SupplierRfqDto) {
        if (busy || fetching) return
        busy = true
        error = null
        selectedRfqId = rfq.id
        quoteComparison = null
        selectedQuoteId = null
        showCapture = false
        scope.launch {
            try {
                quoteComparison = api.comparison(rfq.id, installationId)
            } catch (failure: Exception) {
                quoteComparison = null
                selectedQuoteId = null
                if (shouldDiscardBuyerSnapshot((failure as? HiltechApiException)?.httpStatus)) {
                    clearRestrictedSnapshot()
                }
                error = "Comparison unavailable. Your scope or the source may have changed."
            } finally {
                busy = false
            }
        }
    }

    // This UI is the SAME function mounted by apps/desktopApp Main.kt behind
    // authenticated identity and the original buyer-only feature gates.
    HiltechOperationalTheme {
        BuyerSourcingLiveScreen(
            ui = BuyerSourcingUi(
                organizationId = organizationId,
                needs = needs,
                suppliers = suppliers,
                rounds = rounds,
                selectedRequirementId = selectedRequirementId,
                selectedRfqId = selectedRfqId,
                comparison = quoteComparison,
                selectedQuoteId = selectedQuoteId,
                fetching = fetching,
                busy = busy,
                loaded = loaded,
                error = error,
                message = message,
                showCapture = showCapture,
            ),
            onRefresh = { if (!busy && !fetching) reloadKey++ },
            onSelectRequirement = ::viewRounds,
            onOpenRfq = ::openInternalRfq,
            onCompareRfq = ::compareRfq,
            onSelectQuote = { selectedQuoteId = it },
            onToggleCapture = { showCapture = !showCapture },
            quoteCaptureContent = { rfq ->
                if (quoteDocumentUploader != null && suppliers.isNotEmpty()) {
                    SupplierQuoteCaptureForm(
                        api = api,
                        uploader = quoteDocumentUploader,
                        pickFile = pickQuoteFile,
                        rfq = rfq,
                        suppliers = suppliers,
                        installationId = installationId,
                        newOperationId = newOperationId,
                        onQuoteRecorded = ::refreshAfterRecordedQuote,
                    )
                }
            },
            purchaseCandidateContent = { quote ->
                val current = quoteComparison
                if (canDisplayCandidatePreflight(
                        current, quote.quoteId, purchaseCandidateApi != null,
                    )
                ) {
                    val safeApi = purchaseCandidateApi
                    if (safeApi != null && current != null) {
                        PurchaseOrderCandidateAction(
                            api = safeApi,
                            rfqId = current.rfq.id,
                            quote = quote,
                            installationId = installationId,
                            newOperationId = newOperationId,
                            approvalRequestEnabled = purchaseApprovalRequestEnabled,
                        )
                    }
                }
            },
            canCapture = quoteDocumentUploader != null && suppliers.isNotEmpty(),
        )
    }
}

data class BuyerSourcingUi(
    val organizationId: String,
    val needs: List<SourcingEligibleNeedDto>,
    val suppliers: List<SourcingSupplierCandidateDto>,
    val rounds: List<SupplierRfqDto>,
    val selectedRequirementId: String?,
    val selectedRfqId: String?,
    val comparison: SupplierRfqComparisonDto?,
    val selectedQuoteId: String?,
    val fetching: Boolean,
    val busy: Boolean,
    val loaded: Boolean,
    val error: String?,
    val message: String?,
    val showCapture: Boolean,
)

/**
 * The UI below is not an unmounted concept screen. The entrypoint above
 * supplies its actual authenticated, typed API results and wired commands.
 * The same body can also be rendered with synthetic DTOs for repeatable QA.
 */
@Composable
fun BuyerSourcingLiveScreen(
    ui: BuyerSourcingUi,
    onRefresh: () -> Unit,
    onSelectRequirement: (SourcingEligibleNeedDto) -> Unit,
    onOpenRfq: (SourcingEligibleNeedDto) -> Unit,
    onCompareRfq: (SupplierRfqDto) -> Unit,
    onSelectQuote: (String) -> Unit,
    onToggleCapture: () -> Unit,
    quoteCaptureContent: @Composable (SupplierRfqDto) -> Unit,
    purchaseCandidateContent: @Composable (SupplierQuoteComparisonRowDto) -> Unit,
    canCapture: Boolean,
) {
    val palette = LocalHiltechOperationalColors.current
    var search by remember(ui.organizationId) { mutableStateOf("") }
    val filtered = ui.needs.filter { need ->
        search.isBlank() ||
            need.requirementId.contains(search.trim(), ignoreCase = true) ||
            need.stockItemId.contains(search.trim(), ignoreCase = true) ||
            need.unitCode.contains(search.trim(), ignoreCase = true)
    }
    val selectedNeed = ui.needs.firstOrNull { it.requirementId == ui.selectedRequirementId }
    val selectedRound = ui.rounds.firstOrNull { it.id == ui.selectedRfqId }

    Surface(modifier = Modifier.fillMaxWidth(), color = palette.canvas) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(HiltechSpace.large),
            verticalArrangement = Arrangement.spacedBy(HiltechSpace.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    HiltechSectionHeading(
                        index = "HILTECH / PROCUREMENT / BUYER",
                        title = "Supplier sourcing",
                        subtitle = "Work-source demand → internal RFQ → verified quotations → human review",
                    )
                    Text(
                        "Company-authorized procurement only. Comparison and a quote capture do not issue a PO.",
                        color = palette.secondaryText, fontSize = 13.sp,
                    )
                }
                OutlinedButton(onClick = onRefresh, enabled = !ui.fetching && !ui.busy) {
                    Text(if (ui.fetching) "Loading…" else "Refresh source")
                }
            }
            if (ui.fetching) HiltechStateTag("Loading authorized company data", HiltechStateTone.NEUTRAL)
            ui.error?.let { HiltechStateTag(it, HiltechStateTone.BLOCKED) }
            ui.message?.let { HiltechStateTag(it, HiltechStateTone.CURRENT) }
            if (ui.loaded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small),
                ) {
                    HiltechKeyValue("Technical needs in authorized response", ui.needs.size.toString(),
                        Modifier.weight(1f))
                    HiltechKeyValue("Linked suppliers in authorized response", ui.suppliers.size.toString(),
                        Modifier.weight(1f))
                    HiltechKeyValue("Purchase orders issued by this surface", "None",
                        Modifier.weight(1f))
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 950.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(HiltechSpace.medium),
                    ) {
                        Column(Modifier.weight(.34f)) {
                            BuyerRequirementQueue(
                                ui, filtered, search, { search = it },
                                onSelectRequirement, onOpenRfq,
                            )
                        }
                        Column(Modifier.weight(.66f)) {
                            BuyerRequirementDetail(
                                ui, selectedNeed, selectedRound,
                                onCompareRfq, onSelectQuote, onToggleCapture,
                                quoteCaptureContent, purchaseCandidateContent, canCapture, wide,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(HiltechSpace.medium),
                    ) {
                        BuyerRequirementQueue(
                            ui, filtered, search, { search = it },
                            onSelectRequirement, onOpenRfq,
                        )
                        BuyerRequirementDetail(
                            ui, selectedNeed, selectedRound,
                            onCompareRfq, onSelectQuote, onToggleCapture,
                            quoteCaptureContent, purchaseCandidateContent, canCapture, wide,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyerRequirementQueue(
    ui: BuyerSourcingUi,
    filtered: List<SourcingEligibleNeedDto>,
    search: String,
    onSearch: (String) -> Unit,
    onSelectRequirement: (SourcingEligibleNeedDto) -> Unit,
    onOpenRfq: (SourcingEligibleNeedDto) -> Unit,
) {
    val palette = LocalHiltechOperationalColors.current
    var requestedPage by remember(ui.organizationId, search) { mutableStateOf(0) }
    val window = buyerListWindow(filtered.size, requestedPage, 10)
    val visibleNeeds = filtered.subList(window.start, window.endExclusive)
    HiltechPanel(Modifier.fillMaxWidth()) {
        HiltechSectionHeading(
            index = "01 / DEMAND",
            title = "Needs awaiting sourcing",
            subtitle = "Authorized WorkOrder needs, including sources requiring refresh.",
        )
        OutlinedTextField(
            value = search, onValueChange = onSearch,
            label = { Text("Search authorized source IDs / unit") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            "Matching ${filtered.size} of ${ui.needs.size} authorized response items" +
                if (filtered.isNotEmpty()) " · displaying ${window.start + 1}–${window.endExclusive}" else "",
            color = palette.secondaryText, fontSize = 12.sp,
        )
        if (!ui.fetching && ui.loaded && ui.needs.isEmpty()) {
            Text("No technically ready needs were returned in this company scope.",
                color = palette.secondaryText)
        } else if (filtered.isEmpty() && search.isNotBlank()) {
            Text("No matching items in the currently loaded authorized response.",
                color = palette.secondaryText)
        }
        visibleNeeds.forEach { need ->
            val selected = need.requirementId == ui.selectedRequirementId
            val tone = procurementSourceTone(need.sourceCurrent)
            val shape = com.hiltech.shared.core.design.HiltechShape.small
            Surface(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = selected, role = Role.Button,
                    enabled = !ui.busy && !ui.fetching,
                    onClick = { onSelectRequirement(need) },
                ),
                color = if (selected) palette.selected else palette.surface,
                shape = shape,
                border = BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) palette.accent else palette.divider),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Required: ${need.requestedQuantity} ${need.unitCode}",
                        color = palette.primaryText, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    HiltechStateTag(
                        if (need.sourceCurrent) "Current work source" else "Source changed",
                        tone,
                    )
                    Text("StockItem · ${sourceReferencePreview(need.stockItemId)}",
                        color = palette.secondaryText, fontSize = 12.sp)
                    Text("Technical source v${need.technicalVersion} · " +
                        if (need.sourcingRoundExists) "RFQ exists" else "No RFQ recorded",
                        color = palette.secondaryText, fontSize = 12.sp)
                    if (selected) {
                        SelectionContainer {
                            Text("Requirement ID: ${need.requirementId}",
                                color = palette.secondaryText, fontSize = 11.sp)
                        }
                        if (canOfferNewRfq(need, ui.busy || ui.fetching)) {
                            Button(onClick = { onOpenRfq(need) }) {
                                Text("Open internal RFQ")
                            }
                        }
                    }
                }
            }
        }
        if (window.pageCount > 1) {
            BuyerPageNavigation(window, label = "Needs", onPage = { requestedPage = it })
        }
    }
}

@Composable
private fun BuyerRequirementDetail(
    ui: BuyerSourcingUi,
    need: SourcingEligibleNeedDto?,
    selectedRound: SupplierRfqDto?,
    onCompareRfq: (SupplierRfqDto) -> Unit,
    onSelectQuote: (String) -> Unit,
    onToggleCapture: () -> Unit,
    quoteCaptureContent: @Composable (SupplierRfqDto) -> Unit,
    purchaseCandidateContent: @Composable (SupplierQuoteComparisonRowDto) -> Unit,
    canCapture: Boolean,
    wide: Boolean,
) {
    val palette = LocalHiltechOperationalColors.current
    var showSourceIds by remember(need?.requirementId) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HiltechSpace.medium),
    ) {
        if (need == null) {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    "02 / SOURCE",
                    "Select a requirement",
                    "Open an actual authorized WorkOrder need to inspect its sourcing rounds.",
                )
                Text("Supplier sourcing is permission-bound; no global supplier or stock balances are inferred.",
                    color = palette.secondaryText, fontSize = 13.sp)
            }
            return@Column
        }
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("02 / CURRENT REQUIREMENT", color = palette.accent,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("${need.requestedQuantity} ${need.unitCode} · ${sourceTechnicalReviewCopy(need.technicalState)}",
                        color = palette.primaryText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Work source: ${sourceReferencePreview(need.requirementId)} · no purchase authorization",
                        color = palette.secondaryText, fontSize = 12.sp)
                }
                HiltechStateTag(
                    if (need.sourceCurrent) "Current" else "Changed source",
                    procurementSourceTone(need.sourceCurrent),
                )
            }
            OutlinedButton(onClick = { showSourceIds = !showSourceIds }) {
                Text(if (showSourceIds) "Hide original source identifiers" else "Inspect original source identifiers")
            }
            if (showSourceIds) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HiltechKeyValue("Requirement ID", isolateSourceId(need.requirementId))
                        HiltechKeyValue("StockItem ID", isolateSourceId(need.stockItemId))
                        HiltechKeyValue("Technical state (source)", need.technicalState)
                    }
                }
            }
        }
        HiltechPanel(Modifier.fillMaxWidth()) {
            HiltechSectionHeading(
                index = "03 / SUPPLIER SOURCING",
                title = "Internal RFQ rounds",
                subtitle = "All offers are restricted evidence until authorized company decisions.",
            )
            if (ui.busy) HiltechStateTag("Checking the latest source", HiltechStateTone.NEUTRAL)
            if (!ui.busy && ui.rounds.isEmpty()) {
                Text("No authorized sourcing rounds returned for this requirement.",
                    color = palette.secondaryText)
            }
            ui.rounds.forEach { rfq ->
                val selected = rfq.id == ui.selectedRfqId
                Surface(
                    color = if(selected) palette.selected else palette.surfaceElevated,
                    shape = com.hiltech.shared.core.design.HiltechShape.small,
                    border = BorderStroke(if(selected) 2.dp else 1.dp,
                        if(selected) palette.accent else palette.divider),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(HiltechSpace.xs)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("RFQ · ${sourceReferencePreview(rfq.id)}",
                                color = palette.primaryText, fontWeight = FontWeight.Bold)
                            HiltechStateTag(
                                if(rfq.sourceCurrent) "Current" else "Historical",
                                procurementSourceTone(rfq.sourceCurrent),
                            )
                        }
                        HiltechKeyValue("Captured requirement",
                            "${rfq.capturedQuantity} ${rfq.unitCode} · version ${rfq.capturedRequirementVersion}")
                        Text("${rfq.quoteCount} quotations recorded",
                            color = palette.secondaryText, fontSize = 12.sp)
                        OutlinedButton(onClick = { onCompareRfq(rfq) },
                            enabled = !ui.busy && !ui.fetching) {
                            Text(if(selected) "Refresh authorized comparison" else "Inspect quotations")
                        }
                    }
                }
            }
            if (selectedRound != null && canCapture && selectedRound.sourceCurrent) {
                OutlinedButton(onClick = onToggleCapture, enabled = !ui.busy && !ui.fetching) {
                    Text(if(ui.showCapture) "Close quotation capture" else "Record verified supplier quote")
                }
                if (ui.showCapture) quoteCaptureContent(selectedRound)
            }
        }
        val comparison = ui.comparison
        if (comparison != null && comparison.rfq.id == selectedRound?.id) {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    index = "04 / EVIDENCE & COMPARISON",
                    title = "Latest supplier quotations",
                    subtitle = "Compare captured versions; never infer award or a purchase order.",
                )
                val directlyComparable = comparisonTotalsAreComparable(comparison)
                HiltechStateTag(
                    when {
                        !comparison.rfq.sourceCurrent -> "Historical evidence only — source changed"
                        !directlyComparable -> "Totals not directly comparable — verify currency and source"
                        else -> "Same-source and directly comparable quotation totals"
                    },
                    when {
                        !comparison.rfq.sourceCurrent -> HiltechStateTone.BLOCKED
                        !directlyComparable -> HiltechStateTone.ATTENTION
                        else -> HiltechStateTone.CURRENT
                    },
                )
                Text("Evaluation snapshot: ${comparison.comparison.evaluatedAt}",
                    color = palette.secondaryText, fontSize = 12.sp)
                BuyerQuoteLedger(comparison, ui.suppliers, ui.selectedQuoteId, onSelectQuote, wide)
                val selectedQuote = comparison.comparison.latestBySupplier
                    .firstOrNull { it.quoteId == ui.selectedQuoteId }
                if (selectedQuote != null) {
                    HorizontalDivider(color = palette.divider)
                    BuyerQuoteInspector(selectedQuote, ui.suppliers)
                    if (comparison.rfq.sourceCurrent) {
                        // Wires the existing typed candidate preflight and approved
                        // company approval request WITHOUT inventing policy.
                        purchaseCandidateContent(selectedQuote)
                    } else {
                        Text("Purchase proposal is blocked: refresh the changed source.",
                            color = palette.blockedText, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyerQuoteLedger(
    result: SupplierRfqComparisonDto,
    suppliers: List<SourcingSupplierCandidateDto>,
    selectedQuoteId: String?,
    onSelectQuote: (String) -> Unit,
    wide: Boolean,
) {
    val palette = LocalHiltechOperationalColors.current
    val rows = result.comparison.latestBySupplier
    var requestedPage by remember(result.rfq.id) { mutableStateOf(0) }
    val window = buyerListWindow(rows.size, requestedPage, 15)
    if (rows.isEmpty()) {
        Text("No authorized supplier quotation evidence is available for this RFQ.",
            color = palette.secondaryText)
        return
    }
    Surface(color = palette.surfaceElevated,
        shape = com.hiltech.shared.core.design.HiltechShape.small) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SUPPLIER / EVIDENCE", Modifier.weight(1.3f),
                color = palette.primaryText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("TOTAL / CURRENCY", Modifier.weight(.75f),
                color = palette.primaryText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if(wide) Text("TECHNICAL / ISSUES", Modifier.weight(.95f),
                color = palette.primaryText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
    rows.subList(window.start, window.endExclusive).forEach { quote ->
        val selected = quote.quoteId == selectedQuoteId
        Surface(
            color = if(selected) palette.selected else palette.surface,
            border = BorderStroke(if(selected) 2.dp else 1.dp,
                if(selected) palette.accent else palette.divider),
            shape = com.hiltech.shared.core.design.HiltechShape.small,
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(supplierNameFor(quote.supplierOrganizationId, suppliers),
                            color = palette.primaryText, fontWeight = FontWeight.Bold,
                            fontSize = 14.sp)
                        Text("Quote ${quote.quoteReference} · revision ${quote.revisionNumber}",
                            color = palette.secondaryText, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(.75f)) {
                        Text("${quote.total} ${quote.currency}",
                            color = palette.primaryText, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                        Text(quote.deliveryDays?.let { "$it days" } ?: "Delivery not specified",
                            color = palette.secondaryText, fontSize = 12.sp)
                    }
                    if(wide) Column(Modifier.weight(.95f)) {
                        HiltechStateTag(technicalQuoteText(quote), technicalQuoteTone(quote))
                        if(quote.issues.isNotEmpty()) Text(
                            "${quote.issues.size} issue(s)", color = palette.cautionText,
                            fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = { onSelectQuote(quote.quoteId) }) {
                    Text(if(selected) "Viewing quote evidence" else "Inspect quote and available actions")
                }
            }
        }
    }
    if (window.pageCount > 1) {
        BuyerPageNavigation(window, label = "Quotes", onPage = { requestedPage = it })
    }
}

/** Common native paging controls. The entire authorized response stays available for scoped search. */
@Composable
private fun BuyerPageNavigation(
    window: BuyerListWindow,
    label: String,
    onPage: (Int) -> Unit,
) {
    val palette = LocalHiltechOperationalColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HiltechSpace.small),
    ) {
        OutlinedButton(
            onClick = { onPage(window.pageIndex - 1) },
            enabled = window.hasPrevious,
        ) { Text("Previous ${label.lowercase()}") }
        Text(
            "${label} page ${window.pageIndex + 1} / ${window.pageCount}",
            color = palette.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 13.dp),
        )
        OutlinedButton(
            onClick = { onPage(window.pageIndex + 1) },
            enabled = window.hasNext,
        ) { Text("Next ${label.lowercase()}") }
    }
}

@Composable
private fun BuyerQuoteInspector(
    quote: SupplierQuoteComparisonRowDto,
    suppliers: List<SourcingSupplierCandidateDto>,
) {
    val palette = LocalHiltechOperationalColors.current
    HiltechSectionHeading(
        index = "05 / INSPECTOR",
        title = supplierNameFor(quote.supplierOrganizationId, suppliers),
        subtitle = "Captured quotation facts, not an instruction to award.",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HiltechKeyValue("Quantity", "${quote.quantity} ${quote.unitCode}",
            Modifier.weight(1f))
        HiltechKeyValue("Unit price", "${quote.unitPrice} ${quote.currency}",
            Modifier.weight(1f))
        HiltechKeyValue("Tax", "${quote.taxAmount} ${quote.currency}",
            Modifier.weight(1f))
    }
    HiltechStateTag(technicalQuoteText(quote), technicalQuoteTone(quote))
    quote.issues.forEach { issue ->
        HiltechStateTag("Source issue: $issue", HiltechStateTone.ATTENTION)
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HiltechKeyValue("Supplier organization ID", isolateSourceId(quote.supplierOrganizationId))
            HiltechKeyValue("Quote evidence ID", isolateSourceId(quote.quoteId))
            HiltechKeyValue("Evidence reference", isolateSourceId(quote.evidenceRef))
            HiltechKeyValue("Evidence SHA-256", isolateSourceId(quote.evidenceSha256))
        }
    }
    Text("All evidence is subject to server permission and version checks. " +
        "Quote inspection never issues a purchase order.", color = palette.secondaryText,
        fontSize = 12.sp)
}
