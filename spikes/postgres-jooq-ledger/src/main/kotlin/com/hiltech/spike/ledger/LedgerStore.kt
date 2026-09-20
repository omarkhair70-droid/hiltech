package com.hiltech.spike.ledger

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.sql.DriverManager

enum class CommandResult {
    APPLIED,
    ALREADY_APPLIED,
    CONFLICT,
}

data class AssetSnapshot(
    val state: String,
    val custodian: String?,
    val version: Long,
)

data class StockSnapshot(
    val quantity: Int,
    val version: Long,
)

class LedgerStore(
    private val url: String,
    private val user: String,
    private val password: String,
) {
    fun resetSchema() {
        transaction { ctx ->
            ctx.execute("DROP TABLE IF EXISTS stock_movement")
            ctx.execute("DROP TABLE IF EXISTS stock_item")
            ctx.execute("DROP TABLE IF EXISTS asset_movement")
            ctx.execute("DROP TABLE IF EXISTS asset")

            ctx.execute(
                """
                CREATE TABLE asset (
                    id TEXT PRIMARY KEY,
                    state TEXT NOT NULL CHECK (state IN ('AVAILABLE', 'CHECKED_OUT')),
                    custodian TEXT,
                    version BIGINT NOT NULL CHECK (version >= 1),
                    CHECK (
                        (state = 'AVAILABLE' AND custodian IS NULL)
                        OR
                        (state = 'CHECKED_OUT' AND custodian IS NOT NULL)
                    )
                )
                """.trimIndent(),
            )

            ctx.execute(
                """
                CREATE TABLE asset_movement (
                    id BIGSERIAL PRIMARY KEY,
                    operation_id TEXT NOT NULL UNIQUE,
                    asset_id TEXT NOT NULL REFERENCES asset(id),
                    movement_type TEXT NOT NULL,
                    custodian TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )

            ctx.execute(
                """
                CREATE TABLE stock_item (
                    sku TEXT PRIMARY KEY,
                    quantity INTEGER NOT NULL CHECK (quantity >= 0),
                    version BIGINT NOT NULL CHECK (version >= 1)
                )
                """.trimIndent(),
            )

            ctx.execute(
                """
                CREATE TABLE stock_movement (
                    id BIGSERIAL PRIMARY KEY,
                    operation_id TEXT NOT NULL UNIQUE,
                    sku TEXT NOT NULL REFERENCES stock_item(sku),
                    quantity_delta INTEGER NOT NULL CHECK (quantity_delta <> 0),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
        }
    }

    fun seedAsset(assetId: String) {
        transaction { ctx ->
            ctx.execute(
                "INSERT INTO asset (id, state, custodian, version) VALUES (?, 'AVAILABLE', NULL, 1)",
                assetId,
            )
        }
    }

    fun seedStock(
        sku: String,
        quantity: Int,
    ) {
        transaction { ctx ->
            ctx.execute(
                "INSERT INTO stock_item (sku, quantity, version) VALUES (?, ?, 1)",
                sku,
                quantity,
            )
        }
    }

    fun checkoutAsset(
        operationId: String,
        assetId: String,
        custodian: String,
        expectedVersion: Long,
    ): CommandResult =
        transaction { ctx ->
            if (assetMovementExists(ctx, operationId)) {
                return@transaction CommandResult.ALREADY_APPLIED
            }

            val changed = ctx.execute(
                """
                UPDATE asset
                SET state = 'CHECKED_OUT',
                    custodian = ?,
                    version = version + 1
                WHERE id = ?
                  AND state = 'AVAILABLE'
                  AND version = ?
                """.trimIndent(),
                custodian,
                assetId,
                expectedVersion,
            )

            if (changed == 0) {
                return@transaction if (assetMovementExists(ctx, operationId)) {
                    CommandResult.ALREADY_APPLIED
                } else {
                    CommandResult.CONFLICT
                }
            }

            ctx.execute(
                """
                INSERT INTO asset_movement (
                    operation_id,
                    asset_id,
                    movement_type,
                    custodian
                )
                VALUES (?, ?, 'CHECKOUT', ?)
                """.trimIndent(),
                operationId,
                assetId,
                custodian,
            )

            CommandResult.APPLIED
        }

    fun issueStock(
        operationId: String,
        sku: String,
        quantity: Int,
        expectedVersion: Long,
    ): CommandResult =
        transaction { ctx ->
            require(quantity > 0)

            if (stockMovementExists(ctx, operationId)) {
                return@transaction CommandResult.ALREADY_APPLIED
            }

            val changed = ctx.execute(
                """
                UPDATE stock_item
                SET quantity = quantity - ?,
                    version = version + 1
                WHERE sku = ?
                  AND version = ?
                  AND quantity >= ?
                """.trimIndent(),
                quantity,
                sku,
                expectedVersion,
                quantity,
            )

            if (changed == 0) {
                return@transaction if (stockMovementExists(ctx, operationId)) {
                    CommandResult.ALREADY_APPLIED
                } else {
                    CommandResult.CONFLICT
                }
            }

            ctx.execute(
                """
                INSERT INTO stock_movement (
                    operation_id,
                    sku,
                    quantity_delta
                )
                VALUES (?, ?, ?)
                """.trimIndent(),
                operationId,
                sku,
                -quantity,
            )

            CommandResult.APPLIED
        }

    fun assetSnapshot(assetId: String): AssetSnapshot =
        transaction { ctx ->
            val record = ctx.fetchOne(
                "SELECT state, custodian, version FROM asset WHERE id = ?",
                assetId,
            ) ?: error("Asset not found")

            AssetSnapshot(
                state = record.get("state", String::class.java),
                custodian = record.get("custodian", String::class.java),
                version = record.get("version", java.lang.Long::class.java).toLong(),
            )
        }

    fun stockSnapshot(sku: String): StockSnapshot =
        transaction { ctx ->
            val record = ctx.fetchOne(
                "SELECT quantity, version FROM stock_item WHERE sku = ?",
                sku,
            ) ?: error("Stock item not found")

            StockSnapshot(
                quantity = record.get("quantity", Integer::class.java).toInt(),
                version = record.get("version", java.lang.Long::class.java).toLong(),
            )
        }

    fun assetMovementCount(): Int =
        transaction { ctx ->
            ctx.fetchOne("SELECT COUNT(*) AS c FROM asset_movement")!!
                .get("c", java.lang.Long::class.java)
                .toInt()
        }

    fun stockMovementCount(): Int =
        transaction { ctx ->
            ctx.fetchOne("SELECT COUNT(*) AS c FROM stock_movement")!!
                .get("c", java.lang.Long::class.java)
                .toInt()
        }

    private fun assetMovementExists(
        ctx: DSLContext,
        operationId: String,
    ): Boolean =
        ctx.fetchExists(
            DSL.selectOne()
                .from("asset_movement")
                .where(DSL.field("operation_id").eq(operationId)),
        )

    private fun stockMovementExists(
        ctx: DSLContext,
        operationId: String,
    ): Boolean =
        ctx.fetchExists(
            DSL.selectOne()
                .from("stock_movement")
                .where(DSL.field("operation_id").eq(operationId)),
        )

    private fun <T> transaction(block: (DSLContext) -> T): T {
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            val ctx = DSL.using(connection, SQLDialect.POSTGRES)

            try {
                val result = block(ctx)
                connection.commit()
                return result
            } catch (throwable: Throwable) {
                connection.rollback()
                throw throwable
            }
        }
    }
}
