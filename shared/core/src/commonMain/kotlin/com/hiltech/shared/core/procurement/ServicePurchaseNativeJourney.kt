package com.hiltech.shared.core.procurement

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import kotlinx.coroutines.launch

/**
 * Native wayfinding over the existing authenticated service-purchase components.
 *
 * All five stages stay mounted: unsent text and currently selected sources do
 * not disappear when the user jumps between steps. The host owns the one
 * vertical scroll; this component adds only horizontal step shortcuts.
 *
 * Showing a shortcut is NOT a permission assertion. Every action remains in
 * the original Phase 7 typed API, server source, approval and Evidence gates.
 */
@Composable
fun ServicePurchaseNativeJourney(
    needApi: ServicePurchaseNeedApiClient,
    sourcing: ServiceSupplierSourcingApiClient,
    supplierRegistry: SupplierMasterDataApiClient,
    quoteUploader: ServiceSupplierQuoteUploader,
    api: ServiceOrderAndInvoiceApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    pickQuoteFile: suspend () -> ServiceSelectedFile?,
    uploadFile: suspend (documentKind: String, slot: ServicePurchaseDocumentSlotDto) -> EvidenceMetadataDto,
) {
    val requestAnchor = remember(organizationId, installationId) { BringIntoViewRequester() }
    val quoteAnchor = remember(organizationId, installationId) { BringIntoViewRequester() }
    val approvalAnchor = remember(organizationId, installationId) { BringIntoViewRequester() }
    val deliveryAnchor = remember(organizationId, installationId) { BringIntoViewRequester() }
    val invoiceAnchor = remember(organizationId, installationId) { BringIntoViewRequester() }
    val navigationScope = rememberCoroutineScope()
    val steps = listOf(
        "01 · الطلب" to requestAnchor,
        "02 · عروض الموردين" to quoteAnchor,
        "03 · الموافقات والأوامر" to approvalAnchor,
        "04 · إثبات التنفيذ" to deliveryAnchor,
        "05 · فحص الفواتير" to invoiceAnchor,
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("الخدمات الخارجية / Service purchasing", style = MaterialTheme.typography.titleLarge)
        Text(
            "انتقل مباشرة للخطوة المطلوبة. كل خطوة تقرأ بياناتها وصلاحياتها من المصدر الحقيقي؛ إصدار أمر داخلي لا يعني إرسالًا للمورد، وفحص الفاتورة لا يعني دفعها.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEach { (label, requester) ->
                OutlinedButton(
                    onClick = {
                        navigationScope.launch { requester.bringIntoView() }
                    },
                ) { Text(label) }
            }
        }
        ServiceJourneyAnchor(requestAnchor)
        ServicePurchaseNeedSection(
            api = needApi,
            organizationId = organizationId,
            installationId = installationId,
            newOperationId = newOperationId,
        )
        ServiceJourneyAnchor(quoteAnchor)
        ServiceSupplierQuoteCaptureSection(
            sourcing = sourcing,
            supplierRegistry = supplierRegistry,
            uploader = quoteUploader,
            organizationId = organizationId,
            installationId = installationId,
            newOperationId = newOperationId,
            pickQuoteFile = pickQuoteFile,
        )
        ServicePurchaseOperationsSection(
            sourcing = sourcing,
            api = api,
            organizationId = organizationId,
            installationId = installationId,
            newOperationId = newOperationId,
            uploadFile = uploadFile,
            buyerAnchor = Modifier.bringIntoViewRequester(approvalAnchor),
            deliveryAnchor = Modifier.bringIntoViewRequester(deliveryAnchor),
            invoiceAnchor = Modifier.bringIntoViewRequester(invoiceAnchor),
        )
    }
}

@Composable
private fun ServiceJourneyAnchor(requester: BringIntoViewRequester) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(requester))
}
