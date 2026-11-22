package com.hiltech.server.documents

import java.util.UUID

/**
 * Canonical Evidence can attach ONLY to a real immutable Finance expense
 * draft. No general-purpose file upload or finance-owned storage.
 */
data class FinanceExpenseReceiptEvidenceTarget(
    val expenseDraftId: UUID,
    val organizationId: UUID,
    val createdByUserId: UUID,
    val employeeId: UUID,
)

interface FinanceExpenseReceiptEvidenceTargetPort {
    fun loadTarget(expenseDraftId: UUID): FinanceExpenseReceiptEvidenceTarget?
    fun canReserve(actor: UUID, target: FinanceExpenseReceiptEvidenceTarget): Boolean
    fun canAccess(actor: UUID, target: FinanceExpenseReceiptEvidenceTarget): Boolean
}
