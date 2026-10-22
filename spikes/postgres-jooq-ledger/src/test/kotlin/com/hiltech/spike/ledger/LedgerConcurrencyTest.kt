package com.hiltech.spike.ledger

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerConcurrencyTest {
    private lateinit var store: LedgerStore

    @BeforeEach
    fun reset() {
        store = LedgerStore(
            url = System.getenv("PG_URL") ?: "jdbc:postgresql://localhost:5432/hiltech_spike",
            user = System.getenv("PG_USER") ?: "hiltech",
            password = System.getenv("PG_PASSWORD") ?: "hiltech",
        )

        store.resetSchema()
    }

    @Test
    fun two_concurrent_checkouts_cannot_create_two_custodians() {
        store.seedAsset("fluke-03")

        val outcomes = concurrent(
            first = {
                store.checkoutAsset(
                    operationId = "checkout-a",
                    assetId = "fluke-03",
                    custodian = "tech-a",
                    expectedVersion = 1,
                )
            },
            second = {
                store.checkoutAsset(
                    operationId = "checkout-b",
                    assetId = "fluke-03",
                    custodian = "tech-b",
                    expectedVersion = 1,
                )
            },
        )

        assertEquals(1, outcomes.count { it == CommandResult.APPLIED })
        assertEquals(1, outcomes.count { it == CommandResult.CONFLICT })

        val asset = store.assetSnapshot("fluke-03")
        assertEquals("CHECKED_OUT", asset.state)
        assertTrue(asset.custodian == "tech-a" || asset.custodian == "tech-b")
        assertEquals(2, asset.version)
        assertEquals(1, store.assetMovementCount())
    }

    @Test
    fun retry_with_same_operation_id_is_idempotent() {
        store.seedAsset("otdr-01")

        val first = store.checkoutAsset(
            operationId = "checkout-once",
            assetId = "otdr-01",
            custodian = "tech-a",
            expectedVersion = 1,
        )

        val retry = store.checkoutAsset(
            operationId = "checkout-once",
            assetId = "otdr-01",
            custodian = "tech-a",
            expectedVersion = 1,
        )

        assertEquals(CommandResult.APPLIED, first)
        assertEquals(CommandResult.ALREADY_APPLIED, retry)
        assertEquals(1, store.assetMovementCount())
        assertEquals(2, store.assetSnapshot("otdr-01").version)
    }

    @Test
    fun stale_version_is_rejected_without_new_ledger_entry() {
        store.seedAsset("meter-01")

        assertEquals(
            CommandResult.APPLIED,
            store.checkoutAsset(
                operationId = "checkout-first",
                assetId = "meter-01",
                custodian = "tech-a",
                expectedVersion = 1,
            ),
        )

        assertEquals(
            CommandResult.CONFLICT,
            store.checkoutAsset(
                operationId = "checkout-stale",
                assetId = "meter-01",
                custodian = "tech-b",
                expectedVersion = 1,
            ),
        )

        assertEquals(1, store.assetMovementCount())
    }

    @Test
    fun concurrent_stock_issue_cannot_go_negative() {
        store.seedStock(
            sku = "fiber-patch",
            quantity = 10,
        )

        val outcomes = concurrent(
            first = {
                store.issueStock(
                    operationId = "stock-a",
                    sku = "fiber-patch",
                    quantity = 7,
                    expectedVersion = 1,
                )
            },
            second = {
                store.issueStock(
                    operationId = "stock-b",
                    sku = "fiber-patch",
                    quantity = 7,
                    expectedVersion = 1,
                )
            },
        )

        assertEquals(1, outcomes.count { it == CommandResult.APPLIED })
        assertEquals(1, outcomes.count { it == CommandResult.CONFLICT })

        val stock = store.stockSnapshot("fiber-patch")
        assertEquals(3, stock.quantity)
        assertEquals(2, stock.version)
        assertEquals(1, store.stockMovementCount())
    }

    private fun concurrent(
        first: () -> CommandResult,
        second: () -> CommandResult,
    ): List<CommandResult> {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)

        return try {
            val one = executor.submit<CommandResult> {
                start.await()
                first()
            }

            val two = executor.submit<CommandResult> {
                start.await()
                second()
            }

            start.countDown()

            listOf(
                one.get(20, TimeUnit.SECONDS),
                two.get(20, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
