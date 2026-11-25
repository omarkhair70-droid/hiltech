package com.hiltech.server.documents

import java.time.Instant
import java.util.UUID

data class WorkOrderEvidencePolicySnapshot(
    val workOrderId: UUID,
    val organizationId: UUID,
    val workOrderVersion: Long,
    val lifecycleState: String,
    val instructionRevision: Long?,
    val evidencePolicyId: UUID,
    val evidencePolicyRevision: Int,
    val requirementKey: String,
    val evidenceTypeCode: String,
    val allowedContentTypes: Set<String>,
    val systemMaxSizeBytes: Long,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val securityScanClass: String,
)

data class EvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val policy: WorkOrderEvidencePolicySnapshot,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val supersedesEvidenceId: UUID?,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class EmployeeDocumentEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val employeeDocumentId: UUID,
    val evidenceTypeCode: String,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class SupplierQuoteDocumentEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val documentSlotId: UUID,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class SupplierInvoiceDocumentEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val documentSlotId: UUID,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class PoReceivingDeliveryNoteEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val receivingRecordId: UUID,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class FinanceBankStatementEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val bankProposalId: UUID,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class FinanceExpenseReceiptEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val expenseDraftId: UUID,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

/** Common private signed-document payload; each owning adapter validates its own exact target. */
data class PrivateDocumentEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val targetType: String,
    val targetId: UUID,
    val evidenceTypeCode: String,
    val objectKey: String,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

typealias CommercialEvidenceReservationInsert = PrivateDocumentEvidenceReservationInsert

data class EvidenceReservationRecord(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val targetType: String,
    val targetId: UUID,
    val workOrderId: UUID?,
    val evidenceRequirementKey: String?,
    val evidencePolicyId: UUID?,
    val evidencePolicyRevision: Int?,
    val evidenceTypeCode: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val capturedByUserId: UUID,
    val storageState: String,
    val objectKey: String,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val securityScanClass: String?,
    val uploadState: String,
    val uploadExpiresAt: Instant,
    val evidenceVersion: Long,
    val uploadSessionVersion: Long,
)

data class EvidenceFinalizeRecord(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val organizationId: UUID,
    val targetType: String,
    val targetId: UUID,
    val workOrderId: UUID?,
    val workOrderLifecycleState: String?,
    val capturedByUserId: UUID,
    val evidenceRequirementKey: String?,
    val evidencePolicyId: UUID?,
    val evidencePolicyRevision: Int?,
    val evidenceTypeCode: String,
    val contentType: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val storageState: String,
    val uploadState: String,
    val objectKey: String,
    val uploadExpiresAt: Instant,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val securityScanClass: String,
    val evidenceVersion: Long,
    val uploadSessionVersion: Long,
)

interface EvidencePersistencePort {
    fun loadWorkOrderPolicy(
        workOrderId: UUID,
        requirementKey: String,
    ): WorkOrderEvidencePolicySnapshot?

    fun isValidSupersededEvidence(
        supersedesEvidenceId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
    ): Boolean

    fun insertReservation(
        value: EvidenceReservationInsert,
    )

    fun insertEmployeeDocumentReservation(
        value: EmployeeDocumentEvidenceReservationInsert,
    )

    fun insertSupplierQuoteDocumentReservation(
        value: SupplierQuoteDocumentEvidenceReservationInsert,
    )

    fun insertServiceQuoteDocumentReservation(
        value: SupplierQuoteDocumentEvidenceReservationInsert,
    ) {
        throw UnsupportedOperationException("Service quote Evidence repository is not configured.")
    }

    fun insertServicePurchaseDocumentReservation(
        value: SupplierQuoteDocumentEvidenceReservationInsert,
        documentKind: String,
    ) {
        throw UnsupportedOperationException("Service purchase Evidence repository is not configured.")
    }

    fun insertSupplierInvoiceDocumentReservation(
        value: SupplierInvoiceDocumentEvidenceReservationInsert,
    ) {
        throw UnsupportedOperationException("Invoice Evidence repository is not configured.")
    }

    fun insertPoReceivingDeliveryNoteReservation(
        value: PoReceivingDeliveryNoteEvidenceReservationInsert,
    ) {
        throw UnsupportedOperationException("PO delivery note Evidence repository is not configured.")
    }

    fun insertFinanceBankStatementReservation(
        value: FinanceBankStatementEvidenceReservationInsert,
    ) {
        throw UnsupportedOperationException("Finance bank Evidence target not configured.")
    }

    fun insertFinanceExpenseReceiptReservation(
        value: FinanceExpenseReceiptEvidenceReservationInsert,
    ) {
        throw UnsupportedOperationException("Finance receipt Evidence target not configured.")
    }

    fun insertCommercialReservation(
        value: CommercialEvidenceReservationInsert,
    ) {
        throw UnsupportedOperationException("Commercial Evidence requires an explicitly implemented canonical persistence adapter.")
    }

    fun insertServiceDeliveryReservation(value: PrivateDocumentEvidenceReservationInsert) {
        throw UnsupportedOperationException("Service delivery Evidence requires its canonical persistence adapter.")
    }

    fun findReservationByOperationId(
        operationId: UUID,
    ): EvidenceReservationRecord?

    fun loadEvidenceRecord(
        evidenceId: UUID,
    ): EvidenceFinalizeRecord?

    fun loadFinalizeRecord(
        evidenceId: UUID,
        uploadSessionId: UUID,
    ): EvidenceFinalizeRecord?

    fun markFinalized(
        evidenceId: UUID,
        uploadSessionId: UUID,
        expectedEvidenceVersion: Long,
        expectedUploadSessionVersion: Long,
        state: String,
        finalizedAt: Instant,
    ): Boolean
}
