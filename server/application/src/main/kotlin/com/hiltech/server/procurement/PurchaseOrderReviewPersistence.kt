package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PurchaseReviewRequestRecord(
    val id: UUID, val organizationId: UUID, val candidateId: UUID, val candidateVersion: Long,
    val policyKey: String, val policyRevision: Int, val requester: UUID, val state: String, val version: Long,
)
data class PurchaseReviewNames(val supplierName: String, val supplierCode: String, val itemName: String,
    val itemCode: String, val sourceKind: String, val sourceLabel: String, val preparedByName: String)
data class PurchaseQuoteDeliveryRecord(val id: UUID, val organizationId: UUID, val candidateId: UUID,
    val approvalRequestId: UUID, val approvalRequestVersion: Long, val policyRevision: Int,
    val quoteId: UUID, val evidenceId: UUID, val sha256: String, val reviewer: UUID, val deliveredAt: Instant)

@Repository
class PurchaseOrderReviewPersistence(private val jdbc: JdbcTemplate) {
    fun completedForActor(actor: UUID, requestId: UUID): Pair<UUID, PurchaseReviewCompletedDto>? = jdbc.query(
        """SELECT r.organization_id,r.subject_id,r.state,r.completed_at,
             EXISTS(SELECT 1 FROM approval_decision d WHERE d.approval_request_id=r.id AND d.actor_user_id=?) AS decided_by_actor
           FROM approval_request r JOIN procurement_po_candidate c ON c.id=r.subject_id AND c.organization_id=r.organization_id
           WHERE r.id=? AND r.subject_type='PROCUREMENT_PO_CANDIDATE' AND r.subject_version=1 AND r.completed_at IS NOT NULL
             AND ((r.state IN ('APPROVED','REJECTED','CHANGE_REQUESTED') AND EXISTS(
               SELECT 1 FROM approval_decision d WHERE d.approval_request_id=r.id AND d.actor_user_id=?
                 AND d.subject_version=r.subject_version AND r.state=CASE d.decision
                   WHEN 'APPROVE' THEN 'APPROVED' WHEN 'REJECT' THEN 'REJECTED' WHEN 'REQUEST_CHANGE' THEN 'CHANGE_REQUESTED' END))
               OR (r.state='SUPERSEDED' AND EXISTS(SELECT 1 FROM approval_assignment a WHERE a.approval_request_id=r.id
                 AND a.principal_type='USER' AND a.principal_user_id=? AND a.state='REVOKED')))""".trimIndent(),
        { rs, _ -> rs.getObject("organization_id", UUID::class.java) to PurchaseReviewCompletedDto(
            rs.getObject("subject_id", UUID::class.java).toString(), requestId.toString(), rs.getString("state"),
            rs.getObject("completed_at", OffsetDateTime::class.java).toInstant().toString(), rs.getBoolean("decided_by_actor")) },
        actor, requestId, actor, actor,
    ).singleOrNull()

    fun request(candidateId: UUID, requestId: UUID): PurchaseReviewRequestRecord? = jdbc.query(
        """SELECT a.id,a.organization_id,a.subject_id,a.subject_version,a.policy_key,a.policy_version,a.requester_user_id,a.state,a.version
           FROM approval_request a JOIN procurement_po_candidate c ON c.id=a.subject_id AND c.organization_id=a.organization_id
           WHERE c.id=? AND a.id=? AND a.subject_type='PROCUREMENT_PO_CANDIDATE' AND a.subject_version=1""".trimIndent(),
        { rs, _ -> PurchaseReviewRequestRecord(rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
            rs.getObject("subject_id", UUID::class.java), rs.getLong("subject_version"), rs.getString("policy_key"), rs.getInt("policy_version"),
            rs.getObject("requester_user_id", UUID::class.java), rs.getString("state"), rs.getLong("version")) }, candidateId, requestId,
    ).singleOrNull()

