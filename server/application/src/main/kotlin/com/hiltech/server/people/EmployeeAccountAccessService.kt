package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.EmployeeCredentialReplacementPort
import com.hiltech.server.identity.EmployeeCredentialReplacementResult
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class EmployeeAccountAccessService(
    private val employees: OnboardingPersistencePort,
    private val authority: PeopleAuthorizationPort,
    private val source: EmployeeAccountAccessPersistencePort,
    private val invitations: EmployeeIdentityInvitationPersistencePort,
    private val provider: EmployeeCredentialReplacementPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun availability(actor: UUID, employeeId: UUID, ownDisplayName: String?): EmployeeAccountAccessResponse {
        val employee = employee(employeeId)
        requirePeople(actor, employee.organizationId)
        val account = source.currentLinkedAccount(employee.personId, employee.organizationId, clock.instant())
        val own = if (employee.employeeState in eligible && account == null && !source.hasInvitation(employeeId) &&
            !source.personHasOtherAccount(employee.personId, null)) {
            source.currentAccount(actor, employee.organizationId, clock.instant())?.takeIf { it.personId == null }
        } else null
        return EmployeeAccountAccessResponse(employeeId.toString(), employee.organizationId.toString(), employee.employeeVersion,
            account?.reference(), employee.employeeState in eligible && account != null && provider.availableFor(account.issuer),
            own?.let { OwnAccountCandidateResponse(ownDisplayName?.trim()?.take(160)?.takeIf(String::isNotEmpty),
                it.identityVersion, it.membershipId.toString(), it.membershipVersion) },
            account?.let { source.latestReissue(it.identityId)?.summary() })
    }

    fun reissue(command: CredentialReissueCommand): CredentialReissueResponse {
        requireVersions(command.baseEmployeeVersion, command.baseIdentityVersion, command.baseMembershipVersion)
        if (command.reason.trim().length !in 1..500 ||
            (command.previousOperationId == null) != (command.previousOperationVersion == null) ||
            command.previousOperationVersion?.let { it < 1 } == true) {
            throw problem("ACCOUNT_CREDENTIAL_REQUEST_INVALID", "A reason and current account setup version are required.", HttpStatus.BAD_REQUEST)
        }
        val employee = employee(command.employeeId)
        requirePeople(command.actorUserId, employee.organizationId)
        requireLinkedAccount(employee, command.expectedIdentityId)
        val fingerprint = credentialFingerprint(command)
        return source.serializeCredentialReplacement(command.expectedIdentityId) {
            requirePeople(command.actorUserId, employee.organizationId)
            requireLinkedAccount(employee(command.employeeId), command.expectedIdentityId)
            source.reissue(command.operationId)?.let { previous ->
                if (previous.organizationId != employee.organizationId || previous.employeeId != command.employeeId ||
                    previous.identityId != command.expectedIdentityId || previous.actorUserId != command.actorUserId || previous.requestFingerprint != fingerprint) {
                    throw problem("IDEMPOTENCY_KEY_REUSED", "The operation belongs to a different account request.")
                }
                return@serializeCredentialReplacement response(previous, replayed = true)
            }
            val account = source.transaction {
                source.lockEmployeeAndAccount(command.employeeId, command.expectedIdentityId)
                val fresh = employee(command.employeeId)
                requirePeople(command.actorUserId, fresh.organizationId)
                requireEligible(fresh)
                val current = requireLinkedAccount(fresh, command.expectedIdentityId)
                requireExpected(command, fresh, current)
                val latest = source.latestReissue(current.identityId)
                if (latest?.operationId != command.previousOperationId || latest?.version != command.previousOperationVersion) {
                    throw problem("ACCOUNT_CREDENTIAL_HISTORY_CHANGED", "Account credentials were changed after this form was opened. Refresh before confirming a new issue.")
                }
                if (!provider.availableFor(current.issuer)) throw problem("ACCOUNT_CREDENTIAL_PROVIDER_UNAVAILABLE", "Credential replacement is currently unavailable.", HttpStatus.SERVICE_UNAVAILABLE)
                source.insertDispatch(command, fresh.organizationId, fingerprint, clock.instant())
                audit(command.actorUserId, command.operationId, "EMPLOYEE_CREDENTIAL_REISSUE_DISPATCHED", command.correlationId)
                current
            }
            // Dispatch intent is committed before the external operation. An identical retry
            // reads that intent; it never dispatches or generates a second credential.
            val result = try {
                val fresh = employee(command.employeeId)
                requirePeople(command.actorUserId, fresh.organizationId)
                requireEligible(fresh)
                requireExpected(command, fresh, requireLinkedAccount(fresh, command.expectedIdentityId))
                provider.replace(account.issuer, account.subject)
            } catch (failure: ProductApiException) {
                EmployeeCredentialReplacementResult.Rejected(failure.code)
            } catch (_: Exception) {
                EmployeeCredentialReplacementResult.Unconfirmed("ACCOUNT_CREDENTIAL_PROVIDER_OUTCOME_UNCONFIRMED")
            }
            val (state, safeCode) = when (result) {
                is EmployeeCredentialReplacementResult.Issued -> "ISSUED" to null
                is EmployeeCredentialReplacementResult.Rejected -> "REJECTED" to result.safeCode
                is EmployeeCredentialReplacementResult.Unconfirmed -> "UNCONFIRMED" to result.safeCode
            }
            val recorded = source.transaction {
                if (!source.finishDispatch(command.operationId, state, safeCode, clock.instant())) {
                    throw problem("ACCOUNT_CREDENTIAL_OUTCOME_UNCONFIRMED", "The credential outcome could not be recorded. Check this request before starting a replacement.", HttpStatus.SERVICE_UNAVAILABLE)
                }
                audit(command.actorUserId, command.operationId, "EMPLOYEE_CREDENTIAL_REISSUE_$state", command.correlationId)
                requireNotNull(source.reissue(command.operationId))
            }
            try {
                requirePeople(command.actorUserId, employee.organizationId)
                val fresh = employee(command.employeeId)
                requireEligible(fresh)
                requireExpected(command, fresh, requireLinkedAccount(fresh, command.expectedIdentityId))
            } catch (_: ProductApiException) {
                throw ProductApiException("ACCOUNT_CREDENTIAL_ACCESS_CHANGED_AFTER_DISPATCH",
                    "The request was recorded, but access changed before its result could be delivered.", HttpStatus.CONFLICT, retryable = true)
            }
            response(recorded, replayed = false, secret = (result as? EmployeeCredentialReplacementResult.Issued)?.temporaryCredential)
        }
    }

    fun linkOwnAccount(command: LinkOwnEmployeeAccountCommand): LinkOwnEmployeeAccountResponse {
        requireVersions(command.baseEmployeeVersion, command.baseIdentityVersion, command.baseMembershipVersion)
        val employee = employee(command.employeeId)
        requirePeople(command.actorUserId, employee.organizationId)
        requireOwnAccount(command.actorUserId, employee)
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("employeeId", command.employeeId.toString()); put("employeeVersion", command.baseEmployeeVersion)
            put("identityId", command.actorUserId.toString()); put("identityVersion", command.baseIdentityVersion)
            put("membershipId", command.expectedMembershipId.toString()); put("membershipVersion", command.baseMembershipVersion)
        }.toString())
        val result = invitations.serializeProvisioning(command.employeeId) {
            requirePeople(command.actorUserId, employee.organizationId)
            commands.execute(IdempotentCommandSpec(command.operationId, command.actorUserId, "EMPLOYEE_LINK_OWN_CURRENT_ACCOUNT", "EMPLOYEE",
                command.employeeId, fingerprint, command.correlationId)) {
                source.lockEmployeeAndAccount(command.employeeId, command.actorUserId)
                val fresh = employee(command.employeeId)
                requirePeople(command.actorUserId, fresh.organizationId)
                requireEligible(fresh)
                val own = requireOwnAccount(command.actorUserId, fresh)
                if (fresh.employeeVersion != command.baseEmployeeVersion || own.identityVersion != command.baseIdentityVersion ||
                    own.membershipId != command.expectedMembershipId || own.membershipVersion != command.baseMembershipVersion) {
                    throw problem("ACCOUNT_LINK_SOURCE_CHANGED", "The employee or current account changed. Refresh before linking.")
                }
                if (source.personHasOtherAccount(fresh.personId, own.identityId) || source.hasInvitation(fresh.employeeId)) {
                    throw problem("ACCOUNT_LINK_CONFLICT", "An existing account or account setup already belongs to this employee.")
                }
                if (own.personId == null) {
                    if (!source.linkOwnAccount(fresh, own, clock.instant())) throw problem("ACCOUNT_LINK_SOURCE_CHANGED", "The employee or account changed while linking.")
                    audit.append(AuditEventRecord(actorUserId = command.actorUserId, action = "EMPLOYEE_OWN_ACCOUNT_LINKED",
                        targetType = "EMPLOYEE", targetId = fresh.employeeId, safeDiffJson = """{"fields":["identityLink","employeeVersion"]}""",
                        occurredAt = clock.instant(), correlationId = command.correlationId))
                    events.publishEvent(EmployeeIdentityLinked(employeeId = fresh.employeeId, organizationId = fresh.organizationId,
                        sourceVersion = fresh.employeeVersion + 1, actorUserId = command.actorUserId,
                        occurredAt = clock.instant(), correlationId = command.correlationId))
                }
                IdempotentCommandOutcome("EMPLOYEE_OWN_ACCOUNT_LINKED")
            }
        }
        val current = employee(command.employeeId)
        val linked = source.currentAccount(command.actorUserId, current.organizationId, clock.instant())?.personId == current.personId
        return LinkOwnEmployeeAccountResponse(command.operationId.toString(), command.employeeId.toString(), current.organizationId.toString(),
            current.employeeVersion, linked, result.replayed)
    }

    private fun requireOwnAccount(actor: UUID, employee: OnboardingEmployeeContext): CurrentEmployeeAccount =
        source.currentAccount(actor, employee.organizationId, clock.instant())?.takeIf { it.personId == null || it.personId == employee.personId }
            ?: throw problem("ACCOUNT_LINK_CURRENT_IDENTITY_UNAVAILABLE", "Your current account is restricted or already linked to another person.")
    private fun requireLinkedAccount(employee: OnboardingEmployeeContext, identity: UUID): CurrentEmployeeAccount =
        source.currentAccount(identity, employee.organizationId, clock.instant())?.takeIf { it.personId == employee.personId }
            ?: throw problem("EMPLOYEE_ACCOUNT_RESTRICTED", "A current account linked to this employee and company is required.")
    private fun requireExpected(command: CredentialReissueCommand, employee: OnboardingEmployeeContext, account: CurrentEmployeeAccount) {
        if (employee.employeeVersion != command.baseEmployeeVersion || account.identityVersion != command.baseIdentityVersion ||
            account.membershipId != command.expectedMembershipId || account.membershipVersion != command.baseMembershipVersion) {
            throw problem("ACCOUNT_CREDENTIAL_SOURCE_CHANGED", "The employee account or membership changed. Refresh before issuing a new credential.")
        }
    }
    private fun requireEligible(employee: OnboardingEmployeeContext) {
        if (employee.employeeState !in eligible) throw problem("EMPLOYEE_ACCOUNT_NOT_ELIGIBLE", "The employee is not eligible for account setup.")
    }
    private fun requireVersions(vararg versions: Long) {
        if (versions.any { it < 1 }) throw problem("ACCOUNT_ACCESS_INVALID_VERSION", "Current account versions are required.", HttpStatus.BAD_REQUEST)
    }
    private fun requirePeople(actor: UUID, organizationId: UUID) {
        if (!authority.canManagePeople(actor, organizationId)) throw problem("PEOPLE_ACCESS_DENIED", "Current People administration authority is required.", HttpStatus.FORBIDDEN)
    }
    private fun employee(id: UUID) = employees.employee(id)
        ?: throw problem("EMPLOYEE_NOT_FOUND", "The employee is unavailable.", HttpStatus.NOT_FOUND)
    private fun audit(actor: UUID, operation: UUID, action: String, correlation: String) = audit.append(AuditEventRecord(
        actorUserId = actor, action = action, targetType = "EMPLOYEE_CREDENTIAL_REISSUE", targetId = operation,
        safeDiffJson = """{"fields":["identityId","membershipId","reason","state"]}""", occurredAt = clock.instant(), correlationId = correlation))
    private fun credentialFingerprint(command: CredentialReissueCommand) = IdempotencyKeyContract.fingerprint(buildJsonObject {
        put("employeeId", command.employeeId.toString()); put("employeeVersion", command.baseEmployeeVersion)
        put("identityId", command.expectedIdentityId.toString()); put("identityVersion", command.baseIdentityVersion)
        put("membershipId", command.expectedMembershipId.toString()); put("membershipVersion", command.baseMembershipVersion)
        put("previousOperationId", command.previousOperationId?.toString()); command.previousOperationVersion?.let { put("previousOperationVersion", it) }
        put("reason", command.reason.trim())
    }.toString())
    private fun response(record: CredentialReissueRecord, replayed: Boolean, secret: String? = null) = CredentialReissueResponse(
        record.operationId.toString(), record.employeeId.toString(), record.organizationId.toString(), record.identityId.toString(),
        record.state.takeUnless { it == "DISPATCHING" } ?: "UNCONFIRMED", record.version, replayed, secret, record.safeFailureCode)
    private fun CredentialReissueRecord.summary() = CredentialReissueSummaryResponse(operationId.toString(),
        state.takeUnless { it == "DISPATCHING" } ?: "UNCONFIRMED", version, completedAt?.toString())
    private fun CurrentEmployeeAccount.reference() = EmployeeAccountReferenceResponse(identityId.toString(), identityVersion, membershipId.toString(), membershipVersion)
    private fun problem(code: String, message: String, status: HttpStatus = HttpStatus.CONFLICT) = ProductApiException(code, message, status)
    companion object { private val eligible = setOf(EmployeeState.PREBOARDING, EmployeeState.ACTIVE) }
}
