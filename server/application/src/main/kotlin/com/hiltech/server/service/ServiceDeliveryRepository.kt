package com.hiltech.server.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal val deliveryJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }

@Repository
class ServiceDeliveryRepository(private val jdbc: JdbcTemplate) {
    internal fun lockProject(project: UUID, org: UUID): Boolean = jdbc.query(
        "SELECT id FROM project WHERE id=? AND organization_id=? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, project, org,
    ).isNotEmpty()

    /** Work source writers also lock their work header; preserve one source snapshot during approval. */
    internal fun lockWorkSources(project: UUID) {
        jdbc.query("SELECT id FROM work_order WHERE project_id=? ORDER BY id FOR SHARE", { rs, _ -> rs.getObject(1, UUID::class.java) }, project)
    }

    internal fun lockEvidence(ids: Collection<UUID>) {
        ids.sortedBy { it.toString() }.forEach { id ->
            jdbc.query("SELECT id FROM evidence WHERE id=? FOR SHARE", { rs, _ -> rs.getObject(1, UUID::class.java) }, id)
        }
    }

    internal fun slot(id: UUID): ServiceDeliveryDocumentSlot? = jdbc.query(
        "SELECT id,organization_id,project_id,purpose,title,created_by_user_id FROM service_delivery_document_slot WHERE id=?",
        { rs, _ -> ServiceDeliveryDocumentSlot(rs.uuid("id"), rs.uuid("organization_id"), rs.uuid("project_id"),
            rs.getString("purpose"), rs.getString("title"), rs.uuid("created_by_user_id")) }, id,
    ).singleOrNull()

    internal fun insertSlot(id: UUID, op: UUID, org: UUID, project: UUID, purpose: String, title: String, actor: UUID, at: Instant) {
        jdbc.update("""INSERT INTO service_delivery_document_slot(id,operation_id,organization_id,project_id,purpose,title,created_by_user_id,created_at)
            VALUES(?,?,?,?,?,?,?,?)""", id, op, org, project, purpose, title, actor, at.atOffset(ZoneOffset.UTC))
    }

    internal fun scopeChoices(project: UUID): List<ServiceDeliveryChoice> = jdbc.query(
        """SELECT s.id,s.site_code AS code,s.name AS title,'SITE' AS kind FROM project_site ps JOIN site s ON s.id=ps.site_id WHERE ps.project_id=?
           UNION ALL SELECT w.id,w.work_order_code,w.title,'WORK' FROM work_order w WHERE w.project_id=? AND w.lifecycle_state<>'CANCELLED'
           ORDER BY kind,code,title""", { rs, _ -> ServiceDeliveryChoice(rs.uuid("id").toString(), rs.getString("code"), rs.getString("title"), rs.getString("kind")) }, project, project,
    )

    internal fun tickets(project: UUID): List<ServiceDeliveryChoice> = jdbc.query(
        "SELECT id,title FROM service_support_ticket WHERE project_id=? ORDER BY created_at DESC LIMIT 500",
        { rs, _ -> ServiceDeliveryChoice(rs.uuid("id").toString(), "", rs.getString("title"), "TICKET") }, project,
    )

