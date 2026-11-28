package com.hiltech.shared.core.administration

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.serialization.json.Json

@Composable private fun configurationLabel(kind: String): String = when(kind) {
    "ACCOUNT" -> hiltechText("دليل الحسابات","Chart of accounts")
    "PERIOD" -> hiltechText("الفترات المحاسبية","Accounting periods")
    else -> hiltechText("حسابات البنوك","Bank accounts")
}
@Composable private fun accountKindLabel(kind: String): String = when(kind) {
    "SUPPLIER_PAYABLE" -> hiltechText("مستحقات الموردين","Supplier payables")
    "CUSTOMER_RECEIVABLE" -> hiltechText("مستحقات العملاء","Customer receivables")
    "SALES_REVENUE" -> hiltechText("إيرادات المبيعات","Sales revenue")
    "EXPENSE" -> hiltechText("مصروفات","Expenses")
    "INVENTORY_ASSET" -> hiltechText("أصول المخزون","Inventory assets")
    "FIXED_ASSET" -> hiltechText("أصول ثابتة","Fixed assets")
    "TAX_RECOVERABLE" -> hiltechText("ضرائب قابلة للاسترداد","Recoverable taxes")
    "TAX_PAYABLE" -> hiltechText("ضرائب مستحقة","Tax payable")
    "OTHER_ASSET" -> hiltechText("أصول أخرى","Other assets")
    "BANK_ASSET" -> hiltechText("حساب بنك محاسبي","Bank ledger account")
    "CASH_ASSET" -> hiltechText("حساب خزنة محاسبي","Cashbox ledger account")
    "EMPLOYEE_ADVANCE_ASSET" -> hiltechText("سلف الموظفين — أصل","Employee advances — asset")
    "EMPLOYEE_IMPREST_ASSET" -> hiltechText("العهد المالية — أصل","Employee imprests — asset")
    "PAYROLL_EXPENSE" -> hiltechText("تكلفة الرواتب","Payroll expense")
    "PAYROLL_PAYABLE" -> hiltechText("الرواتب المستحقة","Payroll payable")
    "PAYROLL_DEDUCTION_PAYABLE" -> hiltechText("خصومات الرواتب المستحقة للغير","Payroll deductions payable")
    "EQUITY" -> hiltechText("حقوق الملكية","Equity")
    "OTHER_LIABILITY" -> hiltechText("التزامات أخرى","Other liabilities")
    else -> kind
}
@Composable private fun configurationState(state: String): String = when(state) {
    "OPEN" -> hiltechText("مفتوحة","Open")
    "CLOSED" -> hiltechText("مقفلة","Closed")
    "APPROVED" -> hiltechText("معتمد","Approved")
    "RETIRED" -> hiltechText("متوقف","Retired")
    else -> state
}

