package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.commercial.ClientQuoteDto
import com.hiltech.shared.core.commercial.CommercialQuoteDispatchDto
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Modifier
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
    val customerQuote: ClientQuoteDto? = null,
    val quoteBusy: Boolean = false,
    val deliveryCapabilities: com.hiltech.shared.core.commercial.CommercialDeliveryCapabilitiesDto? = null,
    val dispatchHistory: List<CommercialQuoteDispatchDto> = emptyList(),
    val emailHistory: List<com.hiltech.shared.core.commercial.CommercialQuoteEmailStatusDto> = emptyList(),
    val whatsappHistory: List<com.hiltech.shared.core.commercial.CommercialWhatsappStatusDto> = emptyList(),
    val defaultPaymentTerms: String = "",
    val defaultDeliveryTerms: String = "",
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
                              val sourceChannel: String, val submissionState: String = "RECEIVED") : CommercialDealAction
    data class CloseOpportunity(
        val opportunityId: String,
        val baseVersion: Long,
        val state: String,
        val reason: String,
    ) : CommercialDealAction
    data class ScheduleVisit(val opportunityId: String, val purpose: String,
                             val scheduledAt: String, val siteId: String? = null) : CommercialDealAction
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
                          val exclusions: String, val validityDate: String, val discountAmount: String = "0", val taxAmount: String = "0") : CommercialDealAction
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
    onPreviewQuote: ((String) -> Unit)? = null,
    onExportQuote: ((String) -> Unit)? = null,
    onRecordDispatch: ((String, String, String) -> Unit)? = null,
    onSendEmail: ((String, String) -> Unit)? = null,
    onSendWhatsapp: ((String, String) -> Unit)? = null,
) {
    if (!state.enabled) {
        Text("Commercial operations are locked until company authorization is configured.")
        return
    }
    val detail = state.deal
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(hiltechText("مساحة المبيعات والتعاقد", "Commercial / Deal workspace"), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh) { Text(hiltechText("تحديث", "Refresh")) }
        }
        state.errorCode?.let { Text("Action blocked: $it", color = MaterialTheme.colorScheme.error) }
        if (state.loading) Text(hiltechText("جارٍ تحميل سجل المبيعات…", "Loading authoritative commercial history…"))
        if (detail == null) {
            Text(hiltechText("اختر فرصة للاطلاع على سجلها.", "Select an opportunity to review its source-bound history."))
        } else {
            val op = detail.opportunity
            Text(op.title, style = MaterialTheme.typography.titleLarge)
            Text(op.state + "  ·  " + op.source + "  ·  revision " + op.version)
            var stage by remember(op.id) { mutableStateOf(CommercialStage.DISCOVERY) }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                    state.customerQuote, state.quoteBusy, onPreviewQuote, onExportQuote, state.dispatchHistory, onRecordDispatch, state.emailHistory, onSendEmail, state.defaultPaymentTerms, state.defaultDeliveryTerms, state.whatsappHistory, onSendWhatsapp, state.deliveryCapabilities,
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
    Text(hiltechText("الاستكشاف والتأهيل الفني", "Discovery / technical qualification"), style = MaterialTheme.typography.titleMedium)
    if (deal.qualification.isNotEmpty()) {
        deal.qualification.forEach {
            Text("Qualified discovery: " + it.discoverySummary)
            if (it.openQuestions.isNotBlank()) Text("Open questions: " + it.openQuestions)
        }
    } else {
        Text(hiltechText("لم يُسجل تأهيل فني بعد.", "No qualified discovery recorded."))
    }
    if (canWrite && op.state in setOf("OPEN","QUALIFIED")) {
        var tenderType by remember(op.id) { mutableStateOf("RFQ") }
        var tenderStatus by remember(op.id) { mutableStateOf("RECEIVED") }
        var tenderRef by remember(op.id) { mutableStateOf("") }
        var tenderReceived by remember(op.id) { mutableStateOf("") }
        var tenderDeadline by remember(op.id) { mutableStateOf("") }
        var clarification by remember(op.id) { mutableStateOf("") }
        var sourceChannel by remember(op.id) { mutableStateOf("") }
        Text(hiltechText("استقبال المناقصة وطلب العرض ومواعيد التقديم", "Tender / RFQ intake and deadline revision"))
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("RFQ","RFP","TENDER","OTHER").forEach { type ->
                OutlinedButton(onClick={tenderType=type},enabled=type!=tenderType){Text(type)}
            }
        }
        Text("Tender progress (every change creates a new dated revision)")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("RECEIVED","PREPARING","SUBMITTED","WITHDRAWN","LOST").forEach { status ->
                OutlinedButton(onClick={tenderStatus=status},enabled=status!=tenderStatus){Text(status)}
            }
        }
        OutlinedTextField(value=tenderRef,onValueChange={tenderRef=it},label={Text(hiltechText("مرجع المناقصة", "Tender reference"))})
        OutlinedTextField(value=tenderReceived,onValueChange={tenderReceived=it},
            label={Text(hiltechText("تاريخ الاستلام (UTC)", "Received at (ISO-8601 UTC)"))})
        OutlinedTextField(value=tenderDeadline,onValueChange={tenderDeadline=it},
            label={Text(hiltechText("موعد التقديم (UTC، اختياري)", "Submission deadline (ISO-8601 UTC, optional)"))})
        OutlinedTextField(value=clarification,onValueChange={clarification=it},
            label={Text("Clarification deadline (optional)")})
        OutlinedTextField(value=sourceChannel,onValueChange={sourceChannel=it},
            label={Text("Official source channel")})
        Button(enabled=tenderReceived.isNotBlank(),
            onClick={onAction(CommercialDealAction.TenderRevision(
                op.id,tenderType,tenderReceived,tenderRef,tenderDeadline,clarification,sourceChannel,tenderStatus))}) {
            Text("Record immutable tender deadline revision")
        }
        var visitPurpose by remember(op.id) { mutableStateOf("") }
        var visitDate by remember(op.id) { mutableStateOf("") }
        var visitSiteId by remember(op.id) { mutableStateOf("") }
        OutlinedTextField(value=visitSiteId,onValueChange={visitSiteId=it},
            label={Text("Existing canonical Site ID (optional)")})
        OutlinedTextField(value=visitPurpose,onValueChange={visitPurpose=it},
            label={Text("Site visit / discovery purpose")})
        OutlinedTextField(value=visitDate,onValueChange={visitDate=it},
            label={Text("Visit scheduled at (ISO-8601 UTC)")})
        Button(enabled=visitPurpose.isNotBlank() && visitDate.isNotBlank(),
            onClick={onAction(CommercialDealAction.ScheduleVisit(op.id,visitPurpose,visitDate,visitSiteId.takeIf { it.isNotBlank() }))}) {
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
    if (canWrite && op.state in setOf("OPEN", "QUALIFIED")) {
        Text("Close this opportunity with a recorded reason")
        var closureReason by remember(op.id) { mutableStateOf("") }
        var closureState by remember(op.id) { mutableStateOf("LOST") }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("LOST","WITHDRAWN").forEach { option ->
                OutlinedButton(
                    enabled = closureState != option,
                    onClick = { closureState = option },
                ) { Text(option) }
            }
        }
        OutlinedTextField(
            value = closureReason,
            onValueChange = { closureReason = it },
            label = { Text("Recorded loss / withdrawal reason") },
        )
        Button(
            enabled = closureReason.trim().isNotEmpty(),
            onClick = { onAction(CommercialDealAction.CloseOpportunity(
                op.id, op.version, closureState, closureReason.trim(),
            )) },
        ) {
            Text("Close opportunity as " + closureState)
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
    Text(hiltechText("التكاليف وجدول الكميات", "Costing / BOQ"), style = MaterialTheme.typography.titleMedium)
    if (!canEditPrivateCost) {
        Text(hiltechText("التكاليف الداخلية متاحة للمسؤول المخول فقط.", "Private costing is restricted to explicitly authorized commercial staff."))
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
    HiltechRecordPicker(hiltechText("إصدار نطاق العمل", "Scope version"), scopeVersionId,
        deal.scopeVersions.map { HiltechRecordChoice(it.id, "v" + it.versionNumber, it.description) }, { scopeVersionId = it }, allowKnownReference = false)
    OutlinedTextField(value=currency,onValueChange={currency=it.uppercase()},label={Text(hiltechText("رمز العملة", "ISO currency"))})
    Text("Draft lines: " + lines.size)
    OutlinedTextField(value=code,onValueChange={code=it},label={Text(hiltechText("رقم البند", "Line code"))})
    OutlinedTextField(value=description,onValueChange={description=it},label={Text(hiltechText("وصف العمل أو المادة", "Work / material description"))})
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("MATERIAL","LABOR","SUBCONTRACT","EQUIPMENT","OVERHEAD","OTHER").forEach {
            OutlinedButton(enabled=it!=category,onClick={category=it}){Text(it)}
        }
    }
    OutlinedTextField(value=quantity,onValueChange={quantity=it},label={Text(hiltechText("الكمية", "Quantity"))})
    OutlinedTextField(value=unit,onValueChange={unit=it},label={Text(hiltechText("الوحدة", "Unit"))})
    OutlinedTextField(value=internalCost,onValueChange={internalCost=it},label={Text(hiltechText("تكلفة الوحدة الداخلية (اختياري)", "Private unit cost (optional)"))})
    OutlinedTextField(value=sellPrice,onValueChange={sellPrice=it},label={Text(hiltechText("سعر بيع الوحدة للعميل", "Customer unit sell price"))})
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
    ) { Text(hiltechText("إضافة بند", "Add line to draft")) }
    lines.toList().forEach { line ->
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(line.lineCode + " · " + line.description)
            Text(line.quantity + " " + line.unit + " × " + line.sellUnitPrice + " " + currency)
            OutlinedButton(onClick = { lines.remove(line) }) { Text(hiltechText("حذف البند", "Remove line")) }
        }
    }
    Button(
        enabled = lines.isNotEmpty() && scopeVersionId.isNotBlank() &&
            currency.matches(Regex("^[A-Z]{3}$")),
        onClick = {
            onAction(CommercialDealAction.SaveBoq(op.id,scopeVersionId,currency,lines.toList()))
        },
    ) { Text(hiltechText("حفظ إصدار البنود والأسعار", "Commit complete, immutable BOQ version")) }
}

