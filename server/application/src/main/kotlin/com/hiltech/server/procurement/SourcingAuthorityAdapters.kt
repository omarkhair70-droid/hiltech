package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Company-owned role bindings, never hard-coded employees, guessed thresholds,
 * UI-only permissions or user claims. All requests recheck current membership
 * and the authoritative ApprovalEngine team/user binding.
 */
@Component
@Primary
class CurrentBuyerSourcingAuthority @Autowired constructor(
    private val approvals: ApprovalPersistencePort,
    private val clock: Clock,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.sourcing.buyer-authority-key:}")
    private val buyerAuthorityKey: String = "",
) : SupplierSourcingAuthorizationPort {
    override fun canManageSourcing(actor: UUID, organizationId: UUID): Boolean =
        currentAuthority(actor, organizationId, buyerAuthorityKey)

    private fun currentAuthority(actor: UUID, org: UUID, rawKey: String): Boolean {
        val key = rawKey.trim()
        if (!enabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) return false
        val at = clock.instant()
        if (!approvals.isActiveOrganizationMember(org, actor, at)) return false
        val binding = approvals.resolveCurrentAuthority(org, key, at) ?: return false
        return binding.organizationId == org && binding.authorityKey == key &&
            approvals.actorMatchesAuthority(actor, binding, at)
    }
}

/**
 * Quote technical compliance is independently assigned, even when a buyer
 * can source. An RFQ ID cannot be used to reach another organization's quote.
 */
@Component
@Primary
class CurrentQuoteTechnicalReviewAuthority @Autowired constructor(
    private val approvals: ApprovalPersistencePort,
    private val sourcing: SupplierSourcingPersistence,
    private val clock: Clock,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.sourcing.technical-review-authority-key:}")
    private val technicalAuthorityKey: String = "",
) : SupplierQuoteTechnicalReviewerAuthorizationPort {
    override fun canReviewQuote(actor: UUID, organizationId: UUID, rfqId: UUID): Boolean {
        val key = technicalAuthorityKey.trim()
        if (!enabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) return false
        val rfq = sourcing.rfq(rfqId) ?: return false
        if (rfq.organizationId != organizationId) return false
        val at = clock.instant()
        if (!approvals.isActiveOrganizationMember(organizationId, actor, at)) return false
        val binding = approvals.resolveCurrentAuthority(organizationId, key, at) ?: return false
        return binding.organizationId == organizationId && binding.authorityKey == key &&
            approvals.actorMatchesAuthority(actor, binding, at)
    }
}
