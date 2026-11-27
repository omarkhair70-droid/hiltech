package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    Text(hiltechText("ترحيل أثر السندات والتخصيصات المسجلة مسبقًا إلى دليل الحسابات. لا ينفّذ تحويلًا بنكيًا ولا يؤكد رصيد البنك الكامل.",
        "Post the accounting effect of recorded allocations and vouchers. This does not initiate a bank transfer or confirm the full bank balance."))
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
                if(item.debitAccountName==null || item.creditAccountName==null)
                    Text(hiltechText("الحساب المقابل غير مكتمل. اربط البنك أو الخزنة بحساب معتمد في الإعداد المالي.",
                        "The counterpart account is incomplete. Link the bank or cashbox to an approved account in financial setup."))
                else {
                    HiltechKeyValue(hiltechText("مدين","Debit"),item.debitAccountName)
                    HiltechKeyValue(hiltechText("دائن","Credit"),item.creditAccountName)
                }
                if(item.sourceType=="BANK_VOID") Text(hiltechText("تصحيح قيد التخصيص؛ لا يعني رد أموال من البنك.","Accounting allocation correction, not proof of a bank refund."))
                val ready=q.period!=null && item.debitAccountId!=null && item.creditAccountId!=null &&
                    item.debitAccountId!=item.creditAccountId && item.debitAccountName!=null && item.creditAccountName!=null
                OutlinedButton(enabled=ready && pending==null && !busy,onClick={
                    val period=q.period ?: return@OutlinedButton
                    val intent=financeSettlementIntent(item,period,newOperationId()) ?: return@OutlinedButton
                    pendingJson=Json.encodeToString(SettlementAttempt.serializer(),SettlementAttempt(intent,item,period))
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
                            pendingJson=null;attempted=false;uncertain=false;rejected=false;offset=0;refresh()
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