@Composable
private fun CommercialQuotePanel(
    deal: CommercialDealDetailDto,
    canWrite: Boolean,
    canRelease: Boolean,
    onAction: (CommercialDealAction) -> Unit,
    customerQuote: ClientQuoteDto?,
    quoteBusy: Boolean,
    onPreview: ((String) -> Unit)?,
    onExport: ((String) -> Unit)?,
    dispatchHistory: List<CommercialQuoteDispatchDto>,
    onRecordDispatch: ((String, String, String) -> Unit)?,
    emailHistory: List<com.hiltech.shared.core.commercial.CommercialQuoteEmailStatusDto>,
    onSendEmail: ((String, String) -> Unit)?,
    defaultPaymentTerms: String,
    defaultDeliveryTerms: String,
    whatsappHistory: List<com.hiltech.shared.core.commercial.CommercialWhatsappStatusDto>,
    onSendWhatsapp: ((String, String) -> Unit)?,
    deliveryCapabilities: com.hiltech.shared.core.commercial.CommercialDeliveryCapabilitiesDto?,
) {
    val op=deal.opportunity
    Text(hiltechText("عروض الأسعار والاعتماد", "Customer quotations & approval"), style=MaterialTheme.typography.titleMedium)
    deal.quotes.forEach { q ->
        Text(q.quoteCode + " · v" + q.versionNumber + " · " + q.state)
        Text(q.totalPrice.content + " " + q.currency)
        if (q.state == "RELEASED" && onPreview != null) {
            OutlinedButton(enabled = !quoteBusy, onClick = { onPreview(q.id) }) { Text(hiltechText("معاينة نسخة العميل", "Preview customer copy")) }
            if (customerQuote?.quoteId == q.id) {
                ReleasedQuotePreview(customerQuote)
                if (onExport != null) Button(enabled = !quoteBusy, onClick = { onExport(q.id) }) { Text(hiltechText("تصدير PDF", "Export PDF")) }
                if (canWrite || canRelease) {
                    if (deliveryCapabilities == null) Text(hiltechText("تعذر التحقق من قنوات الإرسال. حفظ المستند متاح.", "Could not verify delivery channels. Document export remains available."))
                    else {
                        if (!deliveryCapabilities.email) Text(hiltechText("البريد المباشر غير مهيأ؛ راجع الحسابات المتصلة.", "Direct email is not configured; review connected accounts."))
                        if (!deliveryCapabilities.whatsapp) Text(hiltechText("واتساب المباشر غير مهيأ؛ راجع الحسابات المتصلة.", "Direct WhatsApp is not configured; review connected accounts."))
                    }
                }
                QuoteEmailPanel(q.id, emailHistory, quoteBusy, if ((canWrite || canRelease) && deliveryCapabilities?.email == true) onSendEmail else null)
                QuoteWhatsappPanel(q.id, whatsappHistory, quoteBusy, if ((canWrite || canRelease) && deliveryCapabilities?.whatsapp == true) onSendWhatsapp else null)
                QuoteDispatchPanel(q.id, dispatchHistory, quoteBusy,
                    if (canWrite || canRelease) onRecordDispatch else null)
            }
        }
        if (canWrite && q.state == "DRAFT") {
            OutlinedButton(onClick={onAction(CommercialDealAction.RequestApproval(q.id))}) {
                Text(hiltechText("طلب اعتماد العرض", "Request independent approval"))
            }
        }
        if (canRelease && q.state == "APPROVAL_PENDING") {
            Button(onClick={onAction(CommercialDealAction.ReleaseApprovedQuote(q.id))}) {
                Text(hiltechText("إصدار العرض بعد اعتماد الإصدار", "Release only after exact-version Approval Engine decision"))
            }
        }
    }
    if (canWrite && op.state in setOf("OPEN","QUALIFIED")) {
        var boqId by remember(op.id) { mutableStateOf("") }
        var payment by remember(op.id) { mutableStateOf(defaultPaymentTerms) }
        var delivery by remember(op.id) { mutableStateOf(defaultDeliveryTerms) }
        var exclusions by remember(op.id) { mutableStateOf("") }
        var validity by remember(op.id) { mutableStateOf("") }
        var discount by remember(op.id) { mutableStateOf("0") }
        var tax by remember(op.id) { mutableStateOf("0") }
        HiltechRecordPicker(hiltechText("إصدار البنود والأسعار", "Priced BOQ version"), boqId,
            deal.boqVersions.map { HiltechRecordChoice(it.id, "v" + it.versionNumber, it.currency) }, { boqId = it }, allowKnownReference = false)
        OutlinedTextField(value=payment,onValueChange={payment=it},label={Text(hiltechText("شروط دفع العميل", "Client payment terms"))})
        OutlinedTextField(value=delivery,onValueChange={delivery=it},label={Text(hiltechText("شروط التسليم", "Delivery terms"))})
        OutlinedTextField(value=exclusions,onValueChange={exclusions=it},label={Text(hiltechText("استبعادات العرض", "Quote exclusions"))})
        OutlinedTextField(value=validity,onValueChange={validity=it},label={Text(hiltechText("صالح حتى (YYYY-MM-DD)", "Valid until (YYYY-MM-DD)"))})
        OutlinedTextField(discount, { discount = it }, label = { Text(hiltechText("قيمة الخصم", "Discount amount")) })
        OutlinedTextField(tax, { tax = it }, label = { Text(hiltechText("قيمة الضريبة", "Tax amount")) })
        Text(hiltechText("القيم مبالغ بعملة العرض وليست نسبًا؛ أدخل الضريبة المعتمدة للمعاملة.", "Amounts in quote currency, not percentages; enter the approved tax amount."))
        Button(
            enabled=validCommercialDecimal(discount) && validCommercialDecimal(tax) && deal.boqVersions.any { it.id == boqId } && payment.isNotBlank() &&
                delivery.isNotBlank() && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(validity),
            onClick={onAction(CommercialDealAction.DraftQuote(
                op.id,boqId,payment,delivery,exclusions,validity,discount,tax))},
        ) { Text(hiltechText("إنشاء إصدار جديد لعرض السعر", "Create new quote version")) }
    }
    Text(hiltechText("نسخة العميل لا تتضمن التكلفة الداخلية أو هامش الربح.", "Customer copies exclude internal cost and margin."))
}

