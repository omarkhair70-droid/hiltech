package com.hiltech.server.procurement

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal val technicalJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }
internal data class SupplierTechnicalQuoteSource(
    val id: UUID, val organizationId: UUID, val rfqId: UUID, val supplierId: UUID, val supplierName: String, val supplierCode: String,
    val reference: String, val revision: Int, val quantity: BigDecimal, val unit: String, val itemId: UUID, val itemName: String, val itemCode: String,
    val requirementId: UUID, val needVersion: Long, val requestedQuantity: BigDecimal, val requestedUnit: String, val sourceLabel: String, val latest: Boolean,
)
internal data class SupplierTechnicalDocumentSlot(val id: UUID, val organizationId: UUID, val quoteId: UUID, val rfqId: UUID, val title: String, val creator: UUID)
internal data class SupplierTechnicalEvidenceSource(val id: UUID, val organizationId: UUID, val quoteId: UUID, val slotId: UUID, val title: String,
    val contentType: String, val size: Long, val sha: String, val key: String) {
    fun dto() = SupplierTechnicalEvidenceDto(id.toString(), title, contentType, size, sha)
}
internal data class SupplierTechnicalPacket(
    val id: UUID, val organizationId: UUID, val quoteId: UUID, val rfqId: UUID, val revision: Int, val needVersion: Long,
    val need: SupplierTechnicalQuoteDto, val details: SupplierTechnicalPacketFields, val hash: String, val creator: UUID, val createdAt: Instant,
)

