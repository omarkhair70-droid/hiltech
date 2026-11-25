package com.hiltech.server.procurement

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

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
    @Value("\${hiltech.procurement.sourcing.buyer-authority-key:PROCUREMENT_BUYER}")
    private val buyerAuthorityKey: String = "",
    private val operationalAuthority: OperationalAuthorityPort? = null,
) : SupplierSourcingAuthorizationPort {
    override fun canManageSourcing(actor: UUID, organizationId: UUID): Boolean =
        currentAuthority(actor, organizationId, buyerAuthorityKey)

    private fun currentAuthority(actor: UUID, org: UUID, rawKey: String): Boolean {
        if (!enabled) return false
        return currentOperationalAuthority(operationalAuthority, approvals, actor, org,
            "hiltech.procurement.sourcing.buyer-authority-key", rawKey, clock.instant()) != null
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
    @Value("\${hiltech.procurement.sourcing.technical-review-authority-key:PROCUREMENT_TECHNICAL_REVIEW}")
    private val technicalAuthorityKey: String = "",
    private val operationalAuthority: OperationalAuthorityPort? = null,
) : SupplierQuoteTechnicalReviewerAuthorizationPort {
    override fun canReviewOrganization(actor: UUID, organizationId: UUID): Boolean = enabled &&
        currentOperationalAuthority(operationalAuthority, approvals, actor, organizationId,
            "hiltech.procurement.sourcing.technical-review-authority-key", technicalAuthorityKey, clock.instant()) != null

    override fun canReviewQuote(actor: UUID, organizationId: UUID, rfqId: UUID): Boolean {
        if (!enabled) return false
        val rfq = sourcing.rfq(rfqId) ?: return false
        if (rfq.organizationId != organizationId) return false
        return canReviewOrganization(actor, organizationId)
    }
}
