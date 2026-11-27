package com.hiltech.server.companysettings

import com.hiltech.server.administration.CompanyResponsibilityKey
import com.hiltech.server.administration.JdbcCompanyResponsibilityPersistence
import com.hiltech.server.administration.JdbcOrganizationAdministrationAuthority
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceConfigurationPostgresContractTest {
    private val at=Instant.parse("2026-11-25T12:00:00Z")
    private val clock=Clock.fixed(at,ZoneOffset.UTC)
    @Test fun setupRecordsFeedTheExistingChartPeriodAndBankSources() {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST")=="1")
        DriverManager.getConnection(requireNotNull(System.getenv("HILTECH_DB_URL")),requireNotNull(System.getenv("HILTECH_DB_USER")),
            requireNotNull(System.getenv("HILTECH_DB_PASSWORD"))).use { connection ->
            connection.autoCommit=false
            try {
                val jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
                val org=UUID.randomUUID();val actor=UUID.randomUUID()
                val before=at.minusSeconds(60).atOffset(ZoneOffset.UTC)
                jdbc.update("""INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
                    VALUES(?,?,'Synthetic finance setup','Synthetic finance setup','HILTECH','ACTIVE',?)""",org,"SYNTH_$org",before)
                jdbc.update("""INSERT INTO user_identity(id,auth_provider,auth_subject,status,created_at)
                    VALUES(?,'synthetic-finance-setup',?,'ACTIVE',?)""",actor,actor.toString(),before)
                jdbc.update("""INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from)
                    VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""",UUID.randomUUID(),org,actor,before)
                val authority=JdbcOrganizationAdministrationAuthority(jdbc,clock)
                val service=FinanceConfigurationService(authority,jdbc,DirectCommands(),JdbcAuditEventWriter(jdbc),clock)
                val account=FinanceConfigurationInput(UUID.randomUUID(),FinanceConfigurationKind.ACCOUNT,"SYNTHETIC-CHART",
                    code="AR-TEST",name="Synthetic receivables",accountKind="CUSTOMER_RECEIVABLE",currency="EGP")
                assertThrows(ProductApiException::class.java) {service.create(actor,org,account,"synthetic")}
                JdbcCompanyResponsibilityPersistence(jdbc).grant(CompanyResponsibilityKey.COMPANY_SETTINGS_ADMIN,
                    UUID.randomUUID(),org,actor,actor,UUID.randomUUID(),at)
                val saved=service.create(actor,org,account,"synthetic")
                assertEquals(saved.record,service.page(actor,org,FinanceConfigurationKind.ACCOUNT,0).records.single())
                assertEquals("CUSTOMER_RECEIVABLE",jdbc.queryForObject("SELECT account_kind FROM finance_gl_account WHERE id=?",
                    String::class.java,saved.record.id))
                assertThrows(ProductApiException::class.java) {service.create(actor,org,account.copy(operationId=UUID.randomUUID()),"synthetic")}
                val period=FinanceConfigurationInput(UUID.randomUUID(),FinanceConfigurationKind.PERIOD,"SYNTHETIC-PERIOD",
                    startDate="2026-11-01",endDate="2026-11-30")
                val savedPeriod=service.create(actor,org,period,"synthetic")
                assertEquals("OPEN",savedPeriod.record.state)
                assertThrows(ProductApiException::class.java) {service.create(actor,org,period.copy(operationId=UUID.randomUUID(),
                    startDate="2026-11-30",endDate="2026-12-31"),"synthetic")}
                val bank=FinanceConfigurationInput(UUID.randomUUID(),FinanceConfigurationKind.BANK,"SYNTHETIC-BANK",
                    code="TEST:****1234",name="Synthetic bank",currency="EGP")
                assertFalse(validFinanceConfiguration(bank.copy(code="1234567890123456")))
                val savedBank=service.create(actor,org,bank,"synthetic")
                assertEquals("TEST:****1234",jdbc.queryForObject("SELECT masked_account_reference FROM finance_bank_account_register WHERE id=?",
                    String::class.java,savedBank.record.id))
                assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor_user_id=? AND action='FINANCE_CONFIGURATION_CREATED'",
                    Int::class.java,actor))
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM finance_ar_posted_journal WHERE organization_id=?",Int::class.java,org))
                assertThrows(ProductApiException::class.java) {service.page(actor,UUID.randomUUID(),FinanceConfigurationKind.BANK,0)}
                jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?",org,actor)
                assertThrows(ProductApiException::class.java) {service.page(actor,org,FinanceConfigurationKind.ACCOUNT,0)}
            } finally {connection.rollback()}
        }
    }
    private class DirectCommands: IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec,action: ()->IdempotentCommandOutcome)=IdempotentCommandExecution(action(),false)
    }
}