@Composable
private fun QuoteWhatsappPanel(quoteId: String, history: List<com.hiltech.shared.core.commercial.CommercialWhatsappStatusDto>, busy: Boolean, send: ((String, String) -> Unit)?) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("إرسال عبر WhatsApp Business", "Send via WhatsApp Business"), style = MaterialTheme.typography.titleMedium)
        history.forEach { item ->
            HiltechKeyValue(item.recipient + " · " + item.createdAt, if(item.state == "API_ACCEPTED")
                hiltechText("قبلت Meta طلب الإرسال؛ التسليم غير مؤكد","Meta accepted the request; delivery unconfirmed")
                else hiltechText("نتيجة غير مؤكدة — تحقق قبل إعادة الإرسال","Uncertain outcome — verify before resending"))
        }
        if (send != null) {
            var recipient by remember(quoteId) { mutableStateOf("") }
            var confirmed by remember(quoteId) { mutableStateOf(false) }
            OutlinedTextField(recipient,{ recipient = it; confirmed = false },label={Text(hiltechText("رقم العميل الدولي","International customer number"))},modifier=Modifier.fillMaxWidth())
            Row {
                Checkbox(confirmed,{confirmed = it})
                Text(hiltechText("أؤكد موافقة العميل على تلقي العرض عبر واتساب وصحة الرقم.","I confirm the recipient consented to WhatsApp delivery and the number is correct."))
            }
            Button(enabled=confirmed && recipient.trim().removePrefix("+").matches(Regex("[1-9][0-9]{7,14}")) && !busy,onClick={
                send(quoteId,recipient.trim()); confirmed=false
            }) { Text(hiltechText("إرسال نسخة العرض","Send released quotation")) }
            Text(hiltechText("يتطلب اتصال Meta وقالب مستند معتمدًا. يمكنك استخدام المشاركة العادية بدلًا منه.","Requires a configured Meta connection and approved document template. Native sharing is also available."))
        }
    }
}

