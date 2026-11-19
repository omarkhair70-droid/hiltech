package com.hiltech.server.warehouse

import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class ReceivingAssetWriteResult(val asset: AssetIdentityView, val movementId: UUID, val tag: AssetTagView?)

@Service
class ReceivingAssetWriter(
    private val assets: AssetPassportPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val projection: AuthorizationProjectionIntentWriter,
    private val events: ApplicationEventPublisher,
) {
    fun requireActiveType(org: UUID, typeId: UUID, at: Instant = Instant.now()): AssetTypeView =
        assets.types(org, at).firstOrNull { it.id == typeId }
            ?: receivingAssetInvalid("Select an active Asset type revision.")

    fun asset(id: UUID): AssetIdentityView? = assets.asset(id)
    fun receivingMovement(lineId: UUID): AssetMovementView? = assets.movementForReceivingLine(lineId)
    fun primaryTag(assetId: UUID): AssetTagView? = assets.tags(assetId).singleOrNull { it.active && it.primary }

    fun receive(context: ReceivingCommandContext, record: ReceivingRecordView, line: ReceivingLineView,
        destination: StorageLocationSnapshot, typeId: UUID, request: ReceiveAssetLineRequest, now: Instant,
        markLineReceived: (AssetIdentityView) -> Unit,
    ): ReceivingAssetWriteResult {
        val warehouseId = destination.warehouseId ?: receivingAssetUnavailable()
        val facility = warehouse.warehouse(warehouseId)?.takeIf {
            it.active && it.organizationId == record.organizationId && destination.organizationId == record.organizationId
        } ?: receivingAssetUnavailable()
        val type = requireActiveType(record.organizationId, typeId, now)
        val code = receivingAssetCode(request.assetCode)
        val name = receivingAssetText(request.name, "Asset name")
        val manufacturer = request.manufacturer?.let { receivingAssetText(it, "Manufacturer") }
        val model = request.model?.let { receivingAssetText(it, "Model") }
        val serial = request.serialNumber?.let { receivingAssetText(it, "Serial number") }
        val ownership = receivingOwnershipCode(request.ownershipType)
        if (type.serialRequired && serial.isNullOrBlank()) receivingAssetInvalid("This Asset type requires a serial number.")
        val tagType = request.tagType?.trim()?.takeIf { it.isNotEmpty() }
        if (tagType != null && (tagType !in type.allowedTagTypes ||
                !authorization.canTagAsset(context.actor, facility, type.operatorMayTag && !type.highValueOrRestricted))) {
            receivingAssetUnavailable()
        }

        val assetId = UUID.nameUUIDFromBytes(("hiltech:receiving-asset:" + line.id).toByteArray(Charsets.UTF_8))
        assets.insert(assetId, record.organizationId, warehouseId, code, name, type, manufacturer, model, serial,
            ownership, request.condition, null, context.actor, now)
        var asset = assets.asset(assetId) ?: receivingAssetUnavailable()
        markLineReceived(asset)
        writeRelation(asset, "warehouse", warehouseId, "warehouse", now, context)
        val target = AssetDestination(destination.storageLocationId, null, destination.projectId, destination.siteId, null)
        val movementId = assets.append(asset, AssetCommandContext(context.operationId, context.actor, context.correlationId),
            AssetCustodyMovement.REGISTER_INITIAL_LOCATION, null, target, request.condition, now, now,
            null, null, line.id)
        asset = assets.asset(assetId) ?: receivingAssetUnavailable()
        writeRelation(asset, "storage_location", destination.storageLocationId, "storage_location", now, context)
        events.publishEvent(AssetCustodyMovementPosted(
            movementId, asset.id, asset.organizationId, AssetCustodyMovement.REGISTER_INITIAL_LOCATION,
            context.actor, context.correlationId, now,
        ))
        val tag = tagType?.let {
            val tagId = UUID.nameUUIDFromBytes(("hiltech:receiving-asset-tag:" + line.id).toByteArray(Charsets.UTF_8))
            assets.issueTag(asset.id, tagId, UUID.randomUUID().toString(), it, null, now)
            assets.tag(asset.id, tagId)
        }
        return ReceivingAssetWriteResult(assets.asset(asset.id) ?: receivingAssetUnavailable(), movementId, tag)
    }

    private fun writeRelation(asset: AssetIdentityView, subjectType: String, subjectId: UUID,
        relation: String, at: Instant, context: ReceivingCommandContext) {
        val tuple = OpenFgaTuple(subjectType, subjectId.toString(), relation, "asset", asset.id.toString())
        val eventId = UUID.nameUUIDFromBytes(
            ("hiltech:receiving-asset-projection:" + context.operationId + ":" + tuple.relationKey).toByteArray(Charsets.UTF_8),
        )
        projection.write(AuthorizationProjectionIntent(
            eventId, tuple, AuthorizationDesiredState.PRESENT, "ASSET", asset.id.toString(), asset.version,
            "ASSET_AUTHORITY_CHANGED", at,
        ))
    }
}

private fun receivingAssetText(value: String, label: String, max: Int = 160) =
    value.trim().takeIf { it.isNotEmpty() && it.length <= max } ?: receivingAssetInvalid("$label is invalid.")

private fun receivingAssetCode(value: String): String = receivingAssetText(value, "Asset code", 64).uppercase(Locale.ROOT)
    .takeIf { Regex("[A-Z0-9][A-Z0-9._-]{0,63}").matches(it) }
    ?: receivingAssetInvalid("Asset code contains unsupported characters.")

private fun receivingOwnershipCode(value: String): String = receivingAssetText(value, "Ownership type", 64).uppercase(Locale.ROOT)
    .takeIf { Regex("[A-Z0-9][A-Z0-9_-]{0,63}").matches(it) }
    ?: receivingAssetInvalid("Ownership type contains unsupported characters.")

private fun receivingAssetInvalid(message: String): Nothing =
    throw com.hiltech.server.platform.ProductApiException("INVALID_RECEIVING", message, org.springframework.http.HttpStatus.BAD_REQUEST)
private fun receivingAssetUnavailable(): Nothing =
    throw com.hiltech.server.platform.ProductApiException("RECEIVING_UNAVAILABLE", "Receiving context is unavailable.",
        org.springframework.http.HttpStatus.NOT_FOUND)
