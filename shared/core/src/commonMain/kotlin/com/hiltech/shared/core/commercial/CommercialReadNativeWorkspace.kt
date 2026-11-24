package com.hiltech.shared.core.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.hiltech.shared.core.CommercialDealAction
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.HiltechRecordChoice
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.CommercialDealWorkspaceSection
import com.hiltech.shared.core.CommercialDealWorkspaceState
import com.hiltech.shared.core.CommercialOpportunityTile
import com.hiltech.shared.core.CommercialWorkbenchSection
import com.hiltech.shared.core.CommercialWorkbenchState
import com.hiltech.shared.core.NativeOperationalWorkspace
import kotlinx.coroutines.CancellationException

/**
 * Phase 10 native workbench. Capabilities come from current company authority;
 * every mutation is still independently authorized by the server.
 */
@OptIn(ExperimentalEncodingApi::class)
fun commercialReadNativeWorkspace(
    client: CommercialApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
    newOperationId: (() -> String)? = null,
    exportPdf: (suspend (String, ByteArray) -> Boolean)? = null,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "commercial-read",
    label = "المبيعات والمناقصات",
    canReadSource = {
        client.opportunities(organizationId, installationId)
        true
    },
    hostOwnsScroll = false,
    content = { _ ->
        val jobs = rememberCoroutineScope()
        val coordinator = remember(client, installationId) { CommercialDealCoordinator(client, installationId) }
        var capabilities by remember(identityId, organizationId, installationId) { mutableStateOf<CommercialCapabilitiesDto?>(null) }
        var clients by remember(identityId, organizationId, installationId) { mutableStateOf<List<CommercialClientRefDto>>(emptyList()) }
        var costs by remember(identityId, organizationId, installationId) { mutableStateOf<List<CommercialPrivateCostLineDto>>(emptyList()) }
        var summaries by remember(identityId, organizationId, installationId) { mutableStateOf<List<CommercialPrivateCostSummaryDto>>(emptyList()) }
        var saving by remember(identityId, organizationId, installationId) { mutableStateOf(false) }
        val retryIds = remember(identityId, organizationId, installationId) { mutableMapOf<Any, String>() }
        var customerQuote by remember(identityId, organizationId, installationId) { mutableStateOf<ClientQuoteDto?>(null) }
        var dispatchHistory by remember(identityId, organizationId, installationId) { mutableStateOf<List<CommercialQuoteDispatchDto>>(emptyList()) }
        var quoteBusy by remember(identityId, organizationId, installationId) { mutableStateOf(false) }
        var feedback by remember(identityId, organizationId, installationId) { mutableStateOf<String?>(null) }
        var items by remember(identityId, organizationId, installationId) {
            mutableStateOf<List<CommercialOpportunityDto>>(emptyList())
        }
        var selectedId by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }
        var detail by remember(identityId, organizationId, installationId) {
            mutableStateOf<CommercialDealDetailDto?>(null)
        }
        var refreshVersion by remember(identityId, organizationId, installationId) {
            mutableIntStateOf(0)
        }
        var listLoading by remember(identityId, organizationId, installationId) {
            mutableStateOf(true)
        }
        var detailLoading by remember(identityId, organizationId, installationId) {
            mutableStateOf(false)
        }
        var error by remember(identityId, organizationId, installationId) {
            mutableStateOf<String?>(null)
        }

        fun perform(key: Any, command: suspend (String) -> CommercialCommandResultDto) {
            val generator = newOperationId ?: return
            if (saving || listLoading || detailLoading) return
            saving = true
            feedback = null
            jobs.launch {
                try {
                    val operation = retryIds.getOrPut(key) { generator() }
                    val result = command(operation)
                    retryIds.remove(key)
                    feedback = result.state
                    refreshVersion++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    feedback = "COMMAND_NOT_CONFIRMED"
                } finally {
                    saving = false
                }
            }
        }
        LaunchedEffect(client, identityId, organizationId, installationId, refreshVersion) {
            listLoading = true
            items = emptyList()
            capabilities = null
            clients = emptyList()
            detail = null
            error = null
            try {
                items = client.opportunities(organizationId, installationId)
                if (newOperationId != null) capabilities = client.capabilities(organizationId, installationId)
                if (capabilities?.canWrite == true) clients = client.clients(organizationId, installationId)
                // An old source selection is invalid when the current list
                // no longer authorizes/contains that opportunity.
                if (selectedId !in items.map { it.id }) selectedId = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selectedId = null
                error = "COMMERCIAL_SOURCE_DENIED_OR_UNAVAILABLE"
            } finally {
                listLoading = false
            }
        }

        LaunchedEffect(client, identityId, organizationId, installationId, selectedId, refreshVersion, items, capabilities) {
            val id = selectedId ?: return@LaunchedEffect
            if (id !in items.map { it.id }) return@LaunchedEffect
            detailLoading = true
            detail = null
            customerQuote = null
            dispatchHistory = emptyList()
            costs = emptyList()
            summaries = emptyList()
            try {
                detail = client.detail(id, installationId)
                    .takeIf { it.opportunity.id == id }
                if (detail == null) error = "COMMERCIAL_DETAIL_SCOPE_CHANGED"
                else if (capabilities?.canCost == true) {
                    costs = coordinator.loadPrivateCosting(id)
                    summaries = coordinator.loadPrivateCostSummary(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "COMMERCIAL_DETAIL_DENIED_OR_UNAVAILABLE"
            } finally {
                detailLoading = false
            }
        }

        val canWrite = !saving && capabilities?.canWrite == true
        val canRelease = !saving && capabilities?.canRelease == true
        val canAward = !saving && capabilities?.canAward == true
        val message = feedback?.let { if (it == "COMMAND_NOT_CONFIRMED") hiltechText("لم يتم تأكيد العملية. أعد المحاولة بنفس البيانات.", "Action not confirmed. Retry with the same values.") else if (it == "PDF_EXPORT_HANDOFF") hiltechText("تم تسليم ملف PDF لنافذة الحفظ أو المشاركة. لم يُسجّل إرسال تلقائي.", "PDF handed to save/share. No automatic dispatch was recorded.") else hiltechText("حالة العملية: ", "Operation state: ") + it }
        if (selectedId == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                message?.let { Text(it) }
                CommercialWorkbenchSection(
                    state = CommercialWorkbenchState(
                        enabled = true,
                        loading = listLoading || saving,
                        canWrite = canWrite,
                        canRelease = canRelease,
                        opportunities = items.map { item ->
                            CommercialOpportunityTile(
                                id = item.id,
                                title = item.title,
                                state = item.state,
                                source = item.source,
                                clientOrganizationId = item.clientOrganizationId,
                                deadline = item.expectedDecisionDate,
                                clientDisplayName = item.clientDisplayName,
                            )
                        },
                        errorCode = error,
                        clientChoices = clients.map { HiltechRecordChoice(it.id, it.displayName, it.organizationCode) },
                    ),
                    onRefresh = { refreshVersion++ },
                    onSelect = { requested ->
                        if (!listLoading && items.any { it.id == requested }) {
                            selectedId = requested
                        }
                    },
                    onCreate = { title, customer, source ->
                        if (canWrite) perform(listOf("create", title, customer, source)) { id ->
                            coordinator.createOpportunity(CreateCommercialOpportunityDto(id, organizationId, customer, title, source))
                        }
                    },
                    onPlanVisit = { opportunity, purpose, date ->
                        if (canWrite) perform(listOf("visit", opportunity, purpose, date)) { id ->
                            client.visit(opportunity, PlanCommercialVisitDto(id, date, purpose), installationId)
                        }
                    },
                    onRequestQuoteApproval = { quote -> if (canWrite) perform("approval:$quote") { client.requestApproval(quote, it, installationId) } },
                    onReleaseApprovedQuote = { quote -> if (canRelease) perform("release:$quote") { client.releaseApprovedQuote(quote, it, installationId) } },
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message?.let { Text(it) }
                OutlinedButton(enabled = !saving, onClick = { selectedId = null; detail = null }) {
                    Text(hiltechText("الرجوع إلى الفرص", "Back to opportunities"))
                }
                Box(Modifier.weight(1f)) {
                    CommercialDealWorkspaceSection(
                        state = CommercialDealWorkspaceState(
                            enabled = true,
                            loading = detailLoading || saving,
                            canWrite = canWrite,
                            canCost = capabilities?.canCost == true,
                            canRelease = canRelease,
                            canAward = canAward,
                            canCreateProject = canAward,
                            privateCostLines = costs,
                            privateCostSummaries = summaries,
                            deal = detail,
                            errorCode = error,
                            customerQuote = customerQuote,
                            quoteBusy = quoteBusy,
                            dispatchHistory = dispatchHistory,
                        ),
                        onRefresh = { refreshVersion++ },
                        onAction = { action -> perform(action) { coordinator.dispatch(action, it) } },
                        onPreviewQuote = { quoteId ->
                            if (!quoteBusy && detail?.quotes?.any { it.id == quoteId && it.state == "RELEASED" } == true) {
                                quoteBusy = true
                                customerQuote = null
                                dispatchHistory = emptyList()
                                jobs.launch {
                                    try {
                                        val loaded = client.customerQuote(quoteId, installationId)
                                        val history = client.dispatchHistory(quoteId, installationId)
                                        if (loaded.quoteId == quoteId && detail?.quotes?.any { it.id == quoteId && it.state == "RELEASED" } == true) {
                                            customerQuote = loaded
                                            dispatchHistory = history
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "QUOTE_PREVIEW_UNAVAILABLE" }
                                    finally { quoteBusy = false }
                                }
                            }
                        },
                        onRecordDispatch = { quoteId, channel, recipient ->
                            if ((canWrite || canRelease) && customerQuote?.quoteId == quoteId) {
                                perform(listOf("dispatch", quoteId, channel, recipient)) { operation ->
                                    client.recordDispatch(quoteId, RecordCommercialQuoteDispatchDto(operation, channel, recipient), installationId)
                                }
                            }
                        },
                        onExportQuote = exportPdf?.let { exporter -> { quoteId: String ->
                            if (!quoteBusy && customerQuote?.quoteId == quoteId &&
                                detail?.quotes?.any { it.id == quoteId && it.state == "RELEASED" } == true) {
                                quoteBusy = true
                                jobs.launch {
                                    try {
                                        val pdf = client.customerPdf(quoteId, installationId)
                                        val bytes = Base64.decode(pdf.base64)
                                        require(bytes.size > 5 && bytes.copyOfRange(0, 5).decodeToString() == "%PDF-")
                                        if (customerQuote?.quoteId == quoteId && detail?.quotes?.any { it.id == quoteId && it.state == "RELEASED" } == true) {
                                            if (exporter(pdf.filename, bytes)) feedback = "PDF_EXPORT_HANDOFF"
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "QUOTE_EXPORT_UNAVAILABLE" }
                                    finally { quoteBusy = false }
                                }
                            }
                        } },
                    )
                }
            }
        }
    },
)
