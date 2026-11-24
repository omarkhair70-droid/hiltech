package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun financeCashboxOpeningDraftValid(code:String,currency:String,custodian:String,reference:String):Boolean =
    code.trim().length in 3..64 && currency.matches(Regex("^[A-Z]{3}$")) &&
        financeLookupIdIsUuid(custodian) && reference.trim().length in 8..180

private fun cashboxScope(source:FinanceCashboxReceiptDto,org:String):Boolean =
    source.organizationId.equals(org,ignoreCase=true) &&
        financeLookupIdIsUuid(source.proposalId)

suspend fun activateApprovedCashbox(
    api:FinancePhysicalCashApiClient, org:String, install:String,
    expected:FinanceCashboxReceiptDto, operation:String,
):FinanceCashboxReceiptDto {
    require(cashboxScope(expected,org) && expected.state=="APPROVED" &&
        expected.approvalRequestId?.let(::financeLookupIdIsUuid)==true &&
        expected.cashboxId==null && financeLookupIdIsUuid(operation))
    val before=api.cashboxStatus(org,expected.proposalId,install)
    check(before.copy(replayed=false)==expected.copy(replayed=false)) {
        "FINANCE_CASHBOX_SOURCE_CHANGED"
    }
    val result=api.activateCashbox(org,expected.proposalId,operation,install)
    check(cashboxScope(result,org) && result.proposalId==expected.proposalId &&
        result.approvalRequestId==expected.approvalRequestId &&
        result.cashboxId==expected.proposalId && result.state=="ACTIVE" &&
        result.cashboxCode==expected.cashboxCode && result.currency==expected.currency &&
        result.custodianUserId==expected.custodianUserId &&
        result.openingBalance.matches(Regex("^0(\\.0+)?$"))) {
        "FINANCE_CASHBOX_ACTIVATION_UNCONFIRMED"
    }
    return result
}

fun financeCashboxOpeningWorkspace(
    identityId:String, organizationId:String, installationId:String,
    api:FinancePhysicalCashApiClient, newOperationId:()->String,
):NativeOperationalWorkspace = NativeOperationalWorkspace(
    id="finance-cashbox-opening",
    label="فتح الخزائن",
    canReadSource={api.openingQueue(organizationId,installationId); true},
    hostOwnsScroll=false,
    content={_ ->
        key(identityId,organizationId,installationId) {
            FinanceCashboxOpeningSection(organizationId,installationId,api,newOperationId)
        }
    },
)

