package com.hiltech.server.approval

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class ApprovalPolicyProvisioner(private val jdbc: JdbcTemplate) {
    fun ensureSingleApprovalPolicy(
        organizationId: UUID,
        policyKey: String,
        authorityKey: String,
        createdBy: UUID,
        at: Instant,
    ): ApprovalPolicySnapshot {
        val existing = jdbc.query(
            """SELECT organization_id, policy_key, version_number, approval_required, step_mode,
                      authority_key, reason_required
               FROM approval_policy_version
               WHERE organization_id = ? AND policy_key = ? AND lifecycle_state = 'ACTIVE'""".trimIndent(),
            { rs, _ ->
                ApprovalPolicySnapshot(
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getString("policy_key"),
                    rs.getInt("version_number"),
                    rs.getBoolean("approval_required"),
                    ApprovalPolicyMode.valueOf(rs.getString("step_mode")),
                    rs.getString("authority_key"),
                    rs.getBoolean("reason_required"),
                )
            },
            organizationId, policyKey,
        ).singleOrNull()
        if (existing != null) {
            require(
                existing.approvalRequired &&
                    existing.mode == ApprovalPolicyMode.SINGLE &&
                    existing.authorityKey == authorityKey &&
                    existing.reasonRequired
            ) { "Active approval policy does not match the Inventory Adjustment contract." }
            return existing
        }
        val version = (jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_number), 0) FROM approval_policy_version WHERE organization_id = ? AND policy_key = ?",
            Int::class.java, organizationId, policyKey,
        ) ?: 0) + 1
        jdbc.update(
            """INSERT INTO approval_policy_version (
                id, organization_id, policy_key, version_number, lifecycle_state,
                approval_required, step_mode, authority_key, reason_required,
                effective_from, effective_to, created_by_user_id, created_at, version
               ) VALUES (?, ?, ?, ?, 'ACTIVE', true, 'SINGLE', ?, true, ?, NULL, ?, ?, 1)""".trimIndent(),
            UUID.randomUUID(), organizationId, policyKey, version, authorityKey,
            at.atOffset(ZoneOffset.UTC), createdBy, at.atOffset(ZoneOffset.UTC),
        )
        return ApprovalPolicySnapshot(
            organizationId, policyKey, version, true, ApprovalPolicyMode.SINGLE, authorityKey, true,
        )
    }
}
