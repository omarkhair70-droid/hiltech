package com.hiltech.server.people

import com.hiltech.server.documents.EmployeeDocumentEvidenceTargetPort
import com.hiltech.server.documents.EmployeeDocumentEvidenceTargetSnapshot
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class PeopleEmployeeDocumentEvidenceTargetAdapter(
    private val persistence:
        HrDocumentsPersistencePort,
    private val authorization:
        PeopleAuthorizationPort,
    private val selfServicePolicy:
        OnboardingSelfServicePolicyPort,
    private val clock: java.time.Clock,
) : EmployeeDocumentEvidenceTargetPort {
    override fun loadTarget(
        documentId: UUID,
    ): EmployeeDocumentEvidenceTargetSnapshot? =
        persistence.documentTarget(
            documentId,
        )?.let {
            EmployeeDocumentEvidenceTargetSnapshot(
                documentId =
                    it.documentId,
                organizationId =
                    it.organizationId,
                employeeId =
                    it.employeeId,
                documentTypeCode =
                    persistence
                        .loadDocument(
                            it.documentId,
                        )!!.documentTypeCode,
                verificationState =
                    it.verificationState.name,
                evidenceId =
                    it.evidenceId,
                version =
                    it.version,
            )
        }

    override fun canReserve(
        identityId: UUID,
        target:
            EmployeeDocumentEvidenceTargetSnapshot,
    ): Boolean =
        authorization.canManagePeople(
            actorUserId =
                identityId,
            organizationId =
                target.organizationId,
        ) ||
            selfServicePolicy
                .canSubmitEmployeeDocument(
                    identityId =
                        identityId,
                    organizationId =
                        target.organizationId,
                    employeeId =
                        target.employeeId,
                    documentTypeCode =
                        target.documentTypeCode,
                    at = clock.instant(),
                )

    override fun canAccess(
        identityId: UUID,
        target:
            EmployeeDocumentEvidenceTargetSnapshot,
    ): Boolean =
        authorization.canManagePeople(
            actorUserId =
                identityId,
            organizationId =
                target.organizationId,
        ) ||
            selfServicePolicy
                .canViewEmployeeDocument(
                    identityId =
                        identityId,
                    organizationId =
                        target.organizationId,
                    employeeId =
                        target.employeeId,
                    documentTypeCode =
                        target.documentTypeCode,
                    at = clock.instant(),
                )

    override fun attachEvidence(
        documentId: UUID,
        evidenceId: UUID,
        at: Instant,
    ): Boolean =
        persistence.attachEvidence(
            documentId =
                documentId,
            evidenceId =
                evidenceId,
            at = at,
        )
}