    internal fun ticketExists(org: UUID, project: UUID, ticket: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM service_support_ticket WHERE id=? AND project_id=? AND organization_id=?)",
        Boolean::class.java, ticket, project, org,
    ) == true

    internal fun ticketTitle(org: UUID, project: UUID, ticket: UUID): String? = jdbc.query(
        "SELECT title FROM service_support_ticket WHERE id=? AND project_id=? AND organization_id=?", { rs, _ -> rs.getString(1) }, ticket, project, org,
    ).singleOrNull()

    internal fun responseProofUsedForOtherRelease(org: UUID, project: UUID, proof: UUID, release: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM service_delivery_event e JOIN service_delivery_proposal p ON p.id=e.proposal_id
           WHERE p.organization_id=? AND p.project_id=? AND p.action='HANDOVER_CLIENT_RESPONSE'
             AND p.details->>'proofEvidenceId'=? AND p.details->>'releaseEventId'<>?)""",
        Boolean::class.java, org, project, proof.toString(), release.toString(),
    ) == true

    internal fun ticketCoverage(project: UUID, ticket: UUID): ServiceDeliveryEvent? = jdbc.query(
        "$EVENT_SELECT WHERE event.project_id=? AND p.action='WARRANTY_ASSESSMENT' AND p.details->>'ticketId'=? ORDER BY event.event_number DESC LIMIT 1",
        { rs, _ -> eventRow(rs) }, project, ticket.toString(),
    ).singleOrNull()

    internal fun name(identity: UUID): String = jdbc.query(
        "SELECT p.display_name FROM user_identity u JOIN person p ON p.id=u.person_id WHERE u.id=?",
        { rs, _ -> rs.getString(1) }, identity,
    ).singleOrNull().orEmpty()

    internal fun evidence(org: UUID, project: UUID, id: UUID, historical: Boolean = false): ServiceDeliveryEvidenceSource? = jdbc.query(
        "$EVIDENCE_SELECT WHERE e.id=? AND service_delivery_evidence_current(?,?,e.id,e.sha256) AND (? OR NOT EXISTS(SELECT 1 FROM evidence replacement WHERE replacement.supersedes_evidence_id=e.id AND replacement.storage_state='READY'))",
        { rs, _ -> evidenceRow(rs, project) }, id, org, project, historical,
    ).singleOrNull()

    internal fun readyDocuments(org: UUID, project: UUID): List<ServiceDeliveryEvidenceSource> = jdbc.query(
        "$EVIDENCE_SELECT WHERE e.organization_id=? AND service_delivery_evidence_current(?,?,e.id,e.sha256) AND NOT EXISTS(SELECT 1 FROM evidence replacement WHERE replacement.supersedes_evidence_id=e.id AND replacement.storage_state='READY') ORDER BY e.created_at DESC LIMIT 500",
        { rs, _ -> evidenceRow(rs, project) }, org, org, project,
    ).distinctBy { it.id }

    internal fun proposal(org: UUID, id: UUID): ServiceDeliveryProposal? = jdbc.query(
        "SELECT * FROM service_delivery_proposal WHERE organization_id=? AND id=?", { rs, _ -> proposalRow(rs) }, org, id,
    ).singleOrNull()

    internal fun proposals(org: UUID, project: UUID): List<ServiceDeliveryProposal> = jdbc.query(
        "SELECT * FROM service_delivery_proposal WHERE organization_id=? AND project_id=? ORDER BY prepared_at DESC,id DESC LIMIT 300",
        { rs, _ -> proposalRow(rs) }, org, project,
    )

    internal fun assigned(org: UUID, actor: UUID): List<ServiceDeliveryProposal> = jdbc.query(
        """SELECT p.* FROM service_delivery_proposal p WHERE p.organization_id=? AND p.reviewer_user_id=?
           AND EXISTS(SELECT 1 FROM approval_request a WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
             AND a.subject_version=p.review_version AND a.policy_key=p.policy_key AND a.state IN('PENDING','APPROVED'))
           AND NOT EXISTS(SELECT 1 FROM service_delivery_event recorded WHERE recorded.proposal_id=p.id)
           ORDER BY p.prepared_at,p.id LIMIT 500""", { rs, _ -> proposalRow(rs) }, org, actor,
    )

    internal fun status(p: ServiceDeliveryProposal): String = jdbc.query(
        """SELECT state FROM approval_request WHERE organization_id=? AND subject_id=? AND subject_version=? AND policy_key=?
           ORDER BY created_at DESC,id DESC LIMIT 1""", { rs, _ -> rs.getString(1) }, p.organizationId, p.id, p.reviewVersion, p.workflow.policyKey,
    ).singleOrNull() ?: "UNAVAILABLE"

    internal fun proposalProofs(p: ServiceDeliveryProposal): List<Pair<UUID, Pair<String, Boolean>>> = jdbc.query(
        "SELECT evidence_id,evidence_sha256,required_to_open FROM service_delivery_proposal_evidence WHERE proposal_id=? ORDER BY evidence_id",
        { rs, _ -> rs.uuid("evidence_id") to (rs.getString("evidence_sha256") to rs.getBoolean("required_to_open")) }, p.id,
    )

    internal fun insertProposal(p: ServiceDeliveryProposal, operation: UUID, proofs: List<ServiceDeliveryProposalProof>) {
        jdbc.update("""INSERT INTO service_delivery_proposal(id,operation_id,organization_id,project_id,action,stream_key,base_event_number,details,
            source_stamp,review_version,policy_key,policy_version,authority_binding_id,authority_binding_version,reviewer_user_id,
            prepared_by_user_id,prepared_at,project_name,project_code,client_name,scope_name,package_documents,package_findings)
            VALUES(?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))""",
            p.id, operation, p.organizationId, p.projectId, p.action, p.stream, p.baseEventNumber, deliveryJson.encodeToString(p.details),
            p.sourceStamp, p.reviewVersion, p.workflow.policyKey, p.policyVersion, p.bindingId, p.bindingVersion, p.reviewer,
            p.preparedBy, p.preparedAt.atOffset(ZoneOffset.UTC), p.projectName, p.projectCode, p.clientName, p.scopeName, deliveryJson.encodeToString(p.packageDocuments), deliveryJson.encodeToString(p.packageFindings),
        )
        proofs.forEach { proof -> jdbc.update(
            """INSERT INTO service_delivery_proposal_evidence(proposal_id,organization_id,project_id,evidence_id,evidence_sha256,required_to_open)
                VALUES(?,?,?,?,?,?)""", p.id, p.organizationId, p.projectId, proof.source.id, proof.source.sha256, proof.requiredToOpen,
        ) }
    }

    internal fun event(org: UUID, id: UUID): ServiceDeliveryEvent? = jdbc.query(
        "$EVENT_SELECT WHERE event.organization_id=? AND event.id=?", { rs, _ -> eventRow(rs) }, org, id,
    ).singleOrNull()

    internal fun recorded(p: ServiceDeliveryProposal): ServiceDeliveryEvent? = jdbc.query(
        "$EVENT_SELECT WHERE event.organization_id=? AND event.proposal_id=?", { rs, _ -> eventRow(rs) }, p.organizationId, p.id,
    ).singleOrNull()

    internal fun events(org: UUID, project: UUID): List<ServiceDeliveryEvent> = jdbc.query(
        "$EVENT_SELECT WHERE event.organization_id=? AND event.project_id=? ORDER BY event.recorded_at DESC,event.id DESC LIMIT 500",
        { rs, _ -> eventRow(rs) }, org, project,
    )

    internal fun latest(org: UUID, project: UUID, stream: String): ServiceDeliveryEvent? = jdbc.query(
        "$EVENT_SELECT WHERE event.organization_id=? AND event.project_id=? AND event.stream_key=? ORDER BY event.event_number DESC LIMIT 1",
        { rs, _ -> eventRow(rs) }, org, project, stream,
    ).singleOrNull()

    internal fun activeRelease(org: UUID, project: UUID): ServiceDeliveryEvent? {
        val latest = latest(org, project, "HANDOVER") ?: return null
        return when (latest.proposal.action) {
            "HANDOVER_RELEASE" -> latest
            "HANDOVER_WITHDRAW" -> null
            else -> latest.proposal.details.releaseEventId?.let { event(org, UUID.fromString(it)) }?.takeIf { it.proposal.projectId == project }
        }
    }

    internal fun currentTerms(org: UUID, project: UUID): List<ServiceDeliveryEvent> = jdbc.query(
        """SELECT * FROM ($EVENT_SELECT WHERE event.organization_id=? AND event.project_id=? AND p.action='WARRANTY_TERMS'
            AND NOT EXISTS(SELECT 1 FROM service_delivery_event later WHERE later.project_id=event.project_id
              AND later.stream_key=event.stream_key AND later.event_number>event.event_number)) terms ORDER BY recorded_at DESC""",
        { rs, _ -> eventRow(rs) }, org, project,
    )

    internal fun recordEvent(id: UUID, op: UUID, p: ServiceDeliveryProposal, approval: UUID, actor: UUID, at: Instant) {
        jdbc.update("""INSERT INTO service_delivery_event(id,operation_id,organization_id,project_id,proposal_id,stream_key,event_number,
            approval_request_id,recorded_by_user_id,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?)""",
            id, op, p.organizationId, p.projectId, p.id, p.stream, p.baseEventNumber + 1, approval, actor, at.atOffset(ZoneOffset.UTC))
    }

    internal fun viewed(actor: UUID, p: ServiceDeliveryProposal): List<String> = jdbc.query(
        """SELECT DISTINCT v.evidence_id FROM service_delivery_evidence_view v JOIN service_delivery_evidence_display d
           ON d.view_receipt_id=v.id AND d.viewer_user_id=v.viewer_user_id
           JOIN service_delivery_proposal_evidence proof ON proof.proposal_id=v.proposal_id AND proof.evidence_id=v.evidence_id AND proof.evidence_sha256=v.evidence_sha256
           WHERE v.proposal_id=? AND v.review_version=? AND v.viewer_user_id=?""",
        { rs, _ -> rs.uuid("evidence_id").toString() }, p.id, p.reviewVersion, actor,
    )

    internal fun recordView(id: UUID, actor: UUID, p: ServiceDeliveryProposal, evidence: ServiceDeliveryEvidenceSource, at: Instant) {
        jdbc.update("""INSERT INTO service_delivery_evidence_view(id,proposal_id,organization_id,project_id,review_version,evidence_id,
            evidence_sha256,viewer_user_id,viewed_at) VALUES(?,?,?,?,?,?,?,?,?)""",
            id, p.id, p.organizationId, p.projectId, p.reviewVersion, evidence.id, evidence.sha256, actor, at.atOffset(ZoneOffset.UTC))
    }

    internal fun deliveredProof(actor: UUID, p: ServiceDeliveryProposal, receipt: UUID): Pair<UUID, String>? = jdbc.query(
        """SELECT evidence_id,evidence_sha256 FROM service_delivery_evidence_view
           WHERE id=? AND proposal_id=? AND organization_id=? AND project_id=? AND review_version=? AND viewer_user_id=?""",
        { rs, _ -> rs.uuid("evidence_id") to rs.getString("evidence_sha256") }, receipt, p.id, p.organizationId, p.projectId, p.reviewVersion, actor,
    ).singleOrNull()

    internal fun confirmDisplayed(actor: UUID, receipt: UUID, at: Instant): Boolean = jdbc.update(
        "INSERT INTO service_delivery_evidence_display(view_receipt_id,viewer_user_id,displayed_at) VALUES(?,?,?) ON CONFLICT(view_receipt_id) DO NOTHING",
        receipt, actor, at.atOffset(ZoneOffset.UTC),
    ) == 1

    private fun proposalRow(rs: ResultSet) = ServiceDeliveryProposal(
        rs.uuid("id"), rs.uuid("organization_id"), rs.uuid("project_id"), rs.getString("action"), rs.getString("stream_key"), rs.getInt("base_event_number"),
        deliveryJson.decodeFromString(rs.getString("details")), rs.getString("source_stamp"), rs.getLong("review_version"), rs.getInt("policy_version"),
        rs.uuid("authority_binding_id"), rs.getLong("authority_binding_version"), rs.uuid("reviewer_user_id"), rs.uuid("prepared_by_user_id"),
        rs.getObject("prepared_at", OffsetDateTime::class.java).toInstant(), rs.getString("project_name"), rs.getString("project_code"),
        rs.getString("client_name"), rs.getString("scope_name"), deliveryJson.decodeFromString(rs.getString("package_documents")), deliveryJson.decodeFromString(rs.getString("package_findings")),
    )

    private fun eventRow(rs: ResultSet) = ServiceDeliveryEvent(rs.uuid("event_id"), proposalRow(rs), rs.getInt("event_number"),
        rs.uuid("recorded_by_user_id"), rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant())

    private fun evidenceRow(rs: ResultSet, project: UUID) = ServiceDeliveryEvidenceSource(
        rs.uuid("id"), rs.uuid("organization_id"), project, rs.getString("display_title"), rs.getString("purpose"), rs.getString("sha256"),
        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("object_key_ref"), rs.getString("target_type"), rs.uuid("target_id"),
        rs.getString("classification_code"), rs.getString("client_visibility_mode"),
    )

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private companion object {
        const val EVENT_SELECT = """SELECT p.*,event.id AS event_id,event.event_number,event.recorded_by_user_id,event.recorded_at
          FROM service_delivery_event event JOIN service_delivery_proposal p ON p.id=event.proposal_id"""
        const val EVIDENCE_SELECT = """SELECT e.*,COALESCE(slot.title,c.contract_code,d.title,e.original_file_name,e.evidence_type_code) AS display_title,
          CASE WHEN e.target_type='COMMERCIAL_CONTRACT' THEN 'AGREEMENT' WHEN slot.id IS NOT NULL THEN slot.purpose ELSE 'WORK_DOCUMENT' END AS purpose
          FROM evidence e LEFT JOIN service_delivery_document_slot slot ON e.target_type='SERVICE_DELIVERY_DOCUMENT' AND slot.id=e.target_id
          LEFT JOIN commercial_contract c ON e.target_type='COMMERCIAL_CONTRACT' AND c.id=e.target_id
          LEFT JOIN LATERAL(SELECT document.title FROM work_document_revision revision JOIN work_document document ON document.id=revision.document_id
             WHERE revision.evidence_id=e.id ORDER BY revision.revision_sequence DESC LIMIT 1) d ON true"""
    }
}
