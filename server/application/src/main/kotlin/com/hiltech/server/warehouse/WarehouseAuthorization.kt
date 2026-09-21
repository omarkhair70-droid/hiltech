package com.hiltech.server.warehouse

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

object WarehouseAuthorizationRelations {
    fun warehouseOrganization(
        warehouseId: UUID,
        organizationId: UUID,
    ) =
        OpenFgaTuple(
            subjectType = "organization",
            subjectId = organizationId.toString(),
            relation = "organization",
            objectType = "warehouse",
            objectId = warehouseId.toString(),
        )

    fun warehouseAuthority(
        binding: WarehouseAuthorityBindingSnapshot,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType =
                if (binding.principalType == InventoryPrincipalType.USER) {
                    "user"
                } else {
                    "team"
                },
            subjectId =
                if (binding.principalType == InventoryPrincipalType.USER) {
                    binding.principalId.toString()
                } else {
                    binding.principalId.toString() + "#member"
                },
            relation =
                when (binding.authorityKey) {
                    WarehouseAuthorityKey.WAREHOUSE_MANAGER -> "manager"
                    WarehouseAuthorityKey.WAREHOUSE_OPERATOR -> "operator"
                    WarehouseAuthorityKey.WAREHOUSE_VIEWER -> "viewer"
                    WarehouseAuthorityKey.ASSET_MASTER_MANAGER ->
                        "asset_master_manager"
                    WarehouseAuthorityKey.INVENTORY_ADJUSTMENT_APPROVER ->
                        "adjustment_approver"
                    WarehouseAuthorityKey.INVENTORY_VALUE_VIEWER ->
                        "value_viewer"
                },
            objectType = "warehouse",
            objectId = binding.warehouseId.toString(),
        )

    fun warehouseAction(
        actorUserId: UUID,
        warehouseId: UUID,
        relation: String,
    ) =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = relation,
            objectType = "warehouse",
            objectId = warehouseId.toString(),
        )

    fun storageWarehouse(
        storageLocationId: UUID,
        warehouseId: UUID,
    ) =
        OpenFgaTuple(
            subjectType = "warehouse",
            subjectId = warehouseId.toString(),
            relation = "warehouse",
            objectType = "storage_location",
            objectId = storageLocationId.toString(),
        )

    fun storageProject(
        storageLocationId: UUID,
        projectId: UUID,
    ) =
        OpenFgaTuple(
            subjectType = "project",
            subjectId = projectId.toString(),
            relation = "project",
            objectType = "storage_location",
            objectId = storageLocationId.toString(),
        )

    fun storageSite(
        storageLocationId: UUID,
        siteId: UUID,
    ) =
        OpenFgaTuple(
            subjectType = "site",
            subjectId = siteId.toString(),
            relation = "site",
            objectType = "storage_location",
            objectId = storageLocationId.toString(),
        )

    fun storageAuthority(
        binding: StorageAuthorityBindingSnapshot,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType =
                if (binding.principalType == InventoryPrincipalType.USER) {
                    "user"
                } else {
                    "team"
                },
            subjectId =
                if (binding.principalType == InventoryPrincipalType.USER) {
                    binding.principalId.toString()
                } else {
                    binding.principalId.toString() + "#member"
                },
            relation =
                when (binding.authorityKey) {
                    StorageLocationAuthorityKey.LOCATION_RESPONSIBLE ->
                        "responsible"
                    StorageLocationAuthorityKey.LOCATION_OPERATOR ->
                        "operator"
                    StorageLocationAuthorityKey.LOCATION_VIEWER ->
                        "viewer"
                },
            objectType = "storage_location",
            objectId = binding.storageLocationId.toString(),
        )

    fun storageAction(
        actorUserId: UUID,
        storageLocationId: UUID,
        relation: String,
    ) =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = relation,
            objectType = "storage_location",
            objectId = storageLocationId.toString(),
        )
}

