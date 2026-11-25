package com.hiltech.server.administration

import com.hiltech.server.people.JdbcPeopleAuthorizationProjectionBridge
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationProjectionBridge
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcResponsibilityAdministrationAuthority(private val jdbc: JdbcTemplate) : ResponsibilityAdministrationAuthorityPort {
    override fun grantableKeys(actor: UUID, organizationId: UUID, at: Instant): Set<CompanyResponsibilityKey> {
        return keys(actor, organizationId, at, requireOnwardDelegation = false)
    }
    override fun delegableKeys(actor: UUID, organizationId: UUID, at: Instant): Set<CompanyResponsibilityKey> {
        return keys(actor, organizationId, at, requireOnwardDelegation = true)
    }
    private fun keys(actor: UUID, organizationId: UUID, at: Instant, requireOnwardDelegation: Boolean): Set<CompanyResponsibilityKey> {
        val now = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT b.authority_key FROM responsibility_administration_binding b
            JOIN organization o ON o.id = b.organization_id
            JOIN user_identity u ON u.id = b.administrator_identity_id
            WHERE b.organization_id = ? AND b.administrator_identity_id = ?
              AND o.organization_type = 'HILTECH' AND o.status = 'ACTIVE' AND u.status = 'ACTIVE'
              AND b.active = true AND b.effective_from <= ?
              AND (b.effective_to IS NULL OR b.effective_to >= ?)
              AND (? = false OR b.can_delegate_administration = true)
              AND EXISTS (
                SELECT 1 FROM organization_membership m
                WHERE m.organization_id = b.organization_id AND m.user_identity_id = b.administrator_identity_id
                  AND m.state = 'ACTIVE' AND m.valid_from <= ? AND (m.valid_until IS NULL OR m.valid_until >= ?)
              )
            ORDER BY b.authority_key
            """.trimIndent(),
            { rs, _ -> CompanyResponsibilityKey.valueOf(rs.getString("authority_key")) },
            organizationId, actor, now, now, requireOnwardDelegation, now, now,
        ).toSet()
    }
}

/** Every identifier in a SQL template comes from this closed source map, never a request. */
private data class ResponsibilitySource(
    val table: String,
    val keyColumn: String?,
    val keyValue: String?,
    val userColumn: String,
    val fromColumn: String,
    val toColumn: String,
    val actorColumn: String?,
    val teams: Boolean,
)

private fun CompanyResponsibilityKey.source(): ResponsibilitySource = when (this) {
    CompanyResponsibilityKey.PEOPLE_ADMIN -> ResponsibilitySource(
        "people_authority_binding", "authority_key", name, "principal_user_id",
        "effective_from", "effective_to", "created_by_user_id", true,
    )
    CompanyResponsibilityKey.PROJECT_ADMIN -> ResponsibilitySource(
        "project_authority_binding", "authority_key", name, "principal_user_id",
        "effective_from", "effective_to", "created_by_user_id", true,
    )
    CompanyResponsibilityKey.SALES_VIEW, CompanyResponsibilityKey.SALES_WRITE,
    CompanyResponsibilityKey.SALES_COST_VIEW, CompanyResponsibilityKey.SALES_RELEASE,
    CompanyResponsibilityKey.SALES_AWARD -> ResponsibilitySource(
        "commercial_authority_binding", "authority_key", name, "principal_user_id",
        "effective_from", "effective_until", "created_by", true,
    )
    CompanyResponsibilityKey.PAYROLL_READ, CompanyResponsibilityKey.PAYROLL_PREPARE,
    CompanyResponsibilityKey.PAYROLL_REVIEW -> ResponsibilitySource(
        "payroll_authority_binding", "capability", name.removePrefix("PAYROLL_"), "identity_id",
        "valid_from", "valid_until", null, false,
    )
    CompanyResponsibilityKey.SUPPLIER_MASTER_DATA_ADMIN -> ResponsibilitySource(
        "supplier_master_data_authority_binding", null, null, "identity_id",
        "effective_from", "effective_to", "created_by_user_id", false,
    )
    CompanyResponsibilityKey.APPROVAL_POLICY_ADMIN -> ResponsibilitySource(
        "organization_administration_authority_binding", "authority_key", name, "identity_id",
        "effective_from", "effective_to", "created_by_user_id", false,
    )
    CompanyResponsibilityKey.FINANCE_PAYROLL_PREPARE, CompanyResponsibilityKey.FINANCE_PAYROLL_REVIEW -> ResponsibilitySource(
        "finance_payroll_authority_binding", "authority_key", name.removePrefix("FINANCE_PAYROLL_"), "identity_id",
        "effective_from", "effective_to", "created_by_user_id", false,
    )
}

@Component
class JdbcCompanyResponsibilityPersistence(private val jdbc: JdbcTemplate) : CompanyResponsibilityPersistencePort {
    override fun lockOrganization(organizationId: UUID) {
        if (jdbc.query("SELECT id FROM organization WHERE id = ? AND status = 'ACTIVE' AND organization_type = 'HILTECH' FOR UPDATE",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, organizationId).isEmpty()) {
            throw ProductApiException("RESPONSIBILITY_ORGANIZATION_UNAVAILABLE", "The company is unavailable.", HttpStatus.NOT_FOUND)
        }
    }
    override fun lockEmployee(employeeId: UUID) {
        if (jdbc.query("SELECT id FROM employee WHERE id = ? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, employeeId).isEmpty()) {
            throw ProductApiException("RESPONSIBILITY_EMPLOYEE_UNAVAILABLE", "The employee is unavailable.", HttpStatus.NOT_FOUND)
        }
    }

    override fun bindings(organizationId: UUID, at: Instant): List<CompanyResponsibilityBinding> =
        CompanyResponsibilityKey.entries.flatMap { key ->
            read(key, "b.organization_id = ?", arrayOf(organizationId), at).filter { it.current }
        }

    override fun binding(key: CompanyResponsibilityKey, bindingId: UUID, at: Instant): CompanyResponsibilityBinding? =
        read(key, "b.id = ?", arrayOf(bindingId), at).singleOrNull()

    private fun read(key: CompanyResponsibilityKey, where: String, args: Array<out Any>, at: Instant): List<CompanyResponsibilityBinding> {
        val source = key.source()
        val principalType = if (source.teams) "b.principal_type" else "'USER'"
        val principalId = if (source.teams) "COALESCE(b.${source.userColumn}, b.principal_team_id)" else "b.${source.userColumn}"
        val keyPredicate = source.keyColumn?.let { "AND b.$it = ?" }.orEmpty()
        val now = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT b.id, b.organization_id, $principalType AS principal_type, $principalId AS principal_id,
              b.version, (b.active = true AND o.status = 'ACTIVE' AND o.organization_type = 'HILTECH'
                AND b.${source.fromColumn} <= ? AND (b.${source.toColumn} IS NULL OR b.${source.toColumn} >= ?)) AS authority_current
            FROM ${source.table} b JOIN organization o ON o.id = b.organization_id
            WHERE $where $keyPredicate ORDER BY b.id
            """.trimIndent(),
            { rs, _ -> CompanyResponsibilityBinding(
                bindingId = rs.getObject("id", UUID::class.java),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                authorityKey = key, principalType = rs.getString("principal_type"),
                principalId = rs.getObject("principal_id", UUID::class.java),
                current = rs.getBoolean("authority_current"), version = rs.getLong("version"),
            ) },
            *listOfNotNull(now, now, *args, source.keyValue).toTypedArray(),
        )
    }

    override fun grant(key: CompanyResponsibilityKey, bindingId: UUID, organizationId: UUID, identityId: UUID,
                       actor: UUID, operationId: UUID, at: Instant) {
        val source = key.source()
        val now = at.atOffset(ZoneOffset.UTC)
        val columns = mutableListOf("id", "organization_id", source.userColumn, "active", source.fromColumn, "created_at", "version")
        // The projection guard compares generations for the tuple, across binding IDs.
        // Regranting an ended People/Projects tuple at version 1 would otherwise
        // leave its prior version-2 ABSENT projection in force forever.
        val initialVersion = if (key == CompanyResponsibilityKey.PEOPLE_ADMIN || key == CompanyResponsibilityKey.PROJECT_ADMIN) {
            (jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM ${source.table} WHERE organization_id = ? AND ${source.userColumn} = ? AND ${source.keyColumn} = ?",
                Long::class.java, organizationId, identityId, source.keyValue,
            ) ?: 0L) + 1L
        } else 1L
        val values = mutableListOf<Any>(bindingId, organizationId, identityId, true, now, now, initialVersion)
        source.keyColumn?.let { columns += it; values += requireNotNull(source.keyValue) }
        source.actorColumn?.let { columns += it; values += actor }
        if (source.teams) { columns += "principal_type"; values += "USER" }
        if (source.table == "payroll_authority_binding") {
            columns += "source_approval_ref"
            values += "RESPONSIBILITY_COMMAND:$operationId"
        }
        jdbc.update("INSERT INTO ${source.table} (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", *values.toTypedArray())
    }

    override fun end(binding: CompanyResponsibilityBinding, at: Instant): Boolean {
        val source = binding.authorityKey.source()
        return jdbc.update(
            """
            UPDATE ${source.table} SET active = false, ${source.toColumn} = ?, version = version + 1
            WHERE id = ? AND organization_id = ? AND version = ? AND active = true
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC), binding.bindingId, binding.organizationId, binding.version,
        ) == 1
    }
}

@Component
class JdbcOrganizationAdministrationAuthority(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : OrganizationAdministrationAuthorityPort {
    override fun canManageApprovalPolicies(actor: UUID, organizationId: UUID): Boolean {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM organization_administration_authority_binding b
              JOIN organization o ON o.id = b.organization_id
              JOIN user_identity u ON u.id = b.identity_id
              WHERE b.organization_id = ? AND b.identity_id = ? AND b.authority_key = 'APPROVAL_POLICY_ADMIN'
                AND o.organization_type = 'HILTECH' AND o.status = 'ACTIVE' AND u.status = 'ACTIVE'
                AND b.active = true AND b.effective_from <= ? AND (b.effective_to IS NULL OR b.effective_to >= ?)
                AND EXISTS (SELECT 1 FROM organization_membership m
                  WHERE m.organization_id = b.organization_id AND m.user_identity_id = b.identity_id
                    AND m.state = 'ACTIVE' AND m.valid_from <= ? AND (m.valid_until IS NULL OR m.valid_until >= ?))
            )
            """.trimIndent(), Boolean::class.java, organizationId, actor, now, now, now, now,
        ) == true
    }
}

@Component
class DomainCompanyResponsibilityProjection(
    private val people: JdbcPeopleAuthorizationProjectionBridge,
    private val projects: ProjectAuthorizationProjectionBridge,
) : CompanyResponsibilityProjectionPort {
    override fun changed(binding: CompanyResponsibilityBinding, at: Instant) {
        when (binding.authorityKey) {
            CompanyResponsibilityKey.PEOPLE_ADMIN -> people.syncBinding(binding.bindingId, at)
            CompanyResponsibilityKey.PROJECT_ADMIN -> projects.syncAuthorityBinding(binding.bindingId)
            else -> Unit // These domain ports read their source binding on each operation.
        }
    }
}
