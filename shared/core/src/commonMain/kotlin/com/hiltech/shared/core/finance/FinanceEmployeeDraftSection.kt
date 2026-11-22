package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Native Android/Windows employee self-service for PRIVATE drafts.
 * The integrator owns navigation and separate OFF-by-default UI switches.
 * No payment / approval / payout button and no local offline financial queue.
 */
@Composable
fun FinanceEmployeeDraftSection(
    api: FinanceEmployeeDraftApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var kind by remember(organizationId) { mutableStateOf(0) }
    var amount by remember(organizationId, kind) { mutableStateOf("") }
    var currency by remember(organizationId, kind) { mutableStateOf("") }
    var purpose by remember(organizationId, kind) { mutableStateOf("") }
    var merchant by remember(organizationId, kind) { mutableStateOf("") }
    var date by remember(organizationId, kind) { mutableStateOf("") }
    var settlement by remember(organizationId, kind) { mutableStateOf("") }
    var operation by remember(organizationId, kind) { mutableStateOf<String?>(null) }
    var entries by remember(organizationId, kind) {
        mutableStateOf<List<FinanceEmployeeDraftDto>>(emptyList())
    }
    var loaded by remember(organizationId, kind) { mutableStateOf(false) }
    var busy by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId, kind) { mutableStateOf<String?>(null) }
    val types = listOf("Expense reimbursement", "Employee advance", "Financial imprest")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("My Finance requests", style = MaterialTheme.typography.titleLarge)
        Text("Drafts only. No approved expense, funded advance, cash custody or payroll deduction.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEachIndexed { index, label ->
                OutlinedButton(
                    enabled = !busy,
                    onClick = { kind = index },
                ) { Text(if (kind == index) "• $label" else label,
                    style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text(types[kind], style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = amount, onValueChange = { amount = it; operation = null },
            label = { Text("Requested amount") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = currency, onValueChange = { currency = it; operation = null },
            label = { Text("Currency (3-letter code)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = purpose, onValueChange = { purpose = it; operation = null },
            label = { Text("Real purpose") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (kind == 0) {
            OutlinedTextField(
                value = merchant, onValueChange = { merchant = it; operation = null },
                label = { Text("Merchant/payee") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = date, onValueChange = { date = it; operation = null },
                label = { Text("Actual personal spend date (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Receipt evidence and policy review are required before submission.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (kind == 1) {
            OutlinedTextField(
                value = settlement, onValueChange = { settlement = it; operation = null },
                label = { Text("Proposed repayment terms (optional; not approved)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("This is not an issued loan or a salary deduction.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (kind == 2) {
            OutlinedTextField(
                value = date, onValueChange = { date = it; operation = null },
                label = { Text("Proposed settlement date (optional, YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Company funds remain under company control; no custody is created by this draft.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val op = operation ?: newOperationId().also { operation = it }
                    val saved = when(kind) {
                        0 -> api.createExpense(
                            organizationId,
                            ExpenseDraftCommandDto(op, amount, currency, purpose, merchant, date),
                            installationId,
                        )
                        1 -> api.createAdvance(
                            organizationId,
                            AdvanceDraftCommandDto(op, amount, currency, purpose,
                                proposedSettlementNote = settlement.takeIf { it.isNotBlank() }),
                            installationId,
                        )
                        else -> api.createImprest(
                            organizationId,
                            ImprestDraftCommandDto(op, amount, currency, purpose,
                                proposedSettlementOn = date.takeIf { it.isNotBlank() }),
                            installationId,
                        )
                    }
                    entries = when(kind) {
                        0 -> api.expenses(organizationId, installationId)
                        1 -> api.advances(organizationId, installationId)
                        else -> api.imprests(organizationId, installationId)
                    }
                    loaded = true
                    operation = null
                    amount = ""
                    purpose = ""
                    merchant = ""
                    settlement = ""
                    date = ""
                    error = if (saved.financialActionAllowed) {
                        "Unexpected financial action. Contact Finance."
                    } else null
                } catch (failure: Exception) {
                    error = failure.message ?: "Unable to save the private draft."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Saving…" else "Save private draft (no money movement)") }

        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    entries = when(kind) {
                        0 -> api.expenses(organizationId, installationId)
                        1 -> api.advances(organizationId, installationId)
                        else -> api.imprests(organizationId, installationId)
                    }
                    loaded = true
                } catch (failure: Exception) {
                    loaded = false
                    entries = emptyList()
                    error = failure.message ?: "Cannot load private drafts."
                } finally {
                    busy = false
                }
            }
        }) { Text("Refresh my " + types[kind] + " drafts") }

        if (loaded && entries.isEmpty()) Text("No private drafts recorded for this type.")
        entries.forEach { draft ->
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(draft.amount + " " + draft.currency,
                        style = MaterialTheme.typography.titleSmall)
                    Text(draft.purpose)
                    Text(draft.state + " / NOT APPROVED OR FUNDED",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
