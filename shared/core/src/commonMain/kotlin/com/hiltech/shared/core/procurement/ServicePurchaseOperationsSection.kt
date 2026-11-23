package com.hiltech.shared.core.procurement

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
) {
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("External services",style=MaterialTheme.typography.titleLarge)
        Text("Request → supplier quote → approval → delivery → invoice review.",
            style=MaterialTheme.typography.bodySmall)
        ServiceBuyerAndIssuerPane(sourcing,api,organizationId,installationId,newOperationId)
        ServiceAcceptancePane(api,organizationId,installationId,newOperationId,uploadFile)
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
    var busy by remember(org) {mutableStateOf(false)}
    var error by remember(org) {mutableStateOf<String?>(null)}
    var needs by remember(org) {mutableStateOf<List<ServiceEligibleNeedDto>>(emptyList())}
    var quotes by remember(org) {mutableStateOf<List<ServiceSupplierQuoteDto>>(emptyList())}
    var currentRfq by remember(org) {mutableStateOf<String?>(null)}
    var selected by remember(org) {mutableStateOf<ServiceOrderCandidateDto?>(null)}
    var queue by remember(org) {mutableStateOf<List<ServiceOrderCandidateDto>>(emptyList())}
    var confirmation by remember(org) {mutableStateOf<String?>(null)}

    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Purchasing and approval",style=MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {needs=sourcing.eligibleNeeds(org,install)}
                    catch(e:Exception){error=e.message?:"Buyer authorization is unavailable."}
                    finally {busy=false}
                }
            }){Text("My approved service requests")}
            needs.forEach { need ->
                OutlinedButton(enabled=!busy&&need.sourceCurrent,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {
                            val rfqId=need.rfqId ?: sourcing.open(
                                need.needId,OpenServiceRfqDto(operation(),need.needVersion),
                                install).rfq.id
                            currentRfq=rfqId
                            quotes=sourcing.comparison(rfqId,install)
                        } catch(e:Exception){error=e.message?:"The service source was changed."}
                        finally {busy=false}
                    }
                }) {Text("Review supplier offers · "+need.specification.take(60))}
            }
            currentRfq?.let { rfq ->
                Text("Verified supplier offers",style=MaterialTheme.typography.titleSmall)
                if(quotes.isEmpty())Text("No verified supplier quotes yet.")
                quotes.forEach { quote ->
                    OutlinedButton(enabled=!busy&&quote.sourceCurrent,onClick={
                        busy=true;error=null
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
                        Text("Select "+quote.supplierOrganizationId.take(8)+
                            " · "+quote.currency+" "+quote.totalAmount)
                    }
                }
            }
            selected?.let { candidate ->
                Text("Selected offer · "+candidate.currency+" "+candidate.totalAmount)
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
                }) {Text("Request company approval")}
                Text("Approval: "+(candidate.approvalState?:"Not requested"))
            }
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {queue=api.queue(org,install)}
                    catch(e:Exception){error=e.message?:"Service order issue permission unavailable."}
                    finally {busy=false}
                }
            }){Text("My approved service orders to issue")}
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
                    ) {Text("Issue approved internal service order")}
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
    var busy by remember(org){mutableStateOf(false)}
    var error by remember(org){mutableStateOf<String?>(null)}
    var confirmation by remember(org){mutableStateOf<String?>(null)}
    var orders by remember(org){mutableStateOf<List<ServiceWorkOrderDto>>(emptyList())}
    var notes by remember(org){mutableStateOf("")}
    var outcome by remember(org){mutableStateOf("ACCEPTED")}
    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Was the service actually completed?",style=MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {orders=api.deliveryOrders(org,install)}
                    catch(e:Exception){error=e.message?:"Service reviewer is not authorized."}
                    finally{busy=false}
                }
            }){Text("My service delivery reviews")}
            OutlinedTextField(value=notes,onValueChange={notes=it},
                label={Text("What was checked, accepted or needs rework")},
                modifier=Modifier.fillMaxWidth())
            listOf("ACCEPTED","NEEDS_REWORK","REJECTED").forEach { choice ->
                OutlinedButton(enabled=!busy,onClick={outcome=choice}) {
                    Text((if(outcome==choice)"✓ " else "")+when(choice) {
                        "ACCEPTED"->"Accepted"
                        "NEEDS_REWORK"->"Needs rework"
                        else->"Rejected"
                    })
                }
            }
            orders.forEach { order ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Service "+order.orderId.take(8)+" · Supplier "+
                        order.supplierOrganizationId.take(8)+
                        (if(order.accepted)" · Accepted" else " · Awaiting review"))
                    OutlinedButton(enabled=!busy&&notes.trim().length in 12..1000,
                        onClick={
                        busy=true;error=null
                        scope.launch {
                            try {
                                val slot=api.documentSlot(order.orderId,
                                    CreateServicePurchaseDocumentSlotDto(operation(),"DELIVERY"),
                                    install)
                                // Native host picks REAL bytes, calls existing
                                // EvidenceApiClient upload/finalize, then returns READY metadata.
                                val uploaded=uploadFile("DELIVERY",slot)
                                require(uploaded.targetType=="SERVICE_PURCHASE_DOCUMENT" &&
                                    uploaded.targetId==slot.slotId &&
                                    uploaded.storageState=="READY")
                                api.reviewDelivery(order.orderId,
                                    RecordServiceDeliveryDto(operation(),slot.slotId,
                                        uploaded.evidenceId,uploaded.sha256,outcome,notes.trim()),
                                    install)
                                orders=api.deliveryOrders(org,install)
                                confirmation="Service review recorded from actual finalized evidence."
                                notes=""
                            } catch(e:Exception){error=e.message?:"Delivery review is incomplete."}
                            finally{busy=false}
                        }
                    }) {Text("Upload proof and record service result")}
                }
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
    var busy by remember(org){mutableStateOf(false)}
    var error by remember(org){mutableStateOf<String?>(null)}
    var confirmation by remember(org){mutableStateOf<String?>(null)}
    var orders by remember(org){mutableStateOf<List<ServiceWorkOrderDto>>(emptyList())}
    var invoices by remember(org){mutableStateOf<List<ServiceInvoiceDto>>(emptyList())}
    var invoiceNumber by remember(org){mutableStateOf("")}
    var invoiceDate by remember(org){mutableStateOf("")}
    var currency by remember(org){mutableStateOf("")}
    var amount by remember(org){mutableStateOf("")}
    var tax by remember(org){mutableStateOf("")}
    var match by remember(org){mutableStateOf<ServiceInvoiceMatchDto?>(null)}

    Card(modifier=Modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Supplier invoice check",style=MaterialTheme.typography.titleMedium)
            Text("Only an accepted service and matching original invoice can be handed to Finance. No payment is issued.",
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=!busy,onClick={
                busy=true;error=null
                scope.launch {
                    try {
                        orders=api.invoiceOrders(org,install)
                        invoices=api.invoices(org,install)
                    } catch(e:Exception){error=e.message?:"Finance permission unavailable."}
                    finally{busy=false}
                }
            }){Text("Load current service orders and invoices")}
            OutlinedTextField(value=invoiceNumber,onValueChange={invoiceNumber=it},
                label={Text("Supplier invoice reference")})
            OutlinedTextField(value=invoiceDate,onValueChange={invoiceDate=it},
                label={Text("Invoice date (YYYY-MM-DD)")})
            OutlinedTextField(value=currency,onValueChange={currency=it.uppercase()},
                label={Text("Currency (EGP, USD...)")})
            OutlinedTextField(value=amount,onValueChange={amount=it},
                label={Text("Supplier service amount")})
            OutlinedTextField(value=tax,onValueChange={tax=it},
                label={Text("Tax shown on invoice")})
            val valid=invoiceNumber.trim().length in 2..96 &&
                Regex("\\d{4}-\\d{2}-\\d{2}").matches(invoiceDate) &&
                Regex("[A-Z]{3}").matches(currency)&&amount.toDoubleOrNull()?.let{it>=0}==true&&
                tax.toDoubleOrNull()?.let{it>=0}==true
            orders.forEach { order ->
                OutlinedButton(enabled=!busy&&valid,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {
                            val slot=api.documentSlot(order.orderId,
                                CreateServicePurchaseDocumentSlotDto(operation(),"INVOICE"),install)
                            val uploaded=uploadFile("INVOICE",slot)
                            require(uploaded.targetType=="SERVICE_PURCHASE_DOCUMENT" &&
                                uploaded.targetId==slot.slotId&&uploaded.storageState=="READY")
                            api.captureInvoice(order.orderId,CaptureServiceInvoiceDto(
                                operation(),slot.slotId,uploaded.evidenceId,uploaded.sha256,
                                invoiceNumber.trim(),invoiceDate,currency,amount,tax),install)
                            invoices=api.invoices(org,install)
                            confirmation="Actual supplier invoice captured, not paid."
                        } catch(e:Exception){error=e.message?:"Invoice evidence is missing or invalid."}
                        finally{busy=false}
                    }
                }) {Text("Upload invoice for service "+order.orderId.take(8))}
            }
            invoices.forEach { inv ->
                OutlinedButton(enabled=!busy,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {match=api.preview(inv.invoiceId,install)}
                        catch(e:Exception){error=e.message?:"Cannot compute current match."}
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
                        } catch(e:Exception){error=e.message?:"Invoice sources changed."}
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
                                confirmation="Sent to Finance review queue; no payable or payment created."
                            }catch(e:Exception){error=e.message?:"Review-only Finance handoff refused."}
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