@Composable
private fun QuoteEmailPanel(quoteId: String, history: List<com.hiltech.shared.core.commercial.CommercialQuoteEmailStatusDto>, busy: Boolean, send: ((String, String) -> Unit)?) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("الإرسال المباشر بالبريد", "Direct email"), style = MaterialTheme.typography.titleMedium)
        history.forEach { item ->
            val status = when (item.state) {
                "SMTP_ACCEPTED" -> hiltechText("قبله خادم البريد؛ الاستلام غير مؤكد", "Accepted by mail server; delivery unconfirmed")
                else -> hiltechText("نتيجة غير مؤكدة — تحقق قبل إعادة الإرسال", "Uncertain outcome — verify before resending")
            }
            HiltechKeyValue(item.recipient + " · " + item.createdAt, status)
        }
        if (send != null) {
            var recipient by remember(quoteId) { mutableStateOf("") }
            var confirmed by remember(quoteId) { mutableStateOf(false) }
            OutlinedTextField(recipient, { recipient = it; confirmed = false }, label = { Text(hiltechText("البريد الإلكتروني للمستلم", "Recipient email")) }, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(confirmed, { confirmed = it })
                Text(hiltechText("أؤكد إرسال نسخة العرض المعتمدة إلى هذا العنوان.", "I confirm sending the released quote to this address."))
            }
            Button(enabled = confirmed && recipient.contains("@") && !busy, onClick = {
                send(quoteId, recipient.trim()); confirmed = false
            }) { Text(hiltechText("إرسال عرض السعر", "Send quotation")) }
        }
    }
}

