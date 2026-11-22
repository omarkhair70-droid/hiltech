package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.commercial.CommercialDealDetailDto
import com.hiltech.shared.core.commercial.CommercialPrivateCostLineDto
import com.hiltech.shared.core.commercial.CommercialPrivateCostSummaryDto

/** Complete, source-driven native desk. Mount in Windows only after role-based shell routing. */
data class CommercialDealWorkspaceState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val canWrite: Boolean = false,
    val canCost: Boolean = false,
    val canRelease: Boolean = false,
    val canAward: Boolean = false,
    val canCreateProject: Boolean = false,
    val deal: CommercialDealDetailDto? = null,
    val privateCostLines: List<CommercialPrivateCostLineDto> = emptyList(),
    val privateCostSummaries: List<CommercialPrivateCostSummaryDto> = emptyList(),
    val errorCode: String? = null,
)

data class CommercialDraftLine(
    val lineCode: String,
    val description: String,
    val category: String,
    val quantity: String,
    val unit: String,
    val internalUnitCost: String?,
    val sellUnitPrice: String,
)

/** Decimal values stay text until validated and encoded as exact JSON numbers, never Double. */
fun validCommercialDecimal(raw: String, requirePositive: Boolean = false): Boolean {
    if (!Regex("^(0|[1-9][0-9]{0,15})(\\.[0-9]{1,4})?$").matches(raw)) return false
    return !requirePositive || raw.any { it in '1'..'9' }
}

sealed interface CommercialDealAction {
    data class TenderRevision(val opportunityId: String, val tenderType: String,
                              val receivedAt: String, val referenceNumber: String,
                              val submissionDeadline: String, val clarificationDeadline: String,
                              val sourceChannel: String) : CommercialDealAction
    data class ScheduleVisit(val opportunityId: String, val purpose: String,
                             val scheduledAt: String) : CommercialDealAction
    data class CompleteVisit(val visitId: String, val baseVersion: Long,
                             val notes: String) : CommercialDealAction
    data class Qualify(val opportunityId: String, val baseVersion: Long,
                       val summary: String, val questions: String) : CommercialDealAction
    data class SaveScope(val opportunityId: String, val description: String,
                         val inclusions: String, val exclusions: String,
                         val assumptions: String, val constraints: String,
                         val clientResponsibilities: String) : CommercialDealAction
    data class SaveBoq(val opportunityId: String, val scopeVersionId: String,
                       val currency: String, val lines: List<CommercialDraftLine>) : CommercialDealAction
    data class DraftQuote(val opportunityId: String, val boqVersionId: String,
                          val paymentTerms: String, val deliveryTerms: String,
                          val exclusions: String, val validityDate: String) : CommercialDealAction
    data class RequestApproval(val quoteId: String) : CommercialDealAction
    data class ReleaseApprovedQuote(val quoteId: String) : CommercialDealAction
    data class DraftContract(val opportunityId: String, val quoteId: String,
                             val code: String, val warrantyTerms: String,
                             val maintenanceTerms: String) : CommercialDealAction
    data class ConfirmSigned(val contractId: String, val evidenceId: String) : CommercialDealAction
    data class DraftClientPo(val opportunityId: String, val contractId: String,
                             val number: String, val issuedOn: String,
                             val amount: String, val currency: String) : CommercialDealAction
    data class VerifyClientPo(val clientPoId: String, val evidenceId: String) : CommercialDealAction
    data class ConvertToProject(val opportunityId: String, val contractId: String,
                                val clientPoId: String) : CommercialDealAction
}
private enum class CommercialStage(val label: String) {
    DISCOVERY("01 Discover"),
    SCOPE("02 Scope"),
    COST("03 Cost / BOQ"),
    QUOTE("04 Quote"),
    AWARD("05 Award"),
}