@Composable
private fun FinanceCashboxOpeningSection(
    org:String, install:String, api:FinancePhysicalCashApiClient, newOperationId:()->String,
) {
    val scope=rememberCoroutineScope()
    var items by remember {mutableStateOf<List<FinanceCashboxReceiptDto>>(emptyList())}
    var selected by remember {mutableStateOf<FinanceCashboxReceiptDto?>(null)}
    var code by remember {mutableStateOf("")}
    var currency by remember {mutableStateOf("EGP")}
    var custodian by remember {mutableStateOf("")}
    var reference by remember {mutableStateOf("")}
    var draft by remember {mutableStateOf<FinanceCashboxOpeningPrepareDto?>(null)}
    var reviewOp by remember {mutableStateOf<String?>(null)}
    var activateOp by remember {mutableStateOf<String?>(null)}
    var confirm by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var revision by remember {mutableStateOf(0)}
    var lookup by remember {mutableStateOf("")}

    LaunchedEffect(revision) {
        busy=true
        error=null
        try {
            val current=api.openingQueue(org,install)
            check(current.all {cashboxScope(it,org)})
            items=current
        } catch (cancelled:CancellationException) {throw cancelled}
        catch (_:Exception) {error="تعذر الوصول لمقترحات فتح الخزائن."}
        finally {busy=false}
    }
    fun perform(action:suspend ()->Unit) {
        if(busy)return
        busy=true;error=null
        scope.launch {
            try {action()}
            catch (cancelled:CancellationException) {throw cancelled}
            catch (_:Exception) {error="لم تتأكد العملية. حدّث الحالة قبل إعادة المحاولة."}
            finally {busy=false}
        }
    }
    Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("فتح خزنة شركة",style=MaterialTheme.typography.titleMedium)
        Text("الرصيد الافتتاحي صفر. يتم الفتح بعد موافقة مستقلة وتحديد أمين خزنة فعلي من الشركة.")
        OutlinedTextField(value=code,onValueChange={code=it},enabled=!busy&&draft==null,
            label={Text("كود الخزنة")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(value=currency,onValueChange={currency=it.trim().uppercase()},
            enabled=!busy&&draft==null,label={Text("العملة (ISO)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(value=custodian,onValueChange={custodian=it.trim()},
            enabled=!busy&&draft==null,label={Text("معرّف أمين الخزنة من سجل الموظفين")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(value=reference,onValueChange={reference=it},
            enabled=!busy&&draft==null,label={Text("مرجع عهدة الشركة")},modifier=Modifier.fillMaxWidth())
        OutlinedButton(enabled=!busy&&selected==null&&financeCashboxOpeningDraftValid(code,currency,custodian,reference),
            onClick={
                val request=draft?:FinanceCashboxOpeningPrepareDto(
                    newOperationId(),code.trim(),currency,custodian,reference.trim()).also {draft=it}
                perform {
                    val result=api.prepareCashbox(org,request,install)
                    check(cashboxScope(result,org)&&result.cashboxCode==request.cashboxCode&&
                        result.currency==request.currency&&result.custodianUserId==request.custodianUserId)
                    selected=result;lookup=result.proposalId
                }
            }) {Text("تجهيز فتح الخزنة")}
        OutlinedButton(enabled=!busy,onClick={
            draft=null;selected=null;lookup="";reviewOp=null;activateOp=null;confirm=false
            code="";custodian="";reference=""
        }) {Text("بدء طلب جديد")}
        OutlinedButton(enabled=!busy,onClick={revision++}) {Text("تحديث طلبات فتح الخزائن")}
        items.forEach {item ->
            OutlinedButton(enabled=!busy,onClick={
                if(selected?.proposalId!=item.proposalId){reviewOp=null;activateOp=null}
                selected=item;lookup=item.proposalId;confirm=false
            }) {Text(item.cashboxCode+" · "+item.currency+" · "+item.state)}
        }
        OutlinedTextField(value=lookup,onValueChange={
            lookup=it.trim();selected=null;reviewOp=null;activateOp=null;confirm=false
        },enabled=!busy,label={Text("متابعة طلب خزنة بمعرّفه")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedButton(enabled=!busy&&financeLookupIdIsUuid(lookup),onClick={
            perform {
                val fresh=api.cashboxStatus(org,lookup,install)
                check(cashboxScope(fresh,org)&&fresh.proposalId==lookup)
                if(fresh.proposalId!=selected?.proposalId){reviewOp=null;activateOp=null}
                selected=fresh;confirm=false
            }
        }) {Text("قراءة الحالة")}
        selected?.let {source ->
            Text("الخزنة: "+source.cashboxCode+" · "+source.currency)
            Text("أمين الخزنة: "+source.custodianUserId)
            Text("الحالة: "+source.state)
            source.approvalRequestId?.let {Text("الموافقة: "+it)}
            source.cashboxId?.let {Text("الخزنة المفعلة: "+it)}
            if(source.state=="PROPOSED"&&source.approvalRequestId==null) {
                OutlinedButton(enabled=!busy,onClick={
                    val op=reviewOp?:newOperationId().also {reviewOp=it}
                    perform {
                        val before=api.cashboxStatus(org,source.proposalId,install)
                        check(before.copy(replayed=false)==source.copy(replayed=false))
                        val result=api.reviewCashbox(org,source.proposalId,op,install)
                        check(cashboxScope(result,org)&&result.proposalId==source.proposalId&&
                            result.state=="PENDING"&&result.approvalRequestId!=null)
                        selected=result
                    }
                }) {Text("طلب مراجعة فتح الخزنة")}
            }
            if(source.state=="APPROVED"&&source.cashboxId==null) {
                OutlinedButton(enabled=!busy,onClick={confirm=true}) {Text("تفعيل الخزنة المعتمدة")}
                if(confirm) {
                    Text("سيتم فتح الخزنة برصيد صفر فقط وفق موافقة الشركة الظاهرة.")
                    OutlinedButton(enabled=!busy,onClick={confirm=false}) {Text("إلغاء")}
                    OutlinedButton(enabled=!busy,onClick={
                        val op=activateOp?:newOperationId().also {activateOp=it}
                        perform {selected=activateApprovedCashbox(api,org,install,source,op);confirm=false}
                    }) {Text("تأكيد التفعيل")}
                }
            }
        }
        if(busy)Text("جارٍ الاتصال بالمصدر…")
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