@Composable
private fun QuoteDispatchPanel(quoteId: String, history: List<CommercialQuoteDispatchDto>, busy: Boolean, record: ((String, String, String) -> Unit)?) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("سجل إرسال العرض", "Recorded dispatch history"), style = MaterialTheme.typography.titleMedium)
        history.forEach { item ->
            HiltechKeyValue(item.channel + " · " + item.recordedAt, item.recipientReference)
        }
        if (history.isEmpty()) Text(hiltechText("لا يوجد إرسال مسجل.", "No dispatch recorded."))
        if (record != null) {
            var channel by remember(quoteId) { mutableStateOf("EMAIL") }
            var recipient by remember(quoteId) { mutableStateOf("") }
            var confirmed by remember(quoteId) { mutableStateOf(false) }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("EMAIL", "WHATSAPP", "PRINT", "OTHER").forEach { choice ->
                    androidx.compose.material3.FilterChip(selected = channel == choice, onClick = { channel = choice; confirmed = false }, label = { Text(choice) })
                }
            }
            OutlinedTextField(recipient, { recipient = it; confirmed = false }, label = { Text(hiltechText("مرجع المستلم", "Recipient reference")) }, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                Text(hiltechText("أؤكد أنني أرسلت نسخة PDF الصادرة بالفعل.", "I confirm I have actually sent the released PDF."))
            }
            Button(enabled = confirmed && recipient.isNotBlank() && !busy, onClick = {
                record(quoteId, channel, recipient.trim())
                confirmed = false
            }) { Text(hiltechText("تسجيل الإرسال الفعلي", "Record actual dispatch")) }
            Text(hiltechText("هذا سجل بإقرار المستخدم، وليس تأكيد استلام من مزود الخدمة.", "This records the operator's confirmation, not a provider delivery receipt."))
        }
    }
}

