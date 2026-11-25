package com.hiltech.server.identity

import com.hiltech.server.organizations.OrganizationIdentityContextPort
import com.hiltech.server.platform.ProductApiException
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class IdentityBootstrapResponse(
    val identityId: String,
    val identityStatus: String,
    val identityVersion: Long,
    val primaryOrganizationId: String?,
    val organizations: List<IdentityOrganizationResponse>,
    val teams: List<IdentityTeamResponse>,
    val device: IdentityDeviceResponse?,
    val productCapabilities: IdentityProductCapabilitiesResponse? = null,
)

data class IdentityProductCapabilitiesResponse(
    val executiveHome: Boolean,
    val personalIssuedStock: Boolean,
    val materialUsageReporting: Boolean,
    val assignedAssetLookup: Boolean,
    val engineeringTests: Boolean,
    val operationAvailability: Map<String, Boolean> = emptyMap(),
)

data class IdentityOrganizationResponse(
    val membershipId: String,
    val organizationId: String,
    val organizationCode: String,
    val displayName: String,
    val organizationType: String,
    val membershipType: String,
    val roleLabel: String?,
    val primary: Boolean,
    val membershipVersion: Long,
)

data class IdentityTeamResponse(
    val teamMembershipId: String,
    val teamId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val roleInTeam: String?,
)

data class DeviceRegistrationRequest(
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
)

data class IdentityDeviceResponse(
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
    val lastSeenAt: String?,
    val version: Long,
)

class IdentityAccessException(
    code: String,
    message: String,
    status: HttpStatus = HttpStatus.FORBIDDEN,
) : ProductApiException(
    code = code,
    message = message,
    status = status,
)

