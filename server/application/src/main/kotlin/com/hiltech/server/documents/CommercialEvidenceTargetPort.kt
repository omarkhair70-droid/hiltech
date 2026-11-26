package com.hiltech.server.documents

import java.util.UUID

/**
 * Documents owns storage/finalization. A business module owns typed target
 * eligibility, tenant scope and actor authorization. No implicit file rights.
 */
data class CommercialEvidenceTargetSnapshot(
    val targetType: String,
    val targetId: UUID,
    val organizationId: UUID,
    val mayReserve: Boolean,
)

interface CommercialEvidenceTargetPort {
    fun loadTarget(targetType: String, targetId: UUID): CommercialEvidenceTargetSnapshot?
    fun canAccess(actorIdentityId: UUID, target: CommercialEvidenceTargetSnapshot): Boolean
}

object DisabledCommercialEvidenceTargetPort : CommercialEvidenceTargetPort {
    override fun loadTarget(targetType: String, targetId: UUID) = null
    override fun canAccess(actorIdentityId: UUID, target: CommercialEvidenceTargetSnapshot) = false
}
