package com.hiltech.server.finance

import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class FinanceCashboxCustodianChoice(
    val identityId: UUID, val employeeId: UUID, val employeeCode: String, val displayName: String, val sourceVersion: Long,
)
data class FinanceCashboxOpeningStored(
    val id: UUID, val operationId: UUID, val organizationId: UUID, val code: String, val currency: String,
    val custodian: UUID, val companyReference: String, val subjectVersion: Long, val policyKey: String,
    val preparedBy: UUID, val createdAt: Instant, val codeSource: String = "MANUAL", val codeSequence: Long? = null,
    val custodianSourceVersion: Long? = null, val reviewSourceVersion: Long? = null,
    val custodianDisplayName: String = "", val custodianEmployeeCode: String = "",
)
data class FinanceCashboxActivationStored(
    val operationId: UUID, val actor: UUID, val approvalRequestId: UUID,
)
data class FinanceCashboxRecordedReview(val requestId: UUID, val state: String)

interface FinanceCashboxOpeningPersistencePort {
    fun lockOrganization(organizationId: UUID)
    fun custodians(organizationId: UUID, query: String, at: Instant, limit: Int): List<FinanceCashboxCustodianChoice>
    fun custodian(organizationId: UUID, identityId: UUID, at: Instant, lock: Boolean = false): FinanceCashboxCustodianChoice?
    fun proposal(organizationId: UUID, id: UUID): FinanceCashboxOpeningStored?
    fun operation(operationId: UUID): FinanceCashboxOpeningStored?
    fun history(organizationId: UUID, preparer: UUID?, query: String, limit: Int): List<FinanceCashboxOpeningStored>
    fun nextNumber(organizationId: UUID): Long
    fun append(proposal: FinanceCashboxOpeningStored)
    fun activation(organizationId: UUID, proposalId: UUID): FinanceCashboxActivationStored?
    fun recordedReview(proposal: FinanceCashboxOpeningStored): FinanceCashboxRecordedReview?
    fun activate(proposal: FinanceCashboxOpeningStored, operationId: UUID, actor: UUID, approvalRequestId: UUID, at: Instant)
    fun balance(organizationId: UUID, cashboxId: UUID): FinanceCashboxBalanceReceipt?
}

internal fun cashboxSourceVersion(vararg parts: Any): Long =
    (ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8)), 0, 8)
        .long and Long.MAX_VALUE).coerceAtLeast(1)

@Component
class JdbcFinanceCashboxOpeningPersistence(private val jdbc: JdbcTemplate) : FinanceCashboxOpeningPersistencePort {
    override fun lockOrganization(organizationId: UUID) {
        if (jdbc.query("SELECT id FROM organization WHERE id=? AND organization_type='HILTECH' AND status='ACTIVE' FOR UPDATE",
                { rs, _ -> rs.getObject("id", UUID::class.java) }, organizationId).size != 1) {
            throw ProductApiException("FINANCE_CASHBOX_NOT_AUTHORIZED", "Current company cash access is required.", HttpStatus.FORBIDDEN)
        }
    }

    override fun custodians(organizationId: UUID, query: String, at: Instant, limit: Int): List<FinanceCashboxCustodianChoice> =
        readCustodians(organizationId, at,
            "AND (p.display_name ILIKE ? ESCAPE '!' OR e.employee_code ILIKE ? ESCAPE '!')",
            arrayOf(pattern(query), pattern(query)), limit, false).distinctBy { it.identityId }

    override fun custodian(organizationId: UUID, identityId: UUID, at: Instant, lock: Boolean): FinanceCashboxCustodianChoice? =
        readCustodians(organizationId, at, "AND u.id=?", arrayOf(identityId), 1, lock).singleOrNull()