class IdentityBootstrapService(
    private val identityRepository: IdentityRuntimeRepository,
    private val organizationContext: OrganizationIdentityContextPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun bootstrap(
        subject: AuthenticatedOidcSubject,
        installationId: UUID?,
    ): IdentityBootstrapResponse {
        val now = clock.instant()
        val identity = resolveActiveIdentity(subject)
        val memberships = organizationContext.activeMemberships(
            identity.id,
            now,
        )

        if (memberships.isEmpty()) {
            throw IdentityAccessException(
                code = "NO_ACTIVE_ORGANIZATION_MEMBERSHIP",
                message = "No active organization membership is available.",
            )
        }

        val device = installationId?.let { id ->
            val current = identityRepository.findDevice(id)
            when {
                current == null ->
                    null

                current.userIdentityId != identity.id ->
                    throw IdentityAccessException(
                        code = "DEVICE_INSTALLATION_CONFLICT",
                        message = "This device installation belongs to another identity.",
                        status = HttpStatus.CONFLICT,
                    )

                current.revokedAt != null ->
                    throw IdentityAccessException(
                        code = "DEVICE_REVOKED",
                        message = "This device installation has been revoked.",
                    )

                else ->
                    current
            }
        }

        identityRepository.markAuthenticated(
            identityId = identity.id,
            authenticatedAt = now,
        )

        val teams = organizationContext.activeTeams(
            identity.id,
            now,
        )

        return IdentityBootstrapResponse(
            identityId = identity.id.toString(),
            identityStatus = identity.status,
            identityVersion = identity.version,
            primaryOrganizationId =
                identity.primaryOrganizationId?.toString(),
            organizations = memberships.map { membership ->
                IdentityOrganizationResponse(
                    membershipId =
                        membership.membershipId.toString(),
                    organizationId =
                        membership.organizationId.toString(),
                    organizationCode =
                        membership.organizationCode,
                    displayName =
                        membership.organizationDisplayName,
                    organizationType =
                        membership.organizationType,
                    membershipType =
                        membership.membershipType,
                    roleLabel = membership.roleLabel,
                    primary =
                        membership.organizationId ==
                            identity.primaryOrganizationId,
                    membershipVersion =
                        membership.membershipVersion,
                )
            },
            teams = teams.map { team ->
                IdentityTeamResponse(
                    teamMembershipId =
                        team.teamMembershipId.toString(),
                    teamId = team.teamId.toString(),
                    organizationId =
                        team.organizationId.toString(),
                    code = team.code,
                    name = team.name,
                    roleInTeam = team.roleInTeam,
                )
            },
            device = device?.toResponse(),
        )
    }

    fun registerDevice(
        subject: AuthenticatedOidcSubject,
        request: DeviceRegistrationRequest,
    ): IdentityDeviceResponse {
        val identity = resolveActiveIdentity(subject)
        val installationId = request.installationId.toUuidOrBadRequest(
            "installationId",
        )
        val platform = request.platform.trim().uppercase()

        if (platform !in setOf("ANDROID", "WINDOWS", "IOS")) {
            throw IdentityAccessException(
                code = "INVALID_DEVICE_PLATFORM",
                message = "Unsupported device platform.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        if (request.appVersion.isBlank()) {
            throw IdentityAccessException(
                code = "INVALID_DEVICE_METADATA",
                message = "appVersion must not be blank.",
                status = HttpStatus.BAD_REQUEST,
            )
        }

        return when (
            val outcome = identityRepository.registerOrTouchDevice(
                identityId = identity.id,
                registration = DeviceRegistration(
                    installationId = installationId,
                    platform = platform,
                    deviceName =
                        request.deviceName?.trim()?.takeIf {
                            it.isNotEmpty()
                        },
                    appVersion = request.appVersion.trim(),
                    osVersion =
                        request.osVersion?.trim()?.takeIf {
                            it.isNotEmpty()
                        },
                ),
                seenAt = clock.instant(),
            )
        ) {
            is DeviceRegistrationOutcome.Active ->
                outcome.device.toResponse()

            DeviceRegistrationOutcome.Revoked ->
                throw IdentityAccessException(
                    code = "DEVICE_REVOKED",
                    message = "This device installation has been revoked.",
                )

            DeviceRegistrationOutcome.OwnedByAnotherIdentity ->
                throw IdentityAccessException(
                    code = "DEVICE_INSTALLATION_CONFLICT",
                    message = "This device installation belongs to another identity.",
                    status = HttpStatus.CONFLICT,
                )
        }
    }

    private fun resolveActiveIdentity(
        subject: AuthenticatedOidcSubject,
    ): UserIdentityRuntime {
        val identity = identityRepository.findByOidcSubject(
            issuer = subject.issuer,
            subject = subject.subject,
        ) ?: throw IdentityAccessException(
            code = "IDENTITY_NOT_PROVISIONED",
            message = "Authenticated identity is not provisioned in HILTECH.",
        )

        if (identity.status != "ACTIVE") {
            throw IdentityAccessException(
                code = "IDENTITY_NOT_ACTIVE",
                message = "HILTECH identity is not active.",
            )
        }

        return identity
    }

    private fun String.toUuidOrBadRequest(
        field: String,
    ): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse {
                throw IdentityAccessException(
                    code = "INVALID_${field.uppercase()}",
                    message = "$field must be a UUID.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }

    private fun DeviceRuntime.toResponse(): IdentityDeviceResponse =
        IdentityDeviceResponse(
            deviceId = id.toString(),
            installationId = installationId.toString(),
            platform = platform,
            deviceName = deviceName,
            appVersion = appVersion,
            osVersion = osVersion,
            lastSeenAt = lastSeenAt?.toString(),
            version = version,
        )
}

@RestController
@RequestMapping("/v1/me")
class IdentityBootstrapController(
    identityRepository: IdentityRuntimeRepository,
    organizationContext: OrganizationIdentityContextPort,
    private val sessionService: IdentitySessionService,
    environment: Environment,
) {
    private val service = IdentityBootstrapService(
        identityRepository = identityRepository,
        organizationContext = organizationContext,
    )
    private val productCapabilities = environment.identityProductCapabilities()

    @GetMapping("/bootstrap")
    fun bootstrap(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
            required = false,
        )
        installationId: String?,
    ): IdentityBootstrapResponse =
        service.bootstrap(
            subject = OidcSubjectResolver.from(jwt),
            installationId = installationId
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching { UUID.fromString(it) }
                        .getOrElse {
                            throw IdentityAccessException(
                                code = "INVALID_DEVICE_INSTALLATION_ID",
                                message = "X-Device-Installation-Id must be a UUID.",
                                status = HttpStatus.BAD_REQUEST,
                            )
                        }
                },
        ).copy(productCapabilities = productCapabilities)

    @PutMapping("/device")
    fun registerDevice(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: DeviceRegistrationRequest,
    ): IdentityDeviceResponse {
        val response = service.registerDevice(
            subject = OidcSubjectResolver.from(jwt),
            request = request,
        )
        sessionService.registerOrTouch(
            jwt = jwt,
            installationId =
                request.installationId.toUuidHeaderOrBadRequest(),
        )
        return response
    }

    private fun String.toUuidHeaderOrBadRequest(): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse {
                throw IdentityAccessException(
                    code = "INVALID_INSTALLATIONID",
                    message = "installationId must be a UUID.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
}

/** Stable transport names for implemented routes. These values never confer authority. */
internal object InternalOperationCatalog {
    val properties: Map<String, String> = linkedMapOf(
        "commercial" to "hiltech.commercial.enabled",
        "executive.home" to "hiltech.executive.home-enabled",
        "field.personal-issued-stock" to "hiltech.field.personal-issued-stock.enabled",
        "field.material-usage-report" to "hiltech.field.material-usage-report.enabled",
        "field.material-usage-review" to "hiltech.field.material-usage-review-read.enabled",
        "field.material-usage-reconciliation" to "hiltech.field.material-usage-reconciliation.enabled",
        "field.assigned-asset-lookup" to "hiltech.field.assigned-asset-lookup.enabled",
        "field.engineering-tests" to "hiltech.field.engineering-tests.enabled",
        "field.work-documents" to "hiltech.field.work-documents.enabled",
        "field.completion" to "hiltech.field.completion.enabled",
        "procurement.requirements" to "hiltech.procurement.requirements.enabled",
        "procurement.warehouse-replenishment" to "hiltech.procurement.warehouse-replenishment.enabled",
        "procurement.office-stock" to "hiltech.procurement.office-stock.enabled",
        "procurement.asset-parts" to "hiltech.procurement.asset-parts.enabled",
        "procurement.sourcing" to "hiltech.procurement.sourcing.enabled",
        "procurement.po-candidates" to "hiltech.procurement.po-candidates.enabled",
        "procurement.approval-request" to "hiltech.procurement.approval-request.enabled",
        "procurement.po-issue" to "hiltech.procurement.po-issue.enabled",
        "procurement.po-receiving" to "hiltech.procurement.po-receiving.enabled",
        "procurement.supplier-invoices" to "hiltech.procurement.supplier-invoices.enabled",
        "procurement.po-cancellation" to "hiltech.procurement.po-cancellation.enabled",
        "procurement.po.delivery-amendments" to "hiltech.procurement.po.delivery-amendments.enabled",
        "procurement.po.commercial-replacement" to "hiltech.procurement.po.commercial-replacement.enabled",
        "procurement.po.supplier-communications" to "hiltech.procurement.po.supplier-communications.enabled",
        "procurement.service-needs" to "hiltech.procurement.service-needs.enabled",
        "procurement.service-sourcing" to "hiltech.procurement.service-sourcing.enabled",
        "procurement.service-orders" to "hiltech.procurement.service-orders.enabled",
        "procurement.service-acceptance" to "hiltech.procurement.service-acceptance.enabled",
        "procurement.service-invoices" to "hiltech.procurement.service-invoices.enabled",
        "procurement.service-supplier-communications" to "hiltech.procurement.service-supplier-communications.enabled",
        "procurement.supplier-master-data" to "hiltech.procurement.supplier-master-data.enabled",
        "finance.employee-requests" to "hiltech.finance.employee-requests.enabled",
        "finance.employee-review" to "hiltech.finance.employee-review.enabled",
        "finance.advances.policy-review" to "hiltech.finance.advances.policy-review.enabled",
        "finance.imprests.policy-review" to "hiltech.finance.imprests.policy-review.enabled",
        "finance.expense-receipts.readiness" to "hiltech.finance.expense-receipts.readiness-enabled",
        "finance.expenses.documentation-review" to "hiltech.finance.expenses.documentation-review.enabled",
        "finance.employee-funding" to "hiltech.finance.employee-funding.enabled",
        "finance.employee-settlement" to "hiltech.finance.employee-settlement.enabled",
        "finance.expense-reimbursement" to "hiltech.finance.expense-reimbursement.enabled",
        "finance.payable-candidates" to "hiltech.finance.payable-candidates.enabled",
        "finance.supplier-accounting-review" to "hiltech.finance.supplier-accounting-review.enabled",
        "finance.payment-preflight" to "hiltech.finance.payment-preflight.enabled",
        "finance.ap-posting" to "hiltech.finance.ap-posting.enabled",
        "finance.ap-reversal" to "hiltech.finance.ap-reversal.enabled",
        "finance.ap-reporting" to "hiltech.finance.ap-reporting.enabled",
        "finance.ap-ar-balances" to "hiltech.finance.ap-ar-balances.enabled",
        "finance.project-sources" to "hiltech.finance.project-sources.enabled",
        "finance.receivables.invoice-candidate" to "hiltech.finance.receivables.invoice-candidate.enabled",
        "finance.receivables.invoice-issuance" to "hiltech.finance.receivables.invoice-issuance.enabled",
        "finance.receivables.sales-award-source-readiness" to "hiltech.finance.receivables.sales-award-source-readiness.enabled",
        "finance.receivables.ar-posting" to "hiltech.finance.receivables.ar-posting.enabled",
        "finance.cashbox" to "hiltech.finance.cashbox.enabled",
        "finance.cashbox.count" to "hiltech.finance.cashbox.count-enabled",
        "finance.cash-allocation" to "hiltech.finance.cash-allocation.enabled",
        "finance.cash-allocation-void" to "hiltech.finance.cash-allocation-void.enabled",
        "finance.bank-statement-intake" to "hiltech.finance.bank-statement-intake.enabled",
        "finance.bank-reconciliation.report" to "hiltech.finance.bank-reconciliation.report-enabled",
        "finance.bank-reconciliation.signoff" to "hiltech.finance.bank-reconciliation.signoff-enabled",
        "finance.gl-period-close" to "hiltech.finance.gl-period-close.enabled",
        "finance.payroll-review-intake" to "hiltech.finance.payroll-review-intake-enabled",
        "service.contract-source" to "hiltech.phase11.contract.enabled",
        "service.handover" to "hiltech.phase11.handover.enabled",
        "service.maintenance" to "hiltech.phase11.maintenance.enabled",
        "service.maintenance.assigned-findings" to "hiltech.phase11.maintenance.assigned-findings.enabled",
        "service.maintenance.review" to "hiltech.phase11.maintenance.review.enabled",
        "service.support" to "hiltech.phase11.support.enabled",
        "service.support.time" to "hiltech.phase11.support.time.enabled",
        "service.warranty" to "hiltech.phase11.warranty.enabled",
        "payroll.periods" to "hiltech.payroll.periods-enabled",
        "payroll.calculation" to "hiltech.payroll.draft-runs-enabled",
        "payroll.run-review" to "hiltech.payroll.approval-request-enabled",
        "payroll.release" to "hiltech.payroll.release-enabled",
        "payroll.finance-handoff" to "hiltech.payroll.finance-handoff-enabled",
        "payroll.finance-results" to "hiltech.payroll.finance-results-enabled",
        "payroll.payslips" to "hiltech.payroll.payslips-enabled",
    )

    // These are the additional Boolean gates explicitly checked by the
    // corresponding controller/service. Business policy and evidence readiness
    // remain source-owned and are deliberately absent from this transport.
    val prerequisites: Map<String, Set<String>> = mapOf(
        "procurement.warehouse-replenishment" to setOf("procurement.requirements"),
        "procurement.office-stock" to setOf("procurement.requirements"),
        "procurement.asset-parts" to setOf("procurement.requirements"),
        "procurement.po-candidates" to setOf("procurement.sourcing"),
        "procurement.approval-request" to setOf("procurement.po-candidates", "procurement.sourcing"),
        "procurement.service-sourcing" to setOf("procurement.service-needs"),
        "procurement.service-orders" to setOf("procurement.service-sourcing"),
        "procurement.service-acceptance" to setOf("procurement.service-orders"),
        "procurement.service-invoices" to setOf("procurement.service-orders"),
        "finance.advances.policy-review" to setOf("finance.employee-requests"),
        "finance.imprests.policy-review" to setOf("finance.employee-requests"),
        "finance.supplier-accounting-review" to setOf("finance.payable-candidates"),
        "finance.cashbox.count" to setOf("finance.cashbox"),
        "service.maintenance.assigned-findings" to setOf("service.maintenance"),
        "service.maintenance.review" to setOf("service.maintenance"),
        "service.support.time" to setOf("service.support"),
        "service.warranty" to setOf("service.support"),
    )
}

internal fun Environment.identityProductCapabilities(): IdentityProductCapabilitiesResponse {
    val configured = InternalOperationCatalog.properties.mapValues { (_, property) ->
        getProperty(property, Boolean::class.java, false)
    }
    val availability = configured.mapValues { (name, enabled) -> enabled &&
        InternalOperationCatalog.prerequisites[name].orEmpty().all { configured.getValue(it) }
    }
    return IdentityProductCapabilitiesResponse(
        executiveHome = availability.getValue("executive.home"),
        personalIssuedStock = availability.getValue("field.personal-issued-stock"),
        materialUsageReporting = availability.getValue("field.material-usage-report"),
        assignedAssetLookup = availability.getValue("field.assigned-asset-lookup"),
        engineeringTests = availability.getValue("field.engineering-tests"),
        operationAvailability = availability,
    )
}
