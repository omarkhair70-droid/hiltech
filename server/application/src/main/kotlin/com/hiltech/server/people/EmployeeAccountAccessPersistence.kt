package com.hiltech.server.people

import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcEmployeeAccountAccessPersistence(private val jdbc: JdbcTemplate, manager: PlatformTransactionManager) : EmployeeAccountAccessPersistencePort {
    private val transactions = TransactionTemplate(manager)
    override fun <T : Any> transaction(action: () -> T): T = requireNotNull(transactions.execute { action() })

    override fun <T : Any> serializeCredentialReplacement(identityId: UUID, action: () -> T): T =
        requireNotNull(jdbc.execute(ConnectionCallback { connection ->
            val lock = "hiltech.account-credential:$identityId"
            val held = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))").use { statement ->
                statement.setString(1, lock)
                statement.executeQuery().use { it.next() && it.getBoolean(1) }
            }
            if (!held) throw ProductApiException("ACCOUNT_CREDENTIAL_COMMAND_BUSY", "Another account access command is running. Retry this request.", HttpStatus.CONFLICT, retryable = true)
            try { action() } finally {
                connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,0))").use { statement -> statement.setString(1, lock); statement.execute() }
            }
        }))

    override fun lockEmployeeAndAccount(employeeId: UUID, identityId: UUID) {
        val employee = jdbc.query("""SELECT p.id FROM person p JOIN employee e ON e.person_id=p.id
            WHERE e.id=? FOR UPDATE OF p,e""".trimIndent(), { rs, _ -> rs.getObject(1, UUID::class.java) }, employeeId).singleOrNull()
        if (employee == null) throw ProductApiException("EMPLOYEE_NOT_FOUND", "The employee is unavailable.", HttpStatus.NOT_FOUND)
        jdbc.query("SELECT id FROM user_identity WHERE id=? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, identityId)
        jdbc.query("""SELECT m.id FROM organization_membership m JOIN employee e ON e.organization_id=m.organization_id
            WHERE e.id=? AND m.user_identity_id=? FOR UPDATE OF m""".trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) }, employeeId, identityId)
    }

    override fun currentAccount(identityId: UUID, organizationId: UUID, at: Instant) = accounts("u.id=?", arrayOf(identityId), organizationId, at).singleOrNull()
    override fun currentLinkedAccount(personId: UUID, organizationId: UUID, at: Instant) = accounts("u.person_id=?", arrayOf(personId), organizationId, at).firstOrNull()
    private fun accounts(where: String, values: Array<out Any>, organizationId: UUID, at: Instant): List<CurrentEmployeeAccount> = jdbc.query(
        """SELECT u.id,u.version,u.person_id,u.auth_provider,u.auth_subject,o.id AS organization_id,m.id AS membership_id,m.version AS membership_version
           FROM user_identity u JOIN organization o ON o.id=?
           JOIN LATERAL (SELECT om.id,om.version FROM organization_membership om
             WHERE om.organization_id=o.id AND om.user_identity_id=u.id AND om.state='ACTIVE'
               AND om.valid_from<=? AND (om.valid_until IS NULL OR om.valid_until>?)
             ORDER BY om.valid_from DESC,om.id LIMIT 1) m ON true
           WHERE u.status='ACTIVE' AND o.status='ACTIVE' AND o.organization_type='HILTECH' AND $where
           ORDER BY u.id""".trimIndent(),
        { rs, _ -> CurrentEmployeeAccount(rs.getObject("id", UUID::class.java), rs.getLong("version"), rs.getObject("person_id", UUID::class.java),
            rs.getObject("organization_id", UUID::class.java), rs.getObject("membership_id", UUID::class.java), rs.getLong("membership_version"),
            rs.getString("auth_provider"), rs.getString("auth_subject")) },
        organizationId, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), *values)

    override fun personHasOtherAccount(personId: UUID, exceptIdentityId: UUID?): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM user_identity WHERE person_id=? AND (?::uuid IS NULL OR id<>?::uuid))",
        Boolean::class.java, personId, exceptIdentityId, exceptIdentityId) == true
    override fun hasInvitation(employeeId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM employee_identity_invitation WHERE employee_id=?)", Boolean::class.java, employeeId) == true

    override fun linkOwnAccount(employee: OnboardingEmployeeContext, account: CurrentEmployeeAccount, at: Instant): Boolean {
        val linked = jdbc.update("""UPDATE user_identity SET person_id=?,primary_organization_id=COALESCE(primary_organization_id,?),version=version+1
            WHERE id=? AND version=? AND status='ACTIVE' AND person_id IS NULL""".trimIndent(),
            employee.personId, employee.organizationId, account.identityId, account.identityVersion)
        if (linked != 1) return false
        return jdbc.update("UPDATE employee SET version=version+1,updated_at=? WHERE id=? AND version=?",
            at.atOffset(ZoneOffset.UTC), employee.employeeId, employee.employeeVersion) == 1
    }

    override fun reissue(operationId: UUID) = read("operation_id=?", operationId).singleOrNull()
    override fun latestReissue(identityId: UUID) = read("identity_id=? ORDER BY sequence_number DESC LIMIT 1", identityId).singleOrNull()
    private fun read(where: String, value: UUID): List<CredentialReissueRecord> = jdbc.query(
        "SELECT * FROM employee_credential_reissue WHERE $where", { rs, _ -> CredentialReissueRecord(
            rs.getObject("operation_id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
            rs.getObject("employee_id", UUID::class.java), rs.getObject("identity_id", UUID::class.java),
            rs.getObject("actor_identity_id", UUID::class.java), rs.getString("request_fingerprint"), rs.getString("state"),
            rs.getString("safe_failure_code"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant(), rs.getLong("version")) }, value)

    override fun insertDispatch(command: CredentialReissueCommand, organizationId: UUID, fingerprint: String, at: Instant) {
        jdbc.update("""INSERT INTO employee_credential_reissue (operation_id,organization_id,employee_id,identity_id,membership_id,actor_identity_id,
            employee_version,identity_version,membership_version,previous_operation_id,previous_operation_version,reason,request_fingerprint,state,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'DISPATCHING',?)""".trimIndent(),
            command.operationId, organizationId, command.employeeId, command.expectedIdentityId, command.expectedMembershipId, command.actorUserId,
            command.baseEmployeeVersion, command.baseIdentityVersion, command.baseMembershipVersion, command.previousOperationId,
            command.previousOperationVersion, command.reason.trim(), fingerprint, at.atOffset(ZoneOffset.UTC))
    }
    override fun finishDispatch(operationId: UUID, state: String, safeCode: String?, at: Instant): Boolean = jdbc.update(
        """UPDATE employee_credential_reissue SET state=?,safe_failure_code=?,completed_at=?,version=version+1
            WHERE operation_id=? AND state='DISPATCHING' AND version=1""".trimIndent(),
        state, safeCode, at.atOffset(ZoneOffset.UTC), operationId) == 1
}