@Component
class WarehouseAuthorizationProjectionBridge(
    private val persistence: WarehousePersistencePort,
    private val writer: AuthorizationProjectionIntentWriter,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun warehouseCreated(
        warehouse: WarehouseSnapshot,
        eventId: UUID,
    ) {
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    WarehouseAuthorizationRelations.warehouseOrganization(
                        warehouse.warehouseId,
                        warehouse.organizationId,
                    ),
                desiredState = AuthorizationDesiredState.PRESENT,
                sourceType = "Warehouse",
                sourceId = warehouse.warehouseId.toString(),
                sourceVersion = warehouse.version,
                eventType = "WAREHOUSE_ORGANIZATION_LINKED",
                occurredAt = clock.instant(),
            ),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun warehouseAuthority(
        bindingId: UUID,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val at = clock.instant()
        val binding =
            requireNotNull(
                persistence.warehouseAuthorityBinding(bindingId, at),
            )
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    WarehouseAuthorizationRelations.warehouseAuthority(binding),
                desiredState =
                    if (binding.current) {
                        AuthorizationDesiredState.PRESENT
                    } else {
                        AuthorizationDesiredState.ABSENT
                    },
                sourceType = "WarehouseAuthorityBinding",
                sourceId = binding.bindingId.toString(),
                sourceVersion = binding.version,
                eventType = "WAREHOUSE_AUTHORITY_CHANGED",
                occurredAt = at,
            ),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun storageCreated(
        storage: StorageLocationSnapshot,
        sourceVersion: Long = storage.version,
    ) {
        val at = clock.instant()
        storage.warehouseId?.let {
            writer.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple =
                        WarehouseAuthorizationRelations.storageWarehouse(
                            storage.storageLocationId,
                            it,
                        ),
                    desiredState = AuthorizationDesiredState.PRESENT,
                    sourceType = "StorageLocation",
                    sourceId = storage.storageLocationId.toString(),
                    sourceVersion = sourceVersion,
                    eventType = "STORAGE_WAREHOUSE_LINKED",
                    occurredAt = at,
                ),
            )
        }
        storage.projectId?.let {
            writer.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple =
                        WarehouseAuthorizationRelations.storageProject(
                            storage.storageLocationId,
                            it,
                        ),
                    desiredState = AuthorizationDesiredState.PRESENT,
                    sourceType = "StorageLocation",
                    sourceId = storage.storageLocationId.toString(),
                    sourceVersion = sourceVersion,
                    eventType = "STORAGE_PROJECT_LINKED",
                    occurredAt = at,
                ),
            )
        }
        storage.siteId?.let {
            writer.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple =
                        WarehouseAuthorizationRelations.storageSite(
                            storage.storageLocationId,
                            it,
                        ),
                    desiredState = AuthorizationDesiredState.PRESENT,
                    sourceType = "StorageLocation",
                    sourceId = storage.storageLocationId.toString(),
                    sourceVersion = sourceVersion,
                    eventType = "STORAGE_SITE_LINKED",
                    occurredAt = at,
                ),
            )
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun storageAuthority(
        bindingId: UUID,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val at = clock.instant()
        val binding =
            requireNotNull(
                persistence.storageAuthorityBinding(bindingId, at),
            )
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    WarehouseAuthorizationRelations.storageAuthority(binding),
                desiredState =
                    if (binding.current) {
                        AuthorizationDesiredState.PRESENT
                    } else {
                        AuthorizationDesiredState.ABSENT
                    },
                sourceType = "StorageLocationAuthorityBinding",
                sourceId = binding.bindingId.toString(),
                sourceVersion = binding.version,
                eventType = "STORAGE_AUTHORITY_CHANGED",
                occurredAt = at,
            ),
        )
    }
}

