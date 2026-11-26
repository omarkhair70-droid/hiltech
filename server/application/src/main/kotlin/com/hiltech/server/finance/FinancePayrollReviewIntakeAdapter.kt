package com.hiltech.server.finance

import com.hiltech.server.payroll.PayrollFinanceReviewIntakePort
import com.hiltech.server.payroll.PayrollFinanceReviewManifest
import com.hiltech.server.payroll.PayrollFinanceReviewReceipt
import com.hiltech.server.platform.ProductApiException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The Finance-owned, immutable review-envelope side of Payroll9's handoff.
 * Stores only a source/beneficiary manifest digest and frozen aggregate; the
 * individual salary facts stay in the Payroll domain. Does NOT post a payable,
 * make payment instructions, create a bank transfer or mark employees PAID.
 *
 * The internal source adapter is available by default. An explicit false
 * incident switch can suspend intake without changing Payroll authority.
 */
@Component
@ConditionalOnProperty(
    prefix = "hiltech.finance",
    name = ["payroll-review-intake-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class FinancePayrollReviewIntakeAdapter(
    private val jdbc: JdbcTemplate,
) : PayrollFinanceReviewIntakePort {
    @Transactional
    override fun acceptForReview(
        manifest: PayrollFinanceReviewManifest,
    ): PayrollFinanceReviewReceipt {
        FinancePayrollReviewIntakePolicy.validate(manifest)
        val id = UUID.nameUUIDFromBytes(
            "hiltech:finance:payroll:review:${manifest.organizationId}:${manifest.runVersionId}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        // SELECT from the canonical released run is not optional. A Finance
        // client cannot fabricate a Payroll review by sending a hash alone.
        // ON CONFLICT DO NOTHING + final exact match protects concurrent replay.
        val inserted = jdbc.update(
            """
            INSERT INTO finance_payroll_review_batch (
                id, organization_id, payroll_period_id, run_version_id, release_id,
                operation_id, requested_by_user_id, run_source_fingerprint,
                manifest_sha256, policy_key, policy_version, currency,
                payable_total, line_count, positive_transfer_count
            )
            SELECT ?, pr.organization_id, run.payroll_period_id,
                   pr.run_version_id, pr.id, ?, ?, pr.run_source_fingerprint,
                   ?, pr.policy_key, pr.policy_version, run.currency, ?, ?, ?
            FROM payroll_run_release pr
            JOIN payroll_run_version run
              ON run.id = pr.run_version_id
             AND run.organization_id = pr.organization_id
            WHERE pr.id = ?
              AND pr.organization_id = ?
              AND pr.run_version_id = ?
              AND pr.run_source_fingerprint = ?
              AND pr.policy_key = ?
              AND pr.policy_version = ?
              AND run.payroll_period_id = ?
              AND run.currency = ?
              AND run.net = ?
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            id, manifest.operationId, manifest.requestedByUserId,
            manifest.manifestSha256, manifest.payableTotal,
            manifest.lines.size, manifest.positiveTransferCount,
            manifest.releaseId, manifest.organizationId, manifest.runVersionId,
            manifest.runSourceFingerprint, manifest.policyKey,
            manifest.policyVersion, manifest.payrollPeriodId,
            manifest.currency, manifest.payableTotal,
        )
        if (inserted > 1) invalid()
        val existing = jdbc.query(
            """
            SELECT id, organization_id, run_version_id, release_id, operation_id,
                   requested_by_user_id, run_source_fingerprint, manifest_sha256,
                   policy_key, policy_version, currency, payable_total,
                   line_count, positive_transfer_count,
                   review_state, bank_execution_available, payroll_period_id
            FROM finance_payroll_review_batch
            WHERE organization_id = ?
              AND (run_version_id = ? OR operation_id = ?)
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                FinancePayrollReviewRow(
                    id = rs.getObject("id", UUID::class.java),
                    org = rs.getObject("organization_id", UUID::class.java),
                    run = rs.getObject("run_version_id", UUID::class.java),
                    release = rs.getObject("release_id", UUID::class.java),
                    operation = rs.getObject("operation_id", UUID::class.java),
                    actor = rs.getObject("requested_by_user_id", UUID::class.java),
                    source = rs.getString("run_source_fingerprint").trim(),
                    hash = rs.getString("manifest_sha256").trim(),
                    policy = rs.getString("policy_key"),
                    policyVersion = rs.getInt("policy_version"),
                    currency = rs.getString("currency"),
                    total = rs.getBigDecimal("payable_total"),
                    count = rs.getInt("line_count"),
                    transfers = rs.getInt("positive_transfer_count"),
                    state = rs.getString("review_state"),
                    bank = rs.getBoolean("bank_execution_available"),
                    period = rs.getObject("payroll_period_id", UUID::class.java),
                )
            },
            manifest.organizationId, manifest.runVersionId, manifest.operationId,
        ).singleOrNull() ?: invalid()
        if (existing.org != manifest.organizationId ||
            existing.run != manifest.runVersionId ||
            existing.release != manifest.releaseId ||
            existing.operation != manifest.operationId ||
            existing.actor != manifest.requestedByUserId ||
            existing.period != manifest.payrollPeriodId ||
            existing.source != manifest.runSourceFingerprint ||
            existing.hash != manifest.manifestSha256 ||
            existing.policy != manifest.policyKey ||
            existing.policyVersion != manifest.policyVersion ||
            existing.currency != manifest.currency ||
            existing.total.compareTo(manifest.payableTotal) != 0 ||
            existing.count != manifest.lines.size ||
            existing.transfers != manifest.positiveTransferCount ||
            existing.state != "REVIEW_REQUIRED" || existing.bank
        ) invalid()
        return PayrollFinanceReviewReceipt(
            organizationId = existing.org,
            runVersionId = existing.run,
            releaseId = existing.release,
            operationId = existing.operation,
            financeReviewBatchId = existing.id,
            manifestSha256 = existing.hash,
            currency = existing.currency,
            payableTotal = existing.total,
            lineCount = existing.count,
            positiveTransferCount = existing.transfers,
            state = existing.state,
            bankExecutionAvailable = false,
            replayed = inserted == 0,
        )
    }

    private fun invalid(): Nothing = throw ProductApiException(
        "FINANCE_PAYROLL_REVIEW_SOURCE_CONFLICT",
        "Finance Payroll review requires one exact released Payroll manifest",
        HttpStatus.CONFLICT,
    )
}

internal data class FinancePayrollReviewRow(
    val id: UUID,
    val org: UUID,
    val run: UUID,
    val release: UUID,
    val operation: UUID,
    val actor: UUID,
    val source: String,
    val hash: String,
    val policy: String,
    val policyVersion: Int,
    val currency: String,
    val total: BigDecimal,
    val count: Int,
    val transfers: Int,
    val state: String,
    val bank: Boolean,
    val period: UUID,
)

internal object FinancePayrollReviewIntakePolicy {
    fun validate(m: PayrollFinanceReviewManifest) {
        val sha = Regex("^[a-f0-9]{64}$")
        val validPolicy = Regex("^[A-Z][A-Z0-9_]{2,95}$")
        val validCurrency = Regex("^[A-Z]{3}$")
        if (!sha.matches(m.manifestSha256) ||
            !sha.matches(m.runSourceFingerprint) ||
            !validPolicy.matches(m.policyKey) ||
            !validCurrency.matches(m.currency) ||
            m.policyVersion < 1 || m.lines.isEmpty() ||
            m.lines.size > 10000 ||
            m.lines.map { it.employeeId }.toSet().size != m.lines.size ||
            m.payableTotal.signum() < 0 ||
            m.payableTotal.stripTrailingZeros().scale() > 6 ||
            m.payableTotal.precision() - m.payableTotal.scale() > 18 ||
            m.lines.any {
                it.currency != m.currency ||
                it.net.signum() < 0 ||
                it.net.stripTrailingZeros().scale() > 6 ||
                it.requiresTransfer != (it.net.signum() > 0)
            } ||
            m.lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.net }
                .compareTo(m.payableTotal) != 0 ||
            m.lines.count { it.requiresTransfer } != m.positiveTransferCount
        ) {
            throw ProductApiException(
                "FINANCE_PAYROLL_REVIEW_MANIFEST_INVALID",
                "Finance Payroll review requires the exact Payroll run and totals",
                HttpStatus.CONFLICT,
            )
        }
    }
}
