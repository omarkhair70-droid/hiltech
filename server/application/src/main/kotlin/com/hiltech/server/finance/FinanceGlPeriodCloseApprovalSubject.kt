package com.hiltech.server.finance

import com.hiltech.server.approval.CompanyReviewPolicyKeys

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

const val FINANCE_GL_PERIOD_CLOSE_SUBJECT = "FINANCE_GL_PERIOD_CLOSE"

data class FinancePeriodCloseSnapshot(
    val organizationId: UUID,
    val periodId: UUID,
    val journalCount: Long,
    val ledgerSnapshotSha256: String,
    val sourceVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
)

/**
 * A period can close only after its end date, with an actual ACTIVE company
 * SINGLE review policy. The source version includes ALL posted AP journals.
 * Any new posting, reviewer change or policy revision invalidates approval.
 */
@Component
class FinanceGlPeriodCloseApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == FINANCE_GL_PERIOD_CLOSE_SUBJECT

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId, subject.subjectId)
                ?.sourceVersion == subject.subjectVersion

    fun current(org: UUID, periodId: UUID): FinancePeriodCloseSnapshot? {
        if (!enabled()) return null
        val policyKey = policyKey() ?: return null
        val now = clock.instant()
        val policy = approvals.loadActivePolicy(org, policyKey, now)
            ?.takeIf {
                it.organizationId == org && it.policyKey == policyKey &&
                    it.approvalRequired && it.mode == ApprovalPolicyMode.SINGLE &&
                    !it.authorityKey.isNullOrBlank()
            } ?: return null
        val reviewer = approvals.resolveCurrentAuthority(
            org, policy.authorityKey!!, now,
        ) ?: return null
        if (reviewer.organizationId != org ||
            reviewer.authorityKey != policy.authorityKey) return null
        if (jdbc.queryForObject(
                "SELECT to_regclass('public.finance_gl_period_close_source') IS NOT NULL",
                Boolean::class.java,
            ) != true) return null
        val actual = jdbc.query(
            """
            SELECT period_id,organization_id,date_end,state,
                journal_count,snapshot_sha256
            FROM public.finance_gl_period_close_source
            WHERE organization_id=? AND period_id=?
                AND NOT EXISTS (
                    SELECT 1 FROM public.finance_gl_period_close c
                    WHERE c.organization_id=? AND c.period_id=?
                )
            """.trimIndent(),
            { rs, _ ->
                SourceRow(
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getObject("period_id", UUID::class.java),
                    rs.getDate("date_end").toLocalDate(),
                    rs.getString("state"),
                    rs.getLong("journal_count"),
                    rs.getString("snapshot_sha256"),
                )
            },org,periodId,org,periodId,
        ).singleOrNull() ?: return null
        // Prevent early closing a fiscal period while work can legally
        // arrive. Future effective dates and local timezone guesses forbidden.
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (actual.state != "OPEN" || !today.isAfter(actual.dateEnd) ||
            actual.organizationId != org || actual.periodId != periodId ||
            actual.journalCount < 0L ||
            !Regex("^[a-f0-9]{64}$").matches(actual.sha256)) return null

        val input = listOf(
            "FINANCE_GL_PERIOD_CLOSE_APPROVAL_V1",org,periodId,
            actual.journalCount,actual.sha256,
            policyKey,policy.versionNumber,reviewer.id,reviewer.version,
        ).joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val version = (ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)
        return FinancePeriodCloseSnapshot(
            organizationId = org,periodId = periodId,
            journalCount = actual.journalCount,
            ledgerSnapshotSha256 = actual.sha256,
            sourceVersion = version,policyKey = policyKey,
            policyVersion = policy.versionNumber,
        )
    }

    fun policyKey(): String? = CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.gl-period-close.policy-key").takeIf {
        Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)
    }

    fun enabled(): Boolean = environment.getProperty(
        "hiltech.finance.gl-period-close.enabled",Boolean::class.java,false,
    ) && environment.getProperty(
        "hiltech.finance.gl-period-close.full-ledger-ready.enabled",
        Boolean::class.java,false,
    )

    private data class SourceRow(
        val organizationId: UUID,
        val periodId: UUID,
        val dateEnd: LocalDate,
        val state: String,
        val journalCount: Long,
        val sha256: String,
    )
}
