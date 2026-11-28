package com.hiltech.shared.core.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException

private suspend fun <T> deskAvailable(read:suspend ()->T):T? =
    try { read() } catch (cancelled:CancellationException) { throw cancelled }
    catch (failure:HiltechApiException) {
        if (failure.httpStatus in setOf(401,403,404)) null else throw failure
    }

fun financeAccountingDeskWorkspace(client:FinanceAccountingDeskApiClient,identityId:String,
    organizationId:String,installationId:String,ledgerEnabled:Boolean):NativeOperationalWorkspace =
    NativeOperationalWorkspace(id="finance-accounting-desk",label="الحسابات والدفتر",
        canReadSource={
            deskAvailable { client.exposure(organizationId,"AP",0,installationId) }!=null
        },
        content={ _ -> key(client,identityId,organizationId,installationId) {
            FinanceAccountingDesk(client,organizationId,installationId,ledgerEnabled)
        }})

@Composable
private fun deskKind(kind:String):String=when(kind) {
    "AP" -> hiltechText("الموردون","Suppliers")
    "AR" -> hiltechText("العملاء","Customers")
    "AP_REVERSAL" -> hiltechText("عكس قيد مورد","Supplier reversal")
    "BALANCES" -> hiltechText("أرصدة الحسابات","Account balances")
    "OPENING_BALANCE" -> hiltechText("قيد افتتاح","Opening journal")
    "BANK_ALLOCATION","CASH_VOUCHER","BANK_VOID","EMPLOYEE_FUNDING","EMPLOYEE_RETURN","IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT","PAYROLL_RECOVERY","PAYROLL_OPENING_PAYMENT" -> settlementLedgerTitle(kind)
    in payrollDeskSources -> payrollGlTitle(kind)
    else -> hiltechText("القيود","Journal entries")
}