/** Ordinary company settings, not a second financial permissions system. */
@Composable
fun FinanceConfigurationWorkspace(org: String,client: FinanceConfigurationApiClient,installation: String,
    newOperationId: ()->String,onReauthenticate: (()->Unit)?) {
    val scope=rememberCoroutineScope()
    var kind by rememberSaveable(org) { mutableStateOf("ACCOUNT") }
    var offset by rememberSaveable(org) { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var data by remember(org) { mutableStateOf<FinanceConfigurationPageDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf(false) }
    var editing by rememberSaveable(org) { mutableStateOf(false) }
    var code by rememberSaveable(org) { mutableStateOf("") }
    var name by rememberSaveable(org) { mutableStateOf("") }
    var accountKind by rememberSaveable(org) { mutableStateOf("") }
    var treasuryId by rememberSaveable(org) { mutableStateOf("") }
    var treasuryName by rememberSaveable(org) { mutableStateOf("") }
    var instruments by remember { mutableStateOf<FinanceTreasuryInstrumentPageDto?>(null) }
    var instrumentsLoading by remember { mutableStateOf(false) }
    var currency by rememberSaveable(org) { mutableStateOf("") }
    var start by rememberSaveable(org) { mutableStateOf("") }
    var end by rememberSaveable(org) { mutableStateOf("") }
    var reference by rememberSaveable(org) { mutableStateOf("") }
    var pendingJson by rememberSaveable(org) { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable(org) { mutableStateOf(false) }
    var uncertain by rememberSaveable(org) { mutableStateOf(false) }
    var rejected by rememberSaveable(org) { mutableStateOf(false) }
    val pending=pendingJson?.let { Json.decodeFromString(FinanceConfigurationIntent.serializer(),it) }
    val editable=pending==null && !busy && !loading && data!=null
    fun resetForm() { code="";name="";accountKind="";currency="";start="";end="";reference="";treasuryId="";treasuryName="" }
    LaunchedEffect(org,client,kind,accountKind,revision) {
        instruments=null
        if(kind=="ACCOUNT" && accountKind in financeTreasuryAccountKinds) {
            instrumentsLoading=true
            try { instruments=client.treasuryInstruments(org,accountKind,installation) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { instruments=null }
            finally { instrumentsLoading=false }
        } else instrumentsLoading=false
    }
    fun refresh() { data=null;loading=true;revision++ }

    LaunchedEffect(org,client,kind,offset,revision) {
        loading=true;data=null
        try { data=client.page(org,kind,offset,installation) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { error=(failure as? HiltechApiException)?.code ?: "SOURCE_UNAVAILABLE" }
        finally { loading=false }
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("الإعداد المالي","Financial setup"),style=MaterialTheme.typography.titleLarge)
        Text(hiltechText("سجّل دليل الحسابات والفترات وحسابات البنوك التي اعتمدتها الشركة. تستخدمها مسارات المحاسبة وكشوف البنك الحالية مباشرة.",
            "Register the company-approved chart, accounting periods and bank accounts. Existing journal and bank-statement workflows use these same records."))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            financeConfigurationKinds.forEach { tab ->
                FilterChip(selected=kind==tab,enabled=!busy && pending==null,onClick={
                    if(kind!=tab) { kind=tab;offset=0;editing=false;resetForm();error=null;notice=false;data=null;loading=true }
                },label={Text(configurationLabel(tab))})
            }
        }
        if(loading) Text(hiltechText("جارٍ قراءة الإعداد…","Reading setup…"))
        TextButton(enabled=!busy && !loading,onClick={error=null;refresh()}) { Text(hiltechText("تحديث","Refresh")) }
        data?.takeIf { it.kind==kind && it.offset==offset && !loading }?.let { page ->
            if(page.records.isEmpty()) Text(hiltechText("لا توجد سجلات في هذه الصفحة.","No records on this page."))
            page.records.forEach { row ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(row.name ?: (row.startDate.orEmpty()+" — "+row.endDate.orEmpty()),style=MaterialTheme.typography.titleMedium)
                    row.code?.let { HiltechKeyValue(hiltechText("الرمز أو المرجع","Code or reference"),it) }
                    row.accountKind?.let { Text(accountKindLabel(it)) }
                    if(row.treasuryInstrumentId!=null) Text(hiltechText("مرتبط بمصدر الخزينة المعتمد","Linked to an approved treasury source"))
                    row.currency?.let { Text(it) }
                    HiltechKeyValue(hiltechText("الحالة","State"),configurationState(row.state))
                    HiltechKeyValue(hiltechText("مرجع اعتماد الشركة","Company authorization"),row.authorizationReference)
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=editable && offset>0,onClick={offset=(offset-50).coerceAtLeast(0);data=null;loading=true}) {
                    Text(hiltechText("السابق","Previous"))
                }
                Text((offset/50+1).toString())
                TextButton(enabled=editable && page.hasMore && offset<1_000_000,onClick={offset+=50;data=null;loading=true}) {
                    Text(hiltechText("التالي","Next"))
                }
            }
        }
        if(notice) Text(hiltechText("تم تسجيل الإعداد وتأكيده من المصدر.","Setup saved and confirmed at source."),color=MaterialTheme.colorScheme.primary)
        if(!editing && pending==null) Button(enabled=editable,onClick={editing=true;notice=false;error=null}) {
            Text(when(kind) {
                "ACCOUNT" -> hiltechText("إضافة حساب محاسبي","Add chart account")
                "PERIOD" -> hiltechText("فتح فترة محاسبية","Add accounting period")
                else -> hiltechText("إضافة حساب بنك","Add bank account")
            })
        }
        if(editing && pending==null) Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(kind=="PERIOD") {
                SetupField(hiltechText("بداية الفترة YYYY-MM-DD","Period start YYYY-MM-DD"),start,editable){start=it.take(10)}
                SetupField(hiltechText("نهاية الفترة YYYY-MM-DD","Period end YYYY-MM-DD"),end,editable){end=it.take(10)}
                Text(hiltechText("لا يمكن إنشاء فترة تتداخل مع فترة مسجلة.","Periods cannot overlap an existing accounting period."))
            } else {
                SetupField(if(kind=="BANK")hiltechText("اسم البنك","Bank name") else hiltechText("اسم الحساب","Account name"),name,editable){name=it.take(180)}
                SetupField(if(kind=="BANK")hiltechText("مرجع الحساب المخفي","Masked account reference") else hiltechText("كود الحساب","Account code"),code,editable){code=it.take(64)}
                if(kind=="BANK") Text(hiltechText("استخدم مرجعًا مخفيًا مثل ****1234 أو MAIN:****1234؛ لا تدخل رقم الحساب الكامل أو IBAN.",
                    "Use a masked reference such as ****1234 or MAIN:****1234; never enter a full account number or IBAN."))
                else HiltechRecordPicker(hiltechText("تصنيف الحساب","Account classification"),accountKind,
                    financeConfigurationAccountKinds.map { HiltechRecordChoice(it,accountKindLabel(it)) },
                    onChange={ if(accountKind!=it) {accountKind=it;treasuryId="";treasuryName="";currency="";instruments=null} },
                    allowKnownReference=false,allowClear=false,enabled=editable)
                if(kind=="ACCOUNT" && accountKind in financeTreasuryAccountKinds) {
                    val source=instruments?.takeIf {it.accountKind==accountKind}
                    if(instrumentsLoading) Text(hiltechText("جارٍ قراءة المصادر المعتمدة…","Reading approved instruments…"))
                    else if(source==null) Text(hiltechText("تعذر تأكيد مصادر الخزينة. حدّث الإعداد.","Treasury sources not confirmed. Refresh setup."))
                    else if(source.records.isEmpty()) Text(hiltechText("لا يوجد مصدر معتمد غير مرتبط. سجّل البنك أو افتح خزنة أولًا.","No unlinked approved instrument. Register the bank or open a cashbox first."))
                    else {
                        HiltechRecordPicker(hiltechText("البنك أو الخزنة الفعلية","Actual bank or cashbox"),treasuryId,
                            source.records.map {HiltechRecordChoice(it.id,it.name,it.currency)},
                            onChange={id->source.records.singleOrNull {it.id==id}?.let {treasuryId=it.id;treasuryName=it.name;currency=it.currency}},
                            allowKnownReference=false,allowClear=false,enabled=editable)
                        if(source.hasMore) Text(hiltechText("تظهر أول 100 مصدر غير مرتبط.","Showing the first 100 unlinked instruments."))
                    }
                    if(currency.isNotBlank()) HiltechKeyValue(hiltechText("عملة المصدر","Source currency"),currency)
                } else SetupField(if(kind=="BANK")hiltechText("العملة","Currency") else hiltechText("العملة — اختياري","Currency — optional"),currency,editable){currency=it.uppercase().take(3)}
            }
            SetupField(hiltechText("مرجع اعتماد الشركة الفعلي","Actual company authorization reference"),reference,editable){reference=it.take(180)}
            val draft=FinanceConfigurationIntent("",kind,reference.trim(),
                code.trim().takeIf {kind!="PERIOD"},name.trim().takeIf {kind!="PERIOD"},
                accountKind.takeIf {kind=="ACCOUNT"},currency.trim().takeIf {kind!="PERIOD" && it.isNotBlank()},
                start.trim().takeIf {kind=="PERIOD"},end.trim().takeIf {kind=="PERIOD"},
                treasuryId.takeIf {kind=="ACCOUNT" && accountKind in financeTreasuryAccountKinds && it.isNotBlank()})
            val treasuryReady=accountKind !in financeTreasuryAccountKinds ||
                (instruments?.accountKind==accountKind && instruments?.records?.any {it.id==treasuryId && it.currency==currency}==true && !instrumentsLoading)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=editable && treasuryReady && financeConfigurationIntentValid(draft),onClick={
                    pendingJson=Json.encodeToString(FinanceConfigurationIntent.serializer(),draft.copy(operationId=newOperationId()))
                    attempted=false;uncertain=false;rejected=false;error=null
                }) { Text(hiltechText("مراجعة قبل الحفظ","Review setup")) }
                TextButton(enabled=!busy,onClick={editing=false;resetForm()}) {Text(hiltechText("إلغاء","Cancel"))}
            }
        }
        pending?.let { intent ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(configurationLabel(intent.kind),style=MaterialTheme.typography.titleMedium)
                intent.name?.let {Text(it)}
                intent.code?.let {Text(it)}
                intent.accountKind?.let {Text(accountKindLabel(it))}
                if(intent.treasuryInstrumentId!=null) HiltechKeyValue(hiltechText("المصدر المرتبط","Linked instrument"),
                    treasuryName.ifBlank {hiltechText("المصدر المحدد سابقًا","Previously selected source")})
                intent.currency?.let {Text(it)}
                intent.startDate?.let {Text(it+" — "+intent.endDate.orEmpty())}
                HiltechKeyValue(hiltechText("مرجع الاعتماد","Authorization reference"),intent.authorizationReference)
                Text(hiltechText("تأكيدك يسجّل الإعداد المعتمد فقط. لا يصرف أموالًا ولا ينشئ قيدًا أو رصيد افتتاح.",
                    "Confirmation records the approved setup only. It does not transfer money or create a journal or opening balance."))
                if(uncertain) Text(hiltechText("نتيجة الطلب السابق غير مؤكدة؛ أعد نفس الطلب دون تغيير بياناته.",
                    "The previous outcome is unconfirmed; retry the same request without changing its data."))
                Button(enabled=!busy && !loading,onClick={
                    if(rejected) { pendingJson=null;attempted=false;uncertain=false;rejected=false;error=null;refresh() }
                    else {
                        busy=true;error=null
                        val priorUnknown=uncertain
                        scope.launch {
                            try {
                                attempted=true;uncertain=true
                                client.create(org,intent,installation)
                                pendingJson=null;attempted=false;uncertain=false;rejected=false
                                editing=false;resetForm();notice=true;offset=0;refresh()
                            } catch(cancelled: CancellationException) {throw cancelled}
                            catch(failure: Exception) {
                                error=(failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                                val disposition=companyResponsibilityFailureDisposition(priorUnknown,failure)
                                uncertain=disposition.hasUnknownOutcome;rejected=disposition.rejectionConfirmed
                            } finally {busy=false}
                        }
                    }
                }) {Text(when {
                    rejected -> hiltechText("الرجوع ومراجعة المصدر","Return and review source")
                    attempted -> hiltechText("إعادة نفس الطلب","Retry same request")
                    else -> hiltechText("تأكيد وحفظ الإعداد","Confirm and save")
                })}
                if(!attempted || rejected) TextButton(enabled=!busy,onClick={
                    pendingJson=null;attempted=false;uncertain=false;rejected=false
                }) {Text(hiltechText("رجوع","Back"))}
            }
        }
        error?.let { problem ->
            Text(when(problem) {
                "FINANCE_CONFIGURATION_CONFLICT" -> hiltechText("يوجد سجل بهذا المرجع أو فترة متداخلة. راجع الإعداد الحالي.","A record with this reference or an overlapping period exists. Review current setup.")
                "FINANCE_CONFIGURATION_INVALID" -> hiltechText("راجع البيانات ومرجع الاعتماد.","Check details and authorization reference.")
                "COMPANY_SETTINGS_DENIED" -> hiltechText("لا تتوفر مسؤولية إعدادات الشركة الحالية.","Current company settings authority is not available.")
                "REAUTH_REQUIRED","SESSION_NOT_ACTIVE","UNAUTHENTICATED","RECENT_REAUTH_REQUIRED" -> hiltechText("أكد تسجيل الدخول قبل حفظ الإعداد.","Confirm sign-in before saving.")
                else -> hiltechText("لم يتأكد المصدر؛ لا تفترض أن الطلب تم.","Source not confirmed; do not assume the request succeeded.")
            },color=MaterialTheme.colorScheme.error)
            if(onReauthenticate!=null && problem in setOf("REAUTH_REQUIRED","SESSION_NOT_ACTIVE","UNAUTHENTICATED","RECENT_REAUTH_REQUIRED"))
                TextButton(enabled=!busy,onClick=onReauthenticate) {Text(hiltechText("تأكيد تسجيل الدخول","Confirm sign-in"))}
        }
    }
    FinanceOpeningSection(org,client,installation,newOperationId,onReauthenticate,revision,pending!=null||busy)
}

