package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * SQL shape test: ephemeral synthetic Finance projection, rolled back.
 * Does NOT purport to prove the separate Phase10 award schema/approvals or
 * invoice/AR accounting. That integration requires the real P10 migration.
 */
class FinanceClientAwardSourcePostgresContractTest {
    @Test fun projectedAwardPaginationAndExactFinanceStateNeverBecomeReceivables() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
        val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            assumeTrue(
                jdbc.queryForObject(
                    "SELECT to_regclass('public.commercial_finance_award_source') IS NULL",
                    Boolean::class.java,
                ) == true,
                "Real Phase10 award projection installed; this synthetic SQL fixture is omitted.",
            )
            val org = UUID.randomUUID()
            val client = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val now = Instant.parse("2026-11-23T15:00:00Z")
            val authorityKey = "FINANCE_AR_SOURCE_REVIEWER"
            val binding = ApprovalAuthorityBindingSnapshot(
                UUID.randomUUID(), org, authorityKey,
                ApprovalPrincipalType.USER, actor,
                now.minusSeconds(30), null, 1,
            )
            val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
            Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, now))
                .thenReturn(true)
            Mockito.`when`(approvals.resolveCurrentAuthority(org, authorityKey, now))
                .thenReturn(binding)
            Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, now))
                .thenReturn(true)
            val env = StandardEnvironment()
            env.propertySources.addFirst(MapPropertySource("finance-read-model-test",
                mapOf(
                    "hiltech.finance.receivables.sales-award-source-readiness.enabled" to true,
                    "hiltech.finance.receivables.sales-award-source-review-authority-key" to authorityKey,
                )))
            val finance = FinanceClientAwardSourceService(
                jdbc, approvals, env, Clock.fixed(now, ZoneOffset.UTC),
            )
            try {
                // Reproduces *exactly* Phase10's published view columns.
                // 101 rows intentionally exercises last-100 truncation semantics.
                jdbc.execute(
                    """
                    CREATE VIEW public.commercial_finance_award_source AS
                    SELECT '$org'::uuid AS organization_id,
                        md5('opportunity-' || g::text)::uuid AS opportunity_id,
                        md5('project-' || g::text)::uuid AS project_id,
                        md5('quote-' || g::text)::uuid AS quote_version_id,
                        md5('contract-' || g::text)::uuid AS contract_id,
                        md5('client-po-' || g::text)::uuid AS client_po_id,
                        '$client'::uuid AS client_organization_id,
                        'EGP'::char(3) AS currency,
                        (1500.000000 + g)::numeric(24,6) AS total_price,
                        'Terms require accountant confirmation'::text AS payment_terms,
                        2::integer AS quote_revision,
                        1::bigint AS quote_record_version
                    FROM generate_series(1,101) g
                    """.trimIndent(),
                )
                val page = finance.list(actor, org)
                assertTrue(page.sourceAvailable)
                assertEquals(100, page.rows.size)
                assertTrue(page.hasMoreSources)
                assertEquals(100, page.queryLimit)
                assertNull(page.reportedCompanyReceivableTotal)
                assertNull(page.reportedCompanyRevenueTotal)
                assertNull(page.reportedCustomerCollectionsTotal)

                val row = page.rows.first()
                assertEquals(org, row.organizationId)
                assertEquals(client, row.clientOrganizationId)
                assertEquals("EGP", row.currency)
                assertEquals(0, BigDecimal(row.sellAmount).remainder(BigDecimal.ONE)
                    .compareTo(BigDecimal.ZERO))
                assertTrue(BigDecimal(row.sellAmount) >= BigDecimal("1501"))
                assertTrue(BigDecimal(row.sellAmount) <= BigDecimal("1601"))
                assertEquals(64, row.sourceFingerprintSha256.length)
                assertTrue(row.financeReviewRequired)
                assertFalse(row.customerInvoiceIssued)
                assertFalse(row.receivableRecognized)
                assertFalse(row.billingMilestonesVerified)
                assertFalse(row.taxTreatmentVerified)
                assertFalse(row.amountDueOrOverdueKnown)
                assertFalse(row.cashCollected)
                assertFalse(row.cashReconciled)

                val exact = finance.detail(actor, org, row.opportunityId)
                assertEquals(row, exact)
                assertEquals(row.sourceFingerprintSha256, exact.sourceFingerprintSha256)
                assertNotEquals(org, client)
            } finally {
                connection.rollback()
            }
        }
    }
}
