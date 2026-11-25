package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.finance.FinancePayrollAuthorityPort
import com.hiltech.server.payroll.Payroll9aAuthorityPort
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPolicyMaintenanceScope
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.procurement.ProcurementCandidateReviewEligibilityPort
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import com.hiltech.server.service.ServiceDeliveryReviewEligibilityPort
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.util.UUID

@Component
class PayrollReviewPolicyService @Autowired constructor(
    private val authority: OrganizationAdministrationAuthorityPort,
    private val reviewers: CompanyReviewCandidateAuthorityPort,
    private val persistence: PayrollReviewPolicyPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val approvalEngine: ApprovalEngineService? = null,
    private val payrollPolicy: PayrollRunPolicyConfiguration = PayrollRunPolicyConfiguration("PAYROLL_RUN_REVIEW"),
    private val procurementPolicy: ProcurementReviewPolicyConfiguration = ProcurementReviewPolicyConfiguration("PROCUREMENT_PO_REVIEW"),
) {
    fun current(actor: UUID, organizationId: UUID, workflow: CompanyReviewWorkflow): PayrollReviewSetupResponse {
        requireAuthority(actor, organizationId)
        val now = clock.instant()
        val state = persistence.current(organizationId, workflow, now)
        val protectedAuthority = state.policy?.authorityKey?.let { it != workflow.authorityKey } == true
        val ready = state.policy?.let { it.current && it.contractMatches } == true && state.reviewer?.current == true &&
            persistence.reviewerIdentities(organizationId, workflow, requireNotNull(state.reviewer), now)
                .any { reviewers.canReview(workflow, it, organizationId) }
        return PayrollReviewSetupResponse(
            organizationId.toString(), when {
                protectedAuthority -> "PROTECTED_EXISTING_AUTHORITY"
                ready -> "READY"
                state.policy == null -> "NOT_CONFIGURED"
                state.policy.contractMatches && state.policy.current -> "REVIEWER_UNAVAILABLE"
                else -> "POLICY_REPAIR_REQUIRED"
            }, state.policy?.id?.toString(), state.policy?.revision, state.policy?.version,
            state.reviewer?.id?.toString(), state.reviewer?.version, state.reviewer?.principalType,
            state.reviewer?.displayName, state.reviewer?.employeeId?.toString(), state.pendingReviews, now.toString(), workflow.name, !protectedAuthority,
            state.pendingPolicies,
        )
    }

    fun candidates(actor: UUID, organizationId: UUID, workflow: CompanyReviewWorkflow, query: String, limit: Int = 50): PayrollReviewerCandidatesResponse {
        requireAuthority(actor, organizationId)
        if (query.length > 120 || limit !in 1..100) throw invalid("REVIEWER_SEARCH_INVALID", "The reviewer search is invalid.")
        val rows = persistence.candidates(organizationId, workflow, query.trim(), clock.instant(), limit + 1)
        val visible = rows.filter { reviewers.canReview(workflow, it.identityId, organizationId) }
        return PayrollReviewerCandidatesResponse(visible.take(limit).map { row ->
            PayrollReviewerCandidateResponse(row.employeeId.toString(), row.identityId.toString(), row.employeeVersion, row.displayName, row.employeeCode)
        }, rows.size > limit)
    }

    fun configure(command: ConfigurePayrollReviewPolicyCommand): ConfigurePayrollReviewPolicyResponse {
        requireAuthority(command.actorUserId, command.organizationId)
        if (command.baseEmployeeVersion < 1 ||
            (command.expectedPolicyId == null) != (command.expectedPolicyVersion == null) ||
            (command.expectedBindingId == null) != (command.expectedBindingVersion == null) ||
            command.expectedPolicyVersion?.let { it < 1 } == true || command.expectedBindingVersion?.let { it < 1 } == true) {
            throw invalid("PAYROLL_REVIEW_SETUP_INVALID_VERSION", "Current setup and employee versions are required.")
        }
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId", command.organizationId.toString())
            put("workflow", command.workflow.name)
            put("reviewerEmployeeId", command.reviewerEmployeeId.toString())
            put("reviewerIdentityId", command.expectedReviewerIdentityId.toString())
            put("employeeVersion", command.baseEmployeeVersion)
            put("policyId", command.expectedPolicyId?.toString())
            command.expectedPolicyVersion?.let { put("policyVersion", it) }
            put("bindingId", command.expectedBindingId?.toString())
            command.expectedBindingVersion?.let { put("bindingVersion", it) }
            put("pendingPoliciesToSupersede", buildJsonArray {
                command.pendingPoliciesToSupersede.sortedBy { it.policyId }.forEach { pending -> add(buildJsonObject {
                    put("policyId", pending.policyId); put("policyRevision", pending.policyRevision)
                    put("policyVersion", pending.policyVersion); put("pendingReviews", pending.pendingReviews)
                }) }
            })
            put("supersedeReason", command.supersedeReason?.trim())
        }.toString())
        val execution = commands.execute(IdempotentCommandSpec(
            command.operationId, command.actorUserId, "CONFIGURE_COMPANY_REVIEW_WORKFLOW", "ORGANIZATION",
            command.organizationId, fingerprint, command.correlationId,
        )) {
            persistence.lockOrganization(command.organizationId)
            persistence.lockEmployee(command.reviewerEmployeeId)
            requireAuthority(command.actorUserId, command.organizationId)
            val now = clock.instant()
            val previous = persistence.current(command.organizationId, command.workflow, now)
            if (previous.policy?.authorityKey?.let { it != command.workflow.authorityKey } == true) {
                throw conflict("REVIEW_WORKFLOW_PROTECTED_AUTHORITY", "The existing workflow uses separately governed approval authority and cannot be replaced by this bounded setup.")
            }
            if (previous.policy?.id != command.expectedPolicyId || previous.policy?.version != command.expectedPolicyVersion ||
                previous.reviewer?.id != command.expectedBindingId || previous.reviewer?.version != command.expectedBindingVersion) {
                throw conflict("PAYROLL_REVIEW_SETUP_CHANGED", "The review setup changed; refresh before configuring it.")
            }
            val reviewer = persistence.candidate(command.organizationId, command.workflow, command.reviewerEmployeeId, command.expectedReviewerIdentityId, now)
            if (reviewer == null || reviewer.employeeVersion != command.baseEmployeeVersion ||
                !reviewers.canReview(command.workflow, reviewer.identityId, command.organizationId)) {
                throw conflict("PAYROLL_REVIEWER_CHANGED", "The selected employee account or domain review responsibility is no longer current.")
            }
            val unchanged = previous.policy?.let { it.current && it.contractMatches } == true &&
                previous.reviewer?.let { it.current && it.principalType == "USER" && it.principalId == reviewer.identityId } == true
            if (!unchanged) {
                if (previous.pendingReviews > 0 && (previous.pendingPolicies.isEmpty() ||
                    previous.pendingPolicies.sumOf { it.pendingReviews } != previous.pendingReviews)) {
                    throw conflict("APPROVAL_POLICY_PENDING_CHANGED", "Refresh the exact pending policy versions before replacing the reviewer.")
                }
                if (command.pendingPoliciesToSupersede.sortedBy { it.policyId } != previous.pendingPolicies.sortedBy { it.policyId }) {
                    throw conflict("APPROVAL_POLICY_PENDING_CONFIRMATION_REQUIRED", "Review and confirm the exact pending policy versions affected by this change.")
                }
                if (previous.pendingPolicies.isNotEmpty()) {
                    val reason = command.supersedeReason?.trim().orEmpty()
                    if (reason.length !in 3..1000 || reason.any { it.code < 32 }) {
                        throw invalid("APPROVAL_POLICY_MAINTENANCE_REASON_REQUIRED", "A reason is required to end pending reviews before replacing the reviewer.")
                    }
                    val engine = approvalEngine ?: throw conflict("APPROVAL_POLICY_MAINTENANCE_UNAVAILABLE", "Review workflow maintenance is unavailable.")
                    val key = when (command.workflow) {
                        CompanyReviewWorkflow.PAYROLL_RUN -> payrollPolicy.key
                        CompanyReviewWorkflow.PROCUREMENT_PO -> procurementPolicy.key
                        else -> command.workflow.policyKey
                    }
                    previous.pendingPolicies.sortedBy { it.policyId }.forEach { pending ->
                        val policyId = runCatching { UUID.fromString(pending.policyId) }.getOrElse {
                            throw invalid("APPROVAL_POLICY_MAINTENANCE_INVALID", "A pending policy identifier is invalid.")
                        }
                        engine.supersedePolicyRequests(command.actorUserId,
                            UUID.nameUUIDFromBytes("company-review-maintenance:${command.operationId}:$policyId".toByteArray(Charsets.UTF_8)),
                            ApprovalPolicyMaintenanceScope(command.organizationId, policyId, key, pending.policyRevision,
                                pending.policyVersion, command.workflow.subjectType, pending.pendingReviews), reason, command.correlationId)
                    }
                }
            }
            val installed = if (unchanged) PayrollReviewPolicyInstallation(
                requireNotNull(previous.policy).id, previous.policy.revision, requireNotNull(previous.reviewer).id,
            ) else persistence.replace(command.organizationId, command.workflow, previous, reviewer, command.actorUserId, command.operationId, now)
            audit.append(AuditEventRecord(
                actorUserId = command.actorUserId, action = if (unchanged) "COMPANY_REVIEW_WORKFLOW_CONFIRMED" else "COMPANY_REVIEW_WORKFLOW_CONFIGURED",
                targetType = "APPROVAL_POLICY_VERSION",
                targetId = installed.policyId, previousStateRef = previous.policy?.revision?.toString(),
                newStateRef = installed.policyRevision.toString(),
                safeDiffJson = """{"fields":["workflow","policyRevision","reviewerEmployeeId","reviewerIdentityId","reviewerBindingId"]}""",
                occurredAt = now, correlationId = command.correlationId, reason = "COMPANY_REVIEW_ROUTE_CONFIGURED",
            ))
            IdempotentCommandOutcome("COMPANY_REVIEW_WORKFLOW_CONFIGURED", buildJsonObject {
                put("policyId", installed.policyId.toString())
                put("policyRevision", installed.policyRevision)
                put("bindingId", installed.bindingId.toString())
            }.toString())
        }
        val receipt = Json.parseToJsonElement(requireNotNull(execution.outcome.resultPayloadJson)).jsonObject
        val policyId = receipt.getValue("policyId").jsonPrimitive.content
        val bindingId = receipt.getValue("bindingId").jsonPrimitive.content
        val current = persistence.current(command.organizationId, command.workflow, clock.instant())
        return ConfigurePayrollReviewPolicyResponse(command.operationId.toString(), command.organizationId.toString(), policyId,
            receipt.getValue("policyRevision").jsonPrimitive.int, bindingId,
            current.policy?.id?.toString() == policyId && current.reviewer?.id?.toString() == bindingId,
            execution.replayed, command.workflow.name)
    }

    private fun requireAuthority(actor: UUID, organizationId: UUID) {
        if (!authority.canManageApprovalPolicies(actor, organizationId)) throw ProductApiException(
            "APPROVAL_POLICY_ADMINISTRATION_DENIED", "Current approval workflow setup authority is required.", HttpStatus.FORBIDDEN,
        )
    }
    private fun invalid(code: String, message: String) = ProductApiException(code, message, HttpStatus.BAD_REQUEST)
    private fun conflict(code: String, message: String) = ProductApiException(code, message, HttpStatus.CONFLICT)
}

@Component
class DomainCompanyReviewCandidateAuthority @Autowired constructor(
    private val payroll: Payroll9aAuthorityPort,
    private val finance: FinancePayrollAuthorityPort,
    private val procurement: ProcurementCandidateReviewEligibilityPort? = null,
    private val service: ServiceDeliveryReviewEligibilityPort? = null,
) : CompanyReviewCandidateAuthorityPort {
    override fun canReview(workflow: CompanyReviewWorkflow, actor: UUID, organizationId: UUID): Boolean = when (workflow) {
        CompanyReviewWorkflow.PAYROLL_SOURCE -> payroll.canReview(actor, organizationId)
        CompanyReviewWorkflow.PAYROLL_RUN -> payroll.canReview(actor, organizationId)
        CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT -> finance.canReview(actor, organizationId)
        CompanyReviewWorkflow.PROCUREMENT_PO -> procurement?.isEligible(actor, organizationId) == true
        CompanyReviewWorkflow.SERVICE_HANDOVER, CompanyReviewWorkflow.SERVICE_WARRANTY -> service?.isEligible(actor, organizationId) == true
    }
}
