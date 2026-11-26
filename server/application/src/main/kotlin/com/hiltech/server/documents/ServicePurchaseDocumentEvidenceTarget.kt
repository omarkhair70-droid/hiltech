package com.hiltech.server.documents

import java.util.UUID

/** Existing Evidence upload/finalize with a type-scoped service document slot. */
data class ServicePurchaseDocumentEvidenceTarget(
    val slotId:UUID,
    val serviceOrderId:UUID,
    val organizationId:UUID,
    val documentKind:String,
)

interface ServicePurchaseDocumentEvidenceTargetPort {
    fun loadTarget(id:UUID):ServicePurchaseDocumentEvidenceTarget?
    fun canReserve(actor:UUID,target:ServicePurchaseDocumentEvidenceTarget):Boolean
    fun canAccess(actor:UUID,target:ServicePurchaseDocumentEvidenceTarget):Boolean
}
