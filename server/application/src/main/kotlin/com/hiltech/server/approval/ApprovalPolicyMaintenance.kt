package com.hiltech.server.approval

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** A bounded policy version and source type, never an arbitrary collection of decisions. */
data class ApprovalPolicyMaintenanceScope(
    val organizationId: UUID,
    val policyId: UUID,
    val policyKey: String,
    val policyRevision: Int,
    val policyVersion: Long,
    val subjectType: String,
    val expectedPendingCount: Int,
)

data class ApprovalPolicyMaintenanceResult(
    val policyId: UUID,
    val supersededCount: Int,
    val replayed: Boolean,
)

/** Implemented by the closed company workflow catalog, separately from business approval authority. */
fun interface ApprovalPolicyMaintenanceAuthorityPort {
    fun canSupersedePending(actor: UUID, scope: ApprovalPolicyMaintenanceScope): Boolean
}

interface ApprovalPolicyMaintenancePersistencePort {
    fun lockPolicy(scope: ApprovalPolicyMaintenanceScope): Boolean
    fun pendingRequestIds(scope: ApprovalPolicyMaintenanceScope): List<UUID>
}

@Component
class JdbcApprovalPolicyMaintenancePersistence(private val jdbc: JdbcTemplate) : ApprovalPolicyMaintenancePersistencePort {
    override fun lockPolicy(scope: ApprovalPolicyMaintenanceScope): Boolean = jdbc.query(
        """SELECT id FROM approval_policy_version
           WHERE id=? AND organization_id=? AND policy_key=? AND version_number=? AND version=?
             AND lifecycle_state IN ('ACTIVE','RETIRED')
           FOR UPDATE""".trimIndent(),
        { rs, _ -> rs.getObject(1, UUID::class.java) }, scope.policyId, scope.organizationId,
        scope.policyKey, scope.policyRevision, scope.policyVersion,
    ).singleOrNull() == scope.policyId

    override fun pendingRequestIds(scope: ApprovalPolicyMaintenanceScope): List<UUID> = jdbc.query(
        """SELECT id FROM approval_request
           WHERE organization_id=? AND policy_key=? AND policy_version=? AND subject_type=? AND state='PENDING'
           ORDER BY id FOR UPDATE""".trimIndent(),
        { rs, _ -> rs.getObject(1, UUID::class.java) }, scope.organizationId, scope.policyKey,
        scope.policyRevision, scope.subjectType,
    )
}
