package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** One-time trusted deployment setup. This is deliberately not a public HTTP endpoint. */
internal fun provisionResponsibilityAdministrator(args: List<String>) {
    require(args.size == 6) {
        "Usage: provision-responsibility-administrator <organization-id> <operator-id> <administrator-id> <comma-separated-catalog-keys> <approved-source-reference> <can-delegate-administration:true|false>"
    }
    fun required(name: String) = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
        "$name must be explicitly configured for trusted provisioning."
    }
    val dataSource = DriverManagerDataSource(required("HILTECH_DB_URL"), required("HILTECH_DB_USER"), required("HILTECH_DB_PASSWORD"))
    val jdbc = JdbcTemplate(dataSource)
    val keys = args[3].split(',').map { CompanyResponsibilityKey.valueOf(it.trim()) }.toSet()
    val onward = requireNotNull(args[5].toBooleanStrictOrNull()) { "An explicit approved onward-delegation boolean is required." }
    val bindings = ResponsibilityAdministratorProvisioner(
        jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)),
        JdbcRoleTeamSourceAuthority(jdbc), JdbcAuditEventWriter(jdbc), Clock.systemUTC(),
    ).provision(UUID.fromString(args[0]), UUID.fromString(args[1]), UUID.fromString(args[2]), keys, args[4], onward)
    println("Responsibility administrator setup verified: ${bindings.size} explicit delegation keys. Existing operational access is unchanged.")
}

internal class ResponsibilityAdministratorProvisioner(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val source: RoleTeamSourceAuthorityPort,
    private val audit: JdbcAuditEventWriter,
    private val clock: Clock,
) {
    fun provision(organizationId: UUID, operatorId: UUID, administratorId: UUID,
                  keys: Set<CompanyResponsibilityKey>, approvedSourceReference: String, canDelegateAdministration: Boolean): List<UUID> {
        val reference = approvedSourceReference.trim()
        require(keys.isNotEmpty()) { "An explicit nonempty delegation ceiling is required." }
        require(reference.length in 1..160) { "An approved source reference is required." }
        return requireNotNull(transaction.execute {
            val company = jdbc.query(
                "SELECT id FROM organization WHERE id = ? AND organization_type = 'HILTECH' AND status = 'ACTIVE' FOR UPDATE",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, organizationId,
            ).singleOrNull()
            require(company != null) { "The active company is unavailable." }
            val now = clock.instant()
            require(source.isOrganizationMemberCurrent(operatorId, organizationId, now)) { "The audited operator must be a current company member." }
            require(source.isOrganizationMemberCurrent(administratorId, organizationId, now)) { "The administrator must be a current company member." }
            val existing = jdbc.query(
                "SELECT id, administrator_identity_id, authority_key, active, effective_from, effective_to, source_approval_ref, can_delegate_administration FROM responsibility_administration_binding WHERE organization_id = ? ORDER BY authority_key",
                { rs, _ -> ExistingSetup(
                    rs.getObject("id", UUID::class.java), rs.getObject("administrator_identity_id", UUID::class.java),
                    CompanyResponsibilityKey.valueOf(rs.getString("authority_key")),
                    rs.getBoolean("active") && !rs.getTimestamp("effective_from").toInstant().isAfter(now) &&
                        (rs.getTimestamp("effective_to")?.toInstant()?.isBefore(now) != true),
                    rs.getString("source_approval_ref"), rs.getBoolean("can_delegate_administration"),
                ) }, organizationId,
            )
            if (existing.isNotEmpty()) {
                require(existing.size == keys.size && existing.all {
                    it.administratorId == administratorId && it.key in keys && it.current && it.reference == reference &&
                        it.canDelegateAdministration == canDelegateAdministration
                }) { "Existing governance history requires an explicitly reviewed governance change; initial setup cannot alter it." }
                return@execute existing.map { it.id }
            }
            keys.sortedBy { it.name }.map { key ->
                val bindingId = UUID.randomUUID()
                jdbc.update(
                    """INSERT INTO responsibility_administration_binding
                       (id, organization_id, administrator_identity_id, authority_key, active,
                        effective_from, source_approval_ref, created_by_user_id, created_at, version, can_delegate_administration)
                       VALUES (?, ?, ?, ?, true, ?, ?, ?, ?, 1, ?)""".trimIndent(),
                    bindingId, organizationId, administratorId, key.name, now.atOffset(ZoneOffset.UTC),
                    reference, operatorId, now.atOffset(ZoneOffset.UTC), canDelegateAdministration,
                )
                audit.append(AuditEventRecord(
                    actorUserId = operatorId, action = "RESPONSIBILITY_INITIAL_ADMINISTRATOR_PROVISIONED",
                    targetType = "RESPONSIBILITY_ADMINISTRATION_BINDING", targetId = bindingId,
                    newStateRef = key.name,
                    safeDiffJson = """{"fields":["organizationId","administratorIdentityId","authorityKey","sourceApprovalRef","canDelegateAdministration"]}""",
                    occurredAt = now, correlationId = bindingId.toString(),
                ))
                bindingId
            }
        })
    }

    private data class ExistingSetup(val id: UUID, val administratorId: UUID, val key: CompanyResponsibilityKey,
                                     val current: Boolean, val reference: String, val canDelegateAdministration: Boolean)
}
