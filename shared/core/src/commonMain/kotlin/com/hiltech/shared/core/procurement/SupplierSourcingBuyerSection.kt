package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.launch

/**
 * Internal buyer entrypoint for Windows. Mounted behind separate default-OFF
 * UI/server flags and a fail-closed server buyer-authorization contract.
 * No supplier email, award, PO, invoice, payment, or offline purchase action.
 */
@Composable
fun SupplierSourcingBuyerSection(
    api: SupplierSourcingApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(organizationId) { mutableStateOf(false) }
    var needs by remember(organizationId) { mutableStateOf<List<SourcingEligibleNeedDto>>(emptyList()) }
    var rounds by remember(organizationId) { mutableStateOf<List<SupplierRfqDto>>(emptyList()) }
    var selectedRequirement by remember(organizationId) { mutableStateOf<String?>(null) }
    var comparingRfq by remember(organizationId) { mutableStateOf<String?>(null) }
    var comparison by remember(organizationId) { mutableStateOf<SupplierRfqComparisonDto?>(null) }
    var pendingOpen by remember(organizationId) { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember(organizationId) { mutableStateOf<String?>(null) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var epoch by remember(organizationId) { mutableStateOf(0) }

    LaunchedEffect(organizationId, epoch) {
        if (epoch == 0) return@LaunchedEffect
        loading = true
        error = null
        needs = emptyList()
        rounds = emptyList()
        comparison = null
        try {
            needs = api.eligibleNeeds(organizationId, installationId)
        } catch (failure: Exception) {
            error = failure.message ?: "Sourcing queue unavailable for this organization and buyer identity."
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Supplier sourcing", style = MaterialTheme.typography.titleLarge)
        Text(
            "Restricted buyer view. Technical readiness and quoted prices do not approve a supplier or purchase order.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { epoch++ }, enabled = !loading) {
            Text(if (loading) "Loading authorized needs…" else "Refresh eligible needs")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it) }
        if (epoch > 0 && needs.isEmpty() && !loading && error == null) {
            Text("No technically ready needs were returned for this organization.")
        }
        needs.take(100).forEach { need ->
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Stock item ${need.stockItemId.take(8)} · ${need.requestedQuantity} ${need.unitCode}")
                    Text("Technical requirement version ${need.technicalVersion}",
                        style = MaterialTheme.typography.bodySmall)
                    if (!need.sourceCurrent) Text("Source versions changed — no new RFQ permitted.")
                    if (!need.sourcingRoundExists && need.sourceCurrent) {
                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                loading = true
                                error = null
                                scope.launch {
                                    try {
                                        val operation = pendingOpen?.takeIf { it.first == need.requirementId }
                                            ?.second ?: newOperationId().also {
                                            pendingOpen = need.requirementId to it
                                        }
                                        api.openRfq(
                                            need.requirementId,
                                            OpenSupplierRfqDto(operation, need.technicalVersion),
                                            installationId,
                                        )
                                        pendingOpen = null
                                        message = "Internal sourcing draft created. No supplier message or PO was issued."
                                        selectedRequirement = need.requirementId
                                        rounds = api.roundsForRequirement(need.requirementId, installationId)
                                        epoch++
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "RFQ draft not created. Refresh the source."
                                    } finally { loading = false }
                                }
                            },
                        ) { Text("Open internal RFQ draft") }
                    }
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            error = null
                            selectedRequirement = need.requirementId
                            rounds = emptyList()
                            comparison = null
                            scope.launch {
                                try {
                                    rounds = api.roundsForRequirement(need.requirementId, installationId)
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Cannot read restricted sourcing rounds."
                                } finally { loading = false }
                            }
                        },
                    ) { Text("View sourcing rounds") }
                    if (selectedRequirement == need.requirementId) {
                        if (rounds.isEmpty()) Text("No restricted RFQ rounds found for this requirement.")
                        rounds.forEach { round ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("RFQ version ${round.capturedRequirementVersion} · ${round.quoteCount} quotations")
                                if (!round.sourceCurrent) Text("RFQ source is stale; compare historic evidence only.")
                                OutlinedButton(
                                    enabled = !loading,
                                    onClick = {
                                        loading = true
                                        comparingRfq = round.id
                                        error = null
                                        comparison = null
                                        scope.launch {
                                            try {
                                                comparison = api.comparison(round.id, installationId)
                                            } catch (failure: Exception) {
                                                error = failure.message ?: "Quote comparison denied."
                                            } finally { loading = false }
                                        }
                                    },
                                ) { Text("Compare quotations") }
                                comparison?.takeIf { comparingRfq == round.id }?.let { view ->
                                    Text("Latest revisions · ${view.recordedSupplierCount} suppliers",
                                        style = MaterialTheme.typography.titleSmall)
                                    view.comparison.latestBySupplier.forEach { quote ->
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(
                                                "Supplier ${quote.supplierOrganizationId.take(8)} · " +
                                                    "${quote.quantity} ${quote.unitCode} · " +
                                                    "${quote.total} ${quote.currency}",
                                            )
                                            Text("Quote ${quote.quoteReference} · Rev ${quote.revisionNumber}",
                                                style = MaterialTheme.typography.bodySmall)
                                            quote.issues.forEach { issue ->
                                                Text("Review: $issue", color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    Text("Comparison only — no supplier selected and no purchasing approval.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