@Composable private fun SetupField(label:String,value:String,enabled:Boolean,onChange:(String)->Unit) {
    OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),singleLine=true,enabled=enabled,label={Text(label)})
}

@Composable
private fun FinanceOpeningSection(org:String,client:FinanceConfigurationApiClient,installation:String,
    newOperationId:()->String,onReauthenticate:(()->Unit)?,setupRevision:Int,setupLocked:Boolean) {
    val scope=rememberCoroutineScope()
    var expanded by rememberSaveable(org){mutableStateOf(false)}
    var currency by rememberSaveable(org){mutableStateOf("")}
    var companyReference by rememberSaveable(org){mutableStateOf("")}
    var sourceReference by rememberSaveable(org){mutableStateOf("")}
    var view by remember{mutableStateOf<FinanceOpeningViewDto?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var loading by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var revision by remember{mutableIntStateOf(0)}
    var accountId by remember{mutableStateOf("")}
    var side by remember{mutableStateOf("DEBIT")}
    var amount by remember{mutableStateOf("")}
    var reference by remember{mutableStateOf("")}
    var employeeObligations by remember(org,currency,accountId){mutableStateOf<List<FinanceOpeningEmployeeObligationDto>>(emptyList())}
    var stockValues by remember(org,currency,accountId){mutableStateOf<List<FinanceOpeningStockValueDto>>(emptyList())}
    var obligations by remember(org,currency,accountId){mutableStateOf<List<FinanceOpeningObligationDto>>(emptyList())}
    var partyId by remember(org,currency,accountId){mutableStateOf("")}
    var documentRef by remember(org,currency,accountId){mutableStateOf("")}
    var documentDate by remember(org,currency,accountId){mutableStateOf("")}
    var dueDate by remember(org,currency,accountId){mutableStateOf("")}
    var residual by remember(org,currency,accountId){mutableStateOf("")}
    var lines by remember(org,currency){mutableStateOf<List<FinanceOpeningLineDto>>(emptyList())}
    var pendingJson by rememberSaveable(org){mutableStateOf<String?>(null)}
    var attempted by rememberSaveable(org){mutableStateOf(false)}
    var uncertain by rememberSaveable(org){mutableStateOf(false)}
    var rejected by rememberSaveable(org){mutableStateOf(false)}
    var posted by remember{mutableStateOf(false)}
    val pending=pendingJson?.let{Json.decodeFromString(FinanceOpeningInputDto.serializer(),it)}
    LaunchedEffect(org,client,currency,expanded,revision,setupRevision) {
        view=null;error=null
        if(!expanded||!currency.matches(Regex("[A-Z]{3}")))return@LaunchedEffect
        loading=true
        try{view=client.opening(org,currency,installation)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=(failure as? HiltechApiException)?.code?:"SOURCE_UNAVAILABLE"}
        finally{loading=false}
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("الأرصدة الافتتاحية","Opening balances"),style=MaterialTheme.typography.titleLarge)
        Text(hiltechText("سجّل كشف افتتاح معتمدًا في أول فترة فقط. البنك من كشفه المعتمد؛ لا يولّد النظام حقوق ملكية أو رصيدًا موازنًا.",
            "Record an authorized opening schedule in the first period only. Bank openings come from approved statements; no equity or balancing amount is invented."))
        TextButton(enabled=!setupLocked&&!busy&&pending==null,onClick={expanded=!expanded}){Text(if(expanded)hiltechText("إخفاء","Hide")else hiltechText("فتح الأرصدة","Open balances"))}
        if(expanded||pending!=null) {
            val editable=!setupLocked&&!busy&&!loading&&pending==null
            SetupField(hiltechText("عملة كشف الافتتاح","Opening currency"),currency,editable){currency=it.uppercase().take(3);accountId="";posted=false}
            if(loading)Text(hiltechText("جارٍ قراءة الحسابات المعتمدة…","Reading approved accounts…"))
            if(posted)Text(hiltechText("تم حفظ قيد الافتتاح.","Opening journal recorded."))
            view?.takeIf{it.currency==currency}?.let{data->
                if(data.existingJournalId!=null)Text(hiltechText("يوجد قيد افتتاح مسجل لهذه العملة. لا يمكن تكراره.","An opening journal already exists for this currency. It cannot be duplicated."))
                else if(!data.canPost)Text(hiltechText("لا توجد فترة أولى مفتوحة وغير متداخلة.","No eligible first open non-overlapping period."))
                else if(pending==null) {
                    val first=data.period
                    if(first!=null)HiltechKeyValue(hiltechText("تاريخ الافتتاح","Opening date"),first.dateStart)
                    Text(hiltechText("يشمل الأصول والالتزامات الأخرى وحقوق الملكية والبنوك المؤيدة، وأرصدة العملاء والموردين من المستندات القديمة. ويشمل المخزون المثبت في تاريخ الافتتاح بقيمة مراجعة. السلف والعهد التاريخية من مستنداتها المتبقية. الرواتب تحتاج مصدر افتتاح منفصل.",
                        "Includes non-control assets, other liabilities, equity, evidenced bank openings and individually documented customer/supplier residuals. Includes verified stock openings with reviewed values. Historical staff advances and imprests use individual residuals. Payroll needs a separate opening source."))
                    SetupField(hiltechText("مرجع اعتماد الشركة","Company authorization"),companyReference,editable){companyReference=it.take(160)}
                    SetupField(hiltechText("مرجع كشف الأرصدة الأصلي","Original opening schedule reference"),sourceReference,editable){sourceReference=it.take(240)}
                    val choices=data.accounts.filter{a->lines.none{it.accountId==a.id}}
                    val account=choices.singleOrNull{it.id==accountId}
                    val control=account?.accountKind in openingControlKinds
                    val inventory=account?.accountKind=="INVENTORY_ASSET"
                    val employeeControl=account?.accountKind in openingEmployeeKinds
                    if(choices.isNotEmpty()&&lines.size<32) {
                        HiltechRecordPicker(hiltechText("الحساب المعتمد","Approved account"),accountId,
                            choices.map{HiltechRecordChoice(it.id,it.name,it.code)},onChange={id->
                                accountId=id;val picked=choices.singleOrNull{it.id==id}
                                side=picked?.bankSide?:if(picked?.accountKind=="SUPPLIER_PAYABLE")"CREDIT" else "DEBIT";amount=picked?.bankAmount.orEmpty()
                                reference=if(picked?.bankStatementId!=null)"APPROVED BANK STATEMENT" else ""
                            },allowKnownReference=false,enabled=editable)
                        if(employeeControl&&first!=null){
                            val employees=data.counterparties.filter{it.kind=="EMPLOYEE"}
                            Text(hiltechText("الرصيد التاريخي المتبقي على الموظف عند الافتتاح، وليس تمويلًا جديدًا.","Historical residual owed by the employee at cutover, not a new disbursement."),style=MaterialTheme.typography.bodySmall)
                            HiltechRecordPicker(hiltechText("الموظف","Employee"),partyId,employees.map{HiltechRecordChoice(it.id,it.name,it.code)},
                                onChange={partyId=it},allowKnownReference=false,enabled=editable)
                            if(data.hasMoreCounterparties)Text(hiltechText("قائمة الجهات والموظفين تعرض أول 500 سجل.","The combined counterparty and employee list shows the first 500 records."))
                            SetupField(hiltechText("مرجع المستند التاريخي","Historical document reference"),documentRef,editable){documentRef=it.take(96)}
                            SetupField(hiltechText("تاريخ المستند YYYY-MM-DD","Document date YYYY-MM-DD"),documentDate,editable){documentDate=it.take(10)}
                            SetupField(hiltechText("الرصيد المتبقي","Residual balance"),residual,editable){residual=it.take(19)}
                            val item=FinanceOpeningEmployeeObligationDto(partyId,documentRef.trim(),documentDate,residual)
                            TextButton(enabled=editable&&openingEmployeeObligationValid(item)&&documentDate<first.dateStart&&
                                employees.any{it.id==partyId}&&employeeObligations.none{it.employeeId==partyId&&it.documentReference.equals(item.documentReference,true)}&&
                                lines.sumOf{it.employeeObligations.size}+employeeObligations.size<1000,onClick={
                                employeeObligations=employeeObligations+item;documentRef="";documentDate="";residual=""
                            }){Text(hiltechText("إضافة الرصيد","Add residual"))}
                            employeeObligations.forEachIndexed{index,o->
                                HiltechKeyValue(employees.singleOrNull{it.id==o.employeeId}?.name.orEmpty()+" · "+o.documentReference,o.amount+" "+currency)
                                TextButton(enabled=editable,onClick={employeeObligations=employeeObligations.filterIndexed{i,_->i!=index}}){Text(hiltechText("حذف الرصيد","Remove residual"))}
                            }
                            HiltechKeyValue(hiltechText("إجمالي الأرصدة","Total residuals"),openingEmployeeTotal(employeeObligations).orEmpty()+" "+currency)
                        } else if(inventory&&first!=null){
                            FinanceOpeningStockEditor(client,org,first.id,currency,installation,editable,stockValues,
                                lines.flatMap{it.stockValues}.map{it.movementId}.toSet()){stockValues=it}
                            HiltechKeyValue(hiltechText("إجمالي قيمة المخزون الافتتاحية","Opening stock carrying value"),openingStockTotal(stockValues).orEmpty()+" "+currency)
                        } else if(control && account!=null) {
                            val kind=if(account.accountKind=="CUSTOMER_RECEIVABLE")"AR" else "AP"
                            val parties=data.counterparties.filter{it.kind==kind}
                            Text(hiltechText("المبالغ المتبقية عند بدء الدفتر — لا تدخل القيمة الأصلية المسددة.","Residual amounts at cutover — do not enter the already settled original amount."),style=MaterialTheme.typography.bodySmall)
                            HiltechRecordPicker(if(kind=="AR")hiltechText("العميل","Customer")else hiltechText("المورد","Supplier"),partyId,
                                parties.map{HiltechRecordChoice(it.id,it.name,it.code)},onChange={partyId=it},allowKnownReference=false,enabled=editable)
                            if(data.hasMoreCounterparties)Text(hiltechText("تظهر أول 500 جهة مرتبطة بالشركة.","Showing the first 500 company-linked counterparties."))
                            SetupField(hiltechText("رقم المستند التاريخي","Historical document reference"),documentRef,editable){documentRef=it.take(96)}
                            SetupField(hiltechText("تاريخ المستند YYYY-MM-DD","Document date YYYY-MM-DD"),documentDate,editable){documentDate=it.take(10)}
                            SetupField(hiltechText("موعد الاستحقاق إن وجد","Due date if known"),dueDate,editable){dueDate=it.take(10)}
                            SetupField(hiltechText("المبلغ المتبقي عند الافتتاح","Residual amount at cutover"),residual,editable){residual=it.take(25)}
                            val item=FinanceOpeningObligationDto(partyId,documentRef.trim(),documentDate,residual,dueDate.takeIf{it.isNotBlank()})
                            val valid=openingObligationValid(item)&&first!=null&&documentDate<first.dateStart&&parties.any{it.id==partyId}&&
                                obligations.none{it.counterpartyId==partyId&&it.documentReference.equals(item.documentReference,true)}&&
                                lines.sumOf{it.obligations.size}+obligations.size<1000
                            TextButton(enabled=editable&&valid,onClick={
                                obligations=obligations+item;documentRef="";documentDate="";dueDate="";residual=""
                            }){Text(hiltechText("إضافة المستحق","Add obligation"))}
                            obligations.forEachIndexed{index,o->
                                HiltechKeyValue(parties.singleOrNull{it.id==o.counterpartyId}?.name.orEmpty()+" · "+o.documentReference,o.amount+" "+currency)
                                Text(o.documentDate+(o.dueDate?.let{" — "+it}.orEmpty()),style=MaterialTheme.typography.bodySmall)
                                TextButton(enabled=editable,onClick={obligations=obligations.filterIndexed{i,_->i!=index}}){Text(hiltechText("حذف المستحق","Remove obligation"))}
                            }
                            HiltechKeyValue(hiltechText("إجمالي المستحقات","Obligation total"),openingObligationTotal(obligations).orEmpty()+" "+currency)
                        } else if(account?.bankStatementId==null) {
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf("DEBIT","CREDIT").forEach{s->FilterChip(selected=side==s,enabled=editable,onClick={side=s},
                                    label={Text(if(s=="DEBIT")hiltechText("مدين","Debit")else hiltechText("دائن","Credit"))})}
                            }
                            SetupField(hiltechText("المبلغ من الكشف","Amount from schedule"),amount,editable){amount=it.take(25)}
                        } else HiltechKeyValue(hiltechText("رصيد البنك المؤيد","Evidenced bank opening"),
                            (if(side=="DEBIT")hiltechText("مدين: ","Debit: ")else hiltechText("دائن: ","Credit: "))+amount+" "+currency)
                        SetupField(hiltechText("مرجع إثبات البند","Line source reference"),reference,editable){reference=it.take(240)}
                        val line=FinanceOpeningLineDto(accountId,side,when{
                            employeeControl->openingEmployeeTotal(employeeObligations).orEmpty()
                            inventory->openingStockTotal(stockValues).orEmpty()
                            control->openingObligationTotal(obligations).orEmpty()
                            else->amount
                        },reference.trim(),account?.bankStatementId,if(control)obligations else emptyList(),if(inventory)stockValues else emptyList(),if(employeeControl)employeeObligations else emptyList())
                        TextButton(enabled=editable&&account!=null&&(!control||obligations.isNotEmpty())&&
                            (!employeeControl||employeeObligations.isNotEmpty())&&(!inventory||(stockValues.isNotEmpty()&&lines.sumOf{it.stockValues.size}+stockValues.size<=1000))&&openingLineValid(line),onClick={
                            lines=lines+line;accountId="";amount="";reference="";side="DEBIT"
                        }){Text(hiltechText("إضافة بند","Add line"))}
                    }
                    if(data.hasMore)Text(hiltechText("تظهر أول 100 حساب مؤهل.","Showing the first 100 eligible accounts."))
                    lines.forEach{line->
                        val name=data.accounts.singleOrNull{it.id==line.accountId}?.name.orEmpty()
                        HiltechKeyValue(name,(if(line.side=="DEBIT")hiltechText("مدين: ","Debit: ")else hiltechText("دائن: ","Credit: "))+line.amount+" "+currency)
                        line.employeeObligations.forEach{o->Text((data.counterparties.singleOrNull{it.kind=="EMPLOYEE"&&it.id==o.employeeId}?.name.orEmpty())+" · "+o.documentReference+" · "+o.amount+" "+currency,style=MaterialTheme.typography.bodySmall)}
                        line.stockValues.forEach{v->Text(v.quantity+" "+v.unit+" · "+v.amount+" "+currency+" · "+v.valuationReference,style=MaterialTheme.typography.bodySmall)}
                        line.obligations.forEach{o->
                            Text((data.counterparties.firstOrNull{it.id==o.counterpartyId}?.name.orEmpty())+" · "+o.documentReference+" · "+o.amount+" "+currency,style=MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled=editable,onClick={lines=lines.filterNot{it.accountId==line.accountId}}){Text(hiltechText("حذف البند","Remove line"))}
                    }
                    val totalDebit=openingTotal(lines,"DEBIT")
                    val totalCredit=openingTotal(lines,"CREDIT")
                    HiltechKeyValue(hiltechText("إجمالي المدين","Total debit"),totalDebit.orEmpty()+" "+currency)
                    HiltechKeyValue(hiltechText("إجمالي الدائن","Total credit"),totalCredit.orEmpty()+" "+currency)
                    if(totalDebit!=totalCredit)Text(hiltechText("المدين والدائن غير متساويين.","Debits and credits are not equal."),color=MaterialTheme.colorScheme.error)
                    val input=FinanceOpeningInputDto("",first?.id.orEmpty(),currency,companyReference.trim(),sourceReference.trim(),lines)
                    Button(enabled=editable&&openingInputValid(input)&&lines.all{line->data.accounts.any{it.id==line.accountId}},onClick={
                        pendingJson=Json.encodeToString(FinanceOpeningInputDto.serializer(),input.copy(operationId=newOperationId()))
                        attempted=false;uncertain=false;rejected=false;error=null
                    }){Text(hiltechText("مراجعة كشف الافتتاح","Review opening schedule"))}
                }
            }
            pending?.let{input->
                Text(hiltechText("تأكيد قيد افتتاح غير قابل للتعديل","Confirm immutable opening journal"),style=MaterialTheme.typography.titleMedium)
                Text(input.currency+" · "+input.companyReference+" · "+input.sourceReference)
                input.lines.forEach{line->
                    val name=view?.accounts?.singleOrNull{it.id==line.accountId}?.name?:hiltechText("الحساب المحدد سابقًا","Previously selected account")
                    HiltechKeyValue(name,(if(line.side=="DEBIT")hiltechText("مدين: ","Debit: ")else hiltechText("دائن: ","Credit: "))+line.amount+" "+input.currency)
                    Text(line.reference,style=MaterialTheme.typography.bodySmall)
                    line.employeeObligations.forEach{o->Text(o.documentReference+" · "+o.documentDate+" · "+o.amount+" "+input.currency,style=MaterialTheme.typography.bodySmall)}
                    line.stockValues.forEach{v->Text(v.quantity+" "+v.unit+" · "+v.amount+" "+input.currency+" · "+v.valuationReference,style=MaterialTheme.typography.bodySmall)}
                    line.obligations.forEach{o->Text(o.documentReference+" · "+o.documentDate+" · "+o.amount+" "+input.currency,style=MaterialTheme.typography.bodySmall)}
                }
                Text(hiltechText("أقر بأن الكشف يعكس الأرصدة المعتمدة عند بدء الدفتر. لن ينفذ هذا القيد أي تحويل.",
                    "I confirm the schedule reflects approved balances at ledger cutover. This journal does not execute a transfer."))
                if(uncertain)Text(hiltechText("النتيجة غير مؤكدة؛ أعد نفس الطلب فقط.","Outcome unconfirmed; retry the same request only."))
                Button(enabled=!setupLocked&&!busy,onClick={
                    if(rejected){pendingJson=null;attempted=false;uncertain=false;rejected=false;revision++}
                    else {
                        val previous=uncertain
                        busy=true;attempted=true;uncertain=true;error=null
                        scope.launch {
                            try{client.postOpening(org,input,installation);pendingJson=null;lines=emptyList();posted=true;attempted=false;uncertain=false;rejected=false;revision++}
                            catch(cancelled:CancellationException){throw cancelled}
                            catch(failure:Exception){
                                error=(failure as? HiltechApiException)?.code?:"OUTCOME_UNCONFIRMED"
                                val disposition=companyResponsibilityFailureDisposition(previous,failure)
                                uncertain=disposition.hasUnknownOutcome;rejected=disposition.rejectionConfirmed
                            }finally{busy=false}
                        }
                    }
                }){Text(if(rejected)hiltechText("مراجعة المصدر","Review source")else if(attempted)hiltechText("إعادة نفس الطلب","Retry same request")else hiltechText("تأكيد وترحيل","Confirm and post"))}
                if(!attempted&&!busy)TextButton(onClick={pendingJson=null}){Text(hiltechText("رجوع","Back"))}
            }
            error?.let{problem->
                Text(when(problem){
                    "FINANCE_OPENING_CHANGED"->hiltechText("راجع توازن المدين والدائن والحسابات وكشف البنك وتاريخ الافتتاح.","Review equal debit/credit totals, accounts, bank statement and opening date.")
                    "FINANCE_OPENING_UNAVAILABLE"->hiltechText("تحتاج مسؤولية الإعداد والترحيل المالي الحالية.","Current company setup and accounting posting responsibilities are required.")
                    else->hiltechText("لم تتأكد النتيجة. تحقق من الاتصال أو الدخول.","Outcome not confirmed. Check connection or sign-in.")
                },color=MaterialTheme.colorScheme.error)
                if(onReauthenticate!=null&&problem in setOf("REAUTH_REQUIRED","RECENT_REAUTH_REQUIRED","UNAUTHENTICATED","SESSION_NOT_ACTIVE"))
                    TextButton(enabled=!busy,onClick=onReauthenticate){Text(hiltechText("تأكيد الدخول","Confirm sign-in"))}
                if(pending==null)TextButton(enabled=!busy&&!loading,onClick={revision++}){Text(hiltechText("تحديث","Refresh"))}
            }
        }
    }
}