@Composable
fun CommercialDealWorkspaceSection(
    state: CommercialDealWorkspaceState,
    onRefresh: () -> Unit,
    onAction: (CommercialDealAction) -> Unit,
) {
    if (!state.enabled) {
        Text("Commercial operations are locked until company authorization is configured.")
        return
    }
    val detail = state.deal
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Commercial / Deal workspace", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
        state.errorCode?.let { Text("Action blocked: $it", color = MaterialTheme.colorScheme.error) }
        if (state.loading) Text("Loading authoritative commercial history…")
        if (detail == null) {
            Text("Select an opportunity to review its source-bound history.")
        } else {
            val op = detail.opportunity
            Text(op.title, style = MaterialTheme.typography.titleLarge)
            Text(op.state + "  ·  " + op.source + "  ·  revision " + op.version)
            var stage by remember(op.id) { mutableStateOf(CommercialStage.DISCOVERY) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CommercialStage.entries.forEach { candidate ->
                    OutlinedButton(
                        enabled = candidate != stage,
                        onClick = { stage = candidate },
                    ) { Text(candidate.label) }
                }
            }
            HorizontalDivider()
            when (stage) {
                CommercialStage.DISCOVERY -> CommercialDiscoveryPanel(
                    detail, state.canWrite, onAction,
                )
                CommercialStage.SCOPE -> CommercialScopePanel(
                    detail, state.canWrite, onAction,
                )
                CommercialStage.COST -> CommercialBoqPanel(
                    detail, state.canWrite && state.canCost,
                    state.privateCostLines,state.privateCostSummaries,onAction,
                )
                CommercialStage.QUOTE -> CommercialQuotePanel(
                    detail, state.canWrite, state.canRelease, onAction,
                )
                CommercialStage.AWARD -> CommercialAwardPanel(
                    detail, state.canAward, state.canCreateProject, onAction,
                )
            }
        }
    }
}

