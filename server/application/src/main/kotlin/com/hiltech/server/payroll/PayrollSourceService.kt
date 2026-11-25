package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

@Component
class PayrollSourceService(
    private val jdbc: JdbcTemplate,
    private val periods: Payroll9aService,
    private val authority: Payroll9aAuthorityPort,
    private val repository: PayrollSourceRepository,
    private val guard: PayrollSourceApprovalVersionSource,
    private val approvals: ApprovalEngineService,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val approvedInputs: ObjectProvider<PayrollApprovedInputSourcePort>? = null,
    private val environment: Environment = StandardEnvironment(),
    private val runPolicy: PayrollRunPolicyConfiguration = PayrollRunPolicyConfiguration(""),
    private val financeIntake: ObjectProvider<PayrollFinanceReviewIntakePort>? = null,
) {
    fun capabilities(actor: UUID, organizationId: UUID): PayrollCapabilitiesDto {
        if (!authority.canRead(actor, organizationId)) denied()
        return PayrollCapabilitiesDto(
            canPrepare = authority.canPrepare(actor, organizationId), canReview = authority.canReview(actor, organizationId),
            periodsAvailable = available("periods-enabled"), runsAvailable = available("draft-runs-enabled"),
            runApprovalAvailable = available("approval-request-enabled"), releaseAvailable = available("release-enabled"),
            financeHandoffAvailable = available("finance-handoff-enabled") && financeIntake?.ifAvailable != null,
            financeStatusAvailable = available("finance-results-enabled"),
            runReviewConfigured = repository.runReviewPolicyReady(organizationId, runPolicy.key),
        )
    }

    private fun available(name: String): Boolean = environment.getProperty("hiltech.payroll.$name", Boolean::class.java, true) == true

    fun list(actor: UUID, periodId: UUID): PayrollSourcesDto {
        val period = periods.detail(actor, periodId)
        val organizationId = UUID.fromString(period.period.organizationId)
        val records = repository.current(organizationId, periodId).map { it.record }
        val blockers = buildList {
            if (period.period.state != "INPUTS_SNAPSHOTTED" || period.employees.isEmpty()) add("PAYROLL_INPUT_SNAPSHOT_REQUIRED")
            if (period.employees.any { it.sourceChanged }) add("PAYROLL_SOURCE_CHANGED_AFTER_SNAPSHOT")
            if (records.none { it.domain == "POLICY" && it.approvalState == "APPROVED" }) add("PAYROLL_POLICY_NOT_VERIFIED")
            for (domain in listOf("COMPENSATION", "APPROVED_TIME", "FINANCE")) {
                if (period.employees.any { employee ->
                    records.none { it.domain == domain && it.employeeId == employee.employeeId && it.approvalState == "APPROVED" }
                }) add("PAYROLL_${domain}_COVERAGE_REQUIRED")
            }
            if (period.employees.any { it.employeeState != "ACTIVE" }) add("PAYROLL_FINAL_PAY_SCOPE_REQUIRED")
            val policy = records.singleOrNull { it.domain == "POLICY" && it.approvalState == "APPROVED" }
            if (policy != null && records.any { it.domain != "POLICY" && it.currency != policy.currency }) add("PAYROLL_SOURCE_CURRENCY_MISMATCH")
            if (policy != null && policy.deductionPolicyReference == null && records.any { r -> r.components.any { it.kind == "DEDUCTION" } }) {
                add("PAYROLL_DEDUCTION_POLICY_REQUIRED")
            }
        }
        val verifiedBlockers = blockers.toMutableList()
        val provider = approvedInputs?.ifAvailable
        if (verifiedBlockers.isEmpty() && provider != null) {
            val policy = provider.policyFor(organizationId, periodId)
            if (policy == null) verifiedBlockers += "PAYROLL_POLICY_NOT_VERIFIED"
            else for (employee in period.employees) {
                try {
                    val id = UUID.fromString(employee.employeeId)
                    val inputs = provider.verifiedFor(organizationId, periodId, id)
                    Payroll9bCalculator.calculate(organizationId, periodId, id, policy, inputs.components,
                        inputs.coverageReceipts, employee.employeeState, inputs.separation)
                } catch (failure: ProductApiException) {
                    verifiedBlockers += failure.code
                }
            }
        }
        return PayrollSourcesDto(periodId.toString(), records, repository.reviewPolicyReady(organizationId), verifiedBlockers.isEmpty(), verifiedBlockers.distinct())
    }

    @Transactional
    fun create(actor: UUID, periodId: UUID, request: PayrollSourceRequestDto, correlationId: String): PayrollSourceCommandDto {
        PayrollSourcePolicy.validate(request)
        val period = periods.detail(actor, periodId)
        val organizationId = UUID.fromString(period.period.organizationId)
        if (!authority.canPrepare(actor, organizationId)) denied()
        jdbc.queryForObject("SELECT id FROM payroll_period WHERE id = ? AND organization_id = ? FOR UPDATE", UUID::class.java, periodId, organizationId)
            ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        val operation = PayrollSourcePolicy.uuid(request.operationId)
        val fingerprint = fingerprint(periodId, request)
        repository.byOperation(operation)?.let { existing ->
            if (existing.organizationId != organizationId || existing.requestFingerprint != fingerprint ||
                existing.record.preparedByIdentityId != actor.toString()
            ) Payroll9aPolicy.fail("PAYROLL_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT)
            return PayrollSourceCommandDto(existing.record, true)
        }
        val fresh = periods.detail(actor, periodId)
        if (fresh.period.state != "INPUTS_SNAPSHOTTED" || fresh.employees.isEmpty()) {
            Payroll9aPolicy.fail("PAYROLL_INPUT_SNAPSHOT_REQUIRED", HttpStatus.CONFLICT)
        }
        val employee = request.employeeId?.let(PayrollSourcePolicy::uuid)
        if (employee != null) {
            val captured = fresh.employees.singleOrNull { UUID.fromString(it.employeeId) == employee }
                ?: Payroll9aPolicy.fail("PAYROLL_SOURCE_EMPLOYEE_NOT_CAPTURED", HttpStatus.CONFLICT)
            if (captured.sourceChanged) Payroll9aPolicy.fail("PAYROLL_SOURCE_CHANGED_AFTER_SNAPSHOT", HttpStatus.CONFLICT)
        }
        val prior = repository.latest(organizationId, periodId, request.domain, employee)
        if (prior?.record?.recordId != request.replacesRecordId?.let { PayrollSourcePolicy.uuid(it).toString() }) {
            Payroll9aPolicy.fail("PAYROLL_SOURCE_REVISION_CHANGED", HttpStatus.CONFLICT)
        }
        val id = UUID.nameUUIDFromBytes("hiltech:payroll-source:$operation".toByteArray(Charsets.UTF_8))
        val revision = (prior?.record?.revision ?: 0) + 1
        jdbc.update(
            """
            INSERT INTO payroll_source_record (
                id, operation_id, organization_id, payroll_period_id, employee_id, source_domain,
                revision, replaces_record_id, request_fingerprint, source_reference, currency, no_inputs,
                fractional_digits, rounding_mode, company_authorization_reference, statutory_policy_reference,
                deduction_policy_reference, prepared_by_user_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, operation, organizationId, periodId, employee, request.domain, revision,
            request.replacesRecordId?.let(PayrollSourcePolicy::uuid), fingerprint, request.sourceReference,
            request.currency, request.noInputs, request.fractionalDigits, request.roundingMode,
            request.companyAuthorizationReference, request.statutoryPolicyReference, request.deductionPolicyReference,
            actor, clock.instant().atOffset(ZoneOffset.UTC),
        )
        request.components.sortedBy { it.code }.forEach { line ->
            jdbc.update(
                "INSERT INTO payroll_source_component (source_record_id,component_code,component_kind,amount,deduction_plan_reference,description) VALUES (?,?,?,?,?,?)",
                id, line.code, line.kind, line.amount.toBigDecimal(), line.deductionPlanReference, line.description,
            )
        }
        audit.append(AuditEventRecord(actorUserId = actor, action = "PAYROLL_SOURCE_RECORDED",
            targetType = PayrollSourcePolicy.SUBJECT_TYPE, targetId = id, previousStateRef = prior?.record?.recordId,
            newStateRef = "DRAFT", occurredAt = clock.instant(), correlationId = correlationId))
        return PayrollSourceCommandDto(requireNotNull(repository.find(id)).record, false)
    }

    @Transactional
    fun requestApproval(actor: UUID, recordId: UUID, operationId: UUID, correlationId: String): PayrollSourceCommandDto {
        val source = sourceFor(actor, recordId)
        if (!authority.canPrepare(actor, source.organizationId) || source.record.preparedByIdentityId != actor.toString()) denied()
        lockPeriod(source)
        val subject = subject(source)
        if (!guard.isCurrent(subject)) Payroll9aPolicy.fail("PAYROLL_SOURCE_REVISION_CHANGED", HttpStatus.CONFLICT)
        if (!repository.reviewPolicyReady(source.organizationId)) Payroll9aPolicy.fail("PAYROLL_SOURCE_REVIEW_NOT_CONFIGURED", HttpStatus.CONFLICT)
        val result = approvals.requestException(actor, operationId, subject, PayrollSourcePolicy.REVIEW_POLICY,
            "PAYROLL_INPUT_REVIEW", null, correlationId, allowAuthorityBypass = false)
        if (!result.approvalRequired) Payroll9aPolicy.fail("PAYROLL_INDEPENDENT_SOURCE_REVIEW_REQUIRED", HttpStatus.CONFLICT)
        return PayrollSourceCommandDto(requireNotNull(repository.find(recordId)).record, result.replayed)
    }

    @Transactional
    fun decide(actor: UUID, recordId: UUID, request: PayrollSourceDecisionRequestDto, correlationId: String): PayrollSourceCommandDto {
        val source = sourceFor(actor, recordId)
        if (!authority.canReview(actor, source.organizationId) || source.record.preparedByIdentityId == actor.toString()) denied()
        lockPeriod(source)
        if (source.record.approvalState == "POLICY_REVIEW_REQUIRED") {
            Payroll9aPolicy.fail("PAYROLL_SOURCE_REVISION_CHANGED", HttpStatus.CONFLICT)
        }
        if (request.decision !in setOf("APPROVE", "REJECT", "REQUEST_CHANGE")) {
            Payroll9aPolicy.fail("PAYROLL_SOURCE_DECISION_INVALID", HttpStatus.BAD_REQUEST)
        }
        val approvalId = source.record.approvalRequestId?.let(UUID::fromString)
            ?: Payroll9aPolicy.fail("PAYROLL_SOURCE_REVIEW_NOT_REQUESTED", HttpStatus.CONFLICT)
        val result = approvals.decide(actorUserId = actor, operationId = PayrollSourcePolicy.uuid(request.operationId),
            approvalRequestId = approvalId, decision = ApprovalDecisionType.valueOf(request.decision),
            comment = request.comment, correlationId = correlationId)
        return PayrollSourceCommandDto(requireNotNull(repository.find(recordId)).record, result.replayed)
    }

    private fun sourceFor(actor: UUID, recordId: UUID): StoredPayrollSource {
        val row = repository.find(recordId) ?: Payroll9aPolicy.fail("PAYROLL_SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authority.canRead(actor, row.organizationId)) denied()
        return row
    }

    private fun subject(source: StoredPayrollSource) = ApprovalSubjectRef(source.organizationId,
        PayrollSourcePolicy.SUBJECT_TYPE, UUID.fromString(source.record.recordId), 1)

    private fun lockPeriod(source: StoredPayrollSource) {
        jdbc.queryForObject("SELECT id FROM payroll_period WHERE id = ? AND organization_id = ? FOR UPDATE",
            UUID::class.java, UUID.fromString(source.record.periodId), source.organizationId)
    }

    private fun denied(): Nothing = Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)

    internal fun fingerprint(periodId: UUID, request: PayrollSourceRequestDto): String {
        val canonical = listOf(periodId, request.domain, request.employeeId?.let(PayrollSourcePolicy::uuid),
            request.replacesRecordId?.let(PayrollSourcePolicy::uuid), request.sourceReference, request.currency,
            request.noInputs, request.fractionalDigits, request.roundingMode, request.companyAuthorizationReference,
            request.statutoryPolicyReference, request.deductionPolicyReference).joinToString("|") +
            request.components.sortedBy { it.code }.joinToString("") {
                "\n${it.code}|${it.kind}|${it.amount.toBigDecimal().stripTrailingZeros().toPlainString()}|${it.deductionPlanReference}|${it.description}"
            }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
