package com.hiltech.server.commercial

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * Real PostgreSQL contract, run only AFTER the unnumbered commercial schema is
 * applied to a disposable database by the Phase10 isolation CI.
 */
class CommercialPostgresAuthorizationContractTest {
    @Test
    fun liveMembershipAndAuthorityAreRecheckedOnEveryCommand() {
        assumeTrue(System.getenv("HILTECH_COMMERCIAL_DB_TEST") == "1")
        database { jdbc ->
            val company = UUID.randomUUID()
            val otherCompany = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val member = UUID.randomUUID()
            val grant = UUID.randomUUID()
            val team = UUID.randomUUID()
            val teamMember = UUID.randomUUID()
            val teamGrant = UUID.randomUUID()

            fun org(id: UUID, code: String) = jdbc.update(
                """INSERT INTO organization (id,organization_code,legal_name,display_name,
                   organization_type,status,created_at)
                   VALUES (?,?,? ,?,'HILTECH','ACTIVE',now())""",
                id,code,code,code,
            )

            org(company,"P10_AUTH_"+company)
            org(otherCompany,"P10_AUTH_"+otherCompany)
            jdbc.update(
                """INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at)
                   VALUES (?,'phase10-contract-test',?,'ACTIVE',now())""",
                actor,actor.toString(),
            )
            jdbc.update(
                """INSERT INTO organization_membership
                   (id,organization_id,user_identity_id,membership_type,state,valid_from)
                   VALUES (?,?,?,'EMPLOYEE','ACTIVE',now()-interval '1 day')""",
                member,company,actor,
            )

            val access = CommercialAccess(jdbc,Clock.systemUTC(),true)

            // A HILTECH employee or Project worker has NO implicit Sales authority.
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_WRITE")
            }
            jdbc.update(
                """INSERT INTO commercial_authority_binding
                   (id,organization_id,authority_key,principal_type,principal_user_id,
                    active,effective_from,created_by,created_at)
                   VALUES (?,?,'SALES_WRITE','USER',?,true,now()-interval '1 day',?,now())""",
                grant,company,actor,actor,
            )
            assertDoesNotThrow { access.require(actor,company,"SALES_WRITE") }
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_COST_VIEW")
            }
            assertThrows(ProductApiException::class.java) {
                access.require(actor,otherCompany,"SALES_WRITE")
            }

