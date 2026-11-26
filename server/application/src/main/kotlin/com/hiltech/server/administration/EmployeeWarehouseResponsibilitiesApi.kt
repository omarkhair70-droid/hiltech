package com.hiltech.server.administration

import com.hiltech.server.people.PeopleService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import com.hiltech.server.warehouse.InventoryPrincipalType
import com.hiltech.server.warehouse.WarehouseAuthorityCommandService
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehouseDelegationPolicy
import com.hiltech.server.warehouse.WarehouseReadService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class EmployeeWarehouseResponsibilitiesResponse(
    val employeeId: String,
    val organizationId: String,
    val employeeVersion: Long,
    val linkedIdentityId: String?,
    val accountStatus: String,
    val scope: String = "MANAGED_WAREHOUSES_ONLY",
    val asOf: String,
    val warehouses: List<EmployeeManagedWarehouseResponse>,
)

data class EmployeeManagedWarehouseResponse(
    val warehouseId: String,
    val code: String,
    val name: String,
    val grantableAuthorityKeys: List<String>,
    val bindings: List<EmployeeWarehouseBindingResponse>,
)

data class EmployeeWarehouseBindingResponse(
    val bindingId: String,
    val authorityKey: String,
    val assignmentSource: String,
    val canEnd: Boolean,
    val version: Long,
)

/**
 * Composes existing People and Warehouse reads; it does not grant authority.
 * People administration alone does not reveal warehouse bindings. Each visible
 * warehouse must pass its current source + projected management authorization.
 * Mutations continue through the existing warehouse grant/end command service.
 */
@Component
class EmployeeWarehouseResponsibilitiesService(
    private val people: PeopleService,
    private val warehouseReads: WarehouseReadService,
    private val warehouseAuthorization: WarehouseAuthorizationPort,
    private val warehouseCommands: WarehouseAuthorityCommandService,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val clock: Clock,
) {
    fun current(actor: UUID, employeeId: UUID): EmployeeWarehouseResponsibilitiesResponse {
        // This is intentionally the admin detail read, not the public directory.
        val employee = people.detail(actor, employeeId)
        val at = clock.instant()
        val identity = employee.linkedIdentityId
        val accountCurrent = identity != null && sourceAuthority.isOrganizationMemberCurrent(
            identity, employee.organizationId, at,
        )
        if (!accountCurrent) {
            // People's linkedIdentityId is already a current-account projection.
            // Null does not establish that an identity has never been created.
            return EmployeeWarehouseResponsibilitiesResponse(
                employeeId = employee.employeeId.toString(),
                organizationId = employee.organizationId.toString(),
                employeeVersion = employee.employeeVersion,
                linkedIdentityId = null,
                accountStatus = "NO_CURRENT_LINKED_ACCOUNT",
                asOf = at.toString(),
                warehouses = emptyList(),
            )
        }
        val currentIdentity = requireNotNull(identity)
        val warehouses = warehouseReads.warehouses(actor, employee.organizationId)
            .filter { it.organizationId == employee.organizationId && it.active }
            .filter { warehouseAuthorization.canManageWarehouse(actor, it) }
            .map { warehouse ->
                // Rechecks current management authority after directory filtering.
                val bindings = warehouseCommands.currentBindings(actor, warehouse.warehouseId)
                    .filter { binding ->
                        binding.current && binding.organizationId == employee.organizationId &&
                            binding.warehouseId == warehouse.warehouseId && when (binding.principalType) {
                                InventoryPrincipalType.USER -> binding.principalId == currentIdentity
                                InventoryPrincipalType.TEAM -> sourceAuthority.isTeamMemberCurrent(
                                    currentIdentity, binding.principalId, at,
                                )
                            }
                    }
                    .map { binding ->
                        val direct = binding.principalType == InventoryPrincipalType.USER
                        EmployeeWarehouseBindingResponse(
                            bindingId = binding.bindingId.toString(),
                            authorityKey = binding.authorityKey.name,
                            assignmentSource = if (direct) "DIRECT" else "TEAM",
                            canEnd = direct && binding.authorityKey in WarehouseDelegationPolicy.delegableKeys,
                            version = binding.version,
                        )
                    }
                    .sortedWith(compareBy({ it.authorityKey }, { it.assignmentSource }, { it.bindingId }))
                EmployeeManagedWarehouseResponse(
                    warehouseId = warehouse.warehouseId.toString(),
                    code = warehouse.code,
                    name = warehouse.name,
                    grantableAuthorityKeys = WarehouseDelegationPolicy.delegableKeys.map { it.name },
                    bindings = bindings,
                )
            }
            .sortedWith(compareBy({ it.code }, { it.warehouseId }))
        return EmployeeWarehouseResponsibilitiesResponse(
            employeeId = employee.employeeId.toString(),
            organizationId = employee.organizationId.toString(),
            employeeVersion = employee.employeeVersion,
            linkedIdentityId = currentIdentity.toString(),
            accountStatus = "CURRENT",
            asOf = at.toString(),
            warehouses = warehouses,
        )
    }
}

@RestController
class EmployeeWarehouseResponsibilitiesController(
    private val service: EmployeeWarehouseResponsibilitiesService,
) {
    @GetMapping("/v1/employees/{employeeId}/warehouse-responsibilities")
    fun current(
        request: HttpServletRequest,
        @PathVariable employeeId: UUID,
    ): ResponseEntity<EmployeeWarehouseResponsibilitiesResponse> {
        val actor = HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                code = "UNAUTHENTICATED",
                message = "Authentication is required.",
                status = HttpStatus.UNAUTHORIZED,
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor, employeeId))
    }
}