    private fun readCustodians(org: UUID, at: Instant, where: String, args: Array<out Any>, limit: Int, lock: Boolean): List<FinanceCashboxCustodianChoice> =
        jdbc.query("""SELECT u.id AS identity_id,u.version AS identity_version,e.id AS employee_id,e.version AS employee_version,
                e.employee_code,p.display_name,m.id AS membership_id,m.version AS membership_version
            FROM employee e JOIN person p ON p.id=e.person_id JOIN user_identity u ON u.person_id=p.id
            JOIN organization_membership m ON m.user_identity_id=u.id AND m.organization_id=e.organization_id
            JOIN organization o ON o.id=e.organization_id
            WHERE e.organization_id=? AND e.state='ACTIVE' AND u.status='ACTIVE'
              AND m.state='ACTIVE' AND m.membership_type='EMPLOYEE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?)
              AND o.status='ACTIVE' AND o.organization_type='HILTECH' $where
            ORDER BY p.display_name,e.employee_code,u.id,m.valid_from DESC,m.id LIMIT ?
            ${if (lock) "FOR SHARE OF e,u,m" else ""}""".trimIndent(), { rs, _ ->
            val identity = rs.getObject("identity_id", UUID::class.java)
            val employee = rs.getObject("employee_id", UUID::class.java)
            FinanceCashboxCustodianChoice(identity, employee, rs.getString("employee_code"), rs.getString("display_name"),
                cashboxSourceVersion("CASHBOX_CUSTODIAN_V1", org, employee, rs.getLong("employee_version"), identity,
                    rs.getLong("identity_version"), rs.getObject("membership_id", UUID::class.java), rs.getLong("membership_version")))
        }, org, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), *args, limit)

    override fun proposal(organizationId: UUID, id: UUID) =
        read("p.organization_id=? AND p.id=?", arrayOf(organizationId, id), 1).singleOrNull()
    override fun operation(operationId: UUID) = read("p.operation_id=?", arrayOf(operationId), 1).singleOrNull()
    override fun history(organizationId: UUID, preparer: UUID?, query: String, limit: Int): List<FinanceCashboxOpeningStored> {
        val where = "p.organization_id=?" + (if (preparer == null) "" else " AND p.prepared_by_user_id=?") +
            " AND (p.cashbox_code ILIKE ? ESCAPE '!' OR COALESCE(p.custodian_display_name,n.display_name,'') ILIKE ? ESCAPE '!' OR p.approved_company_reference ILIKE ? ESCAPE '!')"
        val args = mutableListOf<Any>(organizationId).apply {
            if (preparer != null) add(preparer)
            repeat(3) { add(pattern(query)) }
        }
        return read(where, args.toTypedArray(), limit)
    }
    private fun read(where: String, args: Array<out Any>, limit: Int): List<FinanceCashboxOpeningStored> = jdbc.query(
        """SELECT p.*,COALESCE(p.custodian_display_name,n.display_name,'') AS display_name,
              COALESCE(p.custodian_employee_code,e.employee_code,'') AS employee_code
            FROM finance_cashbox_opening_proposal p JOIN user_identity u ON u.id=p.custodian_user_id
            LEFT JOIN person n ON n.id=u.person_id LEFT JOIN employee e ON e.person_id=u.person_id AND e.organization_id=p.organization_id
            WHERE $where ORDER BY p.created_at DESC,p.id LIMIT ?""".trimIndent(),
        { rs, _ -> stored(rs) }, *args, limit)

    override fun nextNumber(organizationId: UUID): Long = requireNotNull(jdbc.queryForObject(
        "SELECT finance_next_cashbox_number(?)", Long::class.java, organizationId))

    override fun append(proposal: FinanceCashboxOpeningStored) {
        check(jdbc.update("""INSERT INTO finance_cashbox_opening_proposal(
            id,operation_id,organization_id,cashbox_code,currency,custodian_user_id,approved_company_reference,
            opening_balance,subject_version,policy_key,prepared_by_user_id,created_at,code_source,code_sequence,
            custodian_source_version,review_source_version,custodian_display_name,custodian_employee_code)
            VALUES(?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            proposal.id, proposal.operationId, proposal.organizationId, proposal.code, proposal.currency, proposal.custodian,
            proposal.companyReference, proposal.subjectVersion, proposal.policyKey, proposal.preparedBy, proposal.createdAt.atOffset(ZoneOffset.UTC),
            proposal.codeSource, proposal.codeSequence, proposal.custodianSourceVersion, proposal.reviewSourceVersion,
            proposal.custodianDisplayName, proposal.custodianEmployeeCode) == 1)
    }
    override fun activation(organizationId: UUID, proposalId: UUID): FinanceCashboxActivationStored? = jdbc.query(
        "SELECT activation_operation_id,created_by_user_id,opening_approval_request_id FROM finance_cashbox_register WHERE organization_id=? AND id=?",
        { rs, _ -> FinanceCashboxActivationStored(rs.getObject("activation_operation_id", UUID::class.java),
            rs.getObject("created_by_user_id", UUID::class.java), rs.getObject("opening_approval_request_id", UUID::class.java)) },
        organizationId, proposalId).singleOrNull()
    override fun recordedReview(proposal: FinanceCashboxOpeningStored): FinanceCashboxRecordedReview? = jdbc.query(
        """SELECT id,state FROM approval_request WHERE organization_id=? AND subject_type='FINANCE_CASHBOX_OPENING'
           AND subject_id=? AND subject_version=? AND policy_key=? ORDER BY created_at DESC,id LIMIT 1""",
        { rs, _ -> FinanceCashboxRecordedReview(rs.getObject("id", UUID::class.java), rs.getString("state")) },
        proposal.organizationId, proposal.id, proposal.subjectVersion, proposal.policyKey).singleOrNull()
    override fun activate(proposal: FinanceCashboxOpeningStored, operationId: UUID, actor: UUID, approvalRequestId: UUID, at: Instant) {
        check(jdbc.update("""INSERT INTO finance_cashbox_register(id,organization_id,cashbox_code,currency,custodian_user_id,
            approved_company_reference,opening_balance,status,opening_approval_request_id,opening_proposal_id,
            activation_operation_id,created_by_user_id,created_at) VALUES(?,?,?,?,?,?,0,'ACTIVE',?,?,?,?,?)""",
            proposal.id, proposal.organizationId, proposal.code, proposal.currency, proposal.custodian, proposal.companyReference,
            approvalRequestId, proposal.id, operationId, actor, at.atOffset(ZoneOffset.UTC)) == 1)
    }
    override fun balance(organizationId: UUID, cashboxId: UUID): FinanceCashboxBalanceReceipt? = jdbc.query(
        "SELECT cashbox_code,currency,custodian_user_id,cash_on_hand,recorded_vouchers FROM finance_cashbox_balance WHERE organization_id=? AND cashbox_id=?",
        { rs, _ -> FinanceCashboxBalanceReceipt(cashboxId, organizationId, rs.getString("cashbox_code"), rs.getString("currency"),
            rs.getObject("custodian_user_id", UUID::class.java), rs.getBigDecimal("cash_on_hand").toPlainString(), rs.getLong("recorded_vouchers")) },
        organizationId, cashboxId).singleOrNull()

    private fun stored(rs: ResultSet) = FinanceCashboxOpeningStored(
        rs.getObject("id", UUID::class.java), rs.getObject("operation_id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
        rs.getString("cashbox_code"), rs.getString("currency"), rs.getObject("custodian_user_id", UUID::class.java),
        rs.getString("approved_company_reference"), rs.getLong("subject_version"), rs.getString("policy_key"),
        rs.getObject("prepared_by_user_id", UUID::class.java), rs.getTimestamp("created_at").toInstant(), rs.getString("code_source"),
        (rs.getObject("code_sequence") as? Number)?.toLong(), (rs.getObject("custodian_source_version") as? Number)?.toLong(),
        (rs.getObject("review_source_version") as? Number)?.toLong(), rs.getString("display_name"), rs.getString("employee_code"))
    private fun pattern(query: String) = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
}
