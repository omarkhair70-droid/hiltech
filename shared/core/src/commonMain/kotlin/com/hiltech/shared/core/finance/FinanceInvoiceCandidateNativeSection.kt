package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class FinanceInvoiceCandidateDraft(
    val opportunityId:String="",val evidenceId:String="",val amount:String="",
    val legalReference:String="",val contractReference:String="",val issueDate:String="",val dueDate:String="",
)

fun financeValidCalendarDate(value:String):Boolean {
    if(!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))return false
    val year=value.substring(0,4).toInt()
    val month=value.substring(5,7).toInt()
    val day=value.substring(8,10).toInt()
    if(year==0 || month !in 1..12)return false
    val leap=year%4==0 && (year%100!=0 || year%400==0)
    val days=when(month){2->if(leap)29 else 28;4,6,9,11->30;else->31}
    return day in 1..days
}


/**
 * A commercial award is NOT itself permission to issue an invoice.
 * Keep server ownership and source scope, never fabricate finance totals.
 */
suspend fun financeReadClientAwardChoices(apis:FinanceEvidenceReadApis,org:String,install:String):
    FinanceClientAwardSourcePageDto {
    val page=apis.clientAwards.list(org,install)
    check(page.organizationId.equals(org,true) &&
        page.reportedCompanyReceivableTotal==null &&
        page.reportedCompanyRevenueTotal==null &&
        page.reportedCustomerCollectionsTotal==null &&
        page.rows.all { it.organizationId.equals(org,true) &&
            financeLookupIdIsUuid(it.opportunityId) &&
            financeLookupIdIsUuid(it.projectId) &&
            financeLookupIdIsUuid(it.clientOrganizationId) }) {
        "FINANCE_AWARD_SOURCE_SCOPE_MISMATCH"
    }
    return page
}

fun financeInvoiceCandidateValid(d:FinanceInvoiceCandidateDraft):Boolean =
    financeLookupIdIsUuid(d.opportunityId) && financeLookupIdIsUuid(d.evidenceId) &&
    financeReviewIsAmount(d.amount,false) &&
    d.legalReference.isNotBlank() && d.contractReference.isNotBlank() &&
    financeValidCalendarDate(d.issueDate) && financeValidCalendarDate(d.dueDate) && d.dueDate>=d.issueDate

/** Exact decimal comparison, independent of platform floating point. */
private fun invoiceDecimalKey(value:String):String {
    val whole=value.substringBefore('.').trimStart('0').ifBlank {"0"}
    val fraction=value.substringAfter('.',"").trimEnd('0')
    return if(fraction.isEmpty())whole else "$whole.$fraction"
}

suspend fun financeCreateInvoiceCandidate(apis:FinanceEvidenceReadApis,org:String,install:String,
    d:FinanceInvoiceCandidateDraft,operation:String):FinanceArInvoiceCandidateReceiptDto {
    require(financeInvoiceCandidateValid(d) && financeLookupIdIsUuid(operation))
    val r=apis.arCandidate.create(org,d.opportunityId,operation,d.evidenceId,d.amount,
        d.legalReference.trim(),d.contractReference.trim(),d.issueDate,d.dueDate,install)
    check(r.organizationId.equals(org,true) && r.opportunityId.equals(d.opportunityId,true) &&
        financeLookupIdIsUuid(r.candidateId) && financeLookupIdIsUuid(r.customerOrganizationId) &&
        r.state=="DRAFT_UNISSUED" && financeReviewIsAmount(r.requestedAmount,false) &&
        invoiceDecimalKey(r.requestedAmount)==invoiceDecimalKey(d.amount) &&
        !r.invoiceIssued && !r.receivablePosted && !r.cashCollected && !r.bankReconciled) {
        "FINANCE_INVOICE_CANDIDATE_SOURCE_MISMATCH"
    }
    return r
}

