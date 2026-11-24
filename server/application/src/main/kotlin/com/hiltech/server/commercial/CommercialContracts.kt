package com.hiltech.server.commercial

import java.math.BigDecimal

data class CreateCommercialOpportunityRequest(
    val operationId: String,
    val organizationId: String,
    val clientOrganizationId: String,
    val title: String,
    val source: String,
    val description: String? = null,
    val expectedDecisionDate: String? = null,
)

data class CommercialQualifyRequest(
    val operationId: String,
    val baseVersion: Long,
    val discoverySummary: String,
    val openQuestions: String = "",
)

data class CommercialEvidenceConfirmationRequest(
    val operationId: String,
    val evidenceId: String,
)

data class CommercialTenderRequest(
    val operationId: String,
    val tenderType: String,
    val submissionState: String = "RECEIVED",
    val referenceNumber: String? = null,
    val receivedAt: String,
    val submissionDeadline: String? = null,
    val clarificationDeadline: String? = null,
    val sourceChannel: String? = null,
)

data class CommercialVisitRequest(
    val operationId: String,
    val scheduledAt: String,
    val purpose: String,
    val siteId: String? = null,
    val notes: String? = null,
)

data class CommercialVisitCompletionRequest(
    val operationId: String,
    val baseVersion: Long,
    val notes: String,
)

data class CommercialCloseRequest(
    val operationId: String,
    val baseVersion: Long,
    val state: String,
    val reason: String,
)

data class CommercialScopeRequest(
    val operationId: String,
    val description: String,
    val inclusions: String,
    val exclusions: String,
    val assumptions: String,
    val technicalConstraints: String,
    val clientResponsibilities: String,
)

data class CommercialBoqLineInput(
    val lineCode: String,
    val description: String,
    val category: String,
    val quantity: BigDecimal,
    val unit: String,
    val internalUnitCost: BigDecimal? = null,
    val sellUnitPrice: BigDecimal,
)

data class CommercialBoqRequest(
    val operationId: String,
    val scopeVersionId: String,
    val currency: String,
    val lines: List<CommercialBoqLineInput>,
)

data class CommercialQuoteRequest(
    val operationId: String,
    val boqVersionId: String,
    val paymentTerms: String,
    val deliveryTerms: String,
    val exclusions: String,
    val validityDate: String,
)

data class CommercialApprovalRequest(val operationId: String)
data class CommercialContractDraftRequest(
    val operationId: String,
    val quoteVersionId: String,
    val contractCode: String,
    val effectiveDate: String? = null,
    val endDate: String? = null,
    val warrantyTerms: String? = null,
    val maintenanceTerms: String? = null,
)
data class CommercialClientPoDraftRequest(
    val operationId: String,
    val contractId: String,
    val clientPoNumber: String,
    val issuedOn: String,
    val amount: BigDecimal,
    val currency: String,
)
data class CommercialConversionRequest(
    val operationId: String,
    val contractId: String,
    val clientPoId: String,
)

data class CommercialCommandResult(
    val id: String,
    val replayed: Boolean,
    val state: String,
    val correlationId: String,
)

/** Deliberately no internal cost, vendor quote ID, salary, or margin fields. */
data class ClientCommercialQuote(
    val quoteId: String,
    val quoteCode: String,
    val versionNumber: Int,
    val clientOrganizationId: String,
    val currency: String,
    val totalPrice: BigDecimal,
    val paymentTerms: String,
    val deliveryTerms: String,
    val exclusions: String,
    val validityDate: String,
    val lines: List<ClientCommercialQuoteLine>,
    val clientName: String? = null,
    val issuerName: String? = null,
    val releasedAt: String? = null,
)
data class ClientCommercialQuoteLine(
    val lineCode: String,
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
)