@Composable
private fun CommercialDiscoveryPanel(
    deal: CommercialDealDetailDto,
    canWrite: Boolean,
    onAction: (CommercialDealAction) -> Unit,
) {
    val op = deal.opportunity
    Text("Discovery / technical qualification", style = MaterialTheme.typography.titleMedium)
    if (deal.qualification.isNotEmpty()) {
        deal.qualification.forEach {
            Text("Qualified discovery: " + it.discoverySummary)
            if (it.openQuestions.isNotBlank()) Text("Open questions: " + it.openQuestions)
        }
    } else {
        Text("No qualified discovery recorded.")
    }
    if (canWrite && op.state in setOf("OPEN","QUALIFIED")) {
        var tenderType by remember(op.id) { mutableStateOf("RFQ") }
        var tenderRef by remember(op.id) { mutableStateOf("") }
        var tenderReceived by remember(op.id) { mutableStateOf("") }
        var tenderDeadline by remember(op.id) { mutableStateOf("") }
        var clarification by remember(op.id) { mutableStateOf("") }
        var sourceChannel by remember(op.id) { mutableStateOf("") }
        Text("Tender / RFQ intake and deadline revision")
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("RFQ","RFP","TENDER","OTHER").forEach { type ->
                OutlinedButton(onClick={tenderType=type},enabled=type!=tenderType){Text(type)}
            }
        }
        OutlinedTextField(value=tenderRef,onValueChange={tenderRef=it},label={Text("Tender reference")})
        OutlinedTextField(value=tenderReceived,onValueChange={tenderReceived=it},
            label={Text("Received at (ISO-8601 UTC)")})
        OutlinedTextField(value=tenderDeadline,onValueChange={tenderDeadline=it},
            label={Text("Submission deadline (ISO-8601 UTC, optional)")})
        OutlinedTextField(value=clarification,onValueChange={clarification=it},
            label={Text("Clarification deadline (optional)")})
        OutlinedTextField(value=sourceChannel,onValueChange={sourceChannel=it},
            label={Text("Official source channel")})
        Button(enabled=tenderReceived.isNotBlank(),
            onClick={onAction(CommercialDealAction.TenderRevision(
                op.id,tenderType,tenderReceived,tenderRef,tenderDeadline,clarification,sourceChannel))}) {
            Text("Record immutable tender deadline revision")
        }
        var visitPurpose by remember(op.id) { mutableStateOf("") }
        var visitDate by remember(op.id) { mutableStateOf("") }
        OutlinedTextField(value=visitPurpose,onValueChange={visitPurpose=it},
            label={Text("Site visit / discovery purpose")})
        OutlinedTextField(value=visitDate,onValueChange={visitDate=it},
            label={Text("Visit scheduled at (ISO-8601 UTC)")})
        Button(enabled=visitPurpose.isNotBlank() && visitDate.isNotBlank(),
            onClick={onAction(CommercialDealAction.ScheduleVisit(op.id,visitPurpose,visitDate))}) {
            Text("Schedule site visit")
        }
    }
    deal.tenders.forEach {
        Text("Tender " + it.revisionNumber + " · " + it.tenderType + " · " + it.submissionState)
        it.referenceNumber?.let { ref -> Text("Reference " + ref) }
    }
    deal.visits.forEach { visit ->
        Text("Visit: " + visit.purpose + " · " + visit.state)
        if (canWrite && visit.state == "SCHEDULED") {
            var notes by remember(visit.id) { mutableStateOf("") }
            OutlinedTextField(value=notes,onValueChange={notes=it},
                label={Text("Actual technical findings (required)")})
            Button(enabled=notes.isNotBlank(),
                onClick={onAction(CommercialDealAction.CompleteVisit(
                    visit.id,visit.version,notes))}) { Text("Complete and retain visit findings") }
        }
    }
    if (canWrite && op.state == "OPEN") {
        var summary by remember(op.id) { mutableStateOf("") }
        var questions by remember(op.id) { mutableStateOf("") }
        OutlinedTextField(
            value = summary, onValueChange = { summary = it },
            label = { Text("Discovery and qualification rationale") },
        )
        OutlinedTextField(
            value = questions, onValueChange = { questions = it },
            label = { Text("Open technical questions / assumptions") },
        )
        Button(
            enabled = summary.isNotBlank(),
            onClick = {
                onAction(CommercialDealAction.Qualify(op.id, op.version, summary, questions))
            },
        ) { Text("Qualify current opportunity version") }
    }
}

@Composable
private fun CommercialScopePanel(
    deal: CommercialDealDetailDto,
    canWrite: Boolean,
    onAction: (CommercialDealAction) -> Unit,
) {
    val op = deal.opportunity
    Text("Scope / assumptions / client responsibilities", style = MaterialTheme.typography.titleMedium)
    deal.scopeVersions.forEach {
        Text("Scope v" + it.versionNumber + " · " + it.description)
        Text("Included: " + it.inclusions)
        Text("Excluded: " + it.exclusions)
        Text("Assumptions: " + it.assumptions)
    }
    if (canWrite && op.state in setOf("OPEN", "QUALIFIED")) {
        var description by remember(op.id) { mutableStateOf("") }
        var inclusions by remember(op.id) { mutableStateOf("") }
        var exclusions by remember(op.id) { mutableStateOf("") }
        var assumptions by remember(op.id) { mutableStateOf("") }
        var constraints by remember(op.id) { mutableStateOf("") }
        var responsibilities by remember(op.id) { mutableStateOf("") }
        OutlinedTextField(value=description,onValueChange={description=it},label={Text("Scope statement")})
        OutlinedTextField(value=inclusions,onValueChange={inclusions=it},label={Text("Inclusions")})
        OutlinedTextField(value=exclusions,onValueChange={exclusions=it},label={Text("Exclusions")})
        OutlinedTextField(value=assumptions,onValueChange={assumptions=it},label={Text("Assumptions")})
        OutlinedTextField(value=constraints,onValueChange={constraints=it},label={Text("Technical constraints")})
        OutlinedTextField(value=responsibilities,onValueChange={responsibilities=it},label={Text("Client responsibilities")})
        Button(
            enabled = description.isNotBlank(),
            onClick = {
                onAction(CommercialDealAction.SaveScope(
                    op.id,description,inclusions,exclusions,assumptions,constraints,responsibilities))
            },
        ) { Text("Save immutable scope version") }
    }
}

