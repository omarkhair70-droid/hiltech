package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class AssetCommandContext(val operationId: UUID, val actor: UUID, val correlationId: String)
data class AssetCatalogView(val types: List<AssetTypeView>, val canRegister: Boolean)
data class AssetPassportView(
    val identity: AssetIdentityView, val custody: AssetCustodyView?, val tags: List<AssetTagView>,
    val permittedTagTypes: List<String>, val canInitialize: Boolean, val canCorrect: Boolean,
)
data class AssetTagLookupView(
    val assetCode: String, val name: String?, val typeName: String, val manufacturer: String?, val model: String?,
    val serialNumber: String?, val condition: AssetCondition, val locationCode: String?, val employeeCode: String?,
    val custodySince: String?,
)
data class AssetCustodyMovementPosted(
    val movementId: UUID, val assetId: UUID, val organizationId: UUID,
    val type: AssetCustodyMovement, val actorUserId: UUID, val correlationId: String, val occurredAt: Instant,
)

@Service
class AssetPassportService(
    private val assets: AssetPassportPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val fga: ObjectProvider<AuthorizationCheckPort>,
    private val projection: AuthorizationProjectionIntentWriter,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    fun types(actor: UUID, facilityId: UUID): AssetCatalogView {
        val facility = viewableWarehouse(actor, facilityId)
        return AssetCatalogView(assets.types(facility.organizationId, clock.instant()), authorization.canManageMaster(actor, facility))
    }

    fun directory(actor: UUID, facilityId: UUID, search: String, afterCode: String?): List<AssetIdentityView> {
        viewableWarehouse(actor, facilityId)
        return assets.search(facilityId, search.trim().take(160), afterCode?.take(64))
    }

    fun passport(actor: UUID, id: UUID): AssetPassportView {
        viewableWarehouse(actor, facility(asset(id)).warehouseId)
        return readPassport(actor, id)
    }

    fun movements(actor: UUID, id: UUID): List<AssetMovementView> {
        viewableWarehouse(actor, facility(asset(id)).warehouseId)
        return assets.movements(id)
    }

    fun lookup(actor: UUID, input: String): AssetTagLookupView {
        val code = input.trim()
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(code)) throw unavailable()
        val asset = assets.activeTagAsset(code)?.let { assets.asset(it) }?.takeIf { it.lifecycle == "ACTIVE" } ?: throw unavailable()
        val custody = assets.custody(asset.id)
        val facilityAccess = asset.warehouseId?.let(warehouse::warehouse)?.let { authorization.canViewWarehouse(actor, it) } == true
        val locationAccess = custody?.storageLocationId?.let(warehouse::storageLocation)
            ?.takeIf { it.organizationId == asset.organizationId && it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                (it.activeFrom == null || it.activeFrom <= clock.instant()) && (it.activeUntil == null || it.activeUntil > clock.instant()) }
            ?.let { authorization.canViewStorage(actor, it) } == true
        if (!facilityAccess && !locationAccess && !isCustodian(actor, asset, custody)) throw unavailable()
        if (assets.activeTagAsset(code) != asset.id || assets.custody(asset.id)?.sourceMovementId != custody?.sourceMovementId) {
            throw unavailable()
        }
        return AssetTagLookupView(asset.assetCode, asset.name, asset.typeName, asset.manufacturer, asset.model,
            asset.serialNumber, asset.condition, custody?.locationCode, custody?.employeeCode, custody?.since)
    }

    fun register(context: AssetCommandContext, request: AssetRegistrationRequest): AssetPassportView {
        val facility = managedWarehouse(context.actor, request.warehouseId)
        val id = assetId("register", context.operationId)
        val code = assetCode(request.assetCode)
        val name = assetText(request.name, "Asset name")
        val ownership = ownershipCode(request.ownershipType)
        val manufacturer = request.manufacturer?.let { assetText(it, "Manufacturer") }
        val model = request.model?.let { assetText(it, "Model") }
        val serial = request.serialNumber?.let { assetText(it, "Serial number") }
        execute(context, "ASSET_REGISTERED", id, request.warehouseId, request.assetTypeId, code, name,
            ownership, manufacturer, model, serial, request.condition, request.initialLocationId) {
            managedWarehouse(context.actor, facility.warehouseId)
            val now = clock.instant()
            val target = request.initialLocationId?.let { location(context.actor, facility, it) }
            val type = activeType(facility.organizationId, request.assetTypeId, now)
            insert(context, id, facility, type, code, name, ownership, manufacturer, model, serial, request.condition, null, now)
            project(context, asset(id), null, now)
            target?.let {
                append(context, asset(id), AssetCustodyMovement.REGISTER_INITIAL_LOCATION, null, it, request.condition, now, now)
            }
        }
        return readPassport(context.actor, id)
    }

    fun tag(context: AssetCommandContext, id: UUID, version: Long, rawType: String, previousTag: UUID?): AssetTagView {
        tagPolicy(context.actor, asset(id), rawType)
        val tagId = assetId("tag", context.operationId)
        execute(context, if (previousTag == null) "ASSET_TAG_ISSUED" else "ASSET_TAG_REPLACED", id, version, rawType, previousTag) {
            val locked = lockedAsset(id, version)
            tagPolicy(context.actor, locked, rawType)
            val current = assets.tags(id).singleOrNull { it.active && it.primary }
            if (current?.id != previousTag) throw conflict("ASSET_PRIMARY_TAG_CHANGED", "Reload the current primary tag.")
            val now = clock.instant()
            previousTag?.let {
                if (assets.endTag(id, it, now) != 1) throw conflict("ASSET_PRIMARY_TAG_CHANGED", "Reload the current primary tag.")
            }
            assets.issueTag(id, tagId, UUID.randomUUID().toString(), rawType, previousTag, now)
        }
        return requireNotNull(assets.tag(id, tagId))
    }

    fun opening(context: AssetCommandContext, id: UUID, request: OpenAssetCustodyRequest): AssetPassportView {
        managedWarehouse(context.actor, facility(asset(id)).warehouseId)
        val cutover = request.cutoverAt?.let {
            runCatching { Instant.parse(it).truncatedTo(ChronoUnit.MICROS) }
                .getOrElse { throw invalid("Enter a valid cutover timestamp with an offset.") }
        }
        execute(context, "ASSET_CUSTODY_INITIALIZED", id, request.baseVersion, request.locationId,
            request.employeeId, request.workOrderId, request.condition, cutover) {
            val locked = lockedAsset(id, request.baseVersion)
            val facility = managedWarehouse(context.actor, facility(locked).warehouseId)
            if (assets.custody(id) != null) throw conflict("ASSET_CUSTODY_EXISTS", "Opening custody has already been established.")
            val now = clock.instant()
            val source = locked.sourceImportRowId
            if (source != null) {
                val proof = verifiedImport(context.actor, locked.organizationId, source, lock = true)
                if (proof.warehouseId != facility.warehouseId || proof.typeId != locked.typeId || cutover == null ||
                    cutover != proof.cutoverAt || cutover > now || request.locationId != proof.locationId ||
                    request.employeeId != proof.employeeId || request.workOrderId != null) throw reviewRequired()
            } else if (cutover != null) throw invalid("Cutover time is reserved for verified imports.")
            val target = destination(context.actor, facility, request.locationId, request.employeeId, request.workOrderId)
            append(context, locked, AssetCustodyMovement.INITIALIZE_OPENING_CUSTODY, null, target, request.condition,
                cutover ?: now, now, source = source)
        }
        return readPassport(context.actor, id)
    }

    fun correct(context: AssetCommandContext, id: UUID, request: CorrectAssetCustodyRequest): AssetPassportView {
        correctionWarehouse(context.actor, asset(id))
        val reason = assetText(request.reason, "Correction reason", 1000)
        execute(context, "ASSET_CUSTODY_CORRECTED", id, request.baseVersion, request.locationId, request.employeeId,
            request.workOrderId, request.condition, request.sourceMovementId, reason) {
            val locked = lockedAsset(id, request.baseVersion)
            val facility = correctionWarehouse(context.actor, locked)
            val prior = assets.custody(id)?.takeIf { it.sourceMovementId == request.sourceMovementId }
                ?: throw conflict("ASSET_CUSTODY_CHANGED", "Reload the movement to be corrected.")
            val target = destination(context.actor, facility, request.locationId, request.employeeId, request.workOrderId)
            val now = clock.instant()
            append(context, locked, AssetCustodyMovement.CONTROLLED_CORRECTION, prior, target, request.condition, now, now, reason)
        }
        return readPassport(context.actor, id)
    }

    fun approve(context: AssetCommandContext, batchId: UUID, rowId: UUID, request: ApproveAssetImportRequest): AssetPassportView {
        val batch = warehouse.importBatch(batchId) ?: throw unavailable()
        if (!authorization.canManageImport(context.actor, batch.organizationId)) throw unavailable()
        val id = assetId("import", rowId)
        val code = assetCode(request.assetCode)
        val name = assetText(request.name, "Asset name")
        val ownership = ownershipCode(request.ownershipType)
        val verification = verifiedImport(context.actor, batch.organizationId, rowId)
        execute(context, "ASSET_IMPORT_APPROVED", id, batchId, rowId, request.baseVersion, code, name, ownership, request.condition) {
            val locked = warehouse.lockImportBatch(batchId) ?: throw unavailable()
            val row = warehouse.importRow(rowId)?.takeIf { it.batchId == batchId && it.organizationId == batch.organizationId }
                ?: throw unavailable()
            val proof = verifiedImport(context.actor, locked.organizationId, rowId, lock = true)
            if (locked.state == InventoryImportBatchState.CANCELLED || row.candidateClass != InventoryCandidateClass.COMPANY_ASSET ||
                row.reviewState != InventoryImportReviewState.NEEDS_REVIEW || row.version != request.baseVersion ||
                row.version != proof.rowVersion || locked.sourceSha256 != proof.digest || proof != verification ||
                row.issueCodes.map { it.name }.toSet() != proof.resolvedIssues) throw reviewRequired()
            val facility = managedWarehouse(context.actor, proof.warehouseId)
            val now = clock.instant()
            val type = activeType(facility.organizationId, proof.typeId, now)
            insert(context, id, facility, type, code, name, ownership, proof.manufacturer, proof.model, proof.serial, request.condition, rowId, now)
            if (assets.approveImport(rowId, id, context.actor, now, row.version) != 1) throw reviewRequired()
            project(context, asset(id), null, now)
        }
        return readPassport(context.actor, id)
    }

    private fun readPassport(actor: UUID, id: UUID): AssetPassportView {
        val asset = asset(id)
        val facility = facility(asset)
        val custody = assets.custody(id)
        val type = assets.types(asset.organizationId, clock.instant(), asset.typeId).singleOrNull()
        val active = asset.lifecycle == "ACTIVE"
        val allowedTags = type?.allowedTagTypes.orEmpty().takeIf {
            active && type != null && authorization.canTagAsset(actor, facility, type.operatorMayTag && !type.highValueOrRestricted)
        }.orEmpty()
        return AssetPassportView(asset, custody, assets.tags(id), allowedTags,
            active && custody == null && authorization.canManageMaster(actor, facility),
            active && custody != null && authorization.canManageWarehouse(actor, facility))
    }
    private fun asset(id: UUID) = assets.asset(id) ?: throw unavailable()
    private fun facility(asset: AssetIdentityView): WarehouseSnapshot = asset.warehouseId?.let(warehouse::warehouse)
        ?.takeIf { it.organizationId == asset.organizationId } ?: throw unavailable()

    private fun viewableWarehouse(actor: UUID, id: UUID): WarehouseSnapshot =
        warehouse.warehouse(id)?.takeIf { authorization.canViewWarehouse(actor, it) } ?: throw unavailable()

    private fun managedWarehouse(actor: UUID, id: UUID): WarehouseSnapshot =
        warehouse.warehouse(id)?.takeIf { authorization.canManageMaster(actor, it) } ?: throw unavailable()

    private fun correctionWarehouse(actor: UUID, asset: AssetIdentityView): WarehouseSnapshot =
        facility(asset).takeIf { authorization.canManageWarehouse(actor, it) } ?: throw unavailable()

    private fun tagPolicy(actor: UUID, asset: AssetIdentityView, tagType: String) {
        val facility = facility(asset)
        val type = assets.types(asset.organizationId, clock.instant(), asset.typeId).singleOrNull() ?: throw unavailable()
        if (!authorization.canTagAsset(actor, facility, type.operatorMayTag && !type.highValueOrRestricted)) throw unavailable()
        if (tagType !in type.allowedTagTypes) throw invalid("Select a tag type permitted by the Asset type.")
    }

    private fun isCustodian(actor: UUID, asset: AssetIdentityView, custody: AssetCustodyView?): Boolean {
        val employee = custody?.employeeId ?: return false
        val now = clock.instant()
        if (!sourceAuthority.isOrganizationMemberCurrent(actor, asset.organizationId, now) ||
            !assets.employeeActive(asset.organizationId, employee) ||
            actor !in assets.employeeIdentities(asset.organizationId, employee)) return false
        return fga.ifAvailable?.isAllowed(AuthorizationCheckRequest(
            assetTuple("user", actor, "can_view", asset.id),
            listOf(assetTuple("user", actor, "current_custodian", asset.id),
                RoleTeamAuthorizationRelations.organizationMember(actor, asset.organizationId)),
        )) == true
    }

    private fun lockedAsset(id: UUID, version: Long): AssetIdentityView {
        val asset = assets.asset(id, lock = true)?.takeIf { it.lifecycle == "ACTIVE" } ?: throw unavailable()
        if (asset.version != version) throw conflict("ASSET_VERSION_CONFLICT", "Reload the Asset passport.")
        return asset
    }

    private fun location(actor: UUID, facility: WarehouseSnapshot, id: UUID): AssetDestination {
        val now = clock.instant()
        val storage = warehouse.storageLocation(id)?.takeIf {
            it.organizationId == facility.organizationId && it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                (it.activeFrom == null || it.activeFrom <= now) && (it.activeUntil == null || it.activeUntil > now) &&
                (it.warehouseId == facility.warehouseId || authorization.canTransferStock(actor, it))
        } ?: throw unavailable()
        return AssetDestination(storage.storageLocationId, null, storage.projectId, storage.siteId, null)
    }

    private fun destination(actor: UUID, facility: WarehouseSnapshot, locationId: UUID?, employeeId: UUID?, workId: UUID?): AssetDestination {
        if ((locationId == null) == (employeeId == null) || (locationId != null && workId != null)) {
            throw invalid("Select one storage location or one employee. Work context applies to employee custody.")
        }
        if (locationId != null) return location(actor, facility, locationId)
        val employee = requireNotNull(employeeId)
        if (!assets.employeeActive(facility.organizationId, employee)) throw unavailable()
        return workId?.let { assets.employeeWork(facility.organizationId, employee, it) ?: throw unavailable() }
            ?: AssetDestination(null, employee, null, null, null)
    }

    private fun verifiedImport(actor: UUID, org: UUID, rowId: UUID, lock: Boolean = false): AssetImportVerification {
        val proof = assets.verification(rowId, lock) ?: throw reviewRequired()
        val facility = managedWarehouse(actor, proof.warehouseId)
        val now = clock.instant()
        if (facility.organizationId != org || proof.approver != actor || proof.verifiedAt > now ||
            !sourceAuthority.isOrganizationMemberCurrent(proof.verifiedBy, org, now)) throw unavailable()
        return proof
    }

    private fun activeType(org: UUID, id: UUID, at: Instant): AssetTypeView =
        assets.types(org, at).firstOrNull { it.id == id } ?: throw invalid("Select an active Asset type revision.")

    private fun insert(
        context: AssetCommandContext, id: UUID, facility: WarehouseSnapshot, type: AssetTypeView, code: String,
        name: String, ownership: String, manufacturer: String?, model: String?, serial: String?,
        condition: AssetCondition, rowId: UUID?, now: Instant,
    ) {
        if (type.serialRequired && serial.isNullOrBlank()) throw invalid("This Asset type requires a serial number.")
        assets.insert(id, facility.organizationId, facility.warehouseId, code, name, type,
            manufacturer, model, serial, ownership, condition, rowId, context.actor, now)
    }

    private fun append(
        context: AssetCommandContext, asset: AssetIdentityView, type: AssetCustodyMovement, prior: AssetCustodyView?,
        target: AssetDestination, condition: AssetCondition, occurred: Instant, now: Instant, reason: String? = null, source: UUID? = null,
    ) {
        val id = assets.append(asset, context, type, prior, target, condition, occurred, now, reason, source)
        project(context, asset(asset.id), target, now, prior)
        events.publishEvent(AssetCustodyMovementPosted(id, asset.id, asset.organizationId, type, context.actor, context.correlationId, occurred))
    }

    private fun project(context: AssetCommandContext, asset: AssetIdentityView, target: AssetDestination?, at: Instant, prior: AssetCustodyView? = null) {
        fun relation(type: String, id: UUID, key: String, desired: AuthorizationDesiredState) {
            val tuple = assetTuple(type, id, key, asset.id)
            projection.write(AuthorizationProjectionIntent(
                assetId(tuple.relationKey, context.operationId), tuple, desired, "ASSET", asset.id.toString(), asset.version,
                "ASSET_AUTHORITY_CHANGED", at,
            ))
        }
        val present = AuthorizationDesiredState.PRESENT
        val absent = AuthorizationDesiredState.ABSENT
        if (prior == null && target == null) asset.warehouseId?.let { relation("warehouse", it, "warehouse", present) }
        if (prior?.storageLocationId != target?.locationId) {
            prior?.storageLocationId?.let { relation("storage_location", it, "storage_location", absent) }
            target?.locationId?.let { relation("storage_location", it, "storage_location", present) }
        }
        if (prior?.employeeId != target?.employeeId) {
            prior?.employeeId?.let { assets.employeeIdentities(asset.organizationId, it) }
                .orEmpty().forEach { relation("user", it, "current_custodian", absent) }
            target?.employeeId?.let { assets.employeeIdentities(asset.organizationId, it) }
                .orEmpty().forEach { relation("user", it, "current_custodian", present) }
        }
    }

    private fun execute(context: AssetCommandContext, action: String, id: UUID, vararg fields: Any?, write: () -> Unit) {
        try {
            commands.execute(IdempotentCommandSpec(context.operationId, context.actor, action, "ASSET", id,
                warehouseCommandFingerprint(*fields), context.correlationId)) {
                write()
                audit.append(AuditEventRecord(actorUserId = context.actor, action = action, targetType = "ASSET",
                    targetId = id, occurredAt = clock.instant(), correlationId = context.correlationId))
                IdempotentCommandOutcome(action, """{"targetId":"$id"}""")
            }
        } catch (_: DataIntegrityViolationException) {
            throw conflict("ASSET_CONSTRAINT_CONFLICT", "The Asset command conflicts with current identity, tag or custody constraints.")
        }
    }
}

