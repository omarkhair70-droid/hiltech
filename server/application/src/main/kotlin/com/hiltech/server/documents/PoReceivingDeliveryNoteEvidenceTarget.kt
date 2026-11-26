package com.hiltech.server.documents

import java.util.UUID

/**
 * Existing canonical warehouse Receiving record, bound to a real company PO.
 * Evidence is a document attachment, not a second receipt ledger.
 */
data class PoReceivingDeliveryNoteEvidenceTarget(
    val receivingRecordId: UUID,
    val purchaseOrderId: UUID,
    val organizationId: UUID,
    val destinationStorageLocationId: UUID,
    val receivingState: String,
)

interface PoReceivingDeliveryNoteEvidenceTargetPort {
    fun loadTarget(receivingRecordId: UUID): PoReceivingDeliveryNoteEvidenceTarget?
    fun canReserve(actor: UUID, target: PoReceivingDeliveryNoteEvidenceTarget): Boolean
    fun canAccess(actor: UUID, target: PoReceivingDeliveryNoteEvidenceTarget): Boolean
}
