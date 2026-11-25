package com.hiltech.server.administration

import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeopleAuthorizationPort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Minimal administration view: no contact, legal identity, employment terms, or payroll data. */
data class ResponsibilityEmployeeContext(
    val employeeId: UUID, val organizationId: UUID, val employeeVersion: Long, val employeeCode: String,
    val displayName: String, val employeeState: EmployeeState, val linkedIdentityId: UUID?,
)
interface ResponsibilityEmployeeDirectoryPort {
    fun employee(employeeId: UUID): ResponsibilityEmployeeContext?
    fun search(organizationId: UUID, query: String, limit: Int): List<ResponsibilityEmployeeContext>
}

@Component
class JdbcResponsibilityEmployeeDirectory(private val jdbc: JdbcTemplate, private val clock: Clock) : ResponsibilityEmployeeDirectoryPort {
    override fun employee(employeeId: UUID) = read("e.id = ?", arrayOf(employeeId), 1).singleOrNull()
    override fun search(organizationId: UUID, query: String, limit: Int): List<ResponsibilityEmployeeContext> {
        val pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        return read("e.organization_id = ? AND (p.display_name ILIKE ? ESCAPE '!' OR e.employee_code ILIKE ? ESCAPE '!')",
            arrayOf<Any>(organizationId, pattern, pattern), limit)
    }
    private fun read(where: String, args: Array<out Any>, limit: Int): List<ResponsibilityEmployeeContext> {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.query("""SELECT e.id,e.organization_id,e.version,e.employee_code,e.state,p.display_name,
              (SELECT u.id FROM user_identity u WHERE u.person_id=p.id AND u.status='ACTIVE' AND EXISTS (
                SELECT 1 FROM organization_membership m WHERE m.organization_id=e.organization_id AND m.user_identity_id=u.id
                  AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>=?))
                ORDER BY u.id LIMIT 1) AS identity_id
            FROM employee e JOIN person p ON p.id=e.person_id JOIN organization o ON o.id=e.organization_id
            WHERE o.organization_type='HILTECH' AND o.status='ACTIVE' AND $where
            ORDER BY p.display_name,e.employee_code,e.id LIMIT ?""".trimIndent(),
            { rs, _ -> ResponsibilityEmployeeContext(rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
                rs.getLong("version"), rs.getString("employee_code"), rs.getString("display_name"),
                EmployeeState.valueOf(rs.getString("state")), rs.getObject("identity_id", UUID::class.java)) }, at, at, *args, limit)
    }
}

data class ResponsibilityEmployeeResponse(val employeeId: String, val organizationId: String, val version: Long,
                                        val employeeCode: String, val displayName: String, val employeeState: String, val accountStatus: String)
data class ResponsibilityEmployeeDirectoryResponse(val items: List<ResponsibilityEmployeeResponse>, val hasMore: Boolean)

@Component
class ResponsibilityEmployeeDirectoryService(
    private val source: ResponsibilityEmployeeDirectoryPort,
    private val authority: ResponsibilityAdministrationAuthorityPort,
    private val people: PeopleAuthorizationPort,
    private val clock: Clock,
) {
    fun search(actor: UUID, organizationId: UUID, query: String, limit: Int): ResponsibilityEmployeeDirectoryResponse {
        if (authority.grantableKeys(actor, organizationId, clock.instant()).isEmpty() && !people.canManagePeople(actor, organizationId)) {
            throw ProductApiException("RESPONSIBILITY_DIRECTORY_DENIED", "Current responsibility administration authority is required.", HttpStatus.FORBIDDEN)
        }
        if (query.length > 120 || limit !in 1..100) throw ProductApiException("RESPONSIBILITY_SEARCH_INVALID", "The employee search is invalid.", HttpStatus.BAD_REQUEST)
        val rows = source.search(organizationId, query.trim(), limit + 1)
        return ResponsibilityEmployeeDirectoryResponse(rows.take(limit).map {
            ResponsibilityEmployeeResponse(it.employeeId.toString(), it.organizationId.toString(), it.employeeVersion,
                it.employeeCode, it.displayName, it.employeeState.name, if (it.linkedIdentityId == null) "NO_CURRENT_LINKED_ACCOUNT" else "CURRENT")
        }, rows.size > limit)
    }
}

@RestController
class ResponsibilityEmployeeDirectoryController(private val service: ResponsibilityEmployeeDirectoryService) {
    @GetMapping("/v1/organizations/{organizationId}/administration/employees")
    fun search(request: HttpServletRequest, @PathVariable organizationId: String,
               @RequestParam(defaultValue = "") query: String, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<ResponsibilityEmployeeDirectoryResponse> {
        val actor = HiltechRequestContext.current(request).identityId?.let(::uuid)
            ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(actor, uuid(organizationId), query, limit))
    }
    private fun uuid(value: String) = runCatching { UUID.fromString(value) }.getOrElse {
        throw ProductApiException("RESPONSIBILITY_INVALID_IDENTIFIER", "An administration identifier is invalid.", HttpStatus.BAD_REQUEST)
    }
}

data class CompanyAdministrationCapabilitiesResponse(
    val organizationId: String, val canManageResponsibilities: Boolean, val canViewEmployeeResponsibilities: Boolean,
    val canManageApprovalPolicies: Boolean, val managedAuthorityKeys: List<String>, val delegableAuthorityKeys: List<String>,
    val ownPeopleAdministrationStatus: String,
)

@RestController
class CompanyAdministrationCapabilitiesController(
    private val authority: ResponsibilityAdministrationAuthorityPort,
    private val policy: OrganizationAdministrationAuthorityPort,
    private val people: PeopleAuthorizationPort,
    private val ownPeople: OwnPeopleAdministrationService,
    private val clock: Clock,
) {
    @GetMapping("/v1/organizations/{organizationId}/administration/capabilities")
    fun current(request: HttpServletRequest, @PathVariable organizationId: UUID): ResponseEntity<CompanyAdministrationCapabilitiesResponse> {
        val actor = HiltechRequestContext.current(request).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
        val now = clock.instant()
        val managed = authority.grantableKeys(actor, organizationId, now)
        val delegated = authority.delegableKeys(actor, organizationId, now)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CompanyAdministrationCapabilitiesResponse(
            organizationId.toString(), managed.isNotEmpty(), managed.isNotEmpty() || people.canManagePeople(actor, organizationId),
            policy.canManageApprovalPolicies(actor, organizationId), managed.map { it.name }.sorted(), delegated.map { it.name }.sorted(),
            ownPeople.availability(actor, organizationId),
        ))
    }
}