private fun assetTuple(type: String, id: UUID, relation: String, asset: UUID) =
    OpenFgaTuple(type, id.toString(), relation, "asset", asset.toString())

private fun assetId(purpose: String, input: UUID) =
    UUID.nameUUIDFromBytes("hiltech:asset:$purpose:$input".toByteArray(Charsets.UTF_8))

private fun assetText(value: String, label: String, max: Int = 160) =
    value.trim().takeIf { it.isNotEmpty() && it.length <= max } ?: throw invalid("$label is invalid.")

private fun assetCode(value: String): String = assetText(value, "Asset code", 64).uppercase(Locale.ROOT)
    .takeIf { Regex("[A-Z0-9][A-Z0-9._-]{0,63}").matches(it) } ?: throw invalid("Asset code contains unsupported characters.")

private fun ownershipCode(value: String): String = assetText(value, "Ownership type", 64).uppercase(Locale.ROOT)
    .takeIf { Regex("[A-Z0-9][A-Z0-9_-]{0,63}").matches(it) } ?: throw invalid("Ownership type contains unsupported characters.")

private fun unavailable() = ProductApiException("ASSET_UNAVAILABLE", "Asset or custody destination is unavailable.", HttpStatus.NOT_FOUND)
private fun invalid(message: String) = ProductApiException("INVALID_ASSET_COMMAND", message, HttpStatus.BAD_REQUEST)
private fun conflict(code: String, message: String) = ProductApiException(code, message, HttpStatus.CONFLICT)
private fun reviewRequired() = conflict("ASSET_SOURCE_REVIEW_REQUIRED", "Matching independent source verification is required.")