            // Revoke one binding or suspend membership: next operation must fail.
            jdbc.update("UPDATE commercial_authority_binding SET active=false WHERE id=?",grant)
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_WRITE")
            }
            jdbc.update("UPDATE commercial_authority_binding SET active=true WHERE id=?",grant)
            jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE id=?",member)
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_WRITE")
            }
            jdbc.update("UPDATE organization_membership SET state='ACTIVE' WHERE id=?",member)
            jdbc.update("UPDATE user_identity SET status='LOCKED' WHERE id=?",actor)
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_WRITE")
            }
            jdbc.update("UPDATE user_identity SET status='ACTIVE' WHERE id=?",actor)
            assertDoesNotThrow { access.require(actor,company,"SALES_WRITE") }

            // A currently active team grant is scoped to its actual memberships.
            jdbc.update(
                """INSERT INTO team (id,organization_id,code,name,active)
                   VALUES (?,?,?,'Synthetic Sales Team',true)""",
                team,company,"P10_TEAM_"+team,
            )
            jdbc.update(
                """INSERT INTO team_membership
                   (id,team_id,user_identity_id,valid_from)
                   VALUES (?,?,?,now()-interval '1 day')""",
                teamMember,team,actor,
            )
            jdbc.update(
                """INSERT INTO commercial_authority_binding
                   (id,organization_id,authority_key,principal_type,principal_team_id,
                    active,effective_from,created_by,created_at)
                   VALUES (?,?,'SALES_COST_VIEW','TEAM',?,true,now()-interval '1 day',?,now())""",
                teamGrant,company,team,actor,
            )
            assertDoesNotThrow { access.require(actor,company,"SALES_COST_VIEW") }
            jdbc.update("UPDATE team SET active=false WHERE id=?",team)
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_COST_VIEW")
            }
            jdbc.update("UPDATE team SET active=true WHERE id=?",team)
            jdbc.update(
                "UPDATE team_membership SET valid_until=now()-interval '1 minute' WHERE id=?",
                teamMember,
            )
            assertThrows(ProductApiException::class.java) {
                access.require(actor,company,"SALES_COST_VIEW")
            }
        }
    }

    @Test
    fun typedReadyEvidenceCannotBeReusedAcrossTargetsOrTenants() {
        assumeTrue(System.getenv("HILTECH_COMMERCIAL_DB_TEST") == "1")
        database { jdbc ->
            val company = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val evidence = UUID.randomUUID()
            val session = UUID.randomUUID()
            val contract = UUID.randomUUID()
            val po = UUID.randomUUID()
            val sha = "a".repeat(64)
            jdbc.update(
                """INSERT INTO organization (id,organization_code,legal_name,display_name,
                   organization_type,status,created_at)
                   VALUES (?,?,? ,?,'HILTECH','ACTIVE',now())""",
                company,"P10_DOC_"+company,"Test Commercial Files","Test Commercial Files",
            )
            jdbc.update(
                """INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at)
                   VALUES (?,'phase10-contract-evidence',?,'ACTIVE',now())""",
                actor,actor.toString(),
            )
            jdbc.update(
                """INSERT INTO evidence
                   (id,organization_id,target_type,target_id,evidence_type_code,content_type,
                    size_bytes,sha256,captured_at,captured_by_user_id,storage_state,object_key_ref,
                    classification_code,client_visibility_mode,security_scan_class,created_at)
                   VALUES (?,?,'COMMERCIAL_CONTRACT',?,'COMMERCIAL_SIGNED_CONTRACT',
                    'application/pdf',120,?,now(),?,'UPLOADED_UNVERIFIED',?,
                    'HIGHLY_RESTRICTED','INTERNAL_ONLY','SIGNED_DOCUMENT',now())""",
                evidence,company,contract,sha,actor,"phase10-contract/test/" + evidence,
            )
            jdbc.update(
                """INSERT INTO evidence_upload_session
                   (id,evidence_id,expected_sha256,expected_size_bytes,expires_at,
                    state,created_at,operation_id)
                   VALUES (?,?,?,120,now()+interval '1 hour','UPLOADED_UNVERIFIED',now(),?)""",
                session,evidence,sha,UUID.randomUUID(),
            )
            val gate=JdbcCommercialAwardEvidenceGate(jdbc)
            assertFalse(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",contract,company))
            jdbc.update("UPDATE evidence SET storage_state='READY',finalized_at=now() WHERE id=?",evidence)
            jdbc.update("UPDATE evidence_upload_session SET state='READY',finalized_at=now() WHERE id=?",session)
            assertTrue(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",contract,company))
            // READY + an arbitrary-file class is not a valid finalized signed document.
            jdbc.update("UPDATE evidence SET security_scan_class='ARBITRARY_FILE' WHERE id=?",evidence)
            assertFalse(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",contract,company))
            jdbc.update("UPDATE evidence SET security_scan_class='SIGNED_DOCUMENT' WHERE id=?",evidence)
            assertTrue(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",contract,company))
            assertFalse(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",po,company))
            assertFalse(gate.verifiedEvidence(evidence,"COMMERCIAL_CLIENT_PO",contract,company))
            assertFalse(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",contract,UUID.randomUUID()))
            assertFalse(gate.verifiedEvidence(evidence,"WORK_ORDER",contract,company))
            assertFalse(gate.verifiedAward(contract,po,company))
            jdbc.update("UPDATE evidence SET storage_state='QUARANTINED' WHERE id=?",evidence)
            assertFalse(gate.verifiedEvidence(evidence,"COMMERCIAL_CONTRACT",contract,company))
        }
    }

    private fun database(action: (JdbcTemplate) -> Unit) {
        val url=System.getenv("HILTECH_DB_URL") ?: error("Disposable DB URL missing")
        val user=System.getenv("HILTECH_DB_USER") ?: error("Disposable DB user missing")
        val password=System.getenv("HILTECH_DB_PASSWORD") ?: error("Disposable DB password missing")
        DriverManager.getConnection(url,user,password).use { connection ->
            connection.autoCommit=false
            try {
                action(JdbcTemplate(SingleConnectionDataSource(connection,true)))
            } finally {
                connection.rollback() // no business/company or test data survives this contract
            }
        }
    }
}
