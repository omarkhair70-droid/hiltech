package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import kotlinx.coroutines.launch

/**
 * One real service purchasing desk for Windows and Android.
 * Source and permissions are server-derived; there are no mock rows.
 *
 * The host shell supplies its EXISTING native file picker + EvidenceApiClient
 * upload/finalize implementation through [uploadFile]. It must not return
 * unfinalized or fabricated Evidence metadata. This component is only mounted
 * after the actual client integration owner binds file-picking capabilities.
 */
@Composable
fun ServicePurchaseOperationsSection(
    sourcing:ServiceSupplierSourcingApiClient,
    api:ServiceOrderAndInvoiceApiClient,
    organizationId:String,
    installationId:String,
    newOperationId:()->String,
    uploadFile:suspend (documentKind:String,slot:ServicePurchaseDocumentSlotDto)->EvidenceMetadataDto,
    buyerAnchor: Modifier = Modifier,
    deliveryAnchor: Modifier = Modifier,
    invoiceAnchor: Modifier = Modifier,
) {
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("مسار تنفيذ الخدمات / Service lifecycle",style=MaterialTheme.typography.titleLarge)
        Text("طلب الخدمة ← عروض الموردين ← الاعتماد ← إثبات التنفيذ ← مراجعة الفاتورة.",
            style=MaterialTheme.typography.bodySmall)
        // Anchors are small measurable targets; the shell owns vertical scroll.
        Spacer(Modifier.fillMaxWidth().height(1.dp).then(buyerAnchor))
        ServiceBuyerAndIssuerPane(sourcing,api,organizationId,installationId,newOperationId)
        Spacer(Modifier.fillMaxWidth().height(1.dp).then(deliveryAnchor))
        ServiceAcceptancePane(api,organizationId,installationId,newOperationId,uploadFile)
        Spacer(Modifier.fillMaxWidth().height(1.dp).then(invoiceAnchor))
        ServiceInvoicePane(api,organizationId,installationId,newOperationId,uploadFile)
    }
}

@Composable
private fun ServiceBuyerAndIssuerPane(
    sourcing:ServiceSupplierSourcingApiClient,
    api:ServiceOrderAndInvoiceApiClient,
    org:String,install:String,operation:()->String,
) {
    val scope=rememberCoroutineScope()
    var busy by remember(org,install) {mutableStateOf(false)}
    var error by remember(org,install) {mutableStateOf<String?>(null)}
    var needs by remember(org,install) {mutableStateOf<List<ServiceEligibleNeedDto>>(emptyList())}
    var quotes by remember(org,install) {mutableStateOf<List<ServiceSupplierQuoteDto>>(emptyList())}
    var currentRfq by remember(org,install) {mutableStateOf<String?>(null)}
    var selected by remember(org,install) {mutableStateOf<ServiceOrderCandidateDto?>(null)}
    var queue by remember(org,install) {mutableStateOf<List<ServiceOrderCandidateDto>>(emptyList())}
    var confirmation by remember(org,install) {mutableStateOf<String?>(null)}

    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("الشراء والاعتماد / Purchasing & approval",style=MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null;confirmation=null
                // Permission/source reload revokes previous buyer actions immediately.
                needs=emptyList();quotes=emptyList();currentRfq=null;selected=null;queue=emptyList()
                scope.launch {
                    try {needs=sourcing.eligibleNeeds(org,install)}
                    catch(e:Exception){error=e.message?:"Buyer authorization is unavailable."}
                    finally {busy=false}
                }
            }){Text("طلبات الخدمات الجاهزة للشراء")}
            needs.forEach { need ->
                OutlinedButton(enabled=!busy&&need.sourceCurrent,onClick={
                    busy=true;error=null;confirmation=null
                    currentRfq=null;quotes=emptyList();selected=null
                    scope.launch {
                        try {
                            val rfqId=need.rfqId ?: sourcing.open(
                                need.needId,OpenServiceRfqDto(operation(),need.needVersion),
                                install).rfq.id
                            val sourceQuotes=sourcing.comparison(rfqId,install)
                            currentRfq=rfqId
                            quotes=sourceQuotes
                        } catch(e:Exception){error=e.message?:"The service source was changed."}
                        finally {busy=false}
                    }
                }) {Text("مراجعة عروض الموردين · "+need.specification.take(60))}
            }
            currentRfq?.let { rfq ->
                Text("العروض المسجلة للمراجعة",style=MaterialTheme.typography.titleSmall)
                if(quotes.isEmpty())Text("لا توجد عروض موردين حالية لهذا الطلب.")
                quotes.forEach { quote ->
                    OutlinedButton(enabled=!busy&&quote.sourceCurrent,onClick={
                        busy=true;error=null;confirmation=null;selected=null
                        scope.launch {
                            try {
                                val candidate=api.selectQuote(
                                    CreateServiceOrderCandidateDto(operation(),rfq,quote.id),
                                    install).candidate
                                selected=candidate
                                confirmation="Supplier offer selected; request approval next."
                            } catch(e:Exception){error=e.message?:"Offer selection unavailable."}
                            finally{busy=false}
                        }
                    }) {
                        Text("اختيار عرض · "+quote.supplierOrganizationId.take(8)+
                            " · "+quote.currency+" "+quote.totalAmount)
                    }
                }
            }
            selected?.let { candidate ->
                Text("العرض المختار · "+candidate.currency+" "+candidate.totalAmount)
                OutlinedButton(enabled=!busy&&candidate.sourceCurrent,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {
                            api.requestApproval(candidate.id,
                                ServiceOrderApprovalRequestDto(operation()),install)
                            selected=api.candidate(candidate.id,install)
                            confirmation="Approval requested in the existing HILTECH approvals system."
                        } catch(e:Exception){error=e.message?:"Approval not available."}
                        finally {busy=false}
                    }
                }) {Text("طلب اعتماد الشركة")}
                Text("حالة الاعتماد: "+(candidate.approvalState?:"لم يُطلب بعد"))
            }
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null;queue=emptyList()
                scope.launch {
                    try {queue=api.queue(org,install)}
                    catch(e:Exception){error=e.message?:"Service order issue permission unavailable."}
                    finally {busy=false}
                }
            }){Text("أوامر الخدمات المصرح بإصدارها")}
            queue.forEach { candidate ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Supplier "+candidate.supplierOrganizationId.take(8)+
                        " · "+candidate.currency+" "+candidate.totalAmount+
                        " · "+(candidate.approvalState?:"PENDING"))
                    OutlinedButton(
                        enabled=!busy&&candidate.sourceCurrent&&candidate.approvalState=="APPROVED",
                        onClick={
                            busy=true;error=null
                            scope.launch {
                                try {
                                    val issued=api.issue(candidate.id,
                                        IssueServiceOrderDto(operation(),1),install)
                                    queue=queue.filterNot{it.id==candidate.id}
                                    confirmation="Internal service order issued: "+
                                        issued.orderId.take(8)+
                                        ". Supplier was NOT contacted automatically."
                                } catch(e:Exception){error=e.message?:"Order cannot be issued."}
                                finally{busy=false}
                            }
                        }
                    ) {Text("إصدار أمر خدمة داخلي معتمد")}
                }
            }
            confirmation?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable
private fun ServiceAcceptancePane(
    api:ServiceOrderAndInvoiceApiClient,
    org:String,install:String,operation:()->String,
    uploadFile:suspend (String,ServicePurchaseDocumentSlotDto)->EvidenceMetadataDto,
) {
    val scope=rememberCoroutineScope()
    var busy by remember(org,install){mutableStateOf(false)}
    var error by remember(org,install){mutableStateOf<String?>(null)}
    var confirmation by remember(org,install){mutableStateOf<String?>(null)}
    var orders by remember(org,install){mutableStateOf<List<ServiceWorkOrderDto>>(emptyList())}
    var selectedDeliveryOrderId by remember(org,install){mutableStateOf<String?>(null)}
    var notes by remember(org,install){mutableStateOf("")}
    var outcome by remember(org,install){mutableStateOf("ACCEPTED")}
    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("إثبات تنفيذ الخدمة / Delivery review",style=MaterialTheme.typography.titleMedium)
            Text("اختار أمر الخدمة أولًا. الملاحظات ونتيجة الفحص مرتبطة بالأمر المختار فقط.",
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null
                selectedDeliveryOrderId=null;orders=emptyList()
                notes="";outcome="ACCEPTED"
                scope.launch {
                    try {orders=api.deliveryOrders(org,install)}
                    catch(e:Exception){error=e.message?:"Service reviewer is not authorized."}
                    finally{busy=false}
                }
            }){Text("تحميل أوامر الخدمات المصرح بمراجعتها")}
            orders.forEach { order ->
                OutlinedButton(enabled=!busy,onClick={
                    if(selectedDeliveryOrderId!=order.orderId) {
                        selectedDeliveryOrderId=order.orderId
                        notes="";outcome="ACCEPTED";error=null;confirmation=null
                    }
                }) {
                    Text((if(selectedDeliveryOrderId==order.orderId)"✓ " else "")+
                        "أمر "+order.orderId.take(8)+" · مورد "+order.supplierOrganizationId.take(8)+
                        (if(order.accepted)" · مقبول" else " · بانتظار الفحص"))
                }
            }
            val selectedOrder=orders.firstOrNull{it.orderId==selectedDeliveryOrderId}
            selectedOrder?.let { order ->
                Text("الفحص الحالي للأمر "+order.orderId.take(8),style=MaterialTheme.typography.titleSmall)
                OutlinedTextField(value=notes,onValueChange={notes=it},
                    label={Text("ما تم فحصه أو قبوله أو المطلوب إعادة عمله")},
                    modifier=Modifier.fillMaxWidth(),enabled=!busy)
                listOf("ACCEPTED","NEEDS_REWORK","REJECTED").forEach { choice ->
                    OutlinedButton(enabled=!busy,onClick={outcome=choice}) {
                        Text((if(outcome==choice)"✓ " else "")+when(choice) {
                            "ACCEPTED"->"مقبول"
                            "NEEDS_REWORK"->"إعادة عمل"
                            else->"مرفوض"
                        })
                    }
                }
                OutlinedButton(enabled=!busy&&notes.trim().length in 12..1000,
                    onClick={
                    busy=true;error=null
                    val selectedOutcome=outcome
                    val selectedNotes=notes.trim()
                    scope.launch {
                        try {
                            val slot=api.documentSlot(order.orderId,
                                CreateServicePurchaseDocumentSlotDto(operation(),"DELIVERY"),
                                install)
                            // Existing native host delivers true READY Evidence bytes.
                            val uploaded=uploadFile("DELIVERY",slot)
                            require(uploaded.targetType=="SERVICE_PURCHASE_DOCUMENT" &&
                                uploaded.targetId==slot.slotId &&
                                uploaded.storageState=="READY")
                            api.reviewDelivery(order.orderId,
                                RecordServiceDeliveryDto(operation(),slot.slotId,
                                    uploaded.evidenceId,uploaded.sha256,selectedOutcome,selectedNotes),
                                install)
                            orders=api.deliveryOrders(org,install)
                            selectedDeliveryOrderId=null
                            confirmation="تم تسجيل الفحص مع المستند الأصلي؛ لا يعني دفع فاتورة."
                            notes=""
                        } catch(e:Exception){error=e.message?:"Delivery review is incomplete."}
                        finally{busy=false}
                    }
                }) {Text("اختيار مستند إثبات التنفيذ وتسجيل النتيجة")}
            }
            confirmation?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable
