package com.hiltech.server.administration

import java.time.Instant
import java.util.UUID

/** An allowlist of operational roles, never approval or governance authority. */
enum class CompanyResponsibilityKey(val domain: String) {
    PEOPLE_ADMIN("PEOPLE"),
    PROJECT_ADMIN("PROJECTS"),
    SALES_VIEW("COMMERCIAL"),
    SALES_WRITE("COMMERCIAL"),
    SALES_COST_VIEW("COMMERCIAL"),
    SALES_RELEASE("COMMERCIAL"),
    SALES_AWARD("COMMERCIAL"),
    PAYROLL_READ("PAYROLL"),
    PAYROLL_PREPARE("PAYROLL"),
    PAYROLL_REVIEW("PAYROLL"),
    SUPPLIER_MASTER_DATA_ADMIN("SUPPLIERS"),
    APPROVAL_POLICY_ADMIN("ADMINISTRATION"),
    COMPANY_SETTINGS_ADMIN("ADMINISTRATION"),
    FINANCE_PAYROLL_PREPARE("FINANCE"),
    FINANCE_PAYROLL_REVIEW("FINANCE"),
    PROCUREMENT_BUYER("PROCUREMENT"),
    PROCUREMENT_TECHNICAL_REVIEW("PROCUREMENT"),
    PROCUREMENT_NEED_REQUEST("PROCUREMENT"),
    PROCUREMENT_NEED_REVIEW("PROCUREMENT"),
    PROCUREMENT_ORDER_ISSUE("PROCUREMENT"),
    PROCUREMENT_SERVICE_ACCEPT("PROCUREMENT"),
    FINANCE_SUPPLIER_REVIEW("FINANCE"),
    FINANCE_EMPLOYEE_REVIEW("FINANCE"),
    FINANCE_COMMERCIAL_REVIEW("FINANCE"),
    FINANCE_PAYABLE_PREPARE("FINANCE"),
    FINANCE_PAYABLE_POST("FINANCE"),
    FINANCE_RECEIVABLE_PREPARE("FINANCE"),
    FINANCE_RECEIVABLE_EXECUTE("FINANCE"),
    FINANCE_EMPLOYEE_PREPARE("FINANCE"),
    FINANCE_EMPLOYEE_EXECUTE("FINANCE"),
    FINANCE_BANK_STATEMENT_PREPARE("FINANCE"),
    FINANCE_BANK_STATEMENT_IMPORT("FINANCE"),
    FINANCE_BANK_RECONCILIATION_PREPARE("FINANCE"),
    FINANCE_BANK_RECONCILIATION_FINALIZE("FINANCE"),
    FINANCE_CASH_PREPARE("FINANCE"),
    FINANCE_CASH_EXECUTE("FINANCE"),
    FINANCE_PERIOD_CLOSE_PREPARE("FINANCE"),
    FINANCE_PERIOD_CLOSE_EXECUTE("FINANCE"),
    FINANCE_REPORT_READ("FINANCE"),
}

data class CompanyResponsibilityBinding(
    val bindingId: UUID,
    val organizationId: UUID,
    val authorityKey: CompanyResponsibilityKey,
    val principalType: String,
    val principalId: UUID,
    val current: Boolean,
    val version: Long,
    val administrationManaged: Boolean = true,
)

interface ResponsibilityAdministrationAuthorityPort {
    fun grantableKeys(actor: UUID, organizationId: UUID, at: Instant): Set<CompanyResponsibilityKey>
    fun delegableKeys(actor: UUID, organizationId: UUID, at: Instant): Set<CompanyResponsibilityKey>
}

interface OrganizationAdministrationAuthorityPort {
    fun canManageApprovalPolicies(actor: UUID, organizationId: UUID): Boolean
}

interface CompanyResponsibilityPersistencePort {
    fun lockOrganization(organizationId: UUID)
    /** Transaction row lock shared by every command for the employee. */
    fun lockEmployee(employeeId: UUID)
    fun bindings(organizationId: UUID, at: Instant): List<CompanyResponsibilityBinding>
    fun binding(key: CompanyResponsibilityKey, bindingId: UUID, at: Instant): CompanyResponsibilityBinding?
    fun grant(key: CompanyResponsibilityKey, bindingId: UUID, organizationId: UUID, identityId: UUID,
              actor: UUID, operationId: UUID, at: Instant)
    fun end(binding: CompanyResponsibilityBinding, at: Instant): Boolean
}

interface CompanyResponsibilityProjectionPort {
    fun changed(binding: CompanyResponsibilityBinding, at: Instant)
}

data class EmployeeCompanyResponsibilitiesResponse(
    val employeeId: String,
    val organizationId: String,
    val employeeVersion: Long,
    val linkedIdentityId: String?,
    val accountStatus: String,
    val scope: String = "ORGANIZATION_CATALOG",
    val asOf: String,
    val catalog: List<CompanyResponsibilityCatalogResponse>,
    val bindings: List<CompanyResponsibilityBindingResponse>,
    val viewScope: String = "DELEGATED_KEYS",
    val canDelegateAdministration: Boolean = false,
)

data class CompanyResponsibilityCatalogResponse(val authorityKey: String, val domain: String, val canGrant: Boolean)
data class CompanyResponsibilityBindingResponse(
    val bindingId: String,
    val authorityKey: String,
    val domain: String,
    val assignmentSource: String,
    val canEnd: Boolean,
    val current: Boolean,
    val version: Long,
)

data class CompanyResponsibilityCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val organizationId: UUID,
    val baseEmployeeVersion: Long,
    val expectedIdentityId: UUID,
    val authorityKey: CompanyResponsibilityKey,
    val bindingId: UUID? = null,
    val baseBindingVersion: Long? = null,
    val actorUserId: UUID,
    val correlationId: String,
)

data class CompanyResponsibilityCommandResponse(
    val employeeId: String,
    val organizationId: String,
    val identityId: String,
    val authorityKey: String,
    val bindingId: String,
    val resultCode: String,
    val current: Boolean,
    val version: Long,
    val replayed: Boolean,
    val operationId: String,
    val effectiveAccessRemaining: Boolean = false,
)
