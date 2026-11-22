package com.hiltech.server.procurement

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Disposable real PostgreSQL acceptance test, not mocked business rules:
 * source -> team membership -> submitted request -> independent READY review
 * -> RFQ -> active supplier link -> quote document slot.
 * Direct SQL self-review and stale team/source attempts are refused.
 */
class ServiceSourcingPostgresIntegrationTest {
    @Test
    fun reviewedTeamServiceCanSourceCurrentSupplierButNeverSkipIndependentApproval() {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
        val url=System.getenv("HILTECH_DB_URL")?:"jdbc:postgresql://localhost:5432/hiltech"
        val user=System.getenv("HILTECH_DB_USER")?:"hiltech"
        val password=System.getenv("HILTECH_DB_PASSWORD")?:"hiltech"
        val migrations=System.getenv("HILTECH_MIGRATIONS_PATH")?:"database/migrations"
        val ds=DriverManagerDataSource(url,user,password)
        Flyway.configure().dataSource(ds).locations("filesystem:"+migrations)
            .load().migrate()
        val db=JdbcTemplate(ds)
        val org=UUID.randomUUID()
        val supplier=UUID.randomUUID()
        val requester=UUID.randomUUID()
        val reviewer=UUID.randomUUID()
        val team=UUID.randomUUID()
        val need=UUID.randomUUID()
        val rfq=UUID.randomUUID()
        val at=OffsetDateTime.now(ZoneOffset.UTC)
        val name="Test Service Procurement"
        val spec="Qualified external calibration and original field test delivery."
        val criteria="Signed independent test report, calibration ID and documented scope."
        val reason="Third-party test equipment calibration required by the team."

        db.update(
            """
            INSERT INTO organization
             (id,organization_code,legal_name,display_name,organization_type,status,created_at)
             VALUES (?,?,?,?, 'HILTECH','ACTIVE',?)
            """.trimIndent(),
            org,"TEST-SVC-"+org.toString(),name,name,at,
        )
        db.update(
            """
            INSERT INTO organization
             (id,organization_code,legal_name,display_name,organization_type,status,created_at)
             VALUES (?,?,?,?, 'SUPPLIER','ACTIVE',?)
            """.trimIndent(),
            supplier,"TEST-SUP-"+supplier.toString(),"Synthetic supplier",
            "Synthetic supplier",at,
        )
        listOf(requester,reviewer).forEach { id->
            db.update(
                """
                INSERT INTO user_identity
                 (id,auth_provider,auth_subject,status,primary_organization_id,created_at)
                 VALUES (?,?,?,'ACTIVE',?,?)
                """.trimIndent(),
                id,"PHASE7-TEST","service-integration-"+id.toString(),org,at,
            )
            db.update(
                """
                INSERT INTO organization_membership
                 (id,organization_id,user_identity_id,membership_type,state,valid_from)
                 VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)
                """.trimIndent(),UUID.randomUUID(),org,id,at.minusDays(1),
            )
        }
        db.update(
            """
            INSERT INTO team(id,organization_id,code,name,active,version)
            VALUES (?,?,?, ?,true,1)
            """.trimIndent(),team,org,"SVC-"+team.toString(),"External services team",
        )
        db.update(
            """
            INSERT INTO team_membership
             (id,team_id,user_identity_id,role_in_team,valid_from)
             VALUES (?,?,?,'MEMBER',?)
            """.trimIndent(),UUID.randomUUID(),team,requester,at.minusDays(1),
        )
        db.update(
            """
            INSERT INTO procurement_service_need
             (id,operation_id,organization_id,team_id,team_version,specification,
              acceptance_criteria,justification,requested_by_user_id,created_at,correlation_id)
             VALUES (?,?,?,?,1,?,?,?,?,?,'service-postgres-integration')
            """.trimIndent(),
            need,UUID.randomUUID(),org,team,spec,criteria,reason,requester,at,
        )
        fun insertEvent(
            type:String,from:String,to:String,actor:UUID,source:Long,result:Long
        ) = db.update(
            """
            INSERT INTO procurement_service_need_event
             (id,operation_id,organization_id,need_id,source_version,result_version,
              transition_type,from_state,to_state,actor_user_id,occurred_at,correlation_id)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,'service-postgres-integration')
            """.trimIndent(),
            UUID.randomUUID(),UUID.randomUUID(),org,need,source,result,
            type,from,to,actor,at,
        )
        fun insertRfq(version:Long,id:UUID) = db.update(
            """
            INSERT INTO procurement_service_rfq
             (id,operation_id,organization_id,service_need_id,service_need_version,
              specification,acceptance_criteria,created_by_user_id,created_at,correlation_id)
             VALUES (?,?,?,?,?,?,?,?,?,'service-postgres-integration')
            """.trimIndent(),id,UUID.randomUUID(),org,need,version,
            spec,criteria,reviewer,at,
        )
        fun refused(block:()->Unit) {
            val error=assertThrows<DataIntegrityViolationException>{block()}
            val states=generateSequence(error as Throwable?) {it.cause}
                .filterIsInstance<SQLException>().map{it.sqlState}.toList()
            assertTrue("23514" in states,"Expected explicit guarded-source refusal, got "+states)
        }

        // Cannot invent sourcing authorization without a technical decision.
        refused {insertRfq(2L,UUID.randomUUID())}
        insertEvent("SUBMIT","DRAFT","SUBMITTED",requester,1,2)
        // The requester cannot approve their own service purchase.
        refused {insertEvent("TECHNICAL_READY","SUBMITTED",
            "READY_FOR_SOURCING",requester,2,3)}
        insertEvent("TECHNICAL_READY","SUBMITTED",
            "READY_FOR_SOURCING",reviewer,2,3)
        insertRfq(3L,rfq)
        // A previous service version is never equivalent to the accepted one.
        refused {insertRfq(2L,UUID.randomUUID())}

        db.update(
            """
            INSERT INTO procurement_supplier_link_event
             (id,organization_id,supplier_organization_id,revision_number,state,
              source_reference,recorded_by_user_id,recorded_at,correlation_id)
             VALUES (?,?,?,1,'ACTIVE',?,?,?,'service-postgres-integration')
            """.trimIndent(),
            UUID.randomUUID(),org,supplier,"Synthetic supplier test link",reviewer,at,
        )
        fun slot(id:UUID) = db.update(
            """
            INSERT INTO procurement_service_quote_document_slot
             (id,operation_id,organization_id,rfq_id,supplier_organization_id,
              created_by_user_id,created_at,correlation_id)
             VALUES (?,?,?,?,?,?,?,'service-postgres-integration')
            """.trimIndent(),id,UUID.randomUUID(),org,rfq,supplier,reviewer,at,
        )
        slot(UUID.randomUUID())
        // A later company team revision invalidates the frozen source. No
        // further supplier quote slot can be opened against old approval.
        db.update("UPDATE team SET version=2 WHERE id=?",team)
        refused {slot(UUID.randomUUID())}
    }
}
