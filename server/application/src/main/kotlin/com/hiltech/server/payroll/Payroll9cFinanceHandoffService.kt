package com.hiltech.server.payroll

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Review intake only. Finance owns accounts, payment instructions, approval
 * of exact beneficiaries, execution, bank status, recovery and reconciliation.
 * This is deliberately not a bank provider or payment instruction writer.
 */
data class PayrollFinanceReviewLine(
    val employeeId: UUID,
    val net: BigDecimal,
    val currency: String,
    val requiresTransfer: Boolean,
)

data class PayrollFinanceReviewManifest(
    val organizationId: UUID,
    val payrollPeriodId: UUID,
    val runVersionId: UUID,
    val releaseId: UUID,
    val operationId: UUID,
    val requestedByUserId: UUID,
    val runSourceFingerprint: String,
    val policyKey: String,
    val policyVersion: Int,
    val currency: String,
    val payableTotal: BigDecimal,
    val positiveTransferCount: Int,
    val lines: List<PayrollFinanceReviewLine>,
    val manifestSha256: String,
)

/**
 * Must be implemented by the canonical Finance module in the same database
 * transaction. It MUST guarantee a unique (organization, payroll run) review
 * batch, replay an identical operationId, reject conflicting keys and never
 * submit a bank transfer. No implementation is installed in Phase 9.
 */
interface PayrollFinanceReviewIntakePort {
    fun acceptForReview(manifest: PayrollFinanceReviewManifest): PayrollFinanceReviewReceipt
}

data class PayrollFinanceReviewReceipt(
    val organizationId: UUID,
    val runVersionId: UUID,
    val releaseId: UUID,
    val operationId: UUID,
    val financeReviewBatchId: UUID,
    val manifestSha256: String,
    val currency: String,
    val payableTotal: BigDecimal,
    val lineCount: Int,
    val positiveTransferCount: Int,
    val state: String,
    val bankExecutionAvailable: Boolean = false,
    val replayed: Boolean,
)

data class PayrollFinanceHandoffDto(
    val financeReviewBatchId: String,
    val runVersionId: String,
    val state: String,
    val manifestSha256: String,
    val currency: String,
    val payableTotal: String,
    val employeeCount: Int,
    val transferCandidates: Int,
    val bankExecutionAvailable: Boolean = false,
    val replayed: Boolean,
)

/** Shared, deterministic validation independent of Finance's storage layout. */
object PayrollFinanceReviewManifestPolicy {
    fun fromRun(
        run: Payroll9bRunDto,
        releaseId: UUID,
        policyKey: String,
        policyVersion: Int,
        operationId: UUID,
        actorId: UUID,
    ): PayrollFinanceReviewManifest {
        val organizationId = UUID.fromString(run.organizationId)
        val periodId = UUID.fromString(run.payrollPeriodId)
        val runId = UUID.fromString(run.runVersionId)
        val currency = run.currency
        if (run.state != "DRAFT" ||
            !Regex("^[0-9a-f]{64}$").matches(run.sourceFingerprint) ||
            !Regex("^[A-Z]{3}$").matches(currency) ||
            !Regex("^[A-Z][A-Z0-9_]{2,95}$").matches(policyKey) ||
            policyVersion < 1 ||
            run.lines.isEmpty() ||
            run.lines.size > 10000 ||
            run.lines.map { it.employeeId }.toSet().size != run.lines.size ||
            run.lines.map { it.snapshotId }.toSet().size != run.lines.size
        ) Payroll9aPolicy.fail("PAYROLL_FINANCE_MANIFEST_INVALID", HttpStatus.CONFLICT)
        val lines = run.lines.map { line ->
            val net = runCatching { BigDecimal(line.net) }.getOrElse {
                Payroll9aPolicy.fail("PAYROLL_FINANCE_NET_INVALID", HttpStatus.CONFLICT)
            }
            if (net.signum() < 0 || line.currency != currency ||
                net.precision() - net.scale() > 14
            ) Payroll9aPolicy.fail("PAYROLL_FINANCE_NET_INVALID", HttpStatus.CONFLICT)
            PayrollFinanceReviewLine(
                employeeId = runCatching { UUID.fromString(line.employeeId) }.getOrElse {
                    Payroll9aPolicy.fail("PAYROLL_FINANCE_EMPLOYEE_INVALID", HttpStatus.CONFLICT)
                },
                net = net,
                currency = currency,
                requiresTransfer = net.signum() > 0,
            )
        }.sortedBy { it.employeeId.toString() }
        val total = lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }
        val frozen = runCatching { BigDecimal(run.net) }.getOrElse {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_NET_INVALID", HttpStatus.CONFLICT)
        }
        val transfers = lines.count { it.requiresTransfer }
        if (transfers == 0 || frozen.compareTo(total) != 0) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_MANIFEST_TOTAL_MISMATCH", HttpStatus.CONFLICT)
        }
        val canonical = buildString {
            append("HILTECH_PAYROLL_FINANCE_REVIEW_V1").append('|')
            append(organizationId).append('|').append(periodId).append('|')
            append(runId).append('|').append(releaseId).append('|')
            append(operationId).append('|').append(actorId).append('|')
            append(run.sourceFingerprint).append('|').append(policyKey).append('|')
            append(policyVersion).append('|').append(currency).append('|')
            append(total.stripTrailingZeros().toPlainString())
            lines.forEach {
                append('\n').append(it.employeeId).append('|')
                append(it.net.stripTrailingZeros().toPlainString()).append('|')
                append(it.currency).append('|').append(it.requiresTransfer)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PayrollFinanceReviewManifest(
            organizationId, periodId, runId, releaseId, operationId, actorId,
            run.sourceFingerprint, policyKey, policyVersion, currency, total,
            transfers, lines, digest,
        )
    }

    fun validateReceipt(
        manifest: PayrollFinanceReviewManifest,
        receipt: PayrollFinanceReviewReceipt,
    ) {
        if (receipt.organizationId != manifest.organizationId ||
            receipt.runVersionId != manifest.runVersionId ||
            receipt.releaseId != manifest.releaseId ||
            receipt.operationId != manifest.operationId ||
            receipt.manifestSha256 != manifest.manifestSha256 ||
            receipt.currency != manifest.currency ||
            receipt.payableTotal.compareTo(manifest.payableTotal) != 0 ||
            receipt.lineCount != manifest.lines.size ||
            receipt.positiveTransferCount != manifest.positiveTransferCount ||
            receipt.financeReviewBatchId == UUID(0L, 0L) ||
            receipt.state != "REVIEW_REQUIRED" ||
            receipt.bankExecutionAvailable
        ) Payroll9aPolicy.fail("PAYROLL_FINANCE_RECEIPT_INVALID", HttpStatus.CONFLICT)
    }
}

