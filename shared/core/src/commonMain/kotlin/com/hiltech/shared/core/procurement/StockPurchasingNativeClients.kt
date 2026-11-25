package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiClient

/** One authenticated session for the buyer, reviewer handoff and issuer journey. */
class StockPurchasingNativeClients(api: HiltechApiClient, evidence: EvidenceApiClient) {
    val sourcing = SupplierSourcingApiClient(api)
    val candidates = PurchaseOrderCandidateApiClient(api)
    val issue = PurchaseOrderIssueApiClient(api)
    val amendments = PurchaseOrderDeliveryAmendmentApiClient(api)
    val communications = SupplierPoCommunicationApiClient(api)
    val quoteDocuments = SupplierQuoteDocumentUploader(sourcing, evidence)
}
