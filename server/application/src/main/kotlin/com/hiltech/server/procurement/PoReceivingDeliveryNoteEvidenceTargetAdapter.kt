package com.hiltech.server.procurement

import com.hiltech.server.documents.PoReceivingDeliveryNoteEvidenceTarget
import com.hiltech.server.documents.PoReceivingDeliveryNoteEvidenceTargetPort
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Warehouse authority is evaluated against current location and membership on every request. */
@Component
class PoReceivingDeliveryNoteEvidenceTargetAdapter @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val warehouse: WarehousePersistencePort,
    private val authority: WarehouseAuthorizationPort,
    private val clock: Clock,
    @Value("\${hiltech.procurement.po-receiving.enabled:false}")
    private val enabled: Boolean = false,
) : PoReceivingDeliveryNoteEvidenceTargetPort {
    override fun loadTarget(receivingRecordId: UUID): PoReceivingDeliveryNoteEvidenceTarget? =
        jdbc.query(
            """
            SELECT r.id, r.organization_id, r.destination_storage_location_id,
                   r.state, b.purchase_order_id
            FROM receiving_record r
            JOIN procurement_po_receiving_link b
              ON b.receiving_record_id=r.id
             AND b.organization_id=r.organization_id
             AND b.destination_storage_location_id=r.destination_storage_location_id
            WHERE r.id=?
            """.trimIndent(),
            { rs, _ -> PoReceivingDeliveryNoteEvidenceTarget(
                rs.getObject("id", UUID::class.java),
                rs.getObject("purchase_order_id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getObject("destination_storage_location_id", UUID::class.java),
                rs.getString("state"),
            ) }, receivingRecordId,
        ).singleOrNull()

    override fun canReserve(actor: UUID, target: PoReceivingDeliveryNoteEvidenceTarget): Boolean =
        enabled && target.receivingState in setOf("DRAFT", "IN_PROGRESS") &&
            canAccess(actor, target)

    override fun canAccess(actor: UUID, target: PoReceivingDeliveryNoteEvidenceTarget): Boolean {
        if (!enabled || loadTarget(target.receivingRecordId) != target) return false
        val storage = warehouse.storageLocation(target.destinationStorageLocationId)
            ?: return false
        val now = clock.instant()
        return storage.organizationId == target.organizationId &&
            storage.lifecycleState == StorageLocationLifecycle.ACTIVE &&
            (storage.activeFrom == null || storage.activeFrom <= now) &&
            (storage.activeUntil == null || storage.activeUntil > now) &&
            authority.canReceiveAt(actor, storage)
    }
}
