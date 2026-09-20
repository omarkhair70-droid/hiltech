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
                verificationState =
                    it.verificationState.name,
                evidenceId =
                    it.evidenceId,
                version =
                    it.version,
            )
        }

    override fun canManage(
        identityId: UUID,
        organizationId: UUID,
    ): Boolean =
        authorization.canManagePeople(
            actorUserId =
                identityId,
            organizationId =
                organizationId,
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
