package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.hiltechDateText
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.people.companyResponsibilityFailureDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Compatibility helper for callers with a previously supplied human code. */
fun financeCashboxOpeningDraftValid(code:String,currency:String,custodian:String,reference:String):Boolean =
    code.trim().length in 3..64 && currency.matches(Regex("^[A-Z]{3}$")) &&
        financeLookupIdIsUuid(custodian) && reference.trim().length in 8..180

suspend fun activateApprovedCashbox(
    api:FinancePhysicalCashApiClient,org:String,install:String,
    expected:FinanceCashboxReceiptDto,operation:String,
):FinanceCashboxReceiptDto {
    require(validOpeningReceipt(expected,org) && expected.state=="APPROVED" &&
        financeLookupIdIsUuid(expected.approvalRequestId.orEmpty()) && expected.cashboxId==null && financeLookupIdIsUuid(operation))
    val before=api.cashboxStatus(org,expected.proposalId,install)
    check(sameCashboxOpeningSource(before,expected) && before.state in setOf("APPROVED","ACTIVE") &&
        before.approvalRequestId==expected.approvalRequestId) { "FINANCE_CASHBOX_SOURCE_CHANGED" }
    return api.activateCashbox(org,expected.proposalId,operation,install).also { result ->
        check(sameCashboxOpeningSource(result,expected) && result.state=="ACTIVE" && result.cashboxId==expected.proposalId &&
            result.approvalRequestId==expected.approvalRequestId) { "FINANCE_CASHBOX_ACTIVATION_UNCONFIRMED" }
    }
}

fun financeCashboxOpeningWorkspace(
    identityId:String,organizationId:String,installationId:String,
    api:FinancePhysicalCashApiClient,newOperationId:()->String,
    onOpenCompanySettings:(()->Unit)?=null,
):NativeOperationalWorkspace = NativeOperationalWorkspace(
    id="finance-cashbox-opening",label="فتح الخزائن",
    canReadSource={ api.openingWorkbench(organizationId,installationId); true },hostOwnsScroll=false,
    content={ _ -> key(identityId,organizationId,installationId) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FinanceCashboxOpeningWorkspace(organizationId,installationId,api,newOperationId,onOpenCompanySettings)
        }
    } },
)