@Composable
private fun FinanceOpeningStockEditor(client:FinanceConfigurationApiClient,org:String,period:String,currency:String,
    installation:String,editable:Boolean,values:List<FinanceOpeningStockValueDto>,used:Set<String>,
    onChange:(List<FinanceOpeningStockValueDto>)->Unit) {
    var offset by remember(org,period,currency){mutableIntStateOf(0)}
    var revision by remember(org,period,currency){mutableIntStateOf(0)}
    var page by remember(org,period,currency){mutableStateOf<FinanceOpeningStockChoicesDto?>(null)}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf("")}
    var value by remember{mutableStateOf("")}
    var valuationRef by remember{mutableStateOf("")}
    // The source page contains quantities, not spreadsheet valuation guesses.
    LaunchedEffect(client,org,period,currency,installation,offset,revision){
        page=null;loading=true;error=false
        try{page=client.openingStockSources(org,period,currency,offset,installation)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){error=true}
        finally{loading=false}
    }
    Text(hiltechText("مخزون فعلي مثبت في تاريخ الافتتاح","Verified physical stock at cutover"),style=MaterialTheme.typography.titleMedium)
    Text(hiltechText("اختر حركة الافتتاح المثبتة وأدخل إجمالي القيمة الدفترية لهذا البند من كشف تقييم راجعه المحاسب. لن تتغير كمية المخزون. لا يشمل هذا المسار تكلفة الصرف أو تقييم الحركات التالية.",
        "Select a verified opening movement and enter its total carrying value from a reviewed valuation schedule. Stock quantities do not change. This does not value subsequent issues or movements."),style=MaterialTheme.typography.bodySmall)
    if(loading)Text(hiltechText("جارٍ قراءة مصادر المخزون…","Reading stock sources…"))
    if(error)Text(hiltechText("تعذر قراءة مصدر المخزون.","Could not read stock source."),color=MaterialTheme.colorScheme.error)
    page?.let{data->
        val choices=data.records.filter{r->r.movementId !in used&&values.none{it.movementId==r.movementId}}
        if(choices.isEmpty())Text(hiltechText("لا توجد حركات افتتاح غير مختارة في هذه الصفحة.","No unselected opening movements on this page."))
        else HiltechRecordPicker(hiltechText("حركة افتتاح المخزون","Stock opening movement"),selected,
            choices.map{r->HiltechRecordChoice(r.movementId,r.itemName+" · "+r.itemCode,r.quantity+" "+r.unit+" · "+r.locationCode)},
            onChange={selected=it;value="";valuationRef=""},allowKnownReference=false,enabled=editable&&!loading)
        choices.singleOrNull{it.movementId==selected}?.let{r->
            HiltechKeyValue(hiltechText("الكمية المثبتة","Verified quantity"),r.quantity+" "+r.unit)
            SetupField(hiltechText("إجمالي القيمة الدفترية","Total carrying value"),value,editable){value=it.take(25)}
            SetupField(hiltechText("مرجع كشف التقييم المراجع","Reviewed valuation reference"),valuationRef,editable){valuationRef=it.take(240)}
            val draft=FinanceOpeningStockValueDto(r.movementId,r.quantity,r.unit,r.sourceFingerprint,value,valuationRef.trim())
            TextButton(enabled=editable&&values.size+used.size<1000&&openingStockValueValid(draft),onClick={
                onChange(values+draft);selected="";value="";valuationRef=""
            }){Text(hiltechText("إضافة قيمة البند","Add carrying value"))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            TextButton(enabled=editable&&offset>0,onClick={offset=(offset-50).coerceAtLeast(0);selected=""}){Text(hiltechText("السابق","Previous"))}
            TextButton(enabled=editable&&data.hasMore,onClick={offset+=50;selected=""}){Text(hiltechText("التالي","Next"))}
        }
    }
    TextButton(enabled=editable&&!loading,onClick={revision++}){Text(hiltechText("تحديث المصادر","Refresh sources"))}
    values.forEach{v->
        HiltechKeyValue(v.quantity+" "+v.unit,v.amount+" "+currency)
        Text(v.valuationReference,style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=editable,onClick={onChange(values.filterNot{it.movementId==v.movementId})}){Text(hiltechText("حذف القيمة","Remove value"))}
    }
}
