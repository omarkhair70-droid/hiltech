package com.hiltech.server.people

import java.time.Instant
import java.util.UUID

data class CurrentEmployeeAccount(
    val identityId: UUID, val identityVersion: Long, val personId: UUID?,
    val organizationId: UUID, val membershipId: UUID, val membershipVersion: Long,
    val issuer: String, val subject: String,
)
data class CredentialReissueCommand(
    val operationId: UUID, val employeeId: UUID, val baseEmployeeVersion: Long,
    val expectedIdentityId: UUID, val baseIdentityVersion: Long,
    val expectedMembershipId: UUID, val baseMembershipVersion: Long,
    val previousOperationId: UUID?, val previousOperationVersion: Long?, val reason: String,
    val actorUserId: UUID, val correlationId: String,
)
data class CredentialReissueRecord(
    val operationId: UUID, val organizationId: UUID, val employeeId: UUID, val identityId: UUID,
    val actorUserId: UUID, val requestFingerprint: String, val state: String,
    val safeFailureCode: String?, val createdAt: Instant, val completedAt: Instant?, val version: Long,
)
data class LinkOwnEmployeeAccountCommand(
    val operationId: UUID, val employeeId: UUID, val baseEmployeeVersion: Long,
    val baseIdentityVersion: Long, val expectedMembershipId: UUID, val baseMembershipVersion: Long,
    val actorUserId: UUID, val correlationId: String,
)

interface EmployeeAccountAccessPersistencePort {
    fun <T : Any> transaction(action: () -> T): T
    fun <T : Any> serializeCredentialReplacement(identityId: UUID, action: () -> T): T
    fun lockEmployeeAndAccount(employeeId: UUID, identityId: UUID)
    fun currentAccount(identityId: UUID, organizationId: UUID, at: Instant): CurrentEmployeeAccount?
    fun currentLinkedAccount(personId: UUID, organizationId: UUID, at: Instant): CurrentEmployeeAccount?
    fun personHasOtherAccount(personId: UUID, exceptIdentityId: UUID?): Boolean
    fun hasInvitation(employeeId: UUID): Boolean
    fun linkOwnAccount(employee: OnboardingEmployeeContext, account: CurrentEmployeeAccount, at: Instant): Boolean
    fun reissue(operationId: UUID): CredentialReissueRecord?
    fun latestReissue(identityId: UUID): CredentialReissueRecord?
    fun insertDispatch(command: CredentialReissueCommand, organizationId: UUID, fingerprint: String, at: Instant)
    fun finishDispatch(operationId: UUID, state: String, safeCode: String?, at: Instant): Boolean
}

data class EmployeeAccountReferenceResponse(val identityId: String, val identityVersion: Long, val membershipId: String, val membershipVersion: Long)
data class OwnAccountCandidateResponse(val displayName: String?, val identityVersion: Long, val membershipId: String, val membershipVersion: Long)
data class CredentialReissueSummaryResponse(val operationId: String, val status: String, val version: Long, val completedAt: String?)
data class EmployeeAccountAccessResponse(
    val employeeId: String, val organizationId: String, val employeeVersion: Long,
    val account: EmployeeAccountReferenceResponse?, val canReissue: Boolean,
    val ownAccountCandidate: OwnAccountCandidateResponse?, val latestReissue: CredentialReissueSummaryResponse?,
)
data class CredentialReissueResponse(
    val operationId: String, val employeeId: String, val organizationId: String, val identityId: String,
    val status: String, val version: Long, val replayed: Boolean, val temporaryCredential: String?, val safeFailureCode: String?,
) {
    override fun toString() = "CredentialReissueResponse(operationId=$operationId,status=$status,credential=redacted)"
}
data class LinkOwnEmployeeAccountResponse(val operationId: String, val employeeId: String, val organizationId: String,
    val employeeVersion: Long, val linked: Boolean, val replayed: Boolean)