/** Same source and commands, a wide two-column workbench and a stacked mobile form. */
@Composable
fun FinanceCashboxOpeningWorkspace(
    organizationId:String,installationId:String,api:FinancePhysicalCashApiClient,newOperationId:()->String,
    onOpenCompanySettings:(()->Unit)?=null,
) {
    val jobs=rememberCoroutineScope()
    var current by remember(organizationId) { mutableStateOf<FinanceCashboxOpeningWorkbenchDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var historyQuery by rememberSaveable(organizationId) { mutableStateOf("") }
    var appliedHistoryQuery by rememberSaveable(organizationId) { mutableStateOf("") }
    var custodianQuery by rememberSaveable(organizationId) { mutableStateOf("") }
    var directory by remember { mutableStateOf<FinanceCashboxCustodianDirectoryDto?>(null) }
    var directoryLoading by remember { mutableStateOf(false) }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var custodianJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var currency by rememberSaveable(organizationId) { mutableStateOf("EGP") }
    var otherCurrency by rememberSaveable(organizationId) { mutableStateOf(false) }
    var reference by rememberSaveable(organizationId) { mutableStateOf("") }
    var selectedJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var receiptJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var prepared by rememberSaveable(organizationId) { mutableStateOf(false) }
    var pendingJson by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var commandError by remember { mutableStateOf<String?>(null) }
    val custodian=custodianJson?.let { Json.decodeFromString(FinanceCashboxCustodianChoiceDto.serializer(),it) }
    val selected=selectedJson?.let { Json.decodeFromString(FinanceCashboxReceiptDto.serializer(),it) }
    val receipt=receiptJson?.let { Json.decodeFromString(FinanceCashboxReceiptDto.serializer(),it) }
    val pending=pendingJson?.let { Json.decodeFromString(FinanceCashboxOpeningIntent.serializer(),it) }

    LaunchedEffect(organizationId,refresh,appliedHistoryQuery) {
        loading=true;loadError=null
        try {
            current=api.openingWorkbench(organizationId,installationId,appliedHistoryQuery)
            selected?.let { before -> current?.items?.firstOrNull { it.proposalId==before.proposalId }?.let {
                selectedJson=Json.encodeToString(FinanceCashboxReceiptDto.serializer(),it)
            } }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:Exception) { current=null;loadError=(failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { loading=false }
    }
    LaunchedEffect(organizationId,refresh,custodianQuery,current?.canPrepare,current?.reviewSourceVersion) {
        directory=null;directoryError=null
        if(current?.canPrepare!=true || current?.reviewReady!=true) return@LaunchedEffect
        directoryLoading=true
        try {
            delay(200)
            directory=api.custodianChoices(organizationId,installationId,custodianQuery)
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:Exception) { directoryError=(failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { directoryLoading=false }
    }
    fun review(intent:FinanceCashboxOpeningIntent) {
        pendingJson=Json.encodeToString(FinanceCashboxOpeningIntent.serializer(),intent)
        attempted=false;unknown=false;rejected=false;commandError=null;dialog=true
    }
    fun freshDraft() { prepared=false;custodianJson=null;custodianQuery="";reference="";receiptJson=null }

    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hiltechText("العهد النقدية", "Cash custody"),style=MaterialTheme.typography.headlineSmall)
                Text(hiltechText("طلب واضح، مراجعة مستقلة، ثم فتح برصيد صفر.", "Prepare a clear request, obtain independent review, then open with a zero balance."),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick={ refresh++ },enabled=!busy&&!loading) { Text(hiltechText("تحديث", "Refresh")) }
        }
        if(loading) CircularProgressIndicator()
        loadError?.let { Text(cashboxError(it),color=MaterialTheme.colorScheme.error) }
        if(pending!=null&&!dialog) HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("يوجد طلب محفوظ يحتاج تأكيد نتيجته.", "A saved request still needs its outcome confirmed."))
            Button(onClick={ dialog=true }) { Text(hiltechText("متابعة الطلب المحفوظ", "Continue saved request")) }
        }
        receipt?.let { saved -> HiltechPanel(Modifier.fillMaxWidth()) {
            Text(hiltechText("آخر نتيجة مؤكدة", "Last confirmed result"),style=MaterialTheme.typography.labelLarge)
            Text(saved.cashboxCode,style=MaterialTheme.typography.titleLarge.copy(textDirection=TextDirection.Ltr))
            Text(cashboxState(saved.state),color=MaterialTheme.colorScheme.primary)
            Text(saved.custodianDisplayName.ifBlank { hiltechText("أمين العهدة المسجل", "Recorded custodian") }+" · "+saved.currency)
        } }
        current?.takeUnless { loading }?.let { source ->
            val form:@Composable ()->Unit = {
                if(source.canPrepare) HiltechPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(hiltechText("طلب فتح عهدة", "Request a cashbox"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        if(prepared) TextButton(onClick={ freshDraft() },enabled=pending==null&&!busy) { Text(hiltechText("طلب جديد", "New request")) }
                    }
                    if(!prepared) Text(hiltechText("يخصص النظام رقمًا داخليًا عند حفظ الطلب. يظل الرصيد صفرًا حتى تسجيل حركة نقدية معتمدة.",
                        "An internal number is assigned when the request is saved. The balance remains zero until an approved cash movement is recorded."),
                        style=MaterialTheme.typography.bodyMedium)
                    else Text(hiltechText("حُفظ الطلب. تابع حالته وإجراءه التالي من السجل، أو ابدأ طلبًا جديدًا لعهدة أخرى.",
                        "The request is saved. Follow its status and next action in history, or start a new request for another cashbox."),style=MaterialTheme.typography.bodyMedium)
                    source.preparationIssueCode?.takeUnless { prepared }?.let { issue ->
                        Text(cashboxError(issue),color=MaterialTheme.colorScheme.error)
                        if(issue=="FINANCE_CASHBOX_REVIEW_SETUP_REQUIRED" && onOpenCompanySettings!=null)
                            TextButton(onClick=onOpenCompanySettings) { Text(hiltechText("فتح إعداد المراجعات", "Open review setup")) }
                    }
                    if(source.reviewReady&&!prepared) {
                        Text(hiltechText("يراجع الطلب شخص مستقل عن مُعده وعن أمين العهدة.",
                            "The reviewer must be independent of both the preparer and the custodian."),style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(custodianQuery,{ custodianQuery=it.take(120) },Modifier.fillMaxWidth(),singleLine=true,
                            enabled=pending==null&&!busy&&!prepared,label={ Text(hiltechText("ابحث عن أمين العهدة بالاسم أو كود الموظف", "Search custodians by name or employee code")) })
                        if(directoryLoading) CircularProgressIndicator()
                        directoryError?.let { Text(cashboxError(it),color=MaterialTheme.colorScheme.error) }
                        val choices=(directory?.items.orEmpty()+listOfNotNull(custodian)).distinctBy { it.identityId }
                        HiltechRecordPicker(label=hiltechText("أمين العهدة", "Custodian"),value=custodian?.identityId.orEmpty(),
                            choices=choices.map { HiltechRecordChoice(it.identityId,it.displayName,it.employeeCode) },
                            onChange={ id -> custodianJson=choices.firstOrNull { it.identityId==id }?.let { Json.encodeToString(FinanceCashboxCustodianChoiceDto.serializer(),it) } },
                            allowKnownReference=false,allowClear=true,enabled=pending==null&&!busy&&!prepared&&!directoryLoading)
                        if(!directoryLoading&&directoryError==null&&directory?.items?.isEmpty()==true)
                            Text(hiltechText("لا يوجد موظف مطابق متاح للعهدة. اختر حساب موظف نشط مستقل عن مراجع الطلب.",
                                "No matching custodian is available. Choose a current employee account independent of the reviewer."),
                                style=MaterialTheme.typography.bodySmall)
                        if(directory?.hasMore==true) Text(hiltechText("توجد نتائج أخرى؛ اكتب اسمًا أدق.", "More results are available; refine the name."),style=MaterialTheme.typography.bodySmall)
                        val currencies=listOf(
                            HiltechRecordChoice("EGP",hiltechText("الجنيه المصري", "Egyptian pound"),"EGP"),
                            HiltechRecordChoice("USD",hiltechText("الدولار الأمريكي", "US dollar"),"USD"),
                            HiltechRecordChoice("SAR",hiltechText("الريال السعودي", "Saudi riyal"),"SAR"),
                            HiltechRecordChoice("AED",hiltechText("الدرهم الإماراتي", "UAE dirham"),"AED"),
                            HiltechRecordChoice("EUR",hiltechText("اليورو", "Euro"),"EUR"),
                            HiltechRecordChoice("GBP",hiltechText("الجنيه الإسترليني", "Pound sterling"),"GBP"))
                        if(otherCurrency) OutlinedTextField(currency,{ currency=it.trim().uppercase().take(3) },Modifier.fillMaxWidth(),
                            singleLine=true,enabled=pending==null&&!busy&&!prepared,label={ Text(hiltechText("رمز العملة", "Currency code")) },
                            supportingText={ Text(hiltechText("رمز العملة من ثلاثة أحرف، كما في سجلات الشركة.", "The three-letter currency code used in company records.")) },
                            textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=TextDirection.Ltr))
                        else HiltechRecordPicker(hiltechText("عملة العهدة", "Cashbox currency"),currency,currencies,{ currency=it },
                            allowKnownReference=false,allowClear=false,enabled=pending==null&&!busy&&!prepared)
                        TextButton(onClick={ otherCurrency=!otherCurrency },enabled=pending==null&&!busy&&!prepared) {
                            Text(if(otherCurrency) hiltechText("اختيار من العملات الشائعة", "Choose a common currency") else hiltechText("عملة أخرى", "Another currency"))
                        }
                        OutlinedTextField(reference,{ reference=it.take(180) },Modifier.fillMaxWidth(),minLines=2,maxLines=3,
                            enabled=pending==null&&!busy&&!prepared,label={ Text(hiltechText("مرجع تكليف أمين العهدة", "Company custody reference")) },
                            supportingText={ Text(hiltechText("مرجع التكليف أو وصف واضح يميز هذه العهدة في سجلات الشركة.", "The assignment reference or a clear description identifying this custody in company records.")) })
                        Button(enabled=pending==null&&!busy&&!prepared&&!directoryLoading&&directoryError==null&&custodian!=null&&
                            directory?.reviewSourceVersion==source.reviewSourceVersion&&source.reviewSourceVersion!=null&&
                            currency.matches(Regex("^[A-Z]{3}$"))&&reference.trim().length in 8..180,
                            onClick={
                                val selectedCustodian=requireNotNull(custodian)
                                val operation=newOperationId()
                                review(FinanceCashboxOpeningIntent(operation,organizationId,FinanceCashboxOpeningAction.PREPARE,
                                    prepare=FinanceCashboxOpeningPrepareDto(operation,currency=currency,custodianUserId=selectedCustodian.identityId,
                                        companyCustodyReference=reference.trim(),expectedCustodianVersion=selectedCustodian.sourceVersion,
                                        expectedReviewVersion=source.reviewSourceVersion),custodianDisplayName=selectedCustodian.displayName,
                                    custodianEmployeeCode=selectedCustodian.employeeCode))
                            }) { Text(hiltechText("مراجعة طلب الفتح", "Review opening request")) }
                    }
                }
            }
            val history:@Composable ()->Unit = { HiltechPanel(Modifier.fillMaxWidth()) {
                Text(if(source.canReadCompanyHistory) hiltechText("سجل عهد الشركة", "Company cashbox history") else hiltechText("طلبات العهد التي أعددتها", "Cashbox requests you prepared"),
                    style=MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(historyQuery,{ historyQuery=it.take(120) },Modifier.weight(1f),singleLine=true,
                        label={ Text(hiltechText("الرقم أو اسم الأمين أو المرجع", "Number, custodian or reference")) })
                    TextButton(onClick={ appliedHistoryQuery=historyQuery.trim();refresh++ },enabled=!busy&&!loading) { Text(hiltechText("بحث", "Search")) }
                }
                if(source.items.isEmpty()) Text(hiltechText("لا توجد طلبات مطابقة حتى الآن.", "No matching requests yet."),style=MaterialTheme.typography.bodyMedium)
                source.items.forEach { item ->
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(item.cashboxCode+" · "+item.currency,style=MaterialTheme.typography.titleSmall.copy(textDirection=TextDirection.Ltr))
                            Text(item.custodianDisplayName.ifBlank { hiltechText("أمين العهدة المسجل", "Recorded custodian") },style=MaterialTheme.typography.bodyMedium)
                            Text(cashboxState(item.state)+" · "+hiltechDateText(item.createdAt?.take(10)),style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(enabled=!busy&&pending==null,onClick={ selectedJson=Json.encodeToString(FinanceCashboxReceiptDto.serializer(),item) }) {
                            Text(hiltechText("عرض الطلب", "View request"))
                        }
                    }
                }
                if(source.hasMore) Text(hiltechText("توجد طلبات أخرى؛ استخدم البحث للوصول إليها.", "More requests are available; use search to find them."),style=MaterialTheme.typography.bodySmall)
            } }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth>=900.dp && source.canPrepare) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.Top) {
                    Column(Modifier.weight(0.46f)) { form() };Column(Modifier.weight(0.54f)) { history() }
                } else Column(verticalArrangement=Arrangement.spacedBy(16.dp)) { form();history() }
            }
            selected?.let { item -> HiltechPanel(Modifier.fillMaxWidth()) {
                Text(item.cashboxCode,style=MaterialTheme.typography.titleLarge.copy(textDirection=TextDirection.Ltr))
                Text(cashboxState(item.state),style=MaterialTheme.typography.titleMedium)
                Text(item.custodianDisplayName.ifBlank { hiltechText("أمين العهدة المسجل", "Recorded custodian") }+
                    if(item.custodianEmployeeCode.isNotBlank()) " · ${item.custodianEmployeeCode}" else "")
                Text(hiltechText("العملة: ${item.currency} · الرصيد الافتتاحي: صفر", "Currency: ${item.currency} · Opening balance: zero"))
                if(item.companyCustodyReference.isNotBlank()) Text(item.companyCustodyReference)
                item.sourceIssueCode?.let { Text(cashboxError(it),color=MaterialTheme.colorScheme.error) }
                if(item.state=="ACTIVE") Text(hiltechText("العهدة مفتوحة. الحركات النقدية والتسويات لها إجراءاتها واعتماداتها المستقلة.",
                    "The cashbox is open. Cash movements and reconciliation follow their own reviewed procedures."))
                if(item.canRequestReview) Button(enabled=pending==null&&!busy,onClick={ review(FinanceCashboxOpeningIntent(newOperationId(),organizationId,
                    FinanceCashboxOpeningAction.REQUEST_REVIEW,source=item)) }) { Text(hiltechText("إرسال للمراجعة", "Send for review")) }
                if(item.canActivate) Button(enabled=pending==null&&!busy,onClick={ review(FinanceCashboxOpeningIntent(newOperationId(),organizationId,
                    FinanceCashboxOpeningAction.ACTIVATE,source=item)) }) { Text(hiltechText("فتح العهدة المعتمدة", "Open approved cashbox")) }
                if(item.state in setOf("REVIEW_REQUIRED","CHANGE_REQUESTED","REJECTED","SUPERSEDED","CANCELLED"))
                    Text(hiltechText("احتُفظ بهذا الطلب وسجل مراجعته. راجع سبب التغيير، ثم جهّز طلبًا جديدًا بالبيانات الحالية.",
                        "This request and its review history are retained. Review the change, then prepare a new request with current details."),style=MaterialTheme.typography.bodySmall)
            } }
        }
    }
    pending?.takeIf { dialog }?.let { intent ->
        val named=intent.source?.custodianDisplayName ?: intent.custodianDisplayName
        val code=intent.source?.cashboxCode
        val money=intent.source?.currency ?: intent.prepare?.currency.orEmpty()
        fun close() { dialog=false;if(!attempted) pendingJson=null }
        AlertDialog(onDismissRequest={ if(!busy) close() },title={ Text(when(intent.action) {
            FinanceCashboxOpeningAction.PREPARE -> hiltechText("تأكيد طلب فتح العهدة", "Confirm cashbox request")
            FinanceCashboxOpeningAction.REQUEST_REVIEW -> hiltechText("تأكيد إرسال المراجعة", "Confirm review submission")
            FinanceCashboxOpeningAction.ACTIVATE -> hiltechText("تأكيد فتح العهدة المعتمدة", "Confirm approved cashbox opening")
        }) },text={ Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            code?.let { Text(it,style=MaterialTheme.typography.titleLarge.copy(textDirection=TextDirection.Ltr)) }
            Text(named.ifBlank { hiltechText("أمين العهدة المسجل", "Recorded custodian") },style=MaterialTheme.typography.titleMedium)
            Text(hiltechText("العملة: $money · الرصيد الافتتاحي: صفر", "Currency: $money · Opening balance: zero"))
            Text(intent.source?.companyCustodyReference ?: intent.prepare?.companyCustodyReference.orEmpty())
            Text(when(intent.action) {
                FinanceCashboxOpeningAction.PREPARE -> hiltechText("سيُحفظ طلب برقم داخلي تلقائي للمراجعة قبل فتح العهدة.", "A request with an automatic internal number will be saved for review before opening.")
                FinanceCashboxOpeningAction.REQUEST_REVIEW -> hiltechText("سيصل الطلب للمراجع المستقل المعتمد في الشركة.", "The request goes to the company's designated independent reviewer.")
                FinanceCashboxOpeningAction.ACTIVATE -> hiltechText("سيتم فتح العهدة برصيد صفر وفق الموافقة الحالية. لا تُسجل أموال أو أرصدة سابقة بهذه الخطوة.",
                    "Open the cashbox with zero balance under the current approval. This step records no money or historical balance.")
            })
            if(busy) CircularProgressIndicator()
            commandError?.let { Text(cashboxError(it),color=MaterialTheme.colorScheme.error) }
            if(unknown) Text(hiltechText("لم تصل نتيجة مؤكدة. أعد نفس الطلب المحفوظ للتحقق منها دون إنشاء عهدة أخرى.",
                "The outcome is not confirmed. Retry the same saved request to verify it without creating another cashbox."))
            if(rejected) Text(hiltechText("لم يُنفذ هذا الطلب. حدّث السجل والاختيارات قبل المراجعة من جديد.",
                "This request was not executed. Refresh history and choices before reviewing it again."))
        } },confirmButton={ TextButton(enabled=!busy,onClick={
            if(rejected) { pendingJson=null;dialog=false;attempted=false;rejected=false;unknown=false;custodianJson=null;refresh++ }
            else {
                busy=true;commandError=null
                val priorUnknown=unknown
                jobs.launch {
                    var dispatched=false
                    try {
                        val result=api.submitOpeningIntent(intent,installationId) { dispatched=true;attempted=true;unknown=true }
                        val encoded=Json.encodeToString(FinanceCashboxReceiptDto.serializer(),result)
                        receiptJson=encoded;selectedJson=encoded
                        if(intent.action==FinanceCashboxOpeningAction.PREPARE) prepared=true
                        pendingJson=null;dialog=false;attempted=false;unknown=false;rejected=false;refresh++
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(failure:Exception) {
                        commandError=(failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                        if(!priorUnknown&&!dispatched) { unknown=false;rejected=true }
                        else { val disposition=companyResponsibilityFailureDisposition(priorUnknown,failure)
                            unknown=disposition.hasUnknownOutcome;rejected=disposition.rejectionConfirmed }
                    } finally { busy=false }
                }
            }
        }) { Text(when {
            rejected -> hiltechText("تحديث ومراجعة البيانات", "Refresh and review details")
            attempted -> hiltechText("إعادة نفس الطلب", "Retry same request")
            intent.action==FinanceCashboxOpeningAction.PREPARE -> hiltechText("حفظ طلب الفتح", "Save opening request")
            intent.action==FinanceCashboxOpeningAction.REQUEST_REVIEW -> hiltechText("تأكيد الإرسال", "Confirm submission")
            else -> hiltechText("تأكيد الفتح برصيد صفر", "Confirm zero-balance opening")
        }) } },dismissButton={ TextButton(enabled=!busy,onClick={ close() }) {
            Text(if(attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back"))
        } })
    }
}

@Composable
private fun cashboxState(state:String):String = when(state) {
    "PROPOSED" -> hiltechText("طلب محفوظ · جاهز للإرسال", "Saved · ready to submit")
    "PENDING" -> hiltechText("بانتظار المراجعة المستقلة", "Awaiting independent review")
    "APPROVED" -> hiltechText("معتمد · جاهز للفتح", "Approved · ready to open")
    "ACTIVE" -> hiltechText("عهدة مفتوحة", "Cashbox open")
    "REJECTED" -> hiltechText("لم يُعتمد الطلب", "Request declined")
    "CHANGE_REQUESTED" -> hiltechText("مطلوب تعديل الطلب", "Changes requested")
    "SUPERSEDED" -> hiltechText("تجاوزته مراجعة أحدث", "Superseded review")
    "CANCELLED" -> hiltechText("طلب ملغى", "Cancelled")
    else -> hiltechText("تغيرت البيانات · يحتاج مراجعة", "Details changed · review required")
}
@Composable
private fun cashboxError(code:String):String = when(code) {
    "FINANCE_CASHBOX_NOT_AUTHORIZED" -> hiltechText("لم تعد مسؤولية العهد النقدية متاحة لهذا الحساب. راجع مسؤول الشركة.", "Cash custody access is not current for this account. Contact company administration.")
    "FINANCE_CASHBOX_REVIEW_SETUP_REQUIRED" -> hiltechText("حدد مراجع فتح العهد النقدية في إدارة الشركة أولًا.", "Set up the cashbox opening reviewer in company administration first.")
    "FINANCE_CASHBOX_INDEPENDENCE_REQUIRED" -> hiltechText("يلزم مراجع مستقل عن مُعد الطلب وعن أمين العهدة.", "The reviewer must be independent of both the preparer and the custodian.")
    "FINANCE_CASHBOX_CUSTODIAN_CHANGED" -> hiltechText("تغير حساب الموظف المختار أو وضعه؛ حدّث اختيار أمين العهدة.", "The selected employee account or status changed; refresh the custodian choice.")
    "FINANCE_CASHBOX_REVIEW_CHANGED", "FINANCE_CASHBOX_SOURCE_CHANGED", "FINANCE_CASHBOX_STALE" -> hiltechText("تغيرت بيانات العهدة أو مراجعها. السجل محفوظ؛ حدّثه وراجع البيانات الحالية قبل المتابعة.", "The custody details or reviewer changed. History is retained; refresh and review current details before continuing.")
    "FINANCE_CASHBOX_ALREADY_ACTIVE" -> hiltechText("فُتحت هذه العهدة بالفعل بطلب آخر. حدّث السجل لعرضها.", "This cashbox was already opened by another request. Refresh history to view it.")
    "FINANCE_CASHBOX_INVALID" -> hiltechText("اختر موظفًا حاليًا وعملة ومرجعًا واضحًا لتكليفه بالعهدة.", "Choose a current employee, currency and a clear company custody reference.")
    "REAUTH_REQUIRED", "UNAUTHENTICATED", "SESSION_NOT_ACTIVE" -> hiltechText("أكد تسجيل الدخول ثم عد لمتابعة الطلب المحفوظ.", "Confirm sign-in, then return to the saved request.")
    else -> hiltechText("تعذر تأكيد الحالة. تحقق من الاتصال ثم أعد المحاولة بنفس الطلب المحفوظ.", "The result could not be confirmed. Check the connection, then retry the same saved request.")
}
