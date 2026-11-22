package com.hiltech.server.documents

import java.util.UUID

/** An immutable Invoice Evidence target bound to the real PO and supplier. */
data class SupplierInvoiceDocumentEvidenceTarget(
    val slotId: UUID,
    val purchaseOrderId: UUID,
    val purchasingOrganizationId: UUID,
    val supplierOrganizationId: UUID,
)

interface SupplierInvoiceDocumentEvidenceTargetPort {
    fun loadTarget(slotId: UUID): SupplierInvoiceDocumentEvidenceTarget?
    fun canReserve(actor: UUID, target: SupplierInvoiceDocumentEvidenceTarget): Boolean
    fun canAccess(actor: UUID, target: SupplierInvoiceDocumentEvidenceTarget): Boolean
}
