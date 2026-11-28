package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.people.companyResponsibilityFailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SettlementAttempt(val intent:FinanceSettlementPostIntent,val source:FinanceSettlementSourceDto,val period:FinanceDeskPeriodDto)

@Composable
internal fun settlementLedgerTitle(type:String):String=when(type) {
    "BANK_ALLOCATION"->hiltechText("تخصيص بنكي","Bank allocation")
    "CASH_VOUCHER"->hiltechText("سند خزنة","Cash voucher")
    "BANK_VOID"->hiltechText("عكس تخصيص سابق","Reverse an earlier allocation")
    "EMPLOYEE_FUNDING"->hiltechText("صرف سلفة أو عهدة","Employee advance or imprest funding")
    "EMPLOYEE_RETURN"->hiltechText("استرداد سلفة أو رد عهدة","Employee advance recovery or custody return")
    "PAYROLL_RECOVERY"->hiltechText("تحصيل زيادة راتب","Payroll overpayment collection")
    "PAYROLL_OPENING_PAYMENT"->hiltechText("صرف رواتب افتتاحية","Historical payroll payment")
    "IMPREST_EXPENSE"->hiltechText("تسوية مستندات عهدة","Accepted imprest receipts")
    "EXPENSE_REIMBURSEMENT"->hiltechText("رد مصروف موظف","Employee expense reimbursement")
    else->hiltechText("قيد تسوية","Settlement journal")
}

/** Embedded in the existing daily desk. The source is already approved; this is
 * recording a mapped double-entry effect, never executing another payment. */