@Composable
fun FinanceInvoiceCandidateSection(identityId:String,org:String,install:String,apis:FinanceEvidenceReadApis,
    newOperationId:()->String,onUseCandidate:(String)->Unit) {
    key(identityId,org,install) {
        val scope=rememberCoroutineScope()
        var expanded by remember {mutableStateOf(false)}
        var draft by remember {mutableStateOf(FinanceInvoiceCandidateDraft())}
        var operation by remember {mutableStateOf<String?>(null)}
        var result by remember {mutableStateOf<FinanceArInvoiceCandidateReceiptDto?>(null)}
        var error by remember {mutableStateOf<String?>(null)}
        var busy by remember {mutableStateOf(false)}
        fun change(next:FinanceInvoiceCandidateDraft) {draft=next;operation=null;error=null;result=null}
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=!busy,onClick={expanded=!expanded}) {
                Text(if(expanded)"إخفاء إنشاء مسودة فاتورة العميل" else "إنشاء مسودة فاتورة من عقد العميل")
            }
            if(!expanded)return@Column
            Text("مسودة الفاتورة",style=MaterialTheme.typography.titleMedium)
            Text("ابدأ من فرصة تجارية معتمدة ومستند استحقاق الفوترة. المسودة ليست فاتورة صادرة.")
            val editable=!busy && result==null
            OutlinedTextField(value=draft.opportunityId,onValueChange={change(draft.copy(opportunityId=it.trim()))},
                label={Text("معرّف الفرصة التجارية المعتمدة")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.evidenceId,onValueChange={change(draft.copy(evidenceId=it.trim()))},
                label={Text("مستند استحقاق الفوترة")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.amount,onValueChange={change(draft.copy(amount=it.trim()))},
                label={Text("قيمة الفاتورة")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.legalReference,onValueChange={change(draft.copy(legalReference=it))},
                label={Text("مرجع المراجعة القانونية والضريبية")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.contractReference,onValueChange={change(draft.copy(contractReference=it))},
                label={Text("مرجع الاستحقاق التعاقدي")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.issueDate,onValueChange={change(draft.copy(issueDate=it.trim()))},
                label={Text("تاريخ الإصدار YYYY-MM-DD")},enabled=editable,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=draft.dueDate,onValueChange={change(draft.copy(dueDate=it.trim()))},
                label={Text("تاريخ الاستحقاق YYYY-MM-DD")},enabled=editable,modifier=Modifier.fillMaxWidth())
            if(draft.issueDate.isNotBlank() && !financeValidCalendarDate(draft.issueDate)) Text("تاريخ الإصدار غير صحيح.",color=MaterialTheme.colorScheme.error)
            if(draft.dueDate.isNotBlank() && (!financeValidCalendarDate(draft.dueDate) || draft.dueDate<draft.issueDate))
                Text("تاريخ الاستحقاق يجب أن يكون تاريخًا صحيحًا لا يسبق الإصدار.",color=MaterialTheme.colorScheme.error)
            OutlinedButton(enabled=editable && financeInvoiceCandidateValid(draft),onClick={
                val op=operation?:newOperationId().also {operation=it}
                val frozen=draft
                busy=true;error=null
                scope.launch {
                    try {result=financeCreateInvoiceCandidate(apis,org,install,frozen,op)}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){error="لم يؤكد المصدر إنشاء المسودة. تحقق من المستند والصلاحية، ثم أعد المحاولة بنفس العملية."}
                    finally {busy=false}
                }
            }) {Text(if(busy)"جارٍ إنشاء المسودة…" else if(operation!=null)"إعادة محاولة نفس المسودة" else "حفظ المسودة")}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            result?.let {receipt->
                Text("مسودة محفوظة: "+receipt.requestedAmount+" "+receipt.currency,style=MaterialTheme.typography.titleSmall)
                Text("مرجع المسودة: "+receipt.candidateId)
                OutlinedButton(onClick={onUseCandidate(receipt.candidateId);expanded=false}) {Text("متابعة تجهيز إصدار الفاتورة")}
                OutlinedButton(onClick={change(FinanceInvoiceCandidateDraft())}) {Text("مسودة جديدة")}
            }
        }
    }
}