interface WarehouseAuthorizationPort {
    fun canViewWarehouse(actorUserId: UUID, warehouse: WarehouseSnapshot): Boolean
    fun canManageWarehouse(actorUserId: UUID, warehouse: WarehouseSnapshot): Boolean
    fun canManageInventoryOrganization(actorUserId: UUID, organizationId: UUID): Boolean
    fun canManageMaster(actorUserId: UUID, warehouse: WarehouseSnapshot): Boolean
    fun canViewValue(actorUserId: UUID, warehouse: WarehouseSnapshot): Boolean
    fun canViewStorage(actorUserId: UUID, storage: StorageLocationSnapshot): Boolean
}

@Component
class SpringWarehouseAuthorization(
    private val authorizationProvider: ObjectProvider<AuthorizationCheckPort>,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val persistence: WarehousePersistencePort,
    private val clock: Clock,
) : WarehouseAuthorizationPort {
    override fun canViewWarehouse(
        actorUserId: UUID,
        warehouse: WarehouseSnapshot,
    ): Boolean =
        checkWarehouseAction(
            actorUserId,
            warehouse,
            "can_view_inventory",
            acceptableKeys = WarehouseAuthorityKey.entries.toSet(),
        )

    override fun canManageWarehouse(
        actorUserId: UUID,
        warehouse: WarehouseSnapshot,
    ): Boolean =
        checkWarehouseAction(
            actorUserId,
            warehouse,
            "can_manage",
            acceptableKeys = setOf(WarehouseAuthorityKey.WAREHOUSE_MANAGER),
        )

    override fun canManageInventoryOrganization(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean {
        val at = clock.instant()
        if (
            !sourceAuthority.isOrganizationMemberCurrent(
                actorUserId,
                organizationId,
                at,
            )
        ) {
            return false
        }

        return persistence.currentOrganizationWarehouseBindings(
            organizationId,
            at,
        ).any { binding ->
            binding.authorityKey == WarehouseAuthorityKey.WAREHOUSE_MANAGER &&
                principalMatches(actorUserId, binding, at) &&
                persistence.warehouse(binding.warehouseId)
                    ?.let { canManageWarehouse(actorUserId, it) } == true
        }
    }

    override fun canManageMaster(
        actorUserId: UUID,
        warehouse: WarehouseSnapshot,
    ): Boolean =
        checkWarehouseAction(
            actorUserId,
            warehouse,
            "can_manage_master",
            acceptableKeys =
                setOf(
                    WarehouseAuthorityKey.WAREHOUSE_MANAGER,
                    WarehouseAuthorityKey.ASSET_MASTER_MANAGER,
                ),
        )

    override fun canViewValue(
        actorUserId: UUID,
        warehouse: WarehouseSnapshot,
    ): Boolean =
        checkWarehouseAction(
            actorUserId,
            warehouse,
            "can_view_value",
            acceptableKeys =
                setOf(WarehouseAuthorityKey.INVENTORY_VALUE_VIEWER),
        )

    override fun canViewStorage(
        actorUserId: UUID,
        storage: StorageLocationSnapshot,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable ?: return false
        val at = clock.instant()
        if (
            !sourceAuthority.isOrganizationMemberCurrent(
                actorUserId,
                storage.organizationId,
                at,
            )
        ) {
            return false
        }

        val direct =
            persistence.currentStorageAuthorityBindings(
                storage.storageLocationId,
                at,
            ).firstOrNull {
                principalMatches(actorUserId, it, at)
            }

        val guards = mutableListOf<OpenFgaTuple>()
        guards +=
            RoleTeamAuthorizationRelations.organizationMember(
                actorUserId,
                storage.organizationId,
            )

        if (direct != null) {
            guards += WarehouseAuthorizationRelations.storageAuthority(direct)
            if (direct.principalType == InventoryPrincipalType.TEAM) {
                guards +=
                    RoleTeamAuthorizationRelations.teamMember(
                        actorUserId,
                        direct.principalId,
                    )
            }
        } else if (storage.warehouseId != null) {
            val warehouse =
                persistence.warehouse(storage.warehouseId)
                    ?: return false
            val binding =
                currentMatchingWarehouseBinding(
                    actorUserId,
                    warehouse.warehouseId,
                    at,
                    WarehouseAuthorityKey.entries.toSet(),
                ) ?: return false
            guards += WarehouseAuthorizationRelations.warehouseAuthority(binding)
            guards +=
                WarehouseAuthorizationRelations.storageWarehouse(
                    storage.storageLocationId,
                    warehouse.warehouseId,
                )
            if (binding.principalType == InventoryPrincipalType.TEAM) {
                guards +=
                    RoleTeamAuthorizationRelations.teamMember(
                        actorUserId,
                        binding.principalId,
                    )
            }
        } else {
            return false
        }

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    WarehouseAuthorizationRelations.storageAction(
                        actorUserId,
                        storage.storageLocationId,
                        "can_view",
                    ),
                failClosedGuardTuples = guards,
            ),
        )
    }

    private fun checkWarehouseAction(
        actorUserId: UUID,
        warehouse: WarehouseSnapshot,
        relation: String,
        acceptableKeys: Set<WarehouseAuthorityKey>,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable ?: return false
        val at = clock.instant()

        if (
            !warehouse.active ||
            !sourceAuthority.isOrganizationMemberCurrent(
                actorUserId,
                warehouse.organizationId,
                at,
            )
        ) {
            return false
        }

        val binding =
            currentMatchingWarehouseBinding(
                actorUserId,
                warehouse.warehouseId,
                at,
                acceptableKeys,
            ) ?: return false

        val guards =
            buildList {
                add(
                    RoleTeamAuthorizationRelations.organizationMember(
                        actorUserId,
                        warehouse.organizationId,
                    ),
                )
                add(
                    WarehouseAuthorizationRelations.warehouseOrganization(
                        warehouse.warehouseId,
                        warehouse.organizationId,
                    ),
                )
                add(
                    WarehouseAuthorizationRelations.warehouseAuthority(binding),
                )
                if (binding.principalType == InventoryPrincipalType.TEAM) {
                    add(
                        RoleTeamAuthorizationRelations.teamMember(
                            actorUserId,
                            binding.principalId,
                        ),
                    )
                }
            }

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    WarehouseAuthorizationRelations.warehouseAction(
                        actorUserId,
                        warehouse.warehouseId,
                        relation,
                    ),
                failClosedGuardTuples = guards,
            ),
        )
    }

    private fun currentMatchingWarehouseBinding(
        actorUserId: UUID,
        warehouseId: UUID,
        at: Instant,
        acceptableKeys: Set<WarehouseAuthorityKey>,
    ): WarehouseAuthorityBindingSnapshot? =
        persistence.currentWarehouseAuthorityBindings(
            warehouseId,
            at,
        ).firstOrNull {
            it.authorityKey in acceptableKeys &&
                principalMatches(actorUserId, it, at)
        }

    private fun principalMatches(
        actorUserId: UUID,
        binding: WarehouseAuthorityBindingSnapshot,
        at: Instant,
    ): Boolean =
        when (binding.principalType) {
            InventoryPrincipalType.USER ->
                binding.principalId == actorUserId

            InventoryPrincipalType.TEAM ->
                sourceAuthority.isTeamMemberCurrent(
                    actorUserId,
                    binding.principalId,
                    at,
                )
        }

    private fun principalMatches(
        actorUserId: UUID,
        binding: StorageAuthorityBindingSnapshot,
        at: Instant,
    ): Boolean =
        when (binding.principalType) {
            InventoryPrincipalType.USER ->
                binding.principalId == actorUserId

            InventoryPrincipalType.TEAM ->
                sourceAuthority.isTeamMemberCurrent(
                    actorUserId,
                    binding.principalId,
                    at,
                )
        }
}
