package com.hiltech.server.procurement


data class PurchaseReviewProofDto(val evidenceId: String, val sha256: String, val contentType: String,
    val fileName: String, val sizeBytes: Long, val available: Boolean)

data class PurchaseOrderReviewDto(
    val candidateId: String, val organizationId: String, val candidateRevision: Int,
    val approvalRequestId: String, val approvalRequestVersion: Long, val approvalState: String, val policyRevision: Int,
    val supplierName: String, val supplierCode: String, val itemName: String, val itemCode: String,
    val quantity: String, val unitCode: String, val unitPrice: String, val taxAmount: String, val totalAmount: String, val currency: String,
    val sourceKind: String, val sourceLabel: String, val requirementRevision: Long,
    val quoteReference: String, val quoteRevision: Int, val quoteValidUntil: String?, val deliveryDays: Int?,
    val deliveryTerms: String?, val paymentTerms: String?, val warrantyTerms: String?, val selectionReason: String,
    val preparedByName: String, val preparedAt: String,
    val sourceCurrent: Boolean, val independentReviewer: Boolean, val quotationOpened: Boolean,
    val canApprove: Boolean, val canDecide: Boolean, val canCloseStale: Boolean,
    val quotation: PurchaseReviewProofDto?,
)

data class PurchaseReviewSourceDto(val approvalRequestVersion: Long, val candidateRevision: Int,
    val quoteRevision: Int, val evidenceId: String, val expectedSha256: String)

data class PurchaseQuotePageRequestDto(val source: PurchaseReviewSourceDto, val pageIndex: Int)
data class PurchaseQuotePageDto(val deliveryId: String, val candidateId: String, val approvalRequestId: String,
    val source: PurchaseReviewSourceDto, val pageIndex: Int, val pageCount: Int, val imagePngBase64: String)

data class OpenPurchaseReviewQuoteDto(val operationId: String, val deliveryId: String, val source: PurchaseReviewSourceDto)
data class PurchaseQuoteOpeningReceiptDto(val operationId: String, val candidateId: String, val approvalRequestId: String,
    val opened: Boolean, val replayed: Boolean)

data class DecidePurchaseReviewDto(val operationId: String, val source: PurchaseReviewSourceDto,
    val decision: String, val comment: String? = null)
data class PurchaseReviewDecisionReceiptDto(val operationId: String, val candidateId: String,
    val approvalRequestId: String, val approvalState: String, val replayed: Boolean)

/** Canonical own-decision status after a new authenticated session; contains no quotation or salary data. */
data class PurchaseReviewCompletedDto(val candidateId: String, val approvalRequestId: String,
    val approvalState: String, val completedAt: String, val decidedByCurrentUser: Boolean)

