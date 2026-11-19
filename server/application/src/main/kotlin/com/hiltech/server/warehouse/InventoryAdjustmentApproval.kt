package com.hiltech.server.warehouse

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalSubjectAuthoritySource
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.stereotype.Component
import java.time.Instant

const val INVENTORY_ADJUSTMENT_SUBJECT_TYPE = "INVENTORY_ADJUSTMENT_REQUEST"
const val INVENTORY_ADJUSTMENT_POLICY_KEY = "INVENTORY_ADJUSTMENT_STANDARD"
const val INVENTORY_ADJUSTMENT_AUTHORITY_KEY = "INVENTORY_ADJUSTMENT_APPROVER"
const val INVENTORY_ADJUSTMENT_REASON_CODE = "INVENTORY_ADJUSTMENT"

@Component
class InventoryAdjustmentApprovalSource(
    private val stocktakes: StocktakePersistence,
    private val warehouse: WarehousePersistencePort,
) : ApprovalSubjectAuthoritySource, ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == INVENTORY_ADJUSTMENT_SUBJECT_TYPE

    override fun resolve(
        subject: ApprovalSubjectRef,
        authorityKey: String,
        at: Instant,
    ): ApprovalAuthorityBindingSnapshot? {
        if (!supports(subject.subjectType) || authorityKey != INVENTORY_ADJUSTMENT_AUTHORITY_KEY) return null
        val request = stocktakes.adjustmentRequest(subject.subjectId)?.takeIf {
            it.organizationId == subject.organizationId &&
                it.version == subject.subjectVersion &&
                it.appliedMovementId == null
        } ?: return null
        val session = stocktakes.session(request.sessionId)?.takeIf {
            it.organizationId == subject.organizationId
        } ?: return null
        val binding = warehouse.currentWarehouseAuthorityBindings(session.warehouseId, at).filter {
            it.current &&
                it.organizationId == subject.organizationId &&
                it.authorityKey == WarehouseAuthorityKey.INVENTORY_ADJUSTMENT_APPROVER
        }.singleOrNull() ?: return null
        return ApprovalAuthorityBindingSnapshot(
            id = binding.bindingId,
            organizationId = binding.organizationId,
            authorityKey = INVENTORY_ADJUSTMENT_AUTHORITY_KEY,
            principalType = if (binding.principalType == InventoryPrincipalType.USER) {
                ApprovalPrincipalType.USER
            } else {
                ApprovalPrincipalType.TEAM
            },
            principalId = binding.principalId,
            effectiveFrom = binding.effectiveFrom,
            effectiveTo = binding.effectiveTo,
            version = binding.version,
        )
    }

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean {
        if (!supports(subject.subjectType)) return false
        val request = stocktakes.adjustmentRequest(subject.subjectId)?.takeIf {
            it.organizationId == subject.organizationId &&
                it.version == subject.subjectVersion &&
                it.appliedMovementId == null
        } ?: return false
        return stocktakes.discrepancy(request.discrepancyId)?.state == "ADJUSTMENT_REQUESTED"
    }
}