@Composable
private fun FinanceAccountingDesk(client:FinanceAccountingDeskApiClient,org:String,installation:String,ledgerEnabled:Boolean) {
    var tab by remember { mutableStateOf("AP") }
    var periods by remember { mutableStateOf<FinanceDeskPeriodsDto?>(null) }
    var periodId by remember { mutableStateOf("") }
    var exposure by remember { mutableStateOf<FinanceDeskExposureDto?>(null) }
    var ledger by remember { mutableStateOf<FinanceDeskLedgerDto?>(null) }
    var balances by remember { mutableStateOf<FinanceDeskBalancesDto?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var selectedJournal by remember { mutableStateOf<Pair<String,String>?>(null) }

    LaunchedEffect(client,org,installation,ledgerEnabled,revision) {
        periods=null
        if (ledgerEnabled) {
            try {
                val source=deskAvailable { client.periods(org,installation) }
                periods=source
                if (source!=null && source.records.none { it.id==periodId }) {
                    periodId=source.records.firstOrNull()?.id.orEmpty()
                    offset=0
                }
            } catch (cancelled:CancellationException) { throw cancelled }
            catch (_:Exception) { periods=null }
        }
    }
    LaunchedEffect(client,org,installation,tab,periodId,offset,revision,periods?.records) {
        loading=true; error=false; exposure=null; ledger=null; balances=null; selectedJournal=null
        try {
            if (tab=="LEDGER") {
                if (periods?.records?.any { it.id==periodId }==true)
                    ledger=client.ledger(org,periodId,offset,installation)
            } else if(tab=="BALANCES") {
                if(periods?.records?.any{it.id==periodId}==true) balances=client.balances(org,periodId,installation)
            } else exposure=client.exposure(org,tab,offset,installation)
        } catch (cancelled:CancellationException) { throw cancelled }
        catch (_:Exception) { error=true }
        finally { loading=false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        HiltechSectionHeading("FINANCE",hiltechText("الحسابات والدفتر","Accounts & ledger"),
            hiltechText("من القيود المسجلة إلى المستحقات الفعلية، دون خلط العملات أو افتراض حركة نقدية.",
                "From posted journals to recorded obligations, without mixing currencies or inferring cash movement."))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val tabs=listOf("AP","AR")+(if(periods!=null) listOf("BALANCES","LEDGER") else emptyList())
            tabs.forEach { item ->
                FilterChip(selected=tab==item,onClick={
                    if(tab!=item) { tab=item; offset=0; exposure=null; ledger=null; balances=null; loading=true }
                },label={Text(deskKind(item))})
            }
        }
        TextButton(onClick={offset=0;exposure=null;ledger=null;balances=null;loading=true;revision++},enabled=!loading) {
            Text(hiltechText("تحديث من المصدر","Refresh from source"))
        }
        if(tab=="LEDGER"||tab=="BALANCES") {
            periods?.let { list ->
                HiltechRecordPicker(hiltechText("الفترة المحاسبية","Accounting period"),periodId,
                    list.records.map { HiltechRecordChoice(it.id,it.dateStart+" — "+it.dateEnd,
                        if(it.state=="CLOSED") hiltechText("مقفلة","Closed") else hiltechText("مفتوحة","Open")) },
                    onChange={ if(periodId!=it || offset!=0) {periodId=it;offset=0;ledger=null;balances=null;loading=true} },
                    allowKnownReference=false,allowClear=false,enabled=!loading)
                if(list.hasMore) Text(hiltechText("تظهر أحدث 100 فترة فقط.","Showing the latest 100 periods only."))
            }
            if(periods==null) Text(hiltechText("لم يتأكد الوصول إلى الدفتر الحالي.","Current ledger access was not confirmed."))
            else if(periodId.isBlank()) Text(hiltechText("لا توجد فترة محاسبية مسجلة.","No recorded accounting period."))
            balances?.takeIf{tab=="BALANCES"&&it.periodId==periodId&&!loading}?.let{DeskBalances(it)}
            ledger?.takeIf { tab=="LEDGER"&&it.periodId==periodId && it.offset==offset && !loading }?.let { data ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("حركة الحسابات خلال الفترة","Account movements for the period"),style=MaterialTheme.typography.titleLarge)
                    Text(if("OPENING_BALANCE" in data.includedSources)
                        hiltechText("قيود المصادر المتاحة تشمل قيود الافتتاح المسجلة؛ لا تعني اكتمال أرصدة الدفاتر الفرعية أو الحركات غير المرحلة.","Available posted sources include recorded opening journals; unposted movements and complete subledger openings are not implied.")
                    else if(data.includedSources.containsAll(payrollDeskSources))
                        hiltechText("تشمل القيود المرحلة ضمن المصادر المتاحة، ومنها قيود الرواتب الموزعة. لا تشمل أرصدة الافتتاح أو الرواتب غير المرحلة أو حركات لم ترحل؛ ليست ميزان مراجعة شاملًا.",
                            "Includes posted journals from available sources, including allocated payroll. Excludes opening balances, unposted payroll, and unposted movements; not a complete trial balance.")
                    else if(data.includedSources.containsAll(financeEmployeeSettlementTypes))
                        hiltechText("تشمل القيود المرحلة للموردين والعملاء والسداد والتحصيل والسلف والعهد والمصروفات. لا تشمل أرصدة الافتتاح والرواتب والحركات غير المرحلة؛ ليست ميزان مراجعة شاملًا.",
                            "Includes posted AP/AR, settlement, custody and paid-cost journals. Excludes opening balances, payroll and unposted movements; not a complete trial balance.")
                    else if(data.includedSources.containsAll(financeSettlementTypes))
                        hiltechText("قيود الموردين والعملاء وعكس الموردين وقيود السداد والتحصيل المرحلة. لا يشمل أرصدة الافتتاح أو الرواتب والنقد غير المرحل؛ ليس ميزان مراجعة شاملًا.",
                            "Posted supplier/customer journals, supplier reversals and posted settlement journals. Excludes opening balances, payroll and unposted cash; not a complete trial balance.")
                    else hiltechText("قيود الموردين والعملاء وعكس الموردين فقط. لا يشمل أرصدة الافتتاح أو قيود الرواتب والنقد غير المرحلة؛ ليس ميزان مراجعة شاملًا.",
                        "Posted supplier/customer journals and supplier reversals only. Excludes opening balances and unposted payroll/cash entries; not a complete trial balance."),
                        style=MaterialTheme.typography.bodySmall)
                    data.movements.groupBy { it.currency }.forEach { (currency,rows) ->
                        Text(currency,style=MaterialTheme.typography.titleMedium)
                        data.currencyTotals.singleOrNull { it.currency==currency }?.let { total ->
                            HiltechKeyValue(hiltechText("إجمالي الحركة المدينة","Total debit movement"),total.debit+" "+currency)
                            HiltechKeyValue(hiltechText("إجمالي الحركة الدائنة","Total credit movement"),total.credit+" "+currency)
                            Text(if(total.balanced) hiltechText("الحركة المسجلة متوازنة","Posted movement balances")
                                else hiltechText("حركة غير متوازنة؛ يلزم فحص المصدر","Unbalanced movement; source review required"),
                                color=if(total.balanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        HiltechRecordTable(listOf(HiltechTableColumn(hiltechText("الحساب","Account"),2f),
                            HiltechTableColumn(hiltechText("مدين","Debit"),numeric=true),
                            HiltechTableColumn(hiltechText("دائن","Credit"),numeric=true)),
                            rows.map { HiltechTableRecord(listOf(it.accountName+" · "+it.accountCode,it.debit,it.credit)) },"")
                    }
                    if(data.movements.isEmpty()) Text(hiltechText("لا توجد قيود من هذه المصادر في الفترة.","No journals from these sources in this period."))
                }
                Text(hiltechText("سجل القيود","Journal register"),style=MaterialTheme.typography.titleLarge)
                data.journals.forEach { journal ->
                    val id=journal.kind to journal.id
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(deskKind(journal.kind)+" · "+journal.currency,style=MaterialTheme.typography.titleMedium)
                        Text(journal.postedAt.replace('T',' '),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={selectedJournal=if(selectedJournal==id)null else id}) {
                            Text(if(selectedJournal==id) hiltechText("إخفاء القيد","Hide journal") else hiltechText("عرض تفاصيل القيد","View journal"))
                        }
                        if(selectedJournal==id) {
                            journal.lines.forEach { line ->
                                HiltechKeyValue(line.accountName+" · "+line.accountCode,
                                    (if(line.side=="DEBIT") hiltechText("مدين: ","Debit: ") else hiltechText("دائن: ","Credit: "))+line.amount+" "+journal.currency)
                            }
                            Text(journal.id,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                DeskPage(offset,data.hasMore){offset=it;ledger=null;loading=true}
            }
        } else exposure?.takeIf { it.kind==tab && it.offset==offset && !loading }?.let { data ->
            Text(hiltechText("حتى تاريخ المصدر: ","Source as of: ")+data.asOfDate,style=MaterialTheme.typography.bodySmall)
            Text(hiltechText("المستحقات حسب القيود والتخصيصات المسجلة، وليست أرصدة بنكية أو إثبات مطابقة بنكية.",
                "Obligations from posted journals and recorded allocations, not bank balances or proof of reconciliation."),
                style=MaterialTheme.typography.bodySmall)
            data.totals.forEach { total ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(total.knownOutstanding+" "+total.currency,style=MaterialTheme.typography.headlineMedium)
                    Text(hiltechText("المتبقي المعروف في السجلات","Known outstanding in source records"))
                    HiltechKeyValue(hiltechText("متأخر وفق مواعيد موثقة","Overdue with documented dates"),total.knownOverdue+" "+total.currency)
                    if(total.openWithoutDueDate>0) Text(hiltechText("سجلات مفتوحة بلا موعد استحقاق: ","Open records without due dates: ")+total.openWithoutDueDate)
                    if(total.exceptionCount>0) Text(hiltechText("استثناءات تحتاج تصحيحًا ولا تدخل في المبلغ المعروف: ","Exceptions excluded from known amount: ")+total.exceptionCount,
                        color=MaterialTheme.colorScheme.error)
                }
            }
            if(data.totals.isEmpty()) Text(hiltechText("لا توجد قيود مستحقات مسجلة في هذا المصدر.","No posted obligations in this source."))
            data.records.forEach { row ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(row.counterpartyName,style=MaterialTheme.typography.titleMedium)
                    row.openingDocumentReference?.let{ref->Text(hiltechText("مستحق افتتاحي: ","Opening obligation: ")+ref+(row.openingDocumentDate?.let{" · "+it}.orEmpty()),style=MaterialTheme.typography.bodySmall)}
                    HiltechKeyValue(hiltechText("القيمة الأصلية","Original amount"),row.gross+" "+row.currency)
                    HiltechKeyValue(hiltechText("المخصص محاسبيًا","Recorded allocation"),row.allocated+" "+row.currency)
                    HiltechKeyValue(hiltechText("المتبقي","Outstanding"),if(row.reversed || row.outstanding==null || row.outstanding.startsWith("-"))
                        hiltechText("راجع حالة السجل","Review record state") else row.outstanding+" "+row.currency)
                    HiltechKeyValue(hiltechText("موعد الاستحقاق","Due date"),row.dueDate ?: hiltechText("غير موثق","Not documented"))
                    Text(when(row.state) {
                        "UNALLOCATED" -> hiltechText("لم يُخصص تحصيل أو سداد","No allocation recorded")
                        "PARTIALLY_ALLOCATED" -> hiltechText("تخصيص جزئي","Partly allocated")
                        "ALLOCATED_NOT_BANK_RECONCILED" -> hiltechText("مخصص بالكامل؛ لا يثبت المطابقة البنكية","Fully allocated; not proof of bank reconciliation")
                        "REVERSED_NO_OUTSTANDING" -> hiltechText("قيد معكوس","Reversed journal")
                        else -> hiltechText("استثناء للمراجعة","Exception to review")
                    },style=MaterialTheme.typography.bodySmall)
                }
            }
            DeskPage(offset,data.hasMore){offset=it;exposure=null;loading=true}
        }
        if(loading) Text(hiltechText("جارٍ قراءة الحسابات من المصدر…","Reading accounting sources…"))
        if(error) Text(hiltechText("تعذر تأكيد المصدر الحالي. حدّث السجل؛ لا يُعرض مبلغ تقديري.",
            "Current source could not be confirmed. Refresh; no estimated amount is displayed."),color=MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DeskBalances(data:FinanceDeskBalancesDto) {
    var report by remember {mutableStateOf("BALANCES")}
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("الأرصدة من القيود المرحلة","Balances from posted journals"),style=MaterialTheme.typography.titleLarge)
        Text(if(data.recordedOpeningCurrencies.isNotEmpty()) hiltechText("يشمل الرصيد المرحّل قيود الافتتاح المسجلة وصافي القيود السابقة. لا يعني اكتمال افتتاح الدفاتر الفرعية أو كل الحركات؛ ليس ميزان مراجعة شاملًا.","Carried balances include recorded opening journals and net prior entries. Subledger openings and all movements are not necessarily complete; not a complete trial balance.")
            else hiltechText("الرصيد المرحّل هو صافي القيود السابقة المتاحة، وليس رصيد افتتاح معتمدًا. التقرير لا يشمل الحركات غير المرحلة أو مصادر غير متاحة؛ ليس ميزان مراجعة أو قائمة دخل كاملة.",
            "Carried balances are net prior posted entries, not verified opening balances. Unposted or unavailable sources are excluded; this is not a complete trial balance or income statement."),
            style=MaterialTheme.typography.bodySmall)
        Text(hiltechText("أقدم فترة مشمولة: ","Earliest included period: ")+data.firstIncludedDate)
        if(data.recordedOpeningCurrencies.isNotEmpty())Text(hiltechText("عملات لها قيد افتتاح مسجل: ","Currencies with recorded opening: ")+data.recordedOpeningCurrencies.joinToString(", "))
        if(data.priorCoverageHasGaps) Text(hiltechText("توجد فجوات أو تداخلات في الفترات السابقة. راجع التقويم المحاسبي.","Earlier periods have gaps or overlaps. Review the accounting calendar."),color=MaterialTheme.colorScheme.error)
        if(!data.includedSources.containsAll(payrollDeskSources)) Text(hiltechText("قيود الرواتب غير مشمولة في صلاحيتك الحالية.","Payroll journals are outside your current reporting scope."))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("BALANCES" to hiltechText("ميزان الأرصدة","Trial balances"),
            "POSITION" to hiltechText("المركز المسجل","Recorded position"),
            "TREASURY" to hiltechText("حركة النقد والبنوك","Cash & bank movement")).forEach{(id,label)->
            FilterChip(selected=report==id,onClick={report=id},label={Text(label)})
        }
    }
    if(report=="POSITION") {
        DeskRecordedPosition(data)
        return
    }
    if(report=="TREASURY") {
        DeskTreasury(data)
        return
    }
    data.totals.forEach { total ->
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(total.currency,style=MaterialTheme.typography.titleLarge)
            HiltechKeyValue(hiltechText("الختامي — مدين","Closing debit"),total.closingDebit+" "+total.currency)
            HiltechKeyValue(hiltechText("الختامي — دائن","Closing credit"),total.closingCredit+" "+total.currency)
            Text(if(total.balanced)hiltechText("الأرصدة والحركة المسجلة متوازنة","Recorded balances and movement balance")
                else hiltechText("الأرصدة غير متوازنة؛ راجع المصدر","Balances are not balanced; review the source"),
                color=if(total.balanced)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(hiltechText("نتيجة الفترة من المصادر المتاحة","Period result from available sources"),style=MaterialTheme.typography.titleMedium)
            HiltechKeyValue(hiltechText("الإيرادات المثبتة","Recognized revenue"),total.recognizedRevenue+" "+total.currency)
            HiltechKeyValue(hiltechText("المصروفات المثبتة","Recognized expense"),total.recognizedExpense+" "+total.currency)
            Text(total.recognizedResult+" "+total.currency,style=MaterialTheme.typography.headlineSmall)
        }
        data.rows.filter{it.currency==total.currency}.forEach { account ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(account.accountName+" · "+account.accountCode,style=MaterialTheme.typography.titleMedium)
                HiltechRecordTable(listOf(HiltechTableColumn(hiltechText("الرصيد / الحركة","Balance / movement"),2f),
                    HiltechTableColumn(hiltechText("مدين","Debit"),numeric=true),HiltechTableColumn(hiltechText("دائن","Credit"),numeric=true)),
                    listOf(HiltechTableRecord(listOf(hiltechText("مرحّل من السابق","Carried forward"),account.openingDebit,account.openingCredit)),
                        HiltechTableRecord(listOf(hiltechText("حركة الفترة","Period movement"),account.periodDebit,account.periodCredit)),
                        HiltechTableRecord(listOf(hiltechText("ختامي","Closing"),account.closingDebit,account.closingCredit))),"")
            }
        }
    }
    if(data.rows.isEmpty()) Text(hiltechText("لا توجد قيود مرحلة في نطاق التقرير.","No posted entries in this report scope."))
}

@Composable
private fun DeskRecordedPosition(data:FinanceDeskBalancesDto) {
    Text(hiltechText("المركز المالي من القيود المتاحة","Financial position from available journals"),style=MaterialTheme.typography.titleLarge)
    Text(hiltechText("الأصول والالتزامات وحقوق الملكية بحسب تصنيف دليل الحسابات. نتيجة القيود التراكمية منفصلة عن حقوق الملكية؛ لا تفترض إقفال الأرباح أو اكتمال قائمة المركز المالي.",
        "Assets, liabilities and equity use chart classifications. Cumulative journal result stays separate from equity; neither profit closing nor a complete balance sheet is assumed."),style=MaterialTheme.typography.bodySmall)
    data.positions.forEach{p->
        HiltechPanel(Modifier.fillMaxWidth()){
            Text(p.currency,style=MaterialTheme.typography.titleMedium)
            HiltechKeyValue(hiltechText("صافي الأصول المسجلة","Recorded net assets"),p.assets)
            HiltechKeyValue(hiltechText("الالتزامات المسجلة","Recorded liabilities"),p.liabilities)
            HiltechKeyValue(hiltechText("حقوق الملكية المسجلة","Recorded equity"),p.equity)
            HiltechKeyValue(hiltechText("النتيجة التراكمية غير المقفلة","Cumulative nominal-account result"),p.cumulativeRecognizedResult)
            HiltechKeyValue(hiltechText("صافي حسابات غير مصنفة","Unclassified debit net"),p.unclassifiedDebitNet)
            Text(if(p.equationBalances)hiltechText("المعادلة المحاسبية للمصادر متوازنة","Recorded-source accounting equation balances")
                else hiltechText("راجع التصنيف أو عدم التوازن","Review classification or imbalance"),
                color=if(p.equationBalances)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
    if(data.positions.isEmpty())Text(hiltechText("لا توجد أرصدة مسجلة.","No recorded balances."))
}

@Composable
private fun cashFlowCategoryLabel(category:String):String=when(category){
    "OPERATING"->hiltechText("تشغيلية","Operating")
    "INVESTING"->hiltechText("استثمارية","Investing")
    "ACCOUNTING_ADJUSTMENT"->hiltechText("تصحيح محاسبي — ليس تحويلًا","Accounting adjustment — not a transfer")
    else->hiltechText("تحتاج تصنيفًا","Needs classification")
}

@Composable
private fun DeskTreasury(data:FinanceDeskBalancesDto) {
    Text(hiltechText("حركة النقد والبنوك المرحلة","Posted cash & bank movement"),style=MaterialTheme.typography.titleLarge)
    Text(hiltechText("حركة حسابات الخزائن والبنوك في الدفتر، لا رصيد كشف البنك أو دليل حركة نقدية فعلية. تشمل تصحيحات القيود؛ لا تمثل قائمة تدفقات نقدية مصنفة مكتملة.",
        "Ledger movements of cashboxes and banks, not statement balances or proof of actual cash transfer. Includes accounting adjustments; not a complete classified cash flow statement."),style=MaterialTheme.typography.bodySmall)
    data.cashFlow.forEach{flow->
        HiltechPanel(Modifier.fillMaxWidth()){
            Text(hiltechText("تصنيف حركة الفترة","Period movement classification")+" · "+flow.currency,style=MaterialTheme.typography.titleMedium)
            if(flow.categories.isNotEmpty())HiltechRecordTable(listOf(
                HiltechTableColumn(hiltechText("التصنيف","Category"),2f),
                HiltechTableColumn(hiltechText("مدين","Debit"),numeric=true),
                HiltechTableColumn(hiltechText("دائن","Credit"),numeric=true),
                HiltechTableColumn(hiltechText("صافي","Net"),numeric=true)),
                flow.categories.map{HiltechTableRecord(listOf(cashFlowCategoryLabel(it.category),it.debit,it.credit,it.net))},"")
            HiltechKeyValue(hiltechText("صافي الحركة المصنفة","Classified net movement"),flow.netClassifiedMovement)
            HiltechKeyValue(hiltechText("صافي تصحيحات القيود","Accounting adjustments net"),flow.accountingAdjustmentNet)
            HiltechKeyValue(hiltechText("صافي الحركة التي تحتاج تصنيفًا","Unclassified net movement"),flow.unclassifiedNet)
            HiltechKeyValue(hiltechText("صافي حركة الدفتر بالكامل","Total ledger movement"),flow.totalMovement)
            if(flow.categories.any{it.category=="UNCLASSIFIED"})Text(hiltechText("حركات السلف والعهد والمشتريات المختلطة وغيرها تحتاج أساس تصنيف معتمد؛ لا تُوزع تقديريًا.","Staff advances, custody, mixed purchases and other movements need an approved classification basis; no estimated split is made."),color=MaterialTheme.colorScheme.error)
            Text(hiltechText("مصادر التمويل ليست مكتملة في هذا التقرير؛ عدم وجود بند لا يعني عدم وجود حركة تمويل.","Financing sources are not complete in this report; an absent category does not prove no financing movement."),style=MaterialTheme.typography.bodySmall)
            Text(if(flow.reconcilesToLedger)hiltechText("التصنيفات تتطابق مع حركة حسابات النقد والبنوك","Categories reconcile to posted cash and bank movement")
                else hiltechText("التصنيفات لا تتطابق مع حركة الدفتر","Categories do not reconcile to ledger movement"),
                color=if(flow.reconcilesToLedger)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
    data.treasury.forEach{t->
        HiltechPanel(Modifier.fillMaxWidth()){
            Text((if(t.accountKind=="BANK_ASSET")hiltechText("البنوك","Banks")else hiltechText("الخزائن","Cashboxes"))+" · "+t.currency,style=MaterialTheme.typography.titleMedium)
            HiltechKeyValue(hiltechText("صافي المرحّل","Carried net"),t.carriedNet)
            HiltechKeyValue(hiltechText("الزيادة المدينة","Debit increases"),t.debits)
            HiltechKeyValue(hiltechText("النقص الدائن","Credit decreases"),t.credits)
            HiltechKeyValue(hiltechText("صافي الحركة","Net movement"),t.netMovement)
            HiltechKeyValue(hiltechText("صافي الختامي","Closing net"),t.closingNet)
            if(!t.movementBalances)Text(hiltechText("حركة غير متطابقة مع الرصيد؛ راجع المصدر.","Movement does not reconcile to balance; review source."),color=MaterialTheme.colorScheme.error)
            val sources=data.treasurySources.filter{it.currency==t.currency&&it.accountKind==t.accountKind}
            if(sources.isNotEmpty())HiltechRecordTable(listOf(HiltechTableColumn(hiltechText("مصدر القيد","Journal source"),2f),
                HiltechTableColumn(hiltechText("مدين","Debit"),numeric=true),HiltechTableColumn(hiltechText("دائن","Credit"),numeric=true)),
                sources.map{HiltechTableRecord(listOf(deskKind(it.sourceKind)+" · "+cashFlowCategoryLabel(it.category),it.debits,it.credits))},"")
        }
    }
    if(data.treasury.isEmpty())Text(hiltechText("لا توجد قيود خزائن أو بنوك في النطاق الحالي.","No cashbox or bank journals in the current scope."))
}

@Composable
private fun DeskPage(offset:Int,hasMore:Boolean,onPage:(Int)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=offset>0,onClick={onPage((offset-50).coerceAtLeast(0))}) { Text(hiltechText("السابق","Previous")) }
        Text((offset/50+1).toString(),style=MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled=hasMore && offset<1_000_000,onClick={onPage(offset+50)}) { Text(hiltechText("التالي","Next")) }
    }
}