@Composable
internal fun FinanceSettlementLedgerSection(client:FinanceSettlementLedgerApiClient,org:String,installation:String,
    newOperationId:()->String,onReauthenticate:(()->Unit)?,onLockedChange:(Boolean)->Unit) {
    val scope=rememberCoroutineScope()
    var offset by rememberSaveable {mutableIntStateOf(0)}
    var revision by remember {mutableIntStateOf(0)}
    var data by remember {mutableStateOf<FinanceSettlementQueueDto?>(null)}
    var loading by remember {mutableStateOf(true)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var pendingJson by rememberSaveable {mutableStateOf<String?>(null)}
    var attempted by rememberSaveable {mutableStateOf(false)}
    var uncertain by rememberSaveable {mutableStateOf(false)}
    var rejected by rememberSaveable {mutableStateOf(false)}
    var saved by remember {mutableStateOf<FinanceSettlementReceiptDto?>(null)}
    val pending=pendingJson?.let {Json.decodeFromString(SettlementAttempt.serializer(),it)}
    var choiceSource by rememberSaveable {mutableStateOf("")}
    var chosenDebit by rememberSaveable {mutableStateOf("")}
    var treatment by rememberSaveable {mutableStateOf("")}
    var firstRecognition by rememberSaveable {mutableStateOf(false)}
    fun clearChoice(){choiceSource="";chosenDebit="";treatment="";firstRecognition=false}
    LaunchedEffect(busy,pendingJson) {onLockedChange(busy || pendingJson!=null)}
    fun refresh() {data=null;loading=true;revision++}
    LaunchedEffect(client,org,installation,offset,revision) {
        loading=true;data=null
        try {data=client.queue(org,offset,installation)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=(failure as? HiltechApiException)?.code ?: "SOURCE_UNAVAILABLE"}
        finally {loading=false}
    }

    Text(hiltechText("قيود السداد والتحصيل","Settlement accounting"),style=MaterialTheme.typography.titleLarge)
    Text(hiltechText("ترحيل السداد والتحصيل والسلف والعهد والمصروفات المسجلة. لا ينفّذ تحويلًا بنكيًا ولا يؤكد رصيد البنك الكامل.",
        "Post recorded payments, collections, employee custody and paid costs. No bank transfer is initiated and no full bank balance is asserted."))
    if(loading) Text(hiltechText("جارٍ قراءة الحركات…","Reading recorded movements…"))
    TextButton(enabled=!busy && !loading,onClick={error=null;refresh()}) {Text(hiltechText("تحديث من المصدر","Refresh source"))}
    saved?.let { receipt ->
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("تم ترحيل القيد وتأكيده من المصدر","Journal posted and confirmed at source"),color=MaterialTheme.colorScheme.primary)
            Text(receipt.amount+" "+receipt.currency,style=MaterialTheme.typography.headlineSmall)
        }
    }
    data?.takeIf {!loading && it.offset==offset}?.let { q ->
        if(q.period==null) Text(hiltechText("لا توجد فترة حالية مفتوحة وواضحة. راجع إعداد الفترات قبل الترحيل.",
            "No unique current open period. Review accounting period setup before posting."),color=MaterialTheme.colorScheme.error)
        else HiltechKeyValue(hiltechText("فترة الترحيل الحالية","Current posting period"),q.period.dateStart+" — "+q.period.dateEnd)
        if(q.records.isEmpty()) Text(hiltechText("لا توجد حركات غير مرحلة ضمن المسؤوليات المتاحة.","No unposted movements in the available responsibilities."))
        q.records.forEach { item ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(settlementLedgerTitle(item.sourceType),style=MaterialTheme.typography.titleMedium)
                item.counterpartyName?.let {Text(it)}
                Text(item.instrumentName)
                Text(item.amount+" "+item.currency,style=MaterialTheme.typography.headlineSmall)
                HiltechKeyValue(hiltechText("سُجل المصدر","Source recorded"),item.sourceRecordedAt.replace('T',' '))
                val selecting=choiceSource==item.sourceId && pending==null
                val required=item.requiredDebitKind
                val selectedChoice=item.debitChoices.singleOrNull {selecting && it.id==chosenDebit}
                val debitName=selectedChoice?.name ?: item.debitAccountName
                if(item.creditAccountName==null || (required==null && debitName==null))
                    Text(hiltechText("الحساب المقابل غير مكتمل. اربط مصدر البنك أو الخزنة أو رحّل أصل السلفة والعهدة أولًا.",
                        "Counterpart account is incomplete. Link the bank/cashbox or post the original employee funding first."))
                if(required!=null) {
                    if(!selecting) TextButton(enabled=pending==null && !busy,onClick={
                        clearChoice();choiceSource=item.sourceId
                    }) {Text(hiltechText("اختيار الحساب المدين","Choose debit account"))}
                    else {
                        HiltechRecordPicker(hiltechText("الحساب المعتمد","Approved account"),chosenDebit,
                            item.debitChoices.map {HiltechRecordChoice(it.id,it.name,it.code)},
                            onChange={chosenDebit=it},allowKnownReference=false,allowClear=false,enabled=!busy)
                        if(item.debitChoices.isEmpty()) Text(hiltechText("لا يوجد حساب مناسب معتمد في دليل الحسابات.","No suitable approved chart account."))
                        if(item.hasMoreDebitChoices) Text(hiltechText("تظهر أول 100 حساب مطابق.","Showing the first 100 matching accounts."))
                        if(required=="COST") {
                            Text(hiltechText("يستخدم هذا المسار للمبلغ الذي لم يُثبت محاسبيًا من قبل وينتمي بالكامل إلى حساب واحد. إذا سُجل سابقًا أو يحتاج توزيعًا فلا ترحّله هنا.",
                                "Use only for an amount not previously recognized that belongs entirely to one account. Do not post here if previously accrued or needing a split."))
                            Row {
                                Checkbox(checked=firstRecognition,onCheckedChange={firstRecognition=it},enabled=!busy)
                                Text(hiltechText("تحققت من عدم تسجيل المبلغ سابقًا ومن صحة التصنيف الكامل.","I verified no prior recognition and the classification of the entire amount."))
                            }
                            OutlinedTextField(treatment,{treatment=it.take(240)},Modifier.fillMaxWidth(),singleLine=true,
                                label={Text(hiltechText("مرجع المعالجة المحاسبية المعتمدة","Approved accounting treatment reference"))},enabled=!busy)
                        }
                    }
                }
                debitName?.let {HiltechKeyValue(hiltechText("مدين","Debit"),it)}
                item.creditAccountName?.let {HiltechKeyValue(hiltechText("دائن","Credit"),it)}
                if(item.sourceType=="BANK_VOID") Text(hiltechText("تصحيح قيد التخصيص؛ لا يعني رد أموال من البنك.","Accounting allocation correction, not proof of a bank refund."))
                val debitId=selectedChoice?.id ?: item.debitAccountId
                val treatmentRef=treatment.trim().takeIf {required=="COST"}
                val ready=q.period!=null && debitId!=null && item.creditAccountId!=null &&
                    debitId!=item.creditAccountId && debitName!=null && item.creditAccountName!=null &&
                    (required==null || selectedChoice!=null) && (required!="COST" || (firstRecognition && treatmentRef.orEmpty().length>=8))
                OutlinedButton(enabled=ready && pending==null && !busy,onClick={
                    val period=q.period ?: return@OutlinedButton
                    val intent=financeSettlementIntent(item,period,newOperationId(),selectedChoice?.id,treatmentRef) ?: return@OutlinedButton
                    val reviewed=item.copy(debitAccountId=debitId,debitAccountName=debitName,debitChoices=emptyList())
                    pendingJson=Json.encodeToString(SettlementAttempt.serializer(),SettlementAttempt(intent,reviewed,period))
                    attempted=false;uncertain=false;rejected=false;error=null;saved=null
                }) {Text(hiltechText("مراجعة القيد","Review journal"))}
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(enabled=!busy && pending==null && offset>0,onClick={offset=(offset-50).coerceAtLeast(0);data=null;loading=true}) {Text(hiltechText("السابق","Previous"))}
            Text((offset/50+1).toString())
            TextButton(enabled=!busy && pending==null && q.hasMore && offset<1_000_000,onClick={offset+=50;data=null;loading=true}) {Text(hiltechText("التالي","Next"))}
        }
    }
    pending?.let { attempt ->
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("تأكيد ترحيل القيد","Confirm journal posting"),style=MaterialTheme.typography.titleLarge)
            Text(settlementLedgerTitle(attempt.source.sourceType)+" · "+attempt.source.instrumentName)
            attempt.source.counterpartyName?.let {Text(it)}
            Text(attempt.intent.amount+" "+attempt.intent.currency,style=MaterialTheme.typography.headlineSmall)
            HiltechKeyValue(hiltechText("الفترة","Period"),attempt.period.dateStart+" — "+attempt.period.dateEnd)
            HiltechKeyValue(hiltechText("مدين","Debit"),attempt.source.debitAccountName.orEmpty())
            HiltechKeyValue(hiltechText("دائن","Credit"),attempt.source.creditAccountName.orEmpty())
            attempt.intent.accountingTreatmentRef?.let {HiltechKeyValue(hiltechText("مرجع المعالجة","Treatment reference"),it)}
            if(uncertain) Text(hiltechText("نتيجة الطلب السابق غير مؤكدة. إعادة نفس الطلب آمنة ولا تنشئ قيدًا ثانيًا.",
                "Previous outcome is unconfirmed. Retry the identical request; it cannot create a second journal."))
            Button(enabled=!busy && !loading,onClick={
                if(rejected) {pendingJson=null;attempted=false;uncertain=false;rejected=false;error=null;refresh()}
                else {
                    busy=true;error=null
                    val priorUnknown=uncertain
                    scope.launch {
                        try {
                            attempted=true;uncertain=true
                            saved=client.post(org,attempt.intent,installation)
                            pendingJson=null;attempted=false;uncertain=false;rejected=false;clearChoice();offset=0;refresh()
                        } catch(cancelled:CancellationException){throw cancelled}
                        catch(failure:Exception){
                            error=(failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                            val disposition=companyResponsibilityFailureDisposition(priorUnknown,failure)
                            uncertain=disposition.hasUnknownOutcome;rejected=disposition.rejectionConfirmed
                        } finally {busy=false}
                    }
                }
            }) {Text(when {
                rejected->hiltechText("مراجعة المصدر","Review current source")
                attempted->hiltechText("إعادة نفس الطلب","Retry same request")
                else->hiltechText("تأكيد الترحيل","Confirm posting")
            })}
            if(!attempted || rejected) TextButton(enabled=!busy,onClick={pendingJson=null;attempted=false;uncertain=false;rejected=false}) {Text(hiltechText("رجوع","Back"))}
        }
    }
    error?.let { problem ->
        Text(when(problem) {
            "FINANCE_SETTLEMENT_LEDGER_CHANGED"->hiltechText("تغير المصدر أو الحساب أو الفترة، أو سُجل القيد بالفعل. راجع الدفتر والمصدر قبل أي طلب جديد.",
                "Source, account or period changed, or the journal is already posted. Review the ledger and source before a new request.")
            "FINANCE_SETTLEMENT_LEDGER_UNAVAILABLE"->hiltechText("مسؤولية الترحيل الحالية غير متاحة.","Current posting authority is unavailable.")
            "REAUTH_REQUIRED","UNAUTHENTICATED","SESSION_NOT_ACTIVE","RECENT_REAUTH_REQUIRED"->hiltechText("أكد تسجيل الدخول لإتمام الترحيل.","Confirm sign-in to complete posting.")
            else->hiltechText("لم يتأكد المصدر أو نتيجة الطلب.","Source or request outcome not confirmed.")
        },color=MaterialTheme.colorScheme.error)
        if(onReauthenticate!=null && problem in setOf("REAUTH_REQUIRED","UNAUTHENTICATED","SESSION_NOT_ACTIVE","RECENT_REAUTH_REQUIRED"))
            TextButton(enabled=!busy,onClick=onReauthenticate){Text(hiltechText("تأكيد تسجيل الدخول","Confirm sign-in"))}
    }
}