@Repository
class SupplierTechnicalReviewRepository(private val jdbc: JdbcTemplate) {
    internal fun quote(id: UUID): SupplierTechnicalQuoteSource? = jdbc.query("$QUOTE_SELECT WHERE q.id=?", { rs, _ -> quoteRow(rs) }, id).singleOrNull()
    internal fun queue(org: UUID, search: String, offset: Int, limit: Int): List<SupplierTechnicalQuoteSource> = jdbc.query(
        """$QUOTE_SELECT WHERE q.organization_id=? AND NOT EXISTS(SELECT 1 FROM procurement_supplier_quote_evidence newer
            WHERE newer.rfq_id=q.rfq_id AND newer.supplier_organization_id=q.supplier_organization_id AND newer.revision_number>q.revision_number)
          AND position(lower(?) in lower(concat_ws(' ',i.name,i.item_code,coalesce(profile.display_name,supplier.display_name),coalesce(profile.supplier_code,supplier.organization_code),q.supplier_quote_reference)))>0
          ORDER BY q.recorded_at DESC,q.id DESC LIMIT ? OFFSET ?""",
        { rs, _ -> quoteRow(rs) }, org, search, limit, offset,
    )
    internal fun slot(id: UUID): SupplierTechnicalDocumentSlot? = jdbc.query(
        "SELECT * FROM procurement_supplier_technical_document_slot WHERE id=?",
        { rs, _ -> SupplierTechnicalDocumentSlot(rs.id("id"), rs.id("organization_id"), rs.id("quote_id"), rs.id("rfq_id"), rs.getString("title"), rs.id("created_by_user_id")) }, id,
    ).singleOrNull()
    internal fun insertSlot(id: UUID, operation: UUID, quote: SupplierTechnicalQuoteSource, title: String, actor: UUID, at: Instant) {
        jdbc.update("""INSERT INTO procurement_supplier_technical_document_slot(id,operation_id,organization_id,quote_id,rfq_id,title,created_by_user_id,created_at)
          VALUES (?,?,?,?,?,?,?,?)""", id, operation, quote.organizationId, quote.id, quote.rfqId, title, actor, at.atOffset(ZoneOffset.UTC))
    }
    internal fun evidence(org: UUID, quote: UUID, id: UUID): SupplierTechnicalEvidenceSource? = jdbc.query(
        "$EVIDENCE_SELECT AND e.id=? AND e.organization_id=? AND slot.quote_id=?",
        { rs, _ -> evidenceRow(rs) }, id, org, quote,
    ).singleOrNull()
    internal fun readyDocuments(org: UUID, quote: UUID): List<SupplierTechnicalEvidenceSource> = jdbc.query(
        "$EVIDENCE_SELECT AND e.organization_id=? AND slot.quote_id=? ORDER BY slot.created_at DESC,e.id DESC LIMIT 200",
        { rs, _ -> evidenceRow(rs) }, org, quote,
    )
    internal fun packets(quote: UUID): List<SupplierTechnicalPacket> = jdbc.query(
        "SELECT * FROM procurement_supplier_technical_packet WHERE quote_id=? ORDER BY packet_revision DESC", { rs, _ -> packetRow(rs) }, quote,
    )
    internal fun latest(quote: UUID): SupplierTechnicalPacket? = jdbc.query(
        "SELECT * FROM procurement_supplier_technical_packet WHERE quote_id=? ORDER BY packet_revision DESC LIMIT 1", { rs, _ -> packetRow(rs) }, quote,
    ).singleOrNull()
    internal fun packet(org: UUID, quote: UUID, id: UUID): SupplierTechnicalPacket? = jdbc.query(
        "SELECT * FROM procurement_supplier_technical_packet WHERE id=? AND quote_id=? AND organization_id=?", { rs, _ -> packetRow(rs) }, id, quote, org,
    ).singleOrNull()
    internal fun insertPacket(packet: SupplierTechnicalPacket, operation: UUID, proofs: List<SupplierTechnicalEvidenceSource>) {
        jdbc.update("""INSERT INTO procurement_supplier_technical_packet(id,operation_id,organization_id,quote_id,rfq_id,packet_revision,need_version,need_snapshot,details,source_hash,created_by_user_id,created_at)
           VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)""", packet.id, operation, packet.organizationId, packet.quoteId, packet.rfqId, packet.revision, packet.needVersion,
            technicalJson.encodeToString(packet.need), technicalJson.encodeToString(packet.details), packet.hash, packet.creator, packet.createdAt.atOffset(ZoneOffset.UTC))
        proofs.forEach { proof -> jdbc.update("""INSERT INTO procurement_supplier_technical_packet_evidence(packet_id,quote_id,organization_id,evidence_id,evidence_sha256)
           VALUES (?,?,?,?,?)""", packet.id, packet.quoteId, packet.organizationId, proof.id, proof.sha) }
    }
    internal fun proofReferences(packet: UUID): List<Pair<UUID, String>> = jdbc.query(
        "SELECT evidence_id,evidence_sha256 FROM procurement_supplier_technical_packet_evidence WHERE packet_id=? ORDER BY evidence_id", { rs, _ -> rs.id("evidence_id") to rs.getString("evidence_sha256") }, packet,
    )
    internal fun history(quote: UUID): List<SupplierTechnicalAssessmentHistoryDto> = jdbc.query(
        """SELECT a.*,p.packet_revision,coalesce(person.display_name,'') AS reviewer_name FROM procurement_supplier_quote_technical_assessment a
          LEFT JOIN procurement_supplier_technical_packet p ON p.id=a.technical_packet_id
          JOIN user_identity u ON u.id=a.reviewed_by_user_id LEFT JOIN person ON person.id=u.person_id
          WHERE a.quote_evidence_id=? ORDER BY a.review_revision DESC""",
        { rs, _ -> SupplierTechnicalAssessmentHistoryDto(rs.id("id").toString(), rs.getInt("review_revision"), rs.getString("decision"), rs.getString("justification"),
            rs.getString("reviewer_name"), rs.getObject("reviewed_at", OffsetDateTime::class.java).toInstant().toString(), rs.getObject("technical_packet_id", UUID::class.java)?.toString(),
            (rs.getObject("packet_revision") as? Number)?.toInt(), rs.getBoolean("packet_reviewed")) }, quote,
    )
    internal fun name(actor: UUID): String = jdbc.query("SELECT p.display_name FROM user_identity u JOIN person p ON p.id=u.person_id WHERE u.id=?", { rs, _ -> rs.getString(1) }, actor).singleOrNull().orEmpty()
    internal fun recordView(id: UUID, actor: UUID, packet: SupplierTechnicalPacket, proof: SupplierTechnicalEvidenceSource, at: Instant) {
        jdbc.update("""INSERT INTO procurement_supplier_technical_evidence_view(id,packet_id,quote_id,organization_id,evidence_id,evidence_sha256,viewer_user_id,delivered_at)
            VALUES (?,?,?,?,?,?,?,?)""", id, packet.id, packet.quoteId, packet.organizationId, proof.id, proof.sha, actor, at.atOffset(ZoneOffset.UTC))
    }
    internal fun deliveredProof(actor: UUID, packet: SupplierTechnicalPacket, receipt: UUID): Pair<UUID, String>? = jdbc.query(
        """SELECT evidence_id,evidence_sha256 FROM procurement_supplier_technical_evidence_view WHERE id=? AND packet_id=? AND quote_id=? AND organization_id=? AND viewer_user_id=?""",
        { rs, _ -> rs.id("evidence_id") to rs.getString("evidence_sha256") }, receipt, packet.id, packet.quoteId, packet.organizationId, actor,
    ).singleOrNull()
    internal fun confirmDisplayed(actor: UUID, receipt: UUID, at: Instant): Boolean =
        jdbc.update("INSERT INTO procurement_supplier_technical_evidence_display(view_receipt_id,viewer_user_id,displayed_at) VALUES (?,?,?) ON CONFLICT(view_receipt_id) DO NOTHING", receipt, actor, at.atOffset(ZoneOffset.UTC)) == 1
    internal fun displayed(actor: UUID, packet: SupplierTechnicalPacket): List<String> = jdbc.query(
        """SELECT DISTINCT v.evidence_id FROM procurement_supplier_technical_evidence_view v
           JOIN procurement_supplier_technical_evidence_display d ON d.view_receipt_id=v.id AND d.viewer_user_id=v.viewer_user_id
           JOIN procurement_supplier_technical_packet_evidence proof ON proof.packet_id=v.packet_id AND proof.evidence_id=v.evidence_id AND proof.evidence_sha256=v.evidence_sha256
           WHERE v.packet_id=? AND v.quote_id=? AND v.organization_id=? AND v.viewer_user_id=?""",
        { rs, _ -> rs.id("evidence_id").toString() }, packet.id, packet.quoteId, packet.organizationId, actor,
    )
    internal fun lockEvidence(ids: List<UUID>) { ids.sorted().forEach { jdbc.query("SELECT id FROM evidence WHERE id=? FOR UPDATE", { rs, _ -> rs.id("id") }, it) } }
    private fun quoteRow(rs: ResultSet) = SupplierTechnicalQuoteSource(rs.id("id"), rs.id("organization_id"), rs.id("rfq_id"), rs.id("supplier_organization_id"),
        rs.getString("supplier_name"), rs.getString("supplier_code"), rs.getString("supplier_quote_reference"), rs.getInt("revision_number"), rs.getBigDecimal("quoted_quantity"), rs.getString("quoted_unit_code"),
        rs.id("stock_item_id"), rs.getString("item_name"), rs.getString("item_code"), rs.id("requirement_id"), rs.getLong("requirement_version"), rs.getBigDecimal("source_quantity"), rs.getString("source_unit_code"),
        rs.getString("source_label"), rs.getBoolean("latest_quote"))
    private fun packetRow(rs: ResultSet) = SupplierTechnicalPacket(rs.id("id"), rs.id("organization_id"), rs.id("quote_id"), rs.id("rfq_id"), rs.getInt("packet_revision"), rs.getLong("need_version"),
        technicalJson.decodeFromString(rs.getString("need_snapshot")), technicalJson.decodeFromString(rs.getString("details")), rs.getString("source_hash"), rs.id("created_by_user_id"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant())
    private fun evidenceRow(rs: ResultSet) = SupplierTechnicalEvidenceSource(rs.id("id"), rs.id("organization_id"), rs.id("quote_id"), rs.id("target_id"), rs.getString("title"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("object_key_ref"))
    private fun ResultSet.id(column: String) = getObject(column, UUID::class.java)
    private companion object {
        const val QUOTE_SELECT = """SELECT q.id,q.organization_id,q.rfq_id,q.supplier_organization_id,q.supplier_quote_reference,q.revision_number,q.quoted_quantity,q.quoted_unit_code,
          r.stock_item_id,r.requirement_id,r.requirement_version,r.source_quantity,r.source_unit_code,i.name AS item_name,i.item_code,
          coalesce(profile.display_name,supplier.display_name) AS supplier_name,coalesce(profile.supplier_code,supplier.organization_code) AS supplier_code,
          coalesce(work.title,concat_ws(' · ',destination.code,destination.name),'') AS source_label,NOT EXISTS(SELECT 1 FROM procurement_supplier_quote_evidence n WHERE n.rfq_id=q.rfq_id AND n.supplier_organization_id=q.supplier_organization_id AND n.revision_number>q.revision_number) AS latest_quote
          FROM procurement_supplier_quote_evidence q JOIN procurement_supplier_rfq r ON r.id=q.rfq_id AND r.organization_id=q.organization_id
          JOIN stock_item i ON i.id=r.stock_item_id AND i.organization_id=r.organization_id
          JOIN organization supplier ON supplier.id=q.supplier_organization_id
          LEFT JOIN procurement_supplier_profile profile ON profile.organization_id=q.organization_id AND profile.supplier_organization_id=q.supplier_organization_id
          JOIN procurement_purchase_requirement need ON need.id=r.requirement_id AND need.organization_id=r.organization_id
          LEFT JOIN work_order work ON work.id=need.work_order_id AND work.organization_id=need.organization_id
          LEFT JOIN storage_location destination ON destination.id=coalesce(need.warehouse_storage_location_id,need.asset_delivery_location_id,need.office_delivery_location_id)
            AND destination.organization_id=need.organization_id"""
        const val EVIDENCE_SELECT = """SELECT e.id,e.organization_id,e.target_id,e.content_type,e.size_bytes,e.sha256,e.object_key_ref,slot.quote_id,slot.title
          FROM evidence e JOIN procurement_supplier_technical_document_slot slot ON slot.id=e.target_id AND slot.organization_id=e.organization_id
          WHERE e.target_type='SUPPLIER_TECHNICAL_DOCUMENT' AND procurement_technical_evidence_current(e.organization_id,slot.quote_id,e.id,e.sha256)"""
    }
}