@Composable
private fun ReleasedQuotePreview(quote: ClientQuoteDto) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        HiltechSectionHeading("HILTECH / " + quote.quoteCode, hiltechText("عرض سعر", "Quotation"), "v" + quote.versionNumber)
        HiltechKeyValue(hiltechText("العميل", "Customer"), quote.clientName ?: quote.clientOrganizationId)
        HiltechKeyValue(hiltechText("جهة الإصدار", "Issuer"), quote.issuerName ?: "HILTECH")
        HiltechKeyValue(hiltechText("صالح حتى", "Valid until"), quote.validityDate)
        HiltechRecordTable(listOf(
            HiltechTableColumn(hiltechText("البند", "Item"), 2f),
            HiltechTableColumn(hiltechText("الكمية", "Quantity"), numeric = true),
            HiltechTableColumn(hiltechText("الوحدة", "Unit")),
            HiltechTableColumn(hiltechText("سعر الوحدة", "Unit price"), numeric = true),
        ), quote.lines.map { HiltechTableRecord(listOf(it.lineCode + " · " + it.description, it.quantity.content, it.unit, it.unitPrice.content + " " + quote.currency)) },
            hiltechText("لا توجد بنود", "No lines"))
        quote.subtotal?.let { HiltechKeyValue(hiltechText("قبل الخصم والضريبة", "Subtotal"), it.content + " " + quote.currency) }
        HiltechKeyValue(hiltechText("الخصم", "Discount"), quote.discountAmount.content + " " + quote.currency)
        HiltechKeyValue(hiltechText("الضريبة", "Tax"), quote.taxAmount.content + " " + quote.currency)
        HiltechKeyValue(hiltechText("الإجمالي المعتمد", "Released total"), quote.totalPrice.content + " " + quote.currency)
        HiltechKeyValue(hiltechText("شروط الدفع", "Payment terms"), quote.paymentTerms)
        HiltechKeyValue(hiltechText("شروط التسليم", "Delivery terms"), quote.deliveryTerms)
        if (quote.exclusions.isNotBlank()) HiltechKeyValue(hiltechText("الاستبعادات", "Exclusions"), quote.exclusions)
    }
}

@Composable
private fun CommercialAwardPanel(
    deal: CommercialDealDetailDto,
    canAward: Boolean,
    canCreateProject: Boolean,
    onAction: (CommercialDealAction) -> Unit,
) {
    val op=deal.opportunity
    Text(hiltechText("العقد وأمر شراء العميل والتحويل لمشروع", "Contract / client PO / project handoff"),style=MaterialTheme.typography.titleMedium)
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
    HiltechRecordPicker(hiltechText("عرض السعر الصادر", "Released quote"), quoteId,
        deal.quotes.filter { it.state == "RELEASED" }.map { HiltechRecordChoice(it.id, it.quoteCode, "v" + it.versionNumber + " · " + it.totalPrice.content + " " + it.currency) }, { quoteId = it }, allowKnownReference = false)
    OutlinedTextField(value=contractCode,onValueChange={contractCode=it},label={Text(hiltechText("مرجع العقد", "Contract reference"))})
    OutlinedTextField(value=warranty,onValueChange={warranty=it},label={Text(hiltechText("شروط الضمان (اختياري)", "Actual warranty terms (optional)"))})
    OutlinedTextField(value=maintenance,onValueChange={maintenance=it},label={Text(hiltechText("شروط الدعم (اختياري)", "Actual support terms (optional)"))})
    Button(
        enabled=quoteId.isNotBlank() && contractCode.isNotBlank(),
        onClick={onAction(CommercialDealAction.DraftContract(op.id,quoteId,contractCode,warranty,maintenance))},
    ) { Text(hiltechText("إعداد عقد من العرض الصادر", "Draft contract from released quote")) }
    deal.contracts.forEach { c ->
        Text(c.contractCode + " · " + c.state)
        if(c.state=="DRAFT") {
            var evidenceId by remember(c.id) { mutableStateOf("") }
            OutlinedTextField(value=evidenceId,onValueChange={evidenceId=it},
                label={Text("Canonical READY signed-contract Evidence ID")})
            Button(enabled=evidenceId.isNotBlank(),
                onClick={onAction(CommercialDealAction.ConfirmSigned(c.id,evidenceId))}) {
                Text(hiltechText("التحقق من العقد الموقع", "Verify actual signed contract"))
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
