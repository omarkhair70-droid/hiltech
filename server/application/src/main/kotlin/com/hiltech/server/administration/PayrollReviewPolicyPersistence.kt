package com.hiltech.server.administration

import org.springframework.beans.factory.annotation.Autowired
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcPayrollReviewPolicyPersistence @Autowired constructor(private val jdbc: JdbcTemplate,
    private val runPolicy: PayrollRunPolicyConfiguration = PayrollRunPolicyConfiguration("PAYROLL_RUN_REVIEW")) : PayrollReviewPolicyPersistencePort {
    private fun policyKey(workflow: CompanyReviewWorkflow): String {
        val key = if (workflow == CompanyReviewWorkflow.PAYROLL_RUN) runPolicy.key else workflow.policyKey
        if (!Regex("^[A-Z][A-Z0-9_]{2,95}$").matches(key)) throw ProductApiException(
            "COMPANY_REVIEW_WORKFLOW_CONFIGURATION_INVALID", "The installed workflow configuration is invalid.", HttpStatus.CONFLICT)
        return key
    }
    override fun lockOrganization(organizationId: UUID) {
        if (jdbc.query("SELECT id FROM organization WHERE id = ? AND organization_type = 'HILTECH' AND status = 'ACTIVE' FOR UPDATE",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, organizationId).isEmpty()) {
            throw ProductApiException("PAYROLL_REVIEW_COMPANY_UNAVAILABLE", "The company is unavailable.", HttpStatus.NOT_FOUND)
        }
    }

    override fun lockEmployee(employeeId: UUID) {
        if (jdbc.query("SELECT id FROM employee WHERE id = ? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, employeeId).isEmpty()) {
            throw ProductApiException("PAYROLL_REVIEWER_CHANGED", "The employee is unavailable.", HttpStatus.CONFLICT)
        }
    }

    override fun current(organizationId: UUID, workflow: CompanyReviewWorkflow, at: Instant): PayrollReviewSetupRecord {
        val now = at.atOffset(ZoneOffset.UTC)
        val policy = jdbc.query(
            """SELECT id,version_number,version,authority_key,
                 (approval_required = true AND step_mode = 'SINGLE' AND authority_key = ? AND reason_required = true) AS contract_matches,
                 (effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)) AS policy_current
               FROM approval_policy_version WHERE organization_id = ? AND policy_key = ? AND lifecycle_state = 'ACTIVE'""".trimIndent(),
            { rs, _ -> PayrollReviewPolicyRecord(rs.getObject("id", UUID::class.java), rs.getInt("version_number"),
                rs.getLong("version"), rs.getBoolean("contract_matches"), rs.getBoolean("policy_current"), rs.getString("authority_key")) },
            workflow.authorityKey, now, now, organizationId, policyKey(workflow),
        ).singleOrNull()
        val reviewer = jdbc.query(
            """SELECT b.id,b.version,b.principal_type,COALESCE(b.principal_user_id,b.principal_team_id) AS principal_id,
                  COALESCE(p.display_name,t.name) AS display_name,e.id AS employee_id,
                  (b.effective_from <= ? AND (b.effective_to IS NULL OR b.effective_to > ?)
                   AND ((b.principal_type='USER' AND u.status='ACTIVE') OR
                        (b.principal_type='TEAM' AND t.active=true AND t.organization_id=b.organization_id))) AS binding_current
               FROM approval_authority_binding b
               LEFT JOIN user_identity u ON u.id=b.principal_user_id
               LEFT JOIN person p ON p.id=u.person_id
               LEFT JOIN employee e ON e.person_id=p.id AND e.organization_id=b.organization_id
               LEFT JOIN team t ON t.id=b.principal_team_id
               WHERE b.organization_id=? AND b.authority_key=? AND b.active=true""".trimIndent(),
            { rs, _ -> PayrollReviewerBindingRecord(rs.getObject("id", UUID::class.java), rs.getLong("version"),
                rs.getString("principal_type"), rs.getObject("principal_id", UUID::class.java), rs.getString("display_name"),
                rs.getObject("employee_id", UUID::class.java), rs.getBoolean("binding_current")) },
            now, now, organizationId, policy?.authorityKey ?: workflow.authorityKey,
        ).singleOrNull()
        val pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_request WHERE organization_id=? AND policy_key=? AND subject_type=? AND state='PENDING'",
            Int::class.java, organizationId, policyKey(workflow), workflow.subjectType,
        ) ?: 0
        return PayrollReviewSetupRecord(policy, reviewer, pending)
    }

    override fun candidates(organizationId: UUID, workflow: CompanyReviewWorkflow, query: String, at: Instant, limit: Int): List<PayrollReviewerCandidate> {
        val pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        return readCandidates(organizationId, workflow, "AND (p.display_name ILIKE ? ESCAPE '!' OR e.employee_code ILIKE ? ESCAPE '!')",
            arrayOf(pattern, pattern), at, limit)
    }

    override fun candidate(organizationId: UUID, workflow: CompanyReviewWorkflow, employeeId: UUID, identityId: UUID, at: Instant): PayrollReviewerCandidate? =
        readCandidates(organizationId, workflow, "AND e.id = ? AND u.id = ?", arrayOf(employeeId, identityId), at, 1).singleOrNull()

    private fun readCandidates(organizationId: UUID, workflow: CompanyReviewWorkflow, where: String, args: Array<out Any>, at: Instant, limit: Int): List<PayrollReviewerCandidate> {
        val now = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """SELECT e.id,e.version,e.employee_code,p.display_name,u.id AS identity_id
               FROM employee e JOIN person p ON p.id=e.person_id
               JOIN organization o ON o.id=e.organization_id
               JOIN LATERAL (
                 SELECT ui.id FROM user_identity ui
                 WHERE ui.person_id=p.id AND ui.status='ACTIVE' AND EXISTS (
                   SELECT 1 FROM organization_membership m WHERE m.organization_id=e.organization_id AND m.user_identity_id=ui.id
                     AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>=?))
                 ORDER BY ui.id LIMIT 1
               ) u ON true
               WHERE e.organization_id=? AND e.state='ACTIVE' AND o.status='ACTIVE' AND o.organization_type='HILTECH'
                 AND EXISTS (SELECT 1 FROM ${workflow.reviewerTable} a
                   WHERE a.organization_id=e.organization_id AND a.identity_id=u.id AND a.${workflow.reviewerKeyColumn}='REVIEW' AND a.active=true
                     AND a.${workflow.reviewerFromColumn}<=? AND (a.${workflow.reviewerToColumn} IS NULL OR a.${workflow.reviewerToColumn}>=?))
                 $where ORDER BY p.display_name,e.employee_code,e.id LIMIT ?""".trimIndent(),
            { rs, _ -> PayrollReviewerCandidate(rs.getObject("id", UUID::class.java), rs.getObject("identity_id", UUID::class.java),
                rs.getLong("version"), rs.getString("display_name"), rs.getString("employee_code")) },
            now, now, organizationId, now, now, *args, limit,
        )
    }

    override fun reviewerIdentities(organizationId: UUID, workflow: CompanyReviewWorkflow, reviewer: PayrollReviewerBindingRecord, at: Instant): List<UUID> {
        if (!reviewer.current) return emptyList()
        if (reviewer.principalType == "USER") {
            val employee = reviewer.employeeId ?: return emptyList()
            return listOfNotNull(candidate(organizationId, workflow, employee, reviewer.principalId, at)?.identityId)
        }
        if (reviewer.principalType != "TEAM") return emptyList()
        val now = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """SELECT DISTINCT m.user_identity_id FROM team_membership m JOIN team t ON t.id=m.team_id
               JOIN user_identity u ON u.id=m.user_identity_id JOIN employee e ON e.person_id=u.person_id AND e.organization_id=t.organization_id
               WHERE t.id=? AND t.organization_id=? AND t.active=true AND u.status='ACTIVE' AND e.state='ACTIVE'
                 AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>=?)
                 AND EXISTS (SELECT 1 FROM organization_membership om WHERE om.organization_id=t.organization_id
                   AND om.user_identity_id=m.user_identity_id AND om.state='ACTIVE' AND om.valid_from<=?
                   AND (om.valid_until IS NULL OR om.valid_until>=?))
                 AND EXISTS (SELECT 1 FROM ${workflow.reviewerTable} a WHERE a.organization_id=t.organization_id
                   AND a.identity_id=m.user_identity_id AND a.${workflow.reviewerKeyColumn}='REVIEW' AND a.active=true
                   AND a.${workflow.reviewerFromColumn}<=? AND (a.${workflow.reviewerToColumn} IS NULL OR a.${workflow.reviewerToColumn}>=?))""".trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) }, reviewer.principalId, organizationId, now, now, now, now, now, now,
        )
    }

    override fun replace(organizationId: UUID, workflow: CompanyReviewWorkflow, previous: PayrollReviewSetupRecord, reviewer: PayrollReviewerCandidate,
                         actor: UUID, operationId: UUID, at: Instant): PayrollReviewPolicyInstallation {
        val now = at.atOffset(ZoneOffset.UTC)
        previous.policy?.let { policy ->
            check(jdbc.update("""UPDATE approval_policy_version SET lifecycle_state='RETIRED',
                    effective_to=GREATEST(?,effective_from + interval '1 microsecond'),version=version+1
                  WHERE id=? AND organization_id=? AND policy_key=? AND version=? AND lifecycle_state='ACTIVE'""".trimIndent(),
                now, policy.id, organizationId, policyKey(workflow), policy.version) == 1)
        }
        previous.reviewer?.let { binding ->
            check(jdbc.update("""UPDATE approval_authority_binding SET active=false,
                    effective_to=GREATEST(?,effective_from + interval '1 microsecond'),version=version+1
                  WHERE id=? AND organization_id=? AND authority_key=? AND version=? AND active=true""".trimIndent(),
                now, binding.id, organizationId, workflow.authorityKey, binding.version) == 1)
        }
        val revision = (jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_number),0) FROM approval_policy_version WHERE organization_id=? AND policy_key=?",
            Int::class.java, organizationId, policyKey(workflow),
        ) ?: 0) + 1
        val policyId = UUID.nameUUIDFromBytes("company-review-policy:$operationId".toByteArray(StandardCharsets.UTF_8))
        val bindingId = UUID.nameUUIDFromBytes("company-reviewer:$operationId".toByteArray(StandardCharsets.UTF_8))
        jdbc.update("""INSERT INTO approval_policy_version
            (id,organization_id,policy_key,version_number,lifecycle_state,approval_required,step_mode,authority_key,
             reason_required,effective_from,created_by_user_id,created_at,version)
            VALUES (?,?,?,?,'ACTIVE',true,'SINGLE',?,true,?,?,?,1)""".trimIndent(),
            policyId, organizationId, policyKey(workflow), revision,
            workflow.authorityKey, now, actor, now)
        jdbc.update("""INSERT INTO approval_authority_binding
            (id,organization_id,authority_key,principal_type,principal_user_id,effective_from,active,created_by_user_id,created_at,version)
            VALUES (?,?,?,'USER',?,?,true,?,?,1)""".trimIndent(),
            bindingId, organizationId, workflow.authorityKey, reviewer.identityId, now, actor, now)
        return PayrollReviewPolicyInstallation(policyId, revision, bindingId)
    }
}
