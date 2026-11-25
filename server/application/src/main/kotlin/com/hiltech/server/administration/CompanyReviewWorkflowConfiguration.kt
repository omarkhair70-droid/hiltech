package com.hiltech.server.administration

import com.hiltech.server.approval.CompanyReviewPolicyKeys
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** The same source key is one explicitly shared workflow group, never two competing setups. */
@Component
class CompanyReviewWorkflowConfiguration @Autowired constructor(
    private val environment: Environment? = null,
    private val payroll: PayrollRunPolicyConfiguration = PayrollRunPolicyConfiguration("PAYROLL_RUN_REVIEW"),
    private val procurement: ProcurementReviewPolicyConfiguration = ProcurementReviewPolicyConfiguration("PROCUREMENT_PO_REVIEW"),
) {
    fun key(workflow: CompanyReviewWorkflow): String = when (workflow) {
        CompanyReviewWorkflow.PAYROLL_RUN -> payroll.key
        CompanyReviewWorkflow.PROCUREMENT_PO -> procurement.key
        else -> workflow.configurationProperty?.let { property ->
            CompanyReviewPolicyKeys.resolve(environment?.getProperty(property), property)
        } ?: workflow.policyKey
    }

    fun group(workflow: CompanyReviewWorkflow): List<CompanyReviewWorkflow> {
        val installedKey = key(workflow)
        return CompanyReviewWorkflow.entries.filter { key(it) == installedKey }.sortedBy { it.name }
    }

    fun authorityKey(workflow: CompanyReviewWorkflow): String {
        val group = group(workflow)
        // A fixed source contract keeps its own reviewer name when an installed
        // configurable workflow deliberately shares that source key.
        return (group.firstOrNull { it.policyKey == key(workflow) } ?: group.first()).authorityKey
    }
}

fun interface CompanyEmployeeReviewEligibilityPort {
    fun isEligible(actor: UUID, organizationId: UUID): Boolean
}

/** Being eligible for nomination grants no operating or approval authority. */
@Component
class CurrentCompanyEmployeeReviewEligibility(
    private val jdbc: JdbcTemplate, private val clock: Clock,
) : CompanyEmployeeReviewEligibilityPort {
    override fun isEligible(actor: UUID, organizationId: UUID): Boolean {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """SELECT EXISTS (
                SELECT 1 FROM user_identity u JOIN organization_membership m ON m.user_identity_id=u.id
                JOIN organization o ON o.id=m.organization_id
                JOIN employee e ON e.organization_id=o.id AND e.person_id=u.person_id
                WHERE u.id=? AND o.id=? AND u.status='ACTIVE' AND e.state='ACTIVE'
                  AND o.status='ACTIVE' AND o.organization_type='HILTECH'
                  AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?))""".trimIndent(),
            Boolean::class.java, actor, organizationId, now, now,
        ) == true
    }
}
