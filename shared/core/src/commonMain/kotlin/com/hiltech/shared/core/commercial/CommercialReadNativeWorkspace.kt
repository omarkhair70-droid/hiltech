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
 * Phase 10 native read/source integration. Uses the real Sales/Tenders
 * workbench and deal-detail components, but deliberately does not expose
 * client sending, approval, award, costing or project conversion.
 *
 * Mutations belong to the domain owner + independently authorized company
 * policy; an explicit native read-only lane cannot silently grant them.
 */
fun commercialReadNativeWorkspace(
    client: CommercialApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "commercial-read",
    label = "المبيعات والمناقصات",
    canReadSource = {
        client.opportunities(organizationId, installationId)
        true
    },
    hostOwnsScroll = false,
    content = { _ ->
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

        LaunchedEffect(client, identityId, organizationId, installationId, refreshVersion) {
            listLoading = true
            items = emptyList()
            detail = null
            error = null
            try {
                items = client.opportunities(organizationId, installationId)
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

        LaunchedEffect(client, identityId, organizationId, installationId, selectedId, refreshVersion) {
            val id = selectedId ?: return@LaunchedEffect
            if (id !in items.map { it.id }) return@LaunchedEffect
            detailLoading = true
            detail = null
            try {
                detail = client.detail(id, installationId)
                    .takeIf { it.opportunity.id == id }
                if (detail == null) error = "COMMERCIAL_DETAIL_SCOPE_CHANGED"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "COMMERCIAL_DETAIL_DENIED_OR_UNAVAILABLE"
            } finally {
                detailLoading = false
            }
        }

        if (selectedId == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CommercialWorkbenchSection(
                    state = CommercialWorkbenchState(
                        enabled = true,
                        loading = listLoading,
                        canWrite = false,
                        canRelease = false,
                        opportunities = items.map { item ->
                            CommercialOpportunityTile(
                                id = item.id,
                                title = item.title,
                                state = item.state,
                                source = item.source,
                                clientOrganizationId = item.clientOrganizationId,
                                deadline = item.expectedDecisionDate,
                            )
                        },
                        errorCode = error,
                    ),
                    onRefresh = { refreshVersion++ },
                    onSelect = { requested ->
                        if (!listLoading && items.any { it.id == requested }) {
                            selectedId = requested
                        }
                    },
                    // No mutation handlers in the read-only native lane.
                    onCreate = { _, _, _ -> },
                    onPlanVisit = { _, _, _ -> },
                    onRequestQuoteApproval = {},
                    onReleaseApprovedQuote = {},
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { selectedId = null; detail = null }) {
                    Text("الرجوع إلى الفرص")
                }
                Box(Modifier.weight(1f)) {
                    CommercialDealWorkspaceSection(
                        state = CommercialDealWorkspaceState(
                            enabled = true,
                            loading = detailLoading,
                            canWrite = false,
                            canCost = false,
                            canRelease = false,
                            canAward = false,
                            canCreateProject = false,
                            deal = detail,
                            errorCode = error,
                        ),
                        onRefresh = { refreshVersion++ },
                        onAction = { error = "COMMERCIAL_ACTION_NOT_ENABLED_IN_READ_ONLY_LANE" },
                    )
                }
            }
        }
    },
)