@Composable
private fun CommercialBoqPanel(
    deal: CommercialDealDetailDto,
    canEditPrivateCost: Boolean,
    costLines: List<CommercialPrivateCostLineDto>,
    summaries: List<CommercialPrivateCostSummaryDto>,
    onAction: (CommercialDealAction) -> Unit,
) {
    val op = deal.opportunity
    Text("Costing / BOQ", style = MaterialTheme.typography.titleMedium)
    if (!canEditPrivateCost) {
        Text("Private costing is restricted to explicitly authorized commercial staff.")
        return
    }
    summaries.forEach { summary ->
        Text("BOQ v" + summary.versionNumber + " · " + summary.currency)
        Text("Proposed sell: " + summary.proposedSellTotal.content +
             " · Known cost subtotal: " + summary.knownCostSubtotal.content)
        Text(
            if (summary.missingCostLines > 0)
                summary.missingCostLines.toString() + " uncosted line(s). Total cost UNKNOWN."
            else "Complete internal cost: " +
                (summary.completeInternalCostTotal?.content ?: "UNKNOWN"),
            color = if (summary.missingCostLines > 0)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    costLines.forEach {
        Text(it.description + " · " + it.quantity.content + " " + it.unit)
        Text("Internal cost: " + (it.internalUnitCost?.content ?: "not recorded") +
            " · Sell unit price: " + it.sellUnitPrice.content)
    }
    if (op.state !in setOf("OPEN","QUALIFIED")) return
    var scopeVersionId by remember(op.id) { mutableStateOf("") }
    var currency by remember(op.id) { mutableStateOf("") }
    var code by remember(op.id) { mutableStateOf("") }
    var description by remember(op.id) { mutableStateOf("") }
    var category by remember(op.id) { mutableStateOf("MATERIAL") }
    var quantity by remember(op.id) { mutableStateOf("") }
    var unit by remember(op.id) { mutableStateOf("") }
    var internalCost by remember(op.id) { mutableStateOf("") }
    var sellPrice by remember(op.id) { mutableStateOf("") }
    val lines = remember(op.id) { mutableStateListOf<CommercialDraftLine>() }
    OutlinedTextField(value=scopeVersionId,onValueChange={scopeVersionId=it},label={Text("Scope version ID")})
    OutlinedTextField(value=currency,onValueChange={currency=it.uppercase()},label={Text("ISO currency")})
    Text("Draft lines: " + lines.size)
    OutlinedTextField(value=code,onValueChange={code=it},label={Text("Line code")})
    OutlinedTextField(value=description,onValueChange={description=it},label={Text("Work / material description")})
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("MATERIAL","LABOR","SUBCONTRACT","EQUIPMENT","OVERHEAD","OTHER").forEach {
            OutlinedButton(enabled=it!=category,onClick={category=it}){Text(it)}
        }
    }
    OutlinedTextField(value=quantity,onValueChange={quantity=it},label={Text("Quantity")})
    OutlinedTextField(value=unit,onValueChange={unit=it},label={Text("Unit")})
    OutlinedTextField(value=internalCost,onValueChange={internalCost=it},label={Text("Private unit cost (optional)")})
    OutlinedTextField(value=sellPrice,onValueChange={sellPrice=it},label={Text("Customer unit sell price")})
    val lineValid = code.isNotBlank() && description.isNotBlank() && unit.isNotBlank() &&
        validCommercialDecimal(quantity,true) && validCommercialDecimal(sellPrice) &&
        (internalCost.isBlank() || validCommercialDecimal(internalCost)) &&
        lines.none { it.lineCode == code }
    Button(
        enabled = lineValid,
        onClick = {
            lines.add(CommercialDraftLine(
                code.trim(),description.trim(),category,quantity,unit.trim(),
                internalCost.takeIf { it.isNotBlank() },sellPrice))
            code=""; description=""; quantity=""; unit=""; internalCost=""; sellPrice=""
        },
    ) { Text("Add line to draft") }
    lines.forEach { Text(it.lineCode + " · " + it.description) }
    Button(
        enabled = lines.isNotEmpty() && scopeVersionId.isNotBlank() &&
            currency.matches(Regex("^[A-Z]{3}$")),
        onClick = {
            onAction(CommercialDealAction.SaveBoq(op.id,scopeVersionId,currency,lines.toList()))
        },
    ) { Text("Commit complete, immutable BOQ version") }
}

@Composable
private fun CommercialQuotePanel(
    deal: CommercialDealDetailDto,
    canWrite: Boolean,
    canRelease: Boolean,
    onAction: (CommercialDealAction) -> Unit,
) {
    val op=deal.opportunity
    Text("Customer quotation / approval", style=MaterialTheme.typography.titleMedium)
    deal.quotes.forEach { q ->
        Text(q.quoteCode + " · v" + q.versionNumber + " · " + q.state)
        Text(q.totalPrice.content + " " + q.currency)
        if (canWrite && q.state == "DRAFT") {
            OutlinedButton(onClick={onAction(CommercialDealAction.RequestApproval(q.id))}) {
                Text("Request independent approval")
            }
        }
        if (canRelease && q.state == "APPROVAL_PENDING") {
            Button(onClick={onAction(CommercialDealAction.ReleaseApprovedQuote(q.id))}) {
                Text("Release only after exact-version Approval Engine decision")
            }
        }
    }
    if (canWrite && op.state in setOf("OPEN","QUALIFIED")) {
        var boqId by remember(op.id) { mutableStateOf("") }
        var payment by remember(op.id) { mutableStateOf("") }
        var delivery by remember(op.id) { mutableStateOf("") }
        var exclusions by remember(op.id) { mutableStateOf("") }
        var validity by remember(op.id) { mutableStateOf("") }
        OutlinedTextField(value=boqId,onValueChange={boqId=it},label={Text("BOQ version ID")})
        OutlinedTextField(value=payment,onValueChange={payment=it},label={Text("Client payment terms")})
        OutlinedTextField(value=delivery,onValueChange={delivery=it},label={Text("Delivery terms")})
        OutlinedTextField(value=exclusions,onValueChange={exclusions=it},label={Text("Quote exclusions")})
        OutlinedTextField(value=validity,onValueChange={validity=it},label={Text("Valid until (YYYY-MM-DD)")})
        Button(
            enabled=boqId.isNotBlank() && payment.isNotBlank() &&
                delivery.isNotBlank() && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(validity),
            onClick={onAction(CommercialDealAction.DraftQuote(
                op.id,boqId,payment,delivery,exclusions,validity))},
        ) { Text("Create new quote version") }
    }
    Text("Client-visible quote projections never contain internal cost, supplier quote or margin.")
}

@Composable
private fun CommercialAwardPanel(
    deal: CommercialDealDetailDto,
    canAward: Boolean,
    canCreateProject: Boolean,
    onAction: (CommercialDealAction) -> Unit,
) {
    val op=deal.opportunity
    Text("Contract / client PO / project handoff",style=MaterialTheme.typography.titleMedium)
    deal.handoff.firstOrNull()?.let {
        Text("Project created: "+it.projectId)
        Text("Awarded quote: "+it.quoteVersionId)
        return
    }
    if (!canAward) {
        Text("Company Sales Award authority required. No implicit Project admin privilege.")
        return
    }
    var quoteId by remember(op.id) { mutableStateOf("") }
    var contractCode by remember(op.id) { mutableStateOf("") }
    var warranty by remember(op.id) { mutableStateOf("") }
    var maintenance by remember(op.id) { mutableStateOf("") }
    OutlinedTextField(value=quoteId,onValueChange={quoteId=it},label={Text("Released quote ID")})
    OutlinedTextField(value=contractCode,onValueChange={contractCode=it},label={Text("Contract reference")})
    OutlinedTextField(value=warranty,onValueChange={warranty=it},label={Text("Actual warranty terms (optional)")})
    OutlinedTextField(value=maintenance,onValueChange={maintenance=it},label={Text("Actual support terms (optional)")})
    Button(
        enabled=quoteId.isNotBlank() && contractCode.isNotBlank(),
        onClick={onAction(CommercialDealAction.DraftContract(op.id,quoteId,contractCode,warranty,maintenance))},
    ) { Text("Draft contract from released quote") }
    deal.contracts.forEach { c ->
        Text(c.contractCode + " · " + c.state)
        if(c.state=="DRAFT") {
            var evidenceId by remember(c.id) { mutableStateOf("") }
            OutlinedTextField(value=evidenceId,onValueChange={evidenceId=it},
                label={Text("Canonical READY signed-contract Evidence ID")})
            Button(enabled=evidenceId.isNotBlank(),
                onClick={onAction(CommercialDealAction.ConfirmSigned(c.id,evidenceId))}) {
                Text("Verify actual signed contract")
            }
        }
        if(c.state=="SIGNED") {
            var number by remember(c.id) { mutableStateOf("") }
            var issued by remember(c.id) { mutableStateOf("") }
            var amount by remember(c.id) { mutableStateOf("") }
            var currency by remember(c.id) { mutableStateOf("") }
            OutlinedTextField(value=number,onValueChange={number=it},label={Text("Client PO number")})
            OutlinedTextField(value=issued,onValueChange={issued=it},label={Text("Issued YYYY-MM-DD")})
            OutlinedTextField(value=amount,onValueChange={amount=it},label={Text("Client PO amount")})
            OutlinedTextField(value=currency,onValueChange={currency=it.uppercase()},
                label={Text("ISO currency")})
            Button(enabled=number.isNotBlank() && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(issued) &&
                validCommercialDecimal(amount) && currency.matches(Regex("^[A-Z]{3}$")),
                onClick={onAction(CommercialDealAction.DraftClientPo(
                    op.id,c.id,number,issued,amount,currency))}) { Text("Record client PO draft") }
        }
    }
    deal.clientPurchaseOrders.forEach { po ->
        Text("Client PO "+po.clientPoNumber+" · "+(if(po.verified) "verified" else "unverified"))
        if(!po.verified) {
            var evidence by remember(po.id) { mutableStateOf("") }
            OutlinedTextField(value=evidence,onValueChange={evidence=it},
                label={Text("Canonical READY client PO Evidence ID")})
            Button(enabled=evidence.isNotBlank(),
                onClick={onAction(CommercialDealAction.VerifyClientPo(po.id,evidence))}) {
                Text("Verify PO evidence")
            }
        }
        val contract=deal.contracts.firstOrNull { it.id == po.contractId }
        if(po.verified && contract?.state == "SIGNED") {
            Button(
                enabled=canCreateProject,
                onClick={onAction(CommercialDealAction.ConvertToProject(op.id,contract.id,po.id))},
            ) {
                Text("Create canonical project from this verified award")
            }
            if (!canCreateProject) {
                Text("Project creation requires a separate active PROJECT_ADMIN grant.")
            }
        }
    }
    Text("Evidence uploads for commercial targets require the Documents owner integration; unsupported uploads fail closed.")
}