private fun ServiceInvoicePane(
    api:ServiceOrderAndInvoiceApiClient,
    org:String,install:String,operation:()->String,
    uploadFile:suspend (String,ServicePurchaseDocumentSlotDto)->EvidenceMetadataDto,
) {
    val scope=rememberCoroutineScope()
    var busy by remember(org,install){mutableStateOf(false)}
    var error by remember(org,install){mutableStateOf<String?>(null)}
    var confirmation by remember(org,install){mutableStateOf<String?>(null)}
    var orders by remember(org,install){mutableStateOf<List<ServiceWorkOrderDto>>(emptyList())}
    var selectedInvoiceOrderId by remember(org,install){mutableStateOf<String?>(null)}
    var invoices by remember(org,install){mutableStateOf<List<ServiceInvoiceDto>>(emptyList())}
    var invoiceNumber by remember(org,install){mutableStateOf("")}
    var invoiceDate by remember(org,install){mutableStateOf("")}
    var currency by remember(org,install){mutableStateOf("")}
    var amount by remember(org,install){mutableStateOf("")}
    var tax by remember(org,install){mutableStateOf("")}
    var match by remember(org,install){mutableStateOf<ServiceInvoiceMatchDto?>(null)}

    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("فحص فاتورة المورد / Supplier invoice review",style=MaterialTheme.typography.titleMedium)
            Text("اختار أمر خدمة واحدًا وأدخل فاتورته الأصلية. المطابقة تتيح المراجعة المالية فقط ولا تصرف أي مبلغ.",
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null;match=null
                orders=emptyList();invoices=emptyList();selectedInvoiceOrderId=null
                scope.launch {
                    try {
                        orders=api.invoiceOrders(org,install)
                        invoices=api.invoices(org,install)
                    } catch(e:Exception){error=e.message?:"Finance permission unavailable."}
                    finally{busy=false}
                }
            }){Text("تحميل أوامر الخدمات والفواتير الحالية")}
            orders.forEach { order ->
                OutlinedButton(enabled=!busy,onClick={
                    if(selectedInvoiceOrderId!=order.orderId) {
                        selectedInvoiceOrderId=order.orderId
                        invoiceNumber="";invoiceDate="";currency="";amount="";tax=""
                        match=null;error=null;confirmation=null
                    }
                }) {
                    Text((if(selectedInvoiceOrderId==order.orderId)"✓ " else "")+
                        "اختيار أمر خدمة "+order.orderId.take(8)+" · مورد "+
                        order.supplierOrganizationId.take(8))
                }
            }
            val selectedOrder=orders.firstOrNull{it.orderId==selectedInvoiceOrderId}
            selectedOrder?.let { order ->
                Text("فاتورة أمر الخدمة "+order.orderId.take(8),
                    style=MaterialTheme.typography.titleSmall)
                OutlinedTextField(value=invoiceNumber,onValueChange={invoiceNumber=it},
                    label={Text("رقم فاتورة المورد")},enabled=!busy)
                OutlinedTextField(value=invoiceDate,onValueChange={invoiceDate=it},
                    label={Text("تاريخ الفاتورة YYYY-MM-DD")},enabled=!busy)
                OutlinedTextField(value=currency,onValueChange={currency=it.uppercase()},
                    label={Text("العملة EGP / USD ...")},enabled=!busy)
                OutlinedTextField(value=amount,onValueChange={amount=it},
                    label={Text("قيمة الخدمة")},enabled=!busy)
                OutlinedTextField(value=tax,onValueChange={tax=it},
                    label={Text("قيمة الضريبة")},enabled=!busy)
                val nonNegativeMoney=Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$")
                val valid=invoiceNumber.trim().length in 2..96 &&
                    Regex("\\d{4}-\\d{2}-\\d{2}").matches(invoiceDate) &&
                    Regex("[A-Z]{3}").matches(currency) &&
                    nonNegativeMoney.matches(amount) && nonNegativeMoney.matches(tax)
                OutlinedButton(enabled=!busy&&valid,onClick={
                    busy=true;error=null
                    val capturedReference=invoiceNumber.trim()
                    val capturedDate=invoiceDate
                    val capturedCurrency=currency
                    val capturedAmount=amount
                    val capturedTax=tax
                    scope.launch {
                        try {
                            val slot=api.documentSlot(order.orderId,
                                CreateServicePurchaseDocumentSlotDto(operation(),"INVOICE"),install)
                            val uploaded=uploadFile("INVOICE",slot)
                            require(uploaded.targetType=="SERVICE_PURCHASE_DOCUMENT" &&
                                uploaded.targetId==slot.slotId&&uploaded.storageState=="READY")
                            api.captureInvoice(order.orderId,CaptureServiceInvoiceDto(
                                operation(),slot.slotId,uploaded.evidenceId,uploaded.sha256,
                                capturedReference,capturedDate,capturedCurrency,capturedAmount,capturedTax),install)
                            invoices=api.invoices(org,install)
                            match=null
                            selectedInvoiceOrderId=null
                            invoiceNumber="";invoiceDate="";currency="";amount="";tax=""
                            confirmation="تم تسجيل الفاتورة الأصلية؛ لم يتم دفعها."
                        } catch(e:Exception){error=e.message?:"Invoice evidence is missing or invalid."}
                        finally{busy=false}
                    }
                }) {Text("اختيار مستند الفاتورة وتسجيله للأمر المحدد")}
            }
            invoices.forEach { inv ->
                OutlinedButton(enabled=!busy,onClick={
                    busy=true;error=null;match=null
                    scope.launch {
                        try {match=api.preview(inv.invoiceId,install)}
                        catch(e:Exception){
                            match=null
                            error=e.message?:"Cannot compute current match."
                        }
                        finally{busy=false}
                    }
                }) {
                    Text(inv.invoiceNumber+" · "+inv.currency+" "+inv.totalAmount+
                        " · Review")
                }
            }
            match?.let { preview ->
                Text("Service invoice result: "+preview.resultCode)
                Text(preview.expectedCurrency+" "+preview.expectedTotal+
                    " vs "+preview.invoicedCurrency+" "+preview.invoicedTotal,
                    style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!busy,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {
                            match=api.recordMatch(preview.invoiceId,
                                RecordServiceInvoiceMatchDto(
                                    operation(),preview.sourceSha256),install)
                            confirmation="Immutable invoice check recorded."
                        } catch(e:Exception){
                            match=null
                            error=e.message?:"Invoice sources changed."
                        }
                        finally{busy=false}
                    }
                }){Text("Record invoice match result")}
                if(preview.resultCode=="MATCHED" && preview.matchEventId!=null) {
                    OutlinedButton(enabled=!busy,onClick={
                        busy=true;error=null
                        scope.launch {
                            try {
                                api.financeReviewHandoff(preview.invoiceId,
                                    HandOffServiceInvoiceDto(
                                        operation(),requireNotNull(preview.matchEventId)),install)
                                match=null
                                confirmation="أُتيحت الفاتورة للمراجعة المالية فقط؛ لم يُنشأ صرف أو دفع."
                            }catch(e:Exception){
                                match=null
                                error=e.message?:"Review-only Finance handoff refused."
                            }
                            finally{busy=false}
                        }
                    }){Text("Make available for Finance review")}
                }
            }
            confirmation?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}
