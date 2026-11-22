package com.hiltech.server

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.SupplierSourcingPersistence
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionRetryPolicy
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcAuthorizationProjectionStore
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.ProjectionGuardDecision
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import com.hiltech.server.warehouse.AddInventoryImportRowCommand
import com.hiltech.server.warehouse.CreateInventoryImportBatchCommand
import com.hiltech.server.warehouse.InventoryCandidateClass
import com.hiltech.server.warehouse.InventoryImportIssueCode
import com.hiltech.server.warehouse.InventoryImportReviewState
import com.hiltech.server.warehouse.InventoryImportSourceType
import com.hiltech.server.warehouse.JdbcWarehousePersistence
import com.hiltech.server.warehouse.ReviewInventoryImportRowCommand
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehouseImportCreateService
import com.hiltech.server.warehouse.WarehouseImportRowCreateService
import com.hiltech.server.warehouse.WarehouseImportRowReviewService
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DatabaseMigrationContractTest {
    private val enabled = System.getenv("HILTECH_DB_CONTRACT_TEST") == "1"
    private val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrationPath = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"

    @Test
    fun emptyPostgresMigratesThroughV0033ThenV0042AndRejectsInvalidStates() {
        assumeTrue(enabled)

        val priorSliceResult =
            try {
                Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("filesystem:$migrationPath")
                    .target("33")
                    .load()
                    .migrate()
            } catch (failure: Exception) {
                System.err.println(
                    "HILTECH_MIGRATION_CONTRACT_TARGET33_FAILURE: " +
                        generateSequence(failure as Throwable?) { it.cause }
                            .joinToString(" -> ") {
                                it::class.java.simpleName + ": " + (it.message ?: "<no message>")
                            },
                )
                throw failure
            }

        assertEquals(33, priorSliceResult.migrationsExecuted)

        val result = Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        assertEquals(27, result.migrationsExecuted)

        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                val tables = statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                        'organization','user_identity','config_revision','project','site',
                        'work_order','asset','stock_balance','evidence',
                        'authorization_relation_projection','authorization_projection_outbox',
                        'identity_session','activity_event','event_publication',
                        'approval_authority_binding','approval_policy_version','approval_request',
                        'approval_step','approval_assignment','approval_decision',
                        'inbox_item','inbox_user_state',
                        'notification_intent','notification_delivery_attempt',
                        'person','employee','employment',
                        'people_authority_binding',
                        'project_authority_binding','project_responsibility',
                        'workforce_assignment',
                        'team_membership_authority_aggregate',
                        'employee_document',
                        'certification',
                        'onboarding_policy',
                        'onboarding_policy_requirement',
                        'onboarding_case',
                        'onboarding_manual_requirement_resolution',
                        'employee_identity_invitation',
                        'offboarding_case',
                        'offboarding_clearance',
                        'milestone',
                        'work_package',
                        'project_plan_dependency',
                        'work_task',
                        'work_order_dependency',
                        'work_readiness_waiver',
                        'work_crew',
                        'work_crew_member',
                        'project_health_signal',
                        'project_hold_record',
                        'receiving_record',
                        'receiving_line',
                        'receiving_discrepancy',
                        'stocktake_session',
                        'stocktake_snapshot_line',
                        'stock_count_record',
                        'inventory_discrepancy',
                        'inventory_adjustment_request',
                        'field_material_usage_report',
                        'field_material_usage_reconciliation',
                        'field_engineering_test_result',
                        'work_document',
                        'work_document_revision',
                        'work_document_revision_release',
                        'work_completion_submission',
                        'procurement_purchase_requirement',
                        'procurement_requirement_transition',
                        'procurement_supplier_rfq',
                        'procurement_supplier_quote_evidence',
                        'procurement_supplier_quote_technical_assessment',
                        'procurement_supplier_link_event',
                        'procurement_supplier_quote_document_slot',
                        'procurement_po_candidate',
                        'procurement_po_number_counter',
                        'procurement_purchase_order',
                        'procurement_po_lifecycle_event',
                        'procurement_po_delivery_amendment_proposal',
                        'procurement_po_receiving_link',
                        'procurement_po_supplier_communication_event',
                        'procurement_supplier_invoice_document_slot',
                        'procurement_supplier_invoice_intake',
                        'procurement_supplier_invoice_match_event',
                        'procurement_supplier_invoice_finance_handoff',
                        'procurement_po_supersession_link',
                        'procurement_po_receiving_rejection_event'
                      )
                    """.trimIndent(),
                )
                assertTrue(tables.next())
                assertEquals(86, tables.getInt(1))

                // V0050 links canonical Warehouse Receiving to a company-scoped
                // approved PO without creating another physical stock ledger.
                val poReceivingForeignKeys = statement.executeQuery(
                    """
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_po_receiving_link'::regclass
                      AND conname IN ('fk_po_receipt_record', 'fk_po_receipt_order',
                          'fk_po_receipt_destination', 'fk_po_receipt_stock')
                      AND contype = 'f'
                    """.trimIndent(),
                )
                assertTrue(poReceivingForeignKeys.next())
                assertEquals(4, poReceivingForeignKeys.getInt(1))
                val poReceivingGuards = statement.executeQuery(
                    """
                    SELECT count(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_po_receiving_link'::regclass
                      AND tgname IN ('procurement_po_receiving_link_insert_guard',
                          'procurement_po_receiving_link_immutable')
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(poReceivingGuards.next())
                assertEquals(2, poReceivingGuards.getInt(1))

                // V0051 is an append-only, company-scoped record of external
                // communication attestations, not an email-sending service.
                val supplierCommunicationTriggers = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_po_supplier_communication_event'::regclass
                      AND tgname IN (
                          'procurement_po_supplier_communication_insert_guard',
                          'procurement_po_supplier_communication_immutable'
                      )
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(supplierCommunicationTriggers.next())
                assertEquals(2, supplierCommunicationTriggers.getInt(1))

                // S05 supplier Invoice/Match/Handoff have immutable histories.
                val invoiceHistoryTriggers = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal AND (
                      (tgrelid='procurement_supplier_invoice_document_slot'::regclass
                        AND tgname='procurement_supplier_invoice_slot_immutable')
                      OR (tgrelid='procurement_supplier_invoice_intake'::regclass
                        AND tgname IN ('procurement_supplier_invoice_intake_insert_guard',
                          'procurement_supplier_invoice_intake_immutable'))
                      OR (tgrelid='procurement_supplier_invoice_match_event'::regclass
                        AND tgname='procurement_supplier_invoice_match_immutable')
                      OR (tgrelid='procurement_supplier_invoice_finance_handoff'::regclass
                        AND tgname='procurement_supplier_invoice_finance_handoff_immutable')
                    )
                    """.trimIndent(),
                )
                assertTrue(invoiceHistoryTriggers.next())
                assertEquals(5, invoiceHistoryTriggers.getInt(1))

                val poReplacementGuards = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid='procurement_po_supersession_link'::regclass
                      AND tgname IN ('procurement_po_supersession_insert_guard',
                        'procurement_po_supersession_immutable')
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(poReplacementGuards.next())
                assertEquals(2, poReplacementGuards.getInt(1))

                // V0054 records rejected PO arrivals without inflating the
                // StockLedger. The canonical Receiving completion function
                // is upgraded rather than rewriting the old V0032 migration.
                val rejectionGuards = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid='procurement_po_receiving_rejection_event'::regclass
                      AND tgname IN ('procurement_po_receiving_rejection_insert_guard',
                        'procurement_po_receiving_rejection_immutable')
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(rejectionGuards.next())
                assertEquals(2, rejectionGuards.getInt(1))

                val rejectedMovementGuard = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid='receiving_line'::regclass
                      AND tgname='receiving_rejected_po_line_receipt_guard'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(rejectedMovementGuard.next())
                assertEquals(1, rejectedMovementGuard.getInt(1))

                val fullLineRejectionGuard = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid='procurement_po_receiving_rejection_event'::regclass
                      AND tgname='po_rejection_full_line_guard'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(fullLineRejectionGuard.next())
                assertEquals(1, fullLineRejectionGuard.getInt(1))

                val foldedInvoiceIndex = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE schemaname='public'
                      AND tablename='procurement_supplier_invoice_intake'
                      AND indexname='uq_supplier_invoice_number_casefold'
                      AND indexdef ILIKE '%lower%'
                      AND indexdef ILIKE '%btrim%'
                    """.trimIndent(),
                )
                assertTrue(foldedInvoiceIndex.next())
                assertEquals(1, foldedInvoiceIndex.getInt(1))

                // PO receipts need READY private binary Evidence, not a
                // free-text deliveryDocumentRef, before final completion.
                val deliveryNoteGuards = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal AND (
                        (tgrelid='evidence'::regclass
                          AND tgname='po_receiving_note_evidence_insert_guard')
                        OR (tgrelid='receiving_record'::regclass
                          AND tgname='po_receiving_close_delivery_note_guard')
                    )
                    """.trimIndent(),
                )
                assertTrue(deliveryNoteGuards.next())
                assertEquals(2, deliveryNoteGuards.getInt(1))
                val readyNoteIndex = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename='evidence'
                      AND indexname='uq_po_receiving_ready_delivery_note'
                    """.trimIndent(),
                )
                assertTrue(readyNoteIndex.next())
                assertEquals(1, readyNoteIndex.getInt(1))

                // V0058 uses a discriminated and FK-scoped real Warehouse
                // source, not a fabricated WorkOrder reference.
                val typedSourceGuards = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid='procurement_purchase_requirement'::regclass
                      AND conname IN (
                        'fk_procurement_requirement_warehouse_source',
                        'ck_procurement_requirement_typed_source'
                      )
                    """.trimIndent(),
                )
                assertTrue(typedSourceGuards.next())
                assertEquals(2, typedSourceGuards.getInt(1))
                val typedSourceIndex = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename='procurement_purchase_requirement'
                      AND indexname='idx_procurement_warehouse_demand'
                    """.trimIndent(),
                )
                assertTrue(typedSourceIndex.next())
                assertEquals(1, typedSourceIndex.getInt(1))

                // V0059 uses a real company-bound Asset / incident or
                // maintenance source; no synthetic WorkOrder or opaque JSON.
                val assetDemandConstraints = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid='procurement_purchase_requirement'::regclass
                      AND conname IN (
                        'ck_procurement_requirement_typed_source',
                        'fk_requirement_asset_company',
                        'fk_requirement_asset_incident',
                        'fk_requirement_asset_maintenance',
                        'fk_requirement_asset_delivery_location'
                      )
                    """.trimIndent(),
                )
                assertTrue(assetDemandConstraints.next())
                assertEquals(5, assetDemandConstraints.getInt(1))
                val assetDemandGuard = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid='procurement_purchase_requirement'::regclass
                      AND tgname='procurement_asset_source_insert_guard'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(assetDemandGuard.next())
                assertEquals(1, assetDemandGuard.getInt(1))
                val assetDemandIndex = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename='procurement_purchase_requirement'
                      AND indexname='idx_procurement_asset_purchase_need'
                    """.trimIndent(),
                )
                assertTrue(assetDemandIndex.next())
                assertEquals(1, assetDemandIndex.getInt(1))
                val assetIssueColumns = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema='public'
                      AND table_name='procurement_purchase_requirement'
                      AND column_name IN (
                        'asset_id', 'asset_source_version',
                        'asset_incident_id', 'asset_maintenance_id',
                        'asset_issue_version', 'asset_delivery_location_id',
                        'asset_delivery_location_version'
                      )
                    """.trimIndent(),
                )
                assertTrue(assetIssueColumns.next())
                assertEquals(7, assetIssueColumns.getInt(1))

                // V0060: a real current HILTECH team and company Warehouse
                // backs stocked office requests, with DB source type and scope.
                val officeChecks = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid='procurement_purchase_requirement'::regclass
                      AND conname IN ('ck_procurement_requirement_typed_source',
                        'fk_procurement_office_need_team',
                        'fk_procurement_office_need_location')
                    """.trimIndent(),
                )
                assertTrue(officeChecks.next())
                assertEquals(3, officeChecks.getInt(1))
                val officeGuard = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid='procurement_purchase_requirement'::regclass
                      AND tgname='procurement_office_stock_source_insert_guard'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(officeGuard.next())
                assertEquals(1, officeGuard.getInt(1))
                val officeIndex = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename='procurement_purchase_requirement'
                      AND indexname='idx_procurement_office_team_needs'
                    """.trimIndent(),
                )
                assertTrue(officeIndex.next())
                assertEquals(1, officeIndex.getInt(1))

                // V0041 is an append-only event extension, never a second
                // mutable root state. Verify real PostgreSQL metadata, not
                // merely Flyway migration count.
                val intakeChecks = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_requirement_transition'::regclass
                      AND conname IN ('ck_procurement_intake_transition',
                          'ck_procurement_review_reason', 'ck_procurement_revision_snapshot')
                      AND contype = 'c'
                    """.trimIndent(),
                )
                assertTrue(intakeChecks.next())
                assertEquals(3, intakeChecks.getInt(1))

                val revisionColumns = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'procurement_requirement_transition'
                      AND column_name IN (
                          'spec_quantity','spec_justification','spec_work_version','spec_item_version',
                          'spec_unit_code','spec_assessment_code','spec_visible_free_quantity','spec_assessed_at'
                      )
                    """.trimIndent(),
                )
                assertTrue(revisionColumns.next())
                assertEquals(8, revisionColumns.getInt(1))

                val immutableHistory = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_requirement_transition'::regclass
                      AND tgname = 'procurement_requirement_transition_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(immutableHistory.next())
                assertEquals(1, immutableHistory.getInt(1))

                // S02 supplier terms are immutable restricted evidence. Two
                // source-linked tables and protected revision uniqueness.
                val sourcingTriggers = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE NOT tgisinternal AND (
                        (tgrelid = 'procurement_supplier_rfq'::regclass
                            AND tgname = 'procurement_supplier_rfq_immutable')
                        OR (tgrelid = 'procurement_supplier_quote_evidence'::regclass
                            AND tgname = 'procurement_supplier_quote_evidence_immutable')
                    )
                    """.trimIndent(),
                )
                assertTrue(sourcingTriggers.next())
                assertEquals(2, sourcingTriggers.getInt(1))
                val technicalReviewConstraints = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE (conrelid = 'procurement_supplier_quote_technical_assessment'::regclass
                           AND conname = 'fk_procurement_technical_assessment_quote_source')
                       OR (conrelid = 'procurement_supplier_quote_technical_assessment'::regclass
                           AND conname = 'uq_procurement_quote_technical_review_revision')
                    """.trimIndent(),
                )
                assertTrue(technicalReviewConstraints.next())
                assertEquals(2, technicalReviewConstraints.getInt(1))
                val technicalReviewTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_supplier_quote_technical_assessment'::regclass
                      AND tgname = 'procurement_supplier_quote_technical_assessment_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(technicalReviewTrigger.next())
                assertEquals(1, technicalReviewTrigger.getInt(1))
                val supplierLinkTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_supplier_link_event'::regclass
                      AND tgname = 'procurement_supplier_link_event_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(supplierLinkTrigger.next())
                assertEquals(1, supplierLinkTrigger.getInt(1))
                val linkConstraints = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_supplier_link_event'::regclass
                      AND conname IN (
                          'uq_procurement_supplier_link_revision',
                          'ck_procurement_supplier_link_not_self'
                      )
                    """.trimIndent(),
                )
                assertTrue(linkConstraints.next())
                assertEquals(2, linkConstraints.getInt(1))
                val supplierSlotTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_supplier_quote_document_slot'::regclass
                      AND tgname = 'procurement_supplier_quote_document_slot_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(supplierSlotTrigger.next())
                assertEquals(1, supplierSlotTrigger.getInt(1))
                val candidateTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_po_candidate'::regclass
                      AND tgname = 'procurement_po_candidate_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(candidateTrigger.next())
                assertEquals(1, candidateTrigger.getInt(1))
                val purchaseOrderTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_purchase_order'::regclass
                      AND tgname = 'procurement_po_number_issue_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(purchaseOrderTrigger.next())
                assertEquals(1, purchaseOrderTrigger.getInt(1))
                val lifecycleEventTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_po_lifecycle_event'::regclass
                      AND tgname = 'procurement_po_lifecycle_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(lifecycleEventTrigger.next())
                assertEquals(1, lifecycleEventTrigger.getInt(1))
                val lifecycleOrgFk = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_po_lifecycle_event'::regclass
                      AND conname IN (
                        'fk_po_lifecycle_org',
                        'uq_po_lifecycle_version',
                        'fk_po_lifecycle_approved_proposal_scope',
                        'fk_po_lifecycle_approval_request_org',
                        'fk_po_lifecycle_new_destination_org',
                        'ck_po_lifecycle_specific_payload',
                        'ck_po_lifecycle_event_type'
                      )
                    """.trimIndent(),
                )
                assertTrue(lifecycleOrgFk.next())
                assertEquals(7, lifecycleOrgFk.getInt(1))
                val proposalTrigger = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'procurement_po_delivery_amendment_proposal'::regclass
                      AND tgname = 'procurement_po_delivery_proposal_immutable'
                      AND NOT tgisinternal
                    """.trimIndent(),
                )
                assertTrue(proposalTrigger.next())
                assertEquals(1, proposalTrigger.getInt(1))
                val proposalConstraints = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_po_delivery_amendment_proposal'::regclass
                      AND conname IN (
                        'fk_po_delivery_proposal_order_org',
                        'fk_po_delivery_proposal_old_location_org',
                        'fk_po_delivery_proposal_new_location_org',
                        'uq_po_delivery_proposal_base',
                        'ck_po_delivery_changed_destination'
                      )
                    """.trimIndent(),
                )
                assertTrue(proposalConstraints.next())
                assertEquals(5, proposalConstraints.getInt(1))
                val cancelIndex = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename = 'procurement_po_lifecycle_event'
                      AND indexname = 'uq_po_lifecycle_one_cancellation'
                    """.trimIndent(),
                )
                assertTrue(cancelIndex.next())
                assertEquals(1, cancelIndex.getInt(1))
                val purchaseOrderConstraints = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_purchase_order'::regclass
                      AND conname IN (
                        'fk_po_candidate_org',
                        'fk_po_location_org',
                        'fk_po_stock_item_org',
                        'fk_po_approval_policy',
                        'fk_po_approval_request_org',
                        'fk_po_issuer_binding_org',
                        'uq_po_organization_code',
                        'uq_po_organization_serial'
                      )
                    """.trimIndent(),
                )
                assertTrue(purchaseOrderConstraints.next())
                assertEquals(8, purchaseOrderConstraints.getInt(1))
                val candidateScopedFk = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'procurement_po_candidate'::regclass
                      AND conname IN (
                        'fk_procurement_candidate_rfq_scope',
                        'fk_procurement_candidate_quote_scope',
                        'fk_procurement_candidate_requirement_scope',
                        'fk_procurement_candidate_item_scope'
                      ) AND contype = 'f'
                    """.trimIndent(),
                )
                assertTrue(candidateScopedFk.next())
                assertEquals(4, candidateScopedFk.getInt(1))
                val sourcingUniques = statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE (conrelid = 'procurement_supplier_rfq'::regclass
                            AND conname = 'uq_procurement_rfq_source_version')
                       OR (conrelid = 'procurement_supplier_quote_evidence'::regclass
                            AND conname = 'uq_procurement_quote_supplier_revision')
                    """.trimIndent(),
                )
                assertTrue(sourcingUniques.next())
                assertEquals(2, sourcingUniques.getInt(1))
            }

            val orgId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val now = OffsetDateTime.now()

            connection.prepareStatement(
                """
                INSERT INTO organization
                    (id, organization_code, legal_name, display_name, organization_type, status, created_at, version)
                VALUES (?, ?, ?, ?, 'HILTECH', 'ACTIVE', ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, orgId)
                ps.setString(2, "HILTECH-CONTRACT")
                ps.setString(3, "HILTECH Contract Test")
                ps.setString(4, "HILTECH")
                ps.setObject(5, now)
                ps.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO user_identity
                    (id, auth_provider, auth_subject, status, primary_organization_id, created_at, version)
                VALUES (?, 'test', ?, 'ACTIVE', ?, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, "subject-${UUID.randomUUID()}")
                ps.setObject(3, orgId)
                ps.setObject(4, now)
                ps.executeUpdate()
            }

            verifySupplierLinkLookup(orgId, userId, now.toInstant())
            verifyWarehouseStaging(orgId, userId, now.toInstant())

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO organization
                        (id, organization_code, legal_name, display_name, organization_type, status, created_at, version)
                    VALUES (?, ?, 'Invalid', 'Invalid', 'HILTECH', 'BROKEN', ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setString(2, "INVALID-${UUID.randomUUID()}")
                    ps.setObject(3, now)
                    ps.executeUpdate()
                }
            }

            val activeConfigId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO config_revision
                    (id, scope_type, scope_organization_id, family, code, name, lifecycle_state,
                     revision_number, created_at, created_by, activated_at, activated_by, version)
                VALUES (?, 'SYSTEM', NULL, 'work-types', 'CONTRACT_DUP', 'Contract', 'ACTIVE',
                        1, ?, ?, ?, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, activeConfigId)
                ps.setObject(2, now)
                ps.setObject(3, userId)
                ps.setObject(4, now)
                ps.setObject(5, userId)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO config_revision
                        (id, scope_type, scope_organization_id, family, code, name, lifecycle_state,
                         revision_number, created_at, created_by, activated_at, activated_by, version)
                    VALUES (?, 'SYSTEM', NULL, 'work-types', 'CONTRACT_DUP', 'Contract 2', 'ACTIVE',
                            2, ?, ?, ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, now)
                    ps.setObject(3, userId)
                    ps.setObject(4, now)
                    ps.setObject(5, userId)
                    ps.executeUpdate()
                }
            }

            val ownerBindingId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO approval_authority_binding
                    (id, organization_id, authority_key, principal_type, principal_user_id,
                     effective_from, active, created_by_user_id, created_at, version)
                VALUES (?, ?, 'OWNER_FINAL', 'USER', ?, ?, true, ?, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, ownerBindingId)
                ps.setObject(2, orgId)
                ps.setObject(3, userId)
                ps.setObject(4, now)
                ps.setObject(5, userId)
                ps.setObject(6, now)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO approval_authority_binding
                        (id, organization_id, authority_key, principal_type, principal_user_id,
                         effective_from, active, created_by_user_id, created_at, version)
                    VALUES (?, ?, 'OWNER_FINAL', 'USER', ?, ?, true, ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, userId)
                    ps.setObject(4, now)
                    ps.setObject(5, userId)
                    ps.setObject(6, now)
                    ps.executeUpdate()
                }
            }

            val inboxItemId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO inbox_item (
                    id,
                    organization_id,
                    producer_key,
                    source_type,
                    source_id,
                    source_version,
                    action_key,
                    attention_class,
                    target_principal_type,
                    target_user_id,
                    state,
                    safe_title_code,
                    source_event_id,
                    created_at,
                    updated_at,
                    correlation_id,
                    version
                )
                VALUES (
                    ?, ?,
                    'approval:contract:decide',
                    'APPROVAL_REQUEST',
                    ?, 1,
                    'DECIDE_APPROVAL',
                    'ACTION_REQUIRED',
                    'USER', ?,
                    'OPEN',
                    'APPROVAL_DECISION_REQUIRED',
                    ?, ?, ?,
                    'corr-inbox-contract',
                    1
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inboxItemId)
                ps.setObject(2, orgId)
                ps.setObject(3, UUID.randomUUID())
                ps.setObject(4, userId)
                ps.setObject(5, UUID.randomUUID())
                ps.setObject(6, now)
                ps.setObject(7, now)
                ps.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO inbox_user_state (
                    inbox_item_id,
                    user_identity_id,
                    read_at,
                    updated_at,
                    version
                )
                VALUES (?, ?, NULL, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inboxItemId)
                ps.setObject(2, userId)
                ps.setObject(3, now)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO inbox_item (
                        id,
                        organization_id,
                        producer_key,
                        source_type,
                        source_id,
                        source_version,
                        action_key,
                        attention_class,
                        target_principal_type,
                        target_user_id,
                        target_team_id,
                        state,
                        safe_title_code,
                        source_event_id,
                        created_at,
                        updated_at,
                        version
                    )
                    VALUES (
                        ?, ?,
                        'invalid:principal:shape',
                        'APPROVAL_REQUEST',
                        ?, 1,
                        'DECIDE_APPROVAL',
                        'ACTION_REQUIRED',
                        'USER',
                        ?, ?,
                        'OPEN',
                        'APPROVAL_DECISION_REQUIRED',
                        ?, ?, ?, 1
                    )
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setObject(4, userId)
                    ps.setObject(5, UUID.randomUUID())
                    ps.setObject(6, UUID.randomUUID())
                    ps.setObject(7, now)
                    ps.setObject(8, now)
                    ps.executeUpdate()
                }
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO inbox_item (
                        id,
                        organization_id,
                        producer_key,
                        source_type,
                        source_id,
                        source_version,
                        action_key,
                        attention_class,
                        target_principal_type,
                        target_user_id,
                        state,
                        safe_title_code,
                        source_event_id,
                        created_at,
                        updated_at,
                        resolved_at,
                        version
                    )
                    VALUES (
                        ?, ?,
                        'invalid:open:resolved',
                        'APPROVAL_REQUEST',
                        ?, 1,
                        'DECIDE_APPROVAL',
                        'ACTION_REQUIRED',
                        'USER',
                        ?,
                        'OPEN',
                        'APPROVAL_DECISION_REQUIRED',
                        ?, ?, ?, ?, 1
                    )
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setObject(4, userId)
                    ps.setObject(5, UUID.randomUUID())
                    ps.setObject(6, now)
                    ps.setObject(7, now)
                    ps.setObject(8, now)
                    ps.executeUpdate()
                }
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO approval_policy_version
                        (id, organization_id, policy_key, version_number, lifecycle_state,
                         approval_required, step_mode, authority_key, reason_required,
                         effective_from, created_by_user_id, created_at, version)
                    VALUES (?, ?, 'ROUTINE_FINANCE_ADMIN', 1, 'ACTIVE',
                            false, 'SINGLE', 'OWNER_FINAL', false,
                            ?, ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, now)
                    ps.setObject(4, userId)
                    ps.setObject(5, now)
                    ps.executeUpdate()
                }
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO evidence
                        (id, organization_id, target_type, target_id, evidence_type_code, content_type,
                         size_bytes, sha256, captured_at, captured_by_user_id, storage_state,
                         classification_code, client_visibility_mode, created_at, version)
                    VALUES (?, ?, 'CONTRACT_TEST', ?, 'PHOTO', 'image/jpeg',
                            16777217, ?, ?, ?, 'RESERVED', 'INTERNAL', 'INTERNAL', ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setString(4, "a".repeat(64))
                    ps.setObject(5, now)
                    ps.setObject(6, userId)
                    ps.setObject(7, now)
                    ps.executeUpdate()
                }
            }
        }

        val dataSource = DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(dataSource)
        val transactionManager = DataSourceTransactionManager(dataSource)
        val transaction = TransactionTemplate(transactionManager)
        val openFgaProperties = HiltechOpenFgaProperties(
            enabled = true,
            apiUrl = "http://openfga-contract",
            storeId = "store-contract",
            authorizationModelId = "model-contract",
        )
        val intentWriter = JdbcAuthorizationProjectionIntentWriter(
            jdbc = jdbc,
            properties = openFgaProperties,
        )
        val retryPolicy = AuthorizationProjectionRetryPolicy()
        val projectionStore = JdbcAuthorizationProjectionStore(
            jdbc = jdbc,
            transactionManager = transactionManager,
            properties = openFgaProperties,
            retryPolicy = retryPolicy,
        )
        val guard = JdbcAuthorizationProjectionGuard(jdbc)

        val tuple = OpenFgaTuple(
            subjectType = "user",
            subjectId = "contract-user",
            relation = "assigned_user",
            objectType = "work_order",
            objectId = "contract-work",
        )
        val projectionStartedAt = Instant.parse("2026-09-19T00:00:00Z")

        transaction.executeWithoutResult {
            intentWriter.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple = tuple,
                    desiredState = AuthorizationDesiredState.PRESENT,
                    sourceType = "WorkAssignment",
                    sourceId = "contract-assignment",
                    sourceVersion = 1,
                    occurredAt = projectionStartedAt,
                ),
            )
        }

        assertEquals(
            ProjectionGuardDecision.DENY_FAIL_CLOSED,
            guard.evaluate(tuple),
            "Pending grants must fail closed before OpenFGA projection is APPLIED.",
        )

        val grantWork = projectionStore.claimNext(
            projectionStartedAt.plusSeconds(1),
        )
        assertTrue(grantWork != null, "Expected pending grant outbox work.")
        assertEquals(
            AuthorizationDesiredState.PRESENT,
            grantWork!!.projection.desiredState,
        )

        projectionStore.markApplied(
            work = grantWork,
            now = projectionStartedAt.plusSeconds(1),
        )

        assertEquals(
            ProjectionGuardDecision.PROCEED_TO_OPENFGA,
            guard.evaluate(tuple),
            "Applied grants may proceed to the pinned OpenFGA decision.",
        )

        transaction.executeWithoutResult {
            intentWriter.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple = tuple,
                    desiredState = AuthorizationDesiredState.ABSENT,
                    sourceType = "WorkAssignment",
                    sourceId = "contract-assignment",
                    sourceVersion = 2,
                    occurredAt = projectionStartedAt.plusSeconds(2),
                ),
            )
        }

        assertEquals(
            ProjectionGuardDecision.DENY_FAIL_CLOSED,
            guard.evaluate(tuple),
            "Pending revokes must deny immediately while stale OpenFGA tuples may still exist.",
        )

        val revokeWork = projectionStore.claimNext(
            projectionStartedAt.plusSeconds(3),
        )
        assertTrue(revokeWork != null, "Expected pending revoke outbox work.")
        assertEquals(
            AuthorizationDesiredState.ABSENT,
            revokeWork!!.projection.desiredState,
        )

        projectionStore.markApplied(
            work = revokeWork,
            now = projectionStartedAt.plusSeconds(3),
        )

        assertEquals(
            ProjectionGuardDecision.PROCEED_TO_OPENFGA,
            guard.evaluate(tuple),
            "Once revoke projection is APPLIED, normal OpenFGA evaluation may resume.",
        )

        val projectionState = jdbc.queryForMap(
            """
            SELECT desired_state, projection_state, authorization_model_id
            FROM authorization_relation_projection
            WHERE relation_key = ?
            """.trimIndent(),
            tuple.relationKey,
        )
        assertEquals("ABSENT", projectionState["desired_state"])
        assertEquals("APPLIED", projectionState["projection_state"])
        assertEquals("model-contract", projectionState["authorization_model_id"])

        val completedOutbox = jdbc.queryForObject(
            """
            SELECT count(*)
            FROM authorization_projection_outbox
            WHERE relation_key = ?
              AND completed_at IS NOT NULL
            """.trimIndent(),
            Int::class.java,
            tuple.relationKey,
        )
        assertEquals(2, completedOutbox)
    }

    private fun verifyWarehouseStaging(organizationId: UUID, actorId: UUID, at: Instant) {
        val dataSource = DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(dataSource)
        val persistence = JdbcWarehousePersistence(jdbc)
        val warehouseId = UUID.randomUUID()
        persistence.insertWarehouse(warehouseId, organizationId, "TEST-WAREHOUSE", "Synthetic warehouse", null)
        assertThrows<DataIntegrityViolationException> {
            persistence.insertWarehouse(UUID.randomUUID(), organizationId, "TEST-WAREHOUSE", "Duplicate", null)
        }
        val parent = UUID.randomUUID()
        val child = UUID.randomUUID()
        val insertLocation = """
            INSERT INTO storage_location (id, organization_id, warehouse_id, code, name, kind, parent_storage_location_id)
            VALUES (?, ?, ?, ?, ?, 'WAREHOUSE', ?::uuid)
        """.trimIndent()
        jdbc.update(insertLocation, parent, organizationId, warehouseId, "PARENT", "Synthetic parent", null)
        jdbc.update(insertLocation, child, organizationId, warehouseId, "CHILD", "Synthetic child", parent)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE storage_location SET parent_storage_location_id = ? WHERE id = ?", child, parent)
        }
        val projectId = UUID.randomUUID()
        val siteId = UUID.randomUUID()
        val timestamp = at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO project (id, organization_id, client_organization_id, project_code, name,
                lifecycle_state, created_at, created_by, updated_at, source_type)
            VALUES (?, ?, ?, 'TEST-STORAGE', 'Synthetic project', 'DRAFT', ?, ?, ?, 'INTERNAL')
            """.trimIndent(),
            projectId, organizationId, organizationId, timestamp, actorId, timestamp,
        )
        jdbc.update(
            """
            INSERT INTO site (id, organization_id, client_organization_id, site_code, name, status,
                created_at, created_by, updated_at)
            VALUES (?, ?, ?, 'TEST-STORAGE', 'Synthetic site', 'ACTIVE', ?, ?, ?)
            """.trimIndent(),
            siteId, organizationId, organizationId, timestamp, actorId, timestamp,
        )
        val siteStorageId = UUID.randomUUID()
        val insertSiteStorage = """
            INSERT INTO storage_location (id, organization_id, code, name, kind, project_id, site_id, temporary)
            VALUES (?, ?, 'SITE-TEMP', 'Synthetic site storage', 'SITE_STORAGE', ?, ?, true)
        """.trimIndent()
        assertThrows<DataIntegrityViolationException> {
            jdbc.update(insertSiteStorage, siteStorageId, organizationId, projectId, siteId)
        }
        jdbc.update(
            """
            INSERT INTO project_site (id, organization_id, project_id, site_id, lifecycle_state)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            UUID.randomUUID(), organizationId, projectId, siteId,
        )
        jdbc.update(insertSiteStorage, siteStorageId, organizationId, projectId, siteId)
        assertEquals(siteId, requireNotNull(persistence.storageLocation(siteStorageId)).siteId)

        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val audit = JdbcAuditEventWriter(jdbc)
        val authority = Mockito.mock(WarehouseAuthorizationPort::class.java)
        Mockito.`when`(authority.canManageImport(actorId, organizationId)).thenReturn(true)
        val executor = JdbcIdempotentCommandExecutor(
            jdbc,
            DataSourceTransactionManager(dataSource),
            clock,
            HiltechTelemetryRuntime(openTelemetry = OpenTelemetry.noop(), closeAction = {}),
        )
        val create = WarehouseImportCreateService(persistence, authority, executor, audit, clock)
        val stage = WarehouseImportRowCreateService(persistence, authority, executor, audit, clock)
        val review = WarehouseImportRowReviewService(persistence, authority, executor, audit, clock)
        val batchCommand = CreateInventoryImportBatchCommand(
            operationId = UUID.randomUUID(),
            organizationId = organizationId,
            sourceFileName = "synthetic-accessories.xlsx",
            sourceSha256 = "d".repeat(64),
            sourceType = InventoryImportSourceType.LEGACY_EXCEL,
            cutoverDate = null,
            actorUserId = actorId,
            clientOccurredAt = at,
            correlationId = "warehouse-migration-contract",
        )
        val batch = create.create(batchCommand).batch
        assertEquals(batch.batchId, create.create(batchCommand).also { assertTrue(it.replayed) }.batch.batchId)
        assertEquals("INVENTORY_IMPORT_DUPLICATE_SOURCE", assertThrows<ProductApiException> {
            create.create(batchCommand.copy(operationId = UUID.randomUUID()))
        }.code)

        val inventoryTables = listOf("stock_item", "stock_balance", "stock_movement", "asset", "asset_movement")
        val inventoryBefore = inventoryTables.map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) }
        val stageCommand = AddInventoryImportRowCommand(
            operationId = UUID.randomUUID(),
            batchId = batch.batchId,
            sourceSheet = "أكسسوارات",
            sourceRowNumber = 2,
            rawPayloadJson = """{"الرقم التسلسلي":"LOCAL","العدد":0,"سعر القطعه":123.45}""",
            candidateClass = InventoryCandidateClass.STOCK_QUANTITY,
            normalizedBrand = "TEST|BRAND",
            normalizedPartNumber = "TEST-PART",
            normalizedDescription = "Synthetic accessory",
            candidateUom = null,
            candidateQuantity = BigDecimal.ZERO,
            candidateValue = BigDecimal("123.45"),
            candidateCurrencyCode = "EGP",
            sourceIssueCodes = emptyList(),
            actorUserId = actorId,
            clientOccurredAt = at,
            correlationId = batchCommand.correlationId,
        )
        val row = stage.addRow(stageCommand).row
        val expectedIssues = setOf(
            InventoryImportIssueCode.LEGACY_COLUMN_SEMANTICS_AMBIGUOUS,
            InventoryImportIssueCode.UNKNOWN_UOM,
        )
        assertEquals(expectedIssues, row.issueCodes.toSet())
        assertTrue(stage.addRow(stageCommand).replayed)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows<ProductApiException> {
            stage.addRow(stageCommand.copy(sourceRowNumber = 3))
        }.code)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows<ProductApiException> {
            stage.addRow(stageCommand.copy(normalizedBrand = "TEST", normalizedPartNumber = "BRAND|TEST-PART"))
        }.code)

        val reviewCommand = ReviewInventoryImportRowCommand(
            operationId = UUID.randomUUID(),
            batchId = batch.batchId,
            rowId = row.rowId,
            baseVersion = row.version,
            candidateClass = row.candidateClass,
            normalizedBrand = row.normalizedBrand,
            normalizedPartNumber = row.normalizedPartNumber,
            normalizedDescription = row.normalizedDescription,
            candidateUom = "TEST-UOM",
            candidateQuantity = row.candidateQuantity,
            candidateValue = null,
            candidateCurrencyCode = null,
            issueCodes = emptyList(),
            reviewState = InventoryImportReviewState.NEEDS_REVIEW,
            reviewNotes = "Synthetic candidate unit supplied. The legacy label remains unresolved.",
            actorUserId = actorId,
            clientOccurredAt = at,
            correlationId = batchCommand.correlationId,
        )
        val reviewed = review.review(reviewCommand).row
        assertEquals(row.version + 1, reviewed.version)
        assertEquals(row.rawPayloadJson, reviewed.rawPayloadJson)
        assertEquals(row.candidateValue, reviewed.candidateValue)
        assertEquals(row.candidateCurrencyCode, reviewed.candidateCurrencyCode)
        assertEquals(listOf(InventoryImportIssueCode.LEGACY_COLUMN_SEMANTICS_AMBIGUOUS), reviewed.issueCodes)
        assertTrue(review.review(reviewCommand).replayed)
        assertEquals("INVENTORY_IMPORT_ROW_VERSION_CONFLICT", assertThrows<ProductApiException> {
            review.review(reviewCommand.copy(operationId = UUID.randomUUID()))
        }.code)
        assertEquals(
            inventoryBefore,
            inventoryTables.map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) },
        )
    }

    /**
     * Disposable PostgreSQL integration proof: active organization-scoped
     * supplier links are visible, then a later INACTIVE event revokes them.
     */
    private fun verifySupplierLinkLookup(orgId: UUID, userId: UUID, at: Instant) {
        val jdbc = JdbcTemplate(DriverManagerDataSource(url, user, password))
        val supplierId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name, display_name,
                organization_type, status, created_at, version
            ) VALUES (?, ?, 'Supplier contract fixture', 'Supplier contract fixture',
                      'SUPPLIER', 'ACTIVE', ?, 1)
            """.trimIndent(),
            supplierId, "SUP-TEST-${supplierId.toString().take(12)}",
            at.atOffset(ZoneOffset.UTC),
        )
        val sourcing = SupplierSourcingPersistence(jdbc)
        assertEquals(false, sourcing.isEligibleSupplier(orgId, supplierId))
        assertTrue(sourcing.eligibleSuppliers(orgId).isEmpty())

        fun addLink(revision: Int, state: String) {
            jdbc.update(
                """
                INSERT INTO procurement_supplier_link_event (
                    id, organization_id, supplier_organization_id, revision_number,
                    state, source_reference, recorded_by_user_id,
                    recorded_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), orgId, supplierId, revision,
                state, "contract-fixture-supplier-link-$revision", userId,
                at.atOffset(ZoneOffset.UTC), "contract-fixture",
            )
        }
        addLink(1, "ACTIVE")
        assertEquals(true, sourcing.isEligibleSupplier(orgId, supplierId))
        val listed = sourcing.eligibleSuppliers(orgId)
        assertEquals(1, listed.size)
        assertEquals(supplierId, listed.single().supplierOrganizationId)
        assertEquals(1, listed.single().relationshipRevision)

        addLink(2, "INACTIVE")
        assertEquals(false, sourcing.isEligibleSupplier(orgId, supplierId))
        assertTrue(sourcing.eligibleSuppliers(orgId).isEmpty())
    }

    private fun assertConstraintRejects(
        connection: java.sql.Connection,
        block: java.sql.Connection.() -> Unit,
    ) {
        var rejected = false
        try {
            connection.block()
        } catch (_: SQLException) {
            rejected = true
        }
        assertTrue(rejected, "Expected PostgreSQL to reject contract-invalid data")
    }
}
