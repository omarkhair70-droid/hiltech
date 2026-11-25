package com.hiltech.server.service

import com.hiltech.server.documents.ServiceDeliveryDocumentEvidenceTarget
import com.hiltech.server.documents.ServiceDeliveryDocumentEvidenceTargetPort
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Reviewers use the proposal-scoped protected reader, not this project-wide document port. */
@Component
class ServiceDeliveryDocumentTarget(
    private val repository: ServiceDeliveryRepository,
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val clock: Clock,
) : ServiceDeliveryDocumentEvidenceTargetPort {
    override fun loadTarget(slotId: UUID): ServiceDeliveryDocumentEvidenceTarget? = repository.slot(slotId)?.let {
        ServiceDeliveryDocumentEvidenceTarget(it.id, it.organizationId, it.projectId, it.purpose, it.createdBy)
    }
    override fun canReserve(actor: UUID, target: ServiceDeliveryDocumentEvidenceTarget): Boolean = actor == target.createdBy && canAccess(actor, target)
    override fun canAccess(actor: UUID, target: ServiceDeliveryDocumentEvidenceTarget): Boolean =
        projects.project(target.projectId, clock.instant())?.takeIf { it.organizationId == target.organizationId }
            ?.let { authorization.canManageProject(actor, it) } == true
}
