package com.hiltech.server.documents

import java.util.UUID

data class ServiceDeliveryDocumentEvidenceTarget(
    val slotId: UUID, val organizationId: UUID, val projectId: UUID, val purpose: String, val createdBy: UUID,
)

interface ServiceDeliveryDocumentEvidenceTargetPort {
    fun loadTarget(slotId: UUID): ServiceDeliveryDocumentEvidenceTarget?
    fun canReserve(actor: UUID, target: ServiceDeliveryDocumentEvidenceTarget): Boolean
    fun canAccess(actor: UUID, target: ServiceDeliveryDocumentEvidenceTarget): Boolean
}