private data class PayrollReleaseForHandoff(
    val id: UUID,
    val policyKey: String,
    val policyVersion: Int,
)

/**
 * The endpoint coordinates the company's approved source with Finance review.
 * Feature OFF by default; missing Finance intake or policy fails closed.
 * @Transactional is intentional: a future Finance adapter must use the
 * same JDBC transaction, never a non-idempotent external bank call.
 */
@Component
class Payroll9cFinanceHandoffService(
    private val jdbc: JdbcTemplate,
    private val drafts: Payroll9bDraftService,
    private val authority: Payroll9aAuthorityPort,
    private val publication: ObjectProvider<PayrollRunPublicationPort>,
    private val intake: ObjectProvider<PayrollFinanceReviewIntakePort>,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional
    fun handoff(
        actorId: UUID,
        runVersionId: UUID,
        operationId: UUID,
        correlationId: String,
    ): PayrollFinanceHandoffDto {
        val run = drafts.read(actorId, runVersionId)
        val organizationId = UUID.fromString(run.organizationId)
        if (!authority.canReview(actorId, organizationId)) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_HANDOFF_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
        // Serializes against later run generations and correction releases.
        jdbc.queryForObject(
            "SELECT id FROM payroll_period WHERE id = ? AND organization_id = ? FOR UPDATE",
            UUID::class.java, UUID.fromString(run.payrollPeriodId), organizationId,
        ) ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (publication.ifAvailable?.canPublish(
                organizationId, runVersionId, run.sourceFingerprint,
            ) != true
        ) Payroll9aPolicy.fail("PAYROLL_FINANCE_RUN_NOT_CURRENTLY_RELEASED", HttpStatus.CONFLICT)
        val released = jdbc.query(
            """
            SELECT id, policy_key, policy_version
            FROM payroll_run_release
            WHERE organization_id = ? AND run_version_id = ?
              AND run_source_fingerprint = ?
            """.trimIndent(),
            { rs, _ ->
                PayrollReleaseForHandoff(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("policy_key"),
                    rs.getInt("policy_version"),
                )
            }, organizationId, runVersionId, run.sourceFingerprint,
        ).singleOrNull() ?: Payroll9aPolicy.fail(
            "PAYROLL_FINANCE_RUN_NOT_RELEASED", HttpStatus.CONFLICT,
        )
        val manifest = PayrollFinanceReviewManifestPolicy.fromRun(
            run, released.id, released.policyKey, released.policyVersion,
            operationId, actorId,
        )
        val finance = intake.ifAvailable
            ?: Payroll9aPolicy.fail("PAYROLL_FINANCE_INTAKE_NOT_CONNECTED", HttpStatus.CONFLICT)
        // This is one local transactional review intake; the Finance owner
        // decides when a real payable/instruction can legally exist.
        val receipt = finance.acceptForReview(manifest)
        PayrollFinanceReviewManifestPolicy.validateReceipt(manifest, receipt)
        if (!receipt.replayed) {
            audit.append(
                AuditEventRecord(
                    actorUserId = actorId,
                    action = "PAYROLL_BATCH_HANDED_OFF_FOR_FINANCE_REVIEW",
                    targetType = "PAYROLL_RUN_VERSION",
                    targetId = runVersionId,
                    occurredAt = clock.instant(),
                    correlationId = correlationId,
                ),
            )
        }
        return PayrollFinanceHandoffDto(
            financeReviewBatchId = receipt.financeReviewBatchId.toString(),
            runVersionId = runVersionId.toString(),
            state = receipt.state,
            manifestSha256 = receipt.manifestSha256,
            currency = receipt.currency,
            payableTotal = receipt.payableTotal.toPlainString(),
            employeeCount = receipt.lineCount,
            transferCandidates = receipt.positiveTransferCount,
            replayed = receipt.replayed,
        )
    }
}
