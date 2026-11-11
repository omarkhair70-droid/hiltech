package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.Locale
import java.util.UUID

/** Operator-only setup entry point. Database credentials must come from the approved deployment context. */
internal fun provisionWarehouseManager(args: List<String>) {
    require(args.size == 6) {
        "Usage: provision-warehouse-manager <organization-id> <warehouse-id> <operator-id> <manager-id> <code> <name>"
    }
    fun required(name: String): String = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
        "$name must be explicitly configured for provisioning."
    }
    val dataSource = DriverManagerDataSource(
        required("HILTECH_DB_URL"), required("HILTECH_DB_USER"), required("HILTECH_DB_PASSWORD"),
    )
    val openFga = HiltechOpenFgaProperties(
        enabled = true,
        apiUrl = required("HILTECH_FGA_API_URL"),
        storeId = required("HILTECH_FGA_STORE_ID"),
        authorizationModelId = required("HILTECH_FGA_MODEL_ID"),
    ).also { it.validateEnabledConfiguration() }
    val jdbc = JdbcTemplate(dataSource)
    val clock = Clock.systemUTC()
    val persistence = JdbcWarehousePersistence(jdbc)
    val provisioner = WarehouseManagerProvisioner(
        jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), persistence,
        JdbcRoleTeamSourceAuthority(jdbc),
        WarehouseAuthorizationProjectionBridge(
            persistence, JdbcAuthorizationProjectionIntentWriter(jdbc, openFga), clock,
        ),
        JdbcAuditEventWriter(jdbc), clock,
    )
    val bindingId = provisioner.provision(
        UUID.fromString(args[0]), UUID.fromString(args[1]), UUID.fromString(args[2]),
        UUID.fromString(args[3]), args[4], args[5],
    )
    println("Warehouse manager binding $bindingId. Access requires successful OpenFGA projection.")
}

internal class WarehouseManagerProvisioner(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val persistence: WarehousePersistencePort,
    private val source: JdbcRoleTeamSourceAuthority,
    private val projection: WarehouseAuthorizationProjectionBridge,
    private val audit: JdbcAuditEventWriter,
    private val clock: Clock,
) {
    fun provision(
        organizationId: UUID,
        warehouseId: UUID,
        operatorId: UUID,
        managerId: UUID,
        code: String,
        name: String,
    ): UUID {
        val normalizedCode = code.trim().uppercase(Locale.ROOT)
        val normalizedName = name.trim()
        require(normalizedCode.length in 1..64 && normalizedName.length in 1..160) {
            "Warehouse code or name is invalid."
        }
        return requireNotNull(transaction.execute {
            val activeOrg = jdbc.query(
                "SELECT id FROM organization WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                { rs, _ -> rs.getObject("id", UUID::class.java) },
                organizationId,
            ).singleOrNull()
            require(activeOrg != null) { "The organization is unavailable." }
            val now = clock.instant()
            require(source.isOrganizationMemberCurrent(operatorId, organizationId, now)) {
                "The audited operator must be a current organization member."
            }
            require(source.isOrganizationMemberCurrent(managerId, organizationId, now)) {
                "The assigned manager must be a current organization member."
            }
            val existing = persistence.warehouse(warehouseId)
            require(existing == null ||
                (existing.active && existing.organizationId == organizationId &&
                    existing.code == normalizedCode && existing.name == normalizedName)
            ) { "The Warehouse is unavailable or does not match the requested setup." }
            val managers = persistence.currentWarehouseAuthorityBindings(warehouseId, now)
                .filter { it.authorityKey == WarehouseAuthorityKey.WAREHOUSE_MANAGER }
            if (managers.isNotEmpty()) {
                return@execute requireNotNull(managers.singleOrNull {
                    it.principalType == InventoryPrincipalType.USER && it.principalId == managerId
                }) { "The Warehouse already has a current manager." }.bindingId
            }
            val managerHistory = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM warehouse_authority_binding
                    WHERE warehouse_id = ? AND authority_key = 'WAREHOUSE_MANAGER'
                )
                """.trimIndent(),
                Boolean::class.java,
                warehouseId,
            )
            require(managerHistory == false) { "Existing manager history requires separate authority review." }

            if (existing == null) {
                persistence.insertWarehouse(warehouseId, organizationId, normalizedCode, normalizedName, null)
            }
            val warehouse = requireNotNull(persistence.warehouse(warehouseId))
            val bindingId = UUID.randomUUID()
            persistence.insertWarehouseAuthorityBinding(
                bindingId, organizationId, warehouseId, WarehouseAuthorityKey.WAREHOUSE_MANAGER,
                InventoryPrincipalType.USER, managerId, now, operatorId, now,
            )
            projection.warehouseCreated(warehouse, UUID.randomUUID())
            projection.warehouseAuthority(bindingId)
            audit.append(
                AuditEventRecord(
                    actorUserId = operatorId,
                    action = "WAREHOUSE_INITIAL_MANAGER_PROVISIONED",
                    targetType = "WAREHOUSE_AUTHORITY_BINDING",
                    targetId = bindingId,
                    newStateRef = WarehouseAuthorityKey.WAREHOUSE_MANAGER.name,
                    safeDiffJson = """{"fields":["warehouseId","principalUserId","effectiveFrom"]}""",
                    occurredAt = now,
                    correlationId = bindingId.toString(),
                ),
            )
            bindingId
        })
    }
}
