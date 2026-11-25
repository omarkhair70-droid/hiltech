package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalReadService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class PayrollRunReviewDto(
    val runVersionId: String,
    val approvalRequestId: String?,
    val approvalState: String,
    val policyVersion: Int?,
    val sourceCurrent: Boolean,
    val canRequestReview: Boolean,
    val canDecide: Boolean,
    val canRelease: Boolean,
)

data class PayrollRunDecisionRequestDto(
    val operationId: String,
    val approvalRequestId: String,
    val decision: String,
    val comment: String? = null,
)

data class PayrollRunDecisionResultDto(
    val runVersionId: String,
    val approvalRequestId: String,
    val approvalState: String,
    val replayed: Boolean,
)

private data class PayrollRunReviewRecord(
    val preparedBy: UUID,
    val requestId: UUID?,
    val requester: UUID?,
    val state: String?,
    val policyVersion: Int?,
    val released: Boolean,
)

/** The exact payroll calculation is reviewed within its own protected workspace. */
@Component
class PayrollRunReviewService(
    private val jdbc: JdbcTemplate,
    private val drafts: Payroll9bDraftService,
    private val authority: Payroll9aAuthorityPort,
    private val guard: PayrollRunApprovalVersionSource,
    private val approvals: ApprovalEngineService,
    private val approvalReads: ApprovalReadService,
    private val policies: ApprovalPersistencePort,
    private val releaseDecision: PayrollRunReleaseDecisionPort,
    private val configuration: PayrollRunPolicyConfiguration,
    private val clock: Clock,
) {
    fun read(actor: UUID, runId: UUID): PayrollRunReviewDto {
        val run = drafts.read(actor, runId)
        val org = UUID.fromString(run.organizationId)
        val row = record(org, runId)
        val subject = ApprovalSubjectRef(org, "PAYROLL_RUN_VERSION", runId, 1)
        val policy = policies.loadActivePolicy(org, configuration.key, clock.instant())
        val current = !row.released && guard.isCurrent(subject)
        val reviewResponsibility = authority.canReview(actor, org)
        val routine = policy != null && !policy.approvalRequired && policy.mode == ApprovalPolicyMode.NO_APPROVAL_REQUIRED
        val pinned = policy != null && policy.approvalRequired && policy.mode == ApprovalPolicyMode.SINGLE &&
            policy.versionNumber == row.policyVersion
        val independent = actor != row.preparedBy && actor != row.requester
        val canDecide = current && pinned && reviewResponsibility && independent && row.state == "PENDING" &&
            assigned(actor, requireNotNull(row.requestId), subject, requireNotNull(row.policyVersion))
        val canRelease = current && reviewResponsibility && (routine || (pinned && row.state == "APPROVED")) &&
            releaseDecision.resolve(actor, subject, configuration.key) != null
        val state = when {
            row.released -> "RELEASED"
            !current -> "SOURCE_CHANGED"
            policy == null -> "POLICY_NOT_CONFIGURED"
            routine -> "NO_APPROVAL_REQUIRED"
            row.requestId != null && !pinned -> "POLICY_REVIEW_REQUIRED"
            else -> row.state ?: "NOT_REQUESTED"
        }
        return PayrollRunReviewDto(runId.toString(), row.requestId?.toString(), state, row.policyVersion ?: policy?.versionNumber,
            current, current && policy != null && !routine && row.requestId == null &&
                (authority.canPrepare(actor, org) || reviewResponsibility), canDecide, canRelease)
    }

    @Transactional
    fun decide(actor: UUID, runId: UUID, request: PayrollRunDecisionRequestDto, correlationId: String): PayrollRunDecisionResultDto {
        val operation = PayrollSourcePolicy.uuid(request.operationId)
        val requestedApproval = PayrollSourcePolicy.uuid(request.approvalRequestId)
        if (request.decision !in setOf("APPROVE", "REJECT", "REQUEST_CHANGE") || (request.comment?.length ?: 0) > 1000) {
            Payroll9aPolicy.fail("PAYROLL_REVIEW_DECISION_INVALID", HttpStatus.BAD_REQUEST)
        }
        if (request.decision != "APPROVE" && request.comment.isNullOrBlank()) {
            Payroll9aPolicy.fail("APPROVAL_REASON_REQUIRED", HttpStatus.UNPROCESSABLE_ENTITY)
        }
        val run = drafts.read(actor, runId)
        val org = UUID.fromString(run.organizationId)
        if (!authority.canReview(actor, org)) Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)
        jdbc.queryForObject("SELECT id FROM payroll_period WHERE id = ? AND organization_id = ? FOR UPDATE",
            UUID::class.java, UUID.fromString(run.payrollPeriodId), org)
        val row = record(org, runId)
        if (row.requestId != requestedApproval) Payroll9aPolicy.fail("PAYROLL_REVIEW_REQUEST_CHANGED", HttpStatus.CONFLICT)
        if (actor == row.preparedBy || actor == row.requester) {
            Payroll9aPolicy.fail("PAYROLL_INDEPENDENT_RUN_REVIEW_REQUIRED", HttpStatus.FORBIDDEN)
        }
        // A committed decision may be replayed under its original operation
        // after source/policy changes. New decisions still require the exact
        // currently assigned reviewer and current pinned source/policy.
        if (row.state == "PENDING" && !read(actor, runId).canDecide) {
            Payroll9aPolicy.fail("PAYROLL_REVIEW_NOT_AVAILABLE", HttpStatus.CONFLICT)
        }
        val result = approvals.decide(actor, operation, requestedApproval, ApprovalDecisionType.valueOf(request.decision),
            request.comment?.trim()?.takeIf(String::isNotBlank), correlationId)
        return PayrollRunDecisionResultDto(runId.toString(), requestedApproval.toString(), result.state.name, result.replayed)
    }

    private fun assigned(actor: UUID, requestId: UUID, subject: ApprovalSubjectRef, policyVersion: Int): Boolean = try {
        val response = approvalReads.one(actor, requestId)
        response.approvalRequestId == requestId.toString() && response.subjectType == subject.subjectType &&
            response.subjectId == subject.subjectId.toString() && response.subjectVersion == subject.subjectVersion &&
            response.policyKey == configuration.key && response.policyVersion == policyVersion && response.state == "PENDING"
    } catch (failure: ProductApiException) {
        if (failure.code == "OBJECT_NOT_VISIBLE") false else throw failure
    }

    private fun record(org: UUID, runId: UUID): PayrollRunReviewRecord = jdbc.query(
        """
        SELECT r.created_by_user_id, a.id AS request_id, a.requester_user_id, a.state AS approval_state, a.policy_version,
               EXISTS(SELECT 1 FROM payroll_run_release rel WHERE rel.run_version_id = r.id AND rel.organization_id = r.organization_id) AS released
        FROM payroll_run_version r
        LEFT JOIN approval_request a ON a.organization_id = r.organization_id AND a.subject_type = 'PAYROLL_RUN_VERSION'
          AND a.subject_id = r.id AND a.subject_version = 1 AND a.policy_key = ?
        WHERE r.organization_id = ? AND r.id = ?
        """.trimIndent(), { rs, _ -> PayrollRunReviewRecord(rs.getObject("created_by_user_id", UUID::class.java),
            rs.getObject("request_id", UUID::class.java), rs.getObject("requester_user_id", UUID::class.java),
            rs.getString("approval_state"), rs.getObject("policy_version", Integer::class.java)?.toInt(), rs.getBoolean("released")) },
        configuration.key, org, runId,
    ).singleOrNull() ?: Payroll9aPolicy.fail("PAYROLL_RUN_NOT_FOUND", HttpStatus.NOT_FOUND)
}
