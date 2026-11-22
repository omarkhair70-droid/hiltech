package com.hiltech.server.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PurchaseOrderDeliveryAmendmentRecord(
    val id: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val purchaseOrderId: UUID,
    val baseVersion: Long,
    val previousDestinationId: UUID,
    val newDestinationId: UUID,
    val reason: String,
    val proposedBy: UUID,
    val at: Instant,
    val correlationId: String,
)

/** Immutable proposed destination, separate from canonical approved event. */
@Repository
class PurchaseOrderDeliveryAmendmentPersistence(private val jdbc: JdbcTemplate) {
    private val mapper = org.springframework.jdbc.core.RowMapper { rs: java.sql.ResultSet, _: Int ->
        PurchaseOrderDeliveryAmendmentRecord(
            id = rs.getObject("id", UUID::class.java),
            operationId = rs.getObject("operation_id", UUID::class.java),
            organizationId = rs.getObject("organization_id", UUID::class.java),
            purchaseOrderId = rs.getObject("purchase_order_id", UUID::class.java),
            baseVersion = rs.getLong("base_order_version"),
            previousDestinationId = rs.getObject("prior_storage_location_id", UUID::class.java),
            newDestinationId = rs.getObject("proposed_storage_location_id", UUID::class.java),
            reason = rs.getString("reason"),
            proposedBy = rs.getObject("proposed_by_user_id", UUID::class.java),
            at = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            correlationId = rs.getString("correlation_id"),
        )
    }

    fun byId(id: UUID): PurchaseOrderDeliveryAmendmentRecord? = jdbc.query(
        "SELECT * FROM procurement_po_delivery_amendment_proposal WHERE id = ?",
        mapper, id,
    ).singleOrNull()

    fun byOrderAndBase(orderId: UUID, baseVersion: Long): PurchaseOrderDeliveryAmendmentRecord? =
        jdbc.query(
            """
            SELECT * FROM procurement_po_delivery_amendment_proposal
            WHERE purchase_order_id = ? AND base_order_version = ?
            """.trimIndent(), mapper, orderId, baseVersion,
        ).singleOrNull()

    fun insert(proposal: PurchaseOrderDeliveryAmendmentRecord) {
        check(jdbc.update(
            """
            INSERT INTO procurement_po_delivery_amendment_proposal (
                id, operation_id, organization_id, purchase_order_id,
                base_order_version, prior_storage_location_id,
                proposed_storage_location_id, reason,
                proposed_by_user_id, created_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            proposal.id, proposal.operationId, proposal.organizationId,
            proposal.purchaseOrderId, proposal.baseVersion,
            proposal.previousDestinationId, proposal.newDestinationId,
            proposal.reason, proposal.proposedBy,
            proposal.at.atOffset(ZoneOffset.UTC), proposal.correlationId,
        ) == 1)
    }
}
