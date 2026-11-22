package com.hiltech.server.payroll

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

enum class PayrollFinanceCorrectionDisposition {
    NO_PRIOR_TRANSFER_VERIFIED,
    REVERSAL_VERIFIED,
    SETTLED_DIFFERENTIAL_AUTHORIZED,
}

/**
 * Company/payroll and Finance must both attest a correction. A free-text HTTP
 * reason or a previous SUCCESS/PENDING/UNKNOWN payment is not authorization.
 * The Finance source decides what happens to money already issued, including
 * any recovery or difference payment, outside Payroll.
 */
data class PayrollVerifiedCorrectionAuthorization(
    val organizationId: UUID,
    val originalRunVersionId: UUID,
    val replacementRunVersionId: UUID,
    val originalSourceFingerprint: String,
    val replacementSourceFingerprint: String,
    val approval: PayrollInputApproval,
    val reasonCode: String,
    val reasonEvidenceReference: String,
    val reasonEvidenceVersion: Long,
    val financeDisposition: PayrollFinanceCorrectionDisposition,
    val financeClearanceReference: String,
    val financeClearanceVersion: Long,
)

interface PayrollVerifiedCorrectionSourcePort {
    fun verifiedFor(
        organizationId: UUID,
        originalRunVersionId: UUID,
        replacementRunVersionId: UUID,
    ): PayrollVerifiedCorrectionAuthorization?
}

data class PayrollCorrectionReadModel(
    val correctionId: String,
    val originalRunVersionId: String,
    val replacementRunVersionId: String,
    val reasonCode: String,
    val financeDisposition: String,
)

private data class EarlierPayrollRelease(
    val id: UUID,
    val runId: UUID,
    val fingerprint: String,
)

/**
 * Called only by the Payroll run release transaction after source/policy guard.
 * Never writes to Finance, recalculates money, or assumes a returned transfer
 * equals a verified reversal. Missing source blocks a later release.
 */
@Component
class Payroll9cCorrectionService(
    private val jdbc: JdbcTemplate,
    private val source: ObjectProvider<PayrollVerifiedCorrectionSourcePort>,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun recordIfCorrecting(
        actorId: UUID,
        run: Payroll9bRunDto,
        releaseOperationId: UUID,
        correlationId: String,
    ): PayrollCorrectionReadModel? {
        val org = UUID.fromString(run.organizationId)
        val periodId = UUID.fromString(run.payrollPeriodId)
        val runId = UUID.fromString(run.runVersionId)
        val original = jdbc.query(
            """
            SELECT released.id AS release_id, rv.id AS run_id,
                   rv.source_fingerprint AS fingerprint
            FROM payroll_run_release released
            JOIN payroll_run_version rv ON rv.id = released.run_version_id
            WHERE rv.payroll_period_id = ? AND rv.organization_id = ?
            ORDER BY rv.generation DESC LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                EarlierPayrollRelease(
                    id = rs.getObject("release_id", UUID::class.java),
                    runId = rs.getObject("run_id", UUID::class.java),
                    fingerprint = rs.getString("fingerprint"),
                )
            }, periodId, org,
        ).singleOrNull() ?: return null

        if (original.runId == runId ||
            original.fingerprint == run.sourceFingerprint
        ) {
            Payroll9aPolicy.fail("PAYROLL_CORRECTION_SOURCE_UNCHANGED", HttpStatus.CONFLICT)
        }
        val provider = source.ifAvailable
            ?: Payroll9aPolicy.fail("PAYROLL_CORRECTION_FINANCE_NOT_CONNECTED", HttpStatus.CONFLICT)
        val attestation = provider.verifiedFor(org, original.runId, runId)
            ?: Payroll9aPolicy.fail("PAYROLL_CORRECTION_CLEARANCE_MISSING", HttpStatus.CONFLICT)
        if (attestation.organizationId != org ||
            attestation.originalRunVersionId != original.runId ||
            attestation.replacementRunVersionId != runId ||
            attestation.originalSourceFingerprint != original.fingerprint ||
            attestation.replacementSourceFingerprint != run.sourceFingerprint ||
            attestation.approval != PayrollInputApproval.APPROVED ||
            attestation.reasonEvidenceVersion < 1 ||
            attestation.financeClearanceVersion < 1 ||
            !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(attestation.reasonCode) ||
            attestation.reasonEvidenceReference.trim().length !in 3..240 ||
            attestation.financeClearanceReference.trim().length !in 3..240
        ) Payroll9aPolicy.fail("PAYROLL_CORRECTION_CLEARANCE_INVALID", HttpStatus.CONFLICT)

        val id = UUID.nameUUIDFromBytes(
            ("hiltech:payroll-correction:" + releaseOperationId).toByteArray(StandardCharsets.UTF_8),
        )
        jdbc.update(
            """
            INSERT INTO payroll_run_correction (
                id, operation_id, organization_id, original_run_version_id,
                original_release_id, replacement_run_version_id,
                original_source_fingerprint, replacement_source_fingerprint,
                reason_code, reason_evidence_ref, reason_evidence_version,
                finance_disposition, finance_clearance_ref, finance_clearance_version,
                recorded_by_user_id, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, releaseOperationId, org, original.runId, original.id, runId,
            original.fingerprint, run.sourceFingerprint,
            attestation.reasonCode, attestation.reasonEvidenceReference,
            attestation.reasonEvidenceVersion, attestation.financeDisposition.name,
            attestation.financeClearanceReference, attestation.financeClearanceVersion,
            actorId, clock.instant().atOffset(ZoneOffset.UTC),
        )
        audit.append(
            AuditEventRecord(
                actorUserId = actorId,
                action = "PAYROLL_CORRECTION_VERIFIED_FOR_FINANCE_REVIEW",
                targetType = "PAYROLL_RUN_VERSION",
                targetId = runId,
                occurredAt = clock.instant(),
                correlationId = correlationId,
            ),
        )
        return PayrollCorrectionReadModel(
            correctionId = id.toString(),
            originalRunVersionId = original.runId.toString(),
            replacementRunVersionId = runId.toString(),
            reasonCode = attestation.reasonCode,
            financeDisposition = attestation.financeDisposition.name,
        )
    }
}
