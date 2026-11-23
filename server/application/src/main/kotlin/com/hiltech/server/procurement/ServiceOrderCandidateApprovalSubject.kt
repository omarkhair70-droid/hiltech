package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * ApprovalEngine canonical subject version guard, NOT a new approval subsystem.
 * A selected supplier quote cannot remain approvable when source, revision,
 * supplier link, evidence or deadline changes.
 */
@Component
class ServiceOrderCandidateApprovalSubject @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    @Value("\${hiltech.procurement.service-orders.enabled:false}")
    private val enabled: Boolean = false,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String) = subjectType == "SERVICE_ORDER_CANDIDATE"

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean {
        if (!enabled || !supports(subject.subjectType) || subject.subjectVersion != 1L)
            return false
        return jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1
              FROM procurement_service_order_candidate c
              JOIN procurement_service_rfq rfq ON rfq.id=c.rfq_id
                AND rfq.organization_id=c.organization_id
              JOIN procurement_service_need need ON need.id=rfq.service_need_id
                AND need.organization_id=c.organization_id
              JOIN organization org ON org.id=c.organization_id
              JOIN team t ON t.id=need.team_id
                AND t.organization_id=c.organization_id
              JOIN LATERAL (
                SELECT result_version,to_state FROM procurement_service_need_event e
                WHERE e.need_id=need.id ORDER BY result_version DESC LIMIT 1
              ) last ON true
              JOIN procurement_service_supplier_quote q ON q.id=c.quote_id
                AND q.rfq_id=rfq.id AND q.organization_id=c.organization_id
              JOIN procurement_service_quote_document_slot slot ON slot.id=q.slot_id
                AND slot.organization_id=c.organization_id
                AND slot.rfq_id=rfq.id AND slot.supplier_organization_id=q.supplier_organization_id
              JOIN evidence e ON e.id=q.evidence_id AND e.organization_id=c.organization_id
                AND e.target_id=slot.id AND e.target_type='SERVICE_QUOTE_DOCUMENT'
              JOIN evidence_upload_session us ON us.evidence_id=e.id
              JOIN organization supplier ON supplier.id=c.supplier_organization_id
              WHERE c.id=? AND c.organization_id=? AND c.version=1
                AND org.status='ACTIVE' AND org.organization_type='HILTECH'
                AND t.active=true AND t.version=need.team_version
                AND last.to_state='READY_FOR_SOURCING'
                AND last.result_version=rfq.service_need_version
                AND rfq.specification=c.service_specification
                AND rfq.acceptance_criteria=c.service_acceptance_criteria
                AND q.supplier_organization_id=c.supplier_organization_id
                AND q.currency=c.currency AND q.total_amount=c.total_amount
                AND (q.valid_until IS NULL OR q.valid_until>=?)
                AND supplier.organization_type='SUPPLIER' AND supplier.status='ACTIVE'
                AND (SELECT link.state FROM procurement_supplier_link_event link
                  WHERE link.organization_id=c.organization_id
                    AND link.supplier_organization_id=c.supplier_organization_id
                  ORDER BY link.revision_number DESC LIMIT 1)='ACTIVE'
                AND NOT EXISTS (
                  SELECT 1 FROM procurement_service_supplier_quote newer
                  WHERE newer.rfq_id=rfq.id
                    AND newer.supplier_organization_id=q.supplier_organization_id
                    AND newer.revision_number>q.revision_number
                )
                AND e.sha256=q.sha256 AND us.expected_sha256=q.sha256
                AND e.storage_state='READY' AND us.state='READY'
                AND e.finalized_at IS NOT NULL AND us.finalized_at IS NOT NULL
                AND e.classification_code='HIGHLY_RESTRICTED'
                AND e.client_visibility_mode='INTERNAL_ONLY'
              )
            """.trimIndent(), Boolean::class.java,
            subject.subjectId,subject.organizationId,
            java.time.LocalDate.now(clock.withZone(ZoneOffset.UTC)),
        ) == true
    }
}