    fun names(candidateId: UUID): PurchaseReviewNames? = jdbc.query(
        """SELECT COALESCE(sp.display_name,o.display_name) AS supplier_name,
                  COALESCE(sp.supplier_code,o.organization_code) AS supplier_code,i.name AS item_name,i.item_code,
                  r.source_type,COALESCE(
                    CASE WHEN w.id IS NOT NULL THEN p.project_code || ' · ' || p.name || ' / ' || s.name || ' / ' || w.work_order_code || ' · ' || w.title END,
                    CASE WHEN l.id IS NOT NULL THEN l.code || ' · ' || l.name END,
                    CASE WHEN ast.id IS NOT NULL THEN ast.asset_code || ' · ' || COALESCE(ast.model,'') END,
                    t.name,r.justification) AS source_label,COALESCE(person.display_name,'') AS prepared_by_name
           FROM procurement_po_candidate c JOIN organization o ON o.id=c.supplier_organization_id
           LEFT JOIN procurement_supplier_profile sp ON sp.organization_id=c.organization_id AND sp.supplier_organization_id=o.id
           JOIN stock_item i ON i.id=c.stock_item_id AND i.organization_id=c.organization_id
           JOIN procurement_purchase_requirement r ON r.id=c.purchase_requirement_id AND r.organization_id=c.organization_id
           JOIN user_identity u ON u.id=c.selected_by_user_id LEFT JOIN person ON person.id=u.person_id
           LEFT JOIN work_order w ON w.id=r.work_order_id AND w.organization_id=c.organization_id
           LEFT JOIN project p ON p.id=w.project_id AND p.organization_id=c.organization_id
           LEFT JOIN site s ON s.id=w.site_id
           LEFT JOIN storage_location l ON l.id=r.warehouse_storage_location_id AND l.organization_id=c.organization_id
           LEFT JOIN asset ast ON ast.id=r.asset_id AND ast.organization_id=c.organization_id
           LEFT JOIN team t ON t.id=r.office_team_id AND t.organization_id=c.organization_id
           WHERE c.id=?""".trimIndent(),
        { rs, _ -> PurchaseReviewNames(rs.getString("supplier_name"), rs.getString("supplier_code"), rs.getString("item_name"),
            rs.getString("item_code"), rs.getString("source_type"), rs.getString("source_label"), rs.getString("prepared_by_name")) }, candidateId,
    ).singleOrNull()

    fun insertDelivery(record: PurchaseQuoteDeliveryRecord, correlationId: String) {
        check(jdbc.update("""INSERT INTO procurement_po_review_quote_delivery
            (id,organization_id,candidate_id,approval_request_id,approval_request_version,policy_version,quote_evidence_id,evidence_id,
             source_sha256,reviewer_identity_id,delivered_at,correlation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            record.id, record.organizationId, record.candidateId, record.approvalRequestId, record.approvalRequestVersion,
            record.policyRevision, record.quoteId, record.evidenceId, record.sha256, record.reviewer,
            record.deliveredAt.atOffset(ZoneOffset.UTC), correlationId) == 1)
    }

    fun delivery(id: UUID): PurchaseQuoteDeliveryRecord? = jdbc.query(
        "SELECT * FROM procurement_po_review_quote_delivery WHERE id=?", { rs, _ -> PurchaseQuoteDeliveryRecord(
            rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java), rs.getObject("candidate_id", UUID::class.java),
            rs.getObject("approval_request_id", UUID::class.java), rs.getLong("approval_request_version"), rs.getInt("policy_version"),
            rs.getObject("quote_evidence_id", UUID::class.java), rs.getObject("evidence_id", UUID::class.java), rs.getString("source_sha256"),
            rs.getObject("reviewer_identity_id", UUID::class.java), rs.getObject("delivered_at", OffsetDateTime::class.java).toInstant()) }, id,
    ).singleOrNull()

    fun insertOpening(id: UUID, operation: UUID, deliveryId: UUID, at: Instant, correlationId: String) {
        check(jdbc.update("INSERT INTO procurement_po_review_quote_opening(id,operation_id,delivery_id,opened_at,correlation_id) VALUES(?,?,?,?,?)",
            id, operation, deliveryId, at.atOffset(ZoneOffset.UTC), correlationId) == 1)
    }

    fun opened(actor: UUID, candidate: PurchaseOrderCandidateRecord, requestId: UUID, requestVersion: Long, policyRevision: Int): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM procurement_po_review_quote_opening o JOIN procurement_po_review_quote_delivery d ON d.id=o.delivery_id
           WHERE d.organization_id=? AND d.candidate_id=? AND d.approval_request_id=? AND d.approval_request_version=?
             AND d.policy_version=? AND d.quote_evidence_id=? AND d.source_sha256=? AND d.reviewer_identity_id=?)""".trimIndent(),
        Boolean::class.java, candidate.organizationId, candidate.id, requestId, requestVersion, policyRevision,
        candidate.quoteId, candidate.sourceSha256, actor,
    ) == true
}
