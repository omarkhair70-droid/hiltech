package com.hiltech.server.warehouse

import com.hiltech.server.approval.ApprovalPolicyProvisioner
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
import java.util.UUID

internal fun provisionInventoryAdjustmentApprover(args: List<String>) {
    require(args.size == 5) {
        "Usage: provision-inventory-adjustment-approver <organization-id> <warehouse-id> <operator-id> <USER|TEAM> <principal-id>"
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
    val provisioner = WarehouseAdjustmentApprovalProvisioner(
        jdbc = jdbc,
        transaction = TransactionTemplate(DataSourceTransactionManager(dataSource)),
        persistence = persistence,
        source = JdbcRoleTeamSourceAuthority(jdbc),
        projection = WarehouseAuthorizationProjectionBridge(
            persistence, JdbcAuthorizationProjectionIntentWriter(jdbc, openFga), clock,
        ),
        audit = JdbcAuditEventWriter(jdbc),
        policy = ApprovalPolicyProvisioner(jdbc),
        clock = clock,
    )
    val bindingId = provisioner.provision(
        organizationId = UUID.fromString(args[0]),
        warehouseId = UUID.fromString(args[1]),
        operatorId = UUID.fromString(args[2]),
        principalType = InventoryPrincipalType.valueOf(args[3]),
        principalId = UUID.fromString(args[4]),
    )
    println("Inventory adjustment approver binding $bindingId. Use TEAM when multiple people may approve.")
}

internal class WarehouseAdjustmentApprovalProvisioner(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val persistence: WarehousePersistencePort,
    private val source: JdbcRoleTeamSourceAuthority,
    private val projection: WarehouseAuthorizationProjectionBridge,
    private val audit: JdbcAuditEventWriter,
    private val policy: ApprovalPolicyProvisioner,
    private val clock: Clock,
) {
    fun provision(
        organizationId: UUID,
        warehouseId: UUID,
        operatorId: UUID,
        principalType: InventoryPrincipalType,
        principalId: UUID,
    ): UUID = requireNotNull(transaction.execute {
        val now = clock.instant()
        persistence.warehouse(warehouseId)?.takeIf {
            it.active && it.organizationId == organizationId
        } ?: error("The Warehouse is unavailable.")
        require(source.isOrganizationMemberCurrent(operatorId, organizationId, now)) {
            "The audited operator must be a current organization member."
        }
        when (principalType) {
            InventoryPrincipalType.USER -> require(source.isOrganizationMemberCurrent(principalId, organizationId, now)) {
                "The approver user must be a current organization member."
            }
            InventoryPrincipalType.TEAM -> require(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM team WHERE id = ? AND organization_id = ? AND active = true)",
                Boolean::class.java, principalId, organizationId,
            ) == true) { "The approver team must be current in the organization." }
        }

        policy.ensureSingleApprovalPolicy(
            organizationId,
            INVENTORY_ADJUSTMENT_POLICY_KEY,
            INVENTORY_ADJUSTMENT_AUTHORITY_KEY,
            operatorId,
            now,
        )

        val current = persistence.currentWarehouseAuthorityBindings(warehouseId, now).filter {
            it.current && it.authorityKey == WarehouseAuthorityKey.INVENTORY_ADJUSTMENT_APPROVER
        }
        val exact = current.singleOrNull()?.takeIf {
            it.principalType == principalType && it.principalId == principalId
        }
        if (exact != null) return@execute exact.bindingId

        current.forEach {
            require(persistence.endWarehouseAuthorityBinding(it.bindingId, warehouseId, it.version, now)) {
                "Current adjustment authority changed during provisioning."
            }
            projection.warehouseAuthority(it.bindingId)
        }

        val bindingId = UUID.randomUUID()
        persistence.insertWarehouseAuthorityBinding(
            bindingId,
            organizationId,
            warehouseId,
            WarehouseAuthorityKey.INVENTORY_ADJUSTMENT_APPROVER,
            principalType,
            principalId,
            now,
            operatorId,
            now,
        )
        projection.warehouseAuthority(bindingId)
        audit.append(
            AuditEventRecord(
                actorUserId = operatorId,
                action = "INVENTORY_ADJUSTMENT_APPROVER_PROVISIONED",
                targetType = "WAREHOUSE_AUTHORITY_BINDING",
                targetId = bindingId,
                newStateRef = WarehouseAuthorityKey.INVENTORY_ADJUSTMENT_APPROVER.name,
                safeDiffJson = """{"fields":["warehouseId","principalType","principalId","effectiveFrom"]}""",
                occurredAt = now,
                correlationId = bindingId.toString(),
            ),
        )
        bindingId
    })
}
