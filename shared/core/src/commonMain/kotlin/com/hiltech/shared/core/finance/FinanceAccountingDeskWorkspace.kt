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
    else -> hiltechText("القيود","Journal entries")
}

@Composable
private fun FinanceAccountingDesk(client:FinanceAccountingDeskApiClient,org:String,installation:String,ledgerEnabled:Boolean) {
    var tab by remember { mutableStateOf("AP") }
    var periods by remember { mutableStateOf<FinanceDeskPeriodsDto?>(null) }
    var periodId by remember { mutableStateOf("") }
    var exposure by remember { mutableStateOf<FinanceDeskExposureDto?>(null) }
    var ledger by remember { mutableStateOf<FinanceDeskLedgerDto?>(null) }
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
        loading=true; error=false; exposure=null; ledger=null; selectedJournal=null
        try {
            if (tab=="LEDGER") {
                if (periods?.records?.any { it.id==periodId }==true)
                    ledger=client.ledger(org,periodId,offset,installation)
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
            val tabs=listOf("AP","AR")+(if(periods!=null) listOf("LEDGER") else emptyList())
            tabs.forEach { item ->
                FilterChip(selected=tab==item,onClick={
                    if(tab!=item) { tab=item; offset=0; exposure=null; ledger=null; loading=true }
                },label={Text(deskKind(item))})
            }
        }
        TextButton(onClick={offset=0;exposure=null;ledger=null;loading=true;revision++},enabled=!loading) {
            Text(hiltechText("تحديث من المصدر","Refresh from source"))
        }
        if(tab=="LEDGER") {
            periods?.let { list ->
                HiltechRecordPicker(hiltechText("الفترة المحاسبية","Accounting period"),periodId,
                    list.records.map { HiltechRecordChoice(it.id,it.dateStart+" — "+it.dateEnd,
                        if(it.state=="CLOSED") hiltechText("مقفلة","Closed") else hiltechText("مفتوحة","Open")) },
                    onChange={ if(periodId!=it || offset!=0) {periodId=it;offset=0;ledger=null;loading=true} },
                    allowKnownReference=false,allowClear=false,enabled=!loading)
                if(list.hasMore) Text(hiltechText("تظهر أحدث 100 فترة فقط.","Showing the latest 100 periods only."))
            }
            if(periods==null) Text(hiltechText("لم يتأكد الوصول إلى الدفتر الحالي.","Current ledger access was not confirmed."))
            else if(periodId.isBlank()) Text(hiltechText("لا توجد فترة محاسبية مسجلة.","No recorded accounting period."))
            ledger?.takeIf { it.periodId==periodId && it.offset==offset && !loading }?.let { data ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("حركة الحسابات خلال الفترة","Account movements for the period"),style=MaterialTheme.typography.titleLarge)
                    Text(hiltechText("قيود الموردين والعملاء وعكس الموردين فقط. لا يشمل أرصدة الافتتاح أو قيود الرواتب والنقد غير المرحلة؛ ليس ميزان مراجعة شاملًا.",
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
private fun DeskPage(offset:Int,hasMore:Boolean,onPage:(Int)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=offset>0,onClick={onPage((offset-50).coerceAtLeast(0))}) { Text(hiltechText("السابق","Previous")) }
        Text((offset/50+1).toString(),style=MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled=hasMore && offset<1_000_000,onClick={onPage(offset+50)}) { Text(hiltechText("التالي","Next")) }
    }
}
