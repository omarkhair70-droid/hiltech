package com.hiltech.server.finance

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Positive real PostgreSQL source chain:
 * independently issued customer invoice -> balanced AR journal ->
 * witnessed 100 bank credit + 50 physical cash receipt -> no outstanding.
 * A third allocation attempts to exceed the actual source and is rejected.
 * This test is transaction-local; concurrent-writer timing is a distinct gate.
 */
class FinanceMixedBankCashPostgresContractTest {
 @Test fun independentlyApprovedBankAndCashSplitCannotOverCollectRealAR() {
   assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST")=="1")
   val url=System.getenv("HILTECH_DB_URL")?:"jdbc:postgresql://localhost:5432/hiltech"
   val user=System.getenv("HILTECH_DB_USER")?:"hiltech"
   val pass=System.getenv("HILTECH_DB_PASSWORD")?:"hiltech"
   val root=Path.of(System.getenv("HILTECH_REPO_ROOT")?:".").toAbsolutePath()
   val migrationDir=System.getenv("HILTECH_MIGRATIONS_PATH")
     ?:root.resolve("database/migrations").toString()
   Flyway.configure().dataSource(url,user,pass)
     .locations("filesystem:$migrationDir").load().migrate()
   DriverManager.getConnection(url,user,pass).use { connection ->
     connection.autoCommit=false
     try {
       val jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
       fun insert(sql:String,vararg args:Any?)=jdbc.update(sql,*args)
       val org=UUID.randomUUID()
       val customer=UUID.randomUUID()
       val identities=(0..9).map{UUID.randomUUID()}
       val creator=identities[0];val issuer=identities[1]
       val journalRecorder=identities[2];val bankPreparer=identities[3]
       val bankImporter=identities[4];val reviewer=identities[5]
       val allocationRecorder=identities[6];val cashCustodian=identities[7]
       val cashOpeningPreparer=identities[8]
       val alternativeRecorder=identities[9]
       val t=OffsetDateTime.parse("2026-11-23T08:00:00Z")
       val day=LocalDate.parse("2026-11-23")
       val money=BigDecimal("150.000000")
       val bank100=BigDecimal("100.000000")
       val cash50=BigDecimal("50.000000")
       val commercialSha="a".repeat(64)
       val journalSha="d".repeat(64)
       val bankSha="c".repeat(64)
       val policyKeys=mutableSetOf<String>()
       val policyBindings=mutableMapOf<String,UUID>()
       fun organization(id:UUID,code:String) {
         insert("""INSERT INTO organization(
           id,organization_code,legal_name,display_name,
           organization_type,status,created_at)
           VALUES(?,?,?,?,'HILTECH','ACTIVE',?)""",
           id,code,code,code,t)
       }
       organization(org,"MIXED_${org.toString().take(8)}")
       organization(customer,"CUSTOMER_${customer.toString().take(8)}")
       identities.forEachIndexed {idx,actor->
         val person=UUID.randomUUID()
         insert("""INSERT INTO person(id,display_name,created_at,updated_at)
           VALUES(?,?,?,?)""",person,"Finance actor $idx",t,t)
         insert("""INSERT INTO user_identity(id,auth_provider,auth_subject,
           person_id,status,created_at)
           VALUES(?,'finance-contract',?,?,'ACTIVE',?)""",
           actor,UUID.randomUUID().toString(),person,t)
         insert("""INSERT INTO organization_membership(
           id,organization_id,user_identity_id,membership_type,state,valid_from)
           VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""",
           UUID.randomUUID(),org,actor,t)
       }
       fun approval(
         type:String,subject:UUID,version:Long,policy:String,requester:UUID,
         approvedAt:OffsetDateTime=t.plusHours(2),
       ):UUID {
         val authority=policy+"_REVIEWER"
         if(policyKeys.add(policy)){
           insert("""INSERT INTO approval_authority_binding(
              id,organization_id,authority_key,principal_type,
              principal_user_id,effective_from,active,
              created_by_user_id,created_at)
              VALUES(?,?,?,'USER',?,?,true,?,?)""",
              policyBindings.getOrPut(policy){UUID.randomUUID()},org,authority,reviewer,t,creator,t)
           insert("""INSERT INTO approval_policy_version(
              id,organization_id,policy_key,version_number,lifecycle_state,
              approval_required,step_mode,authority_key,reason_required,
              effective_from,created_by_user_id,created_at)
              VALUES(?,?,?,1,'ACTIVE',true,'SINGLE',?,false,?,?,?)""",
              UUID.randomUUID(),org,policy,authority,t,creator,t)
         }
         val id=UUID.randomUUID()
         val step=UUID.randomUUID()
         val assignment=UUID.randomUUID()
         insert("""INSERT INTO approval_request(
           id,organization_id,subject_type,subject_id,subject_version,
           policy_key,policy_version,requester_user_id,state,
           reason_code,created_at,completed_at)
           VALUES(?,?,?,?,?,?,1,?,'APPROVED','COMPANY_FINANCE_REVIEW',?,?)""",
           id,org,type,subject,version,policy,requester,t,approvedAt)
         insert("""INSERT INTO approval_step(
           id,approval_request_id,sequence_number,mode,authority_key,state)
           VALUES(?,?,1,'SINGLE',?,'COMPLETED')""",
           step,id,authority)
         insert("""INSERT INTO approval_assignment(
           id,approval_request_id,approval_step_id,principal_type,
           principal_user_id,state,assigned_at,acted_at)
           VALUES(?,?,?,'USER',?,'ACTED',?,?)""",
           assignment,id,step,reviewer,t,approvedAt)
         insert("""INSERT INTO approval_decision(
           id,approval_request_id,approval_assignment_id,actor_user_id,
           decision,decided_at,subject_version,operation_id)
           VALUES(?,?,?,?,'APPROVE',?,?,?)""",
           UUID.randomUUID(),id,assignment,reviewer,approvedAt,
           version,UUID.randomUUID())
         return id
       }
       fun evidence(targetType:String,target:UUID,sha:String):UUID {
         val id=UUID.randomUUID()
         insert("""INSERT INTO evidence(
           id,organization_id,target_type,target_id,evidence_type_code,
           content_type,size_bytes,sha256,captured_at,captured_by_user_id,
           storage_state,object_key_ref,finalized_at,classification_code,
           client_visibility_mode,created_at)
           VALUES(?,?,?,?,'SIGNED_COMPANY_PROOF','application/pdf',128,?,?,?,
             'READY',?,?,'HIGHLY_RESTRICTED','INTERNAL_ONLY',?)""",
           id,org,targetType,target,sha,t,creator,
           "finance/contract/fixtures/$id.pdf",t.plusHours(1),t)
         insert("""INSERT INTO evidence_upload_session(
           id,evidence_id,expected_sha256,expected_size_bytes,
           expires_at,state,created_at,finalized_at,operation_id)
           VALUES(?,?,?,128,?,'READY',?,?,?)""",
           UUID.randomUUID(),id,sha,t.plusDays(1),t,t.plusHours(1),
           UUID.randomUUID())
         return id
       }

       // Immutable commercial invoice candidate, separately reviewed invoice
       // document and actual independently approved AR issuance event.
       val candidate=UUID.randomUUID()
       val milestoneEvidence=evidence(
         "FINANCE_AR_BILLABLE_MILESTONE",candidate,"b".repeat(64))
       insert("""INSERT INTO finance_ar_invoice_candidate(
         id,operation_id,organization_id,opportunity_id,project_id,
         customer_organization_id,signed_contract_id,verified_client_po_id,
         commercial_source_sha256,billing_milestone_evidence_id,
         contractual_billable_reference,legal_tax_review_reference,
         invoice_issue_date,invoice_due_date,currency,requested_amount,
         state,created_by_user_id,created_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,?,?,?,?,?,
           ?,?,'EGP',?,'DRAFT',?,?,'mixed-AR-source')""",
         candidate,UUID.randomUUID(),org,UUID.randomUUID(),UUID.randomUUID(),
         customer,UUID.randomUUID(),UUID.randomUUID(),commercialSha,
         milestoneEvidence,"SIGNED-MILESTONE-2026","COMPANY-NO-TAX-SCOPE",
         day,day.plusDays(30),money,creator,t)
       val issuanceProposal=UUID.randomUUID()
       val invoiceEvidence=evidence(
         "FINANCE_AR_ISSUED_INVOICE",candidate,"e".repeat(64))
       val invoicePolicy="FINANCE_AR_ISSUANCE_REVIEW"
       val issuedInvoice=UUID.randomUUID()
       insert("""INSERT INTO finance_ar_invoice_issuance_proposal(
         id,operation_id,organization_id,candidate_id,invoice_number,
         invoice_document_evidence_id,source_fingerprint_sha256,
         approval_policy_key,approval_subject_version,created_by_user_id,
         created_at) VALUES(?,?,?,?,?,?,?,?,101,?,?)""",
         issuanceProposal,UUID.randomUUID(),org,candidate,
         "MIX-AR-${candidate.toString().take(8)}",invoiceEvidence,commercialSha,
         invoicePolicy,creator,t)
       val issuanceApproval=approval("FINANCE_AR_INVOICE_ISSUANCE",
         issuanceProposal,101,invoicePolicy,creator)
       insert("""INSERT INTO finance_ar_issued_invoice(
         id,operation_id,organization_id,candidate_id,proposal_id,
         invoice_number,invoice_document_evidence_id,invoice_approval_id,
         approval_policy_key,approval_subject_version,source_fingerprint_sha256,
         customer_organization_id,currency,gross_amount,issue_date,due_date,
         issued_by_user_id,issued_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,?,?,101,?,?,'EGP',?,?,?,?,?,'mixed-AR-issued')""",
         issuedInvoice,UUID.randomUUID(),org,candidate,issuanceProposal,
         "MIX-AR-${candidate.toString().take(8)}",invoiceEvidence,issuanceApproval,
         invoicePolicy,commercialSha,customer,money,day,day.plusDays(30),
         issuer,t.plusHours(3))
       val period=UUID.randomUUID()
       insert("""INSERT INTO finance_gl_period(
         id,organization_id,date_start,date_end,state,
         approved_company_period_reference,created_at)
         VALUES(?,?,? ,?,'OPEN','COMPANY-NOV-DEC-2026',?)""",
         period,org,day.minusDays(15),day.plusDays(45),t)
       fun chart(kind:String,code:String):UUID{
         val id=UUID.randomUUID()
         insert("""INSERT INTO finance_gl_account(
           id,organization_id,account_code,account_name,account_kind,
           currency,state,approved_company_chart_reference,created_at)
           VALUES(?,?,?,?,?,'EGP','APPROVED','COMPANY-CHART-2026',?)""",
           id,org,code,code,kind,t)
         return id
       }
       val receivable=chart("CUSTOMER_RECEIVABLE","AR-TRADE")
       val sales=chart("SALES_REVENUE","REVENUE")
       val ledgerProposal=UUID.randomUUID()
       val ledgerPolicy="FINANCE_AR_JOURNAL_POSTING"
       insert("""INSERT INTO finance_ar_posting_proposal(
         id,operation_id,organization_id,issued_invoice_id,
         original_issuance_approval_id,source_fingerprint_sha256,
         ledger_fingerprint_sha256,ledger_subject_version,fiscal_period_id,
         currency,gross_amount,accounting_treatment_reference,
         tax_disposition_reference,tax_evidence_id,policy_key,state,
         created_by_user_id,created_at,sealed_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,202,?,'EGP',?,?,
         ?,null,?,'DRAFT',?,?,null,'mixed-ledger-proposal')""",
         ledgerProposal,UUID.randomUUID(),org,issuedInvoice,
         issuanceApproval,commercialSha,journalSha,period,money,
         "COMPANY-AR-LEDGER-TREATMENT","COMPANY-NO-TAX-ALLOCATION",
         ledgerPolicy,issuer,t)
       val lineDebit=UUID.randomUUID()
       val lineCredit=UUID.randomUUID()
       for((lineId,n,account,side) in listOf(
         listOf(lineDebit,1,receivable,"DEBIT"),
         listOf(lineCredit,2,sales,"CREDIT")
       )) {
         insert("""INSERT INTO finance_ar_posting_proposal_line(
             id,organization_id,proposal_id,line_number,gl_account_id,side,amount)
             VALUES(?,?,?,?,?,?,?)""",
           lineId,org,ledgerProposal,n,account,side,money)
       }
       insert("""UPDATE finance_ar_posting_proposal
         SET state='SEALED',sealed_at=? WHERE organization_id=? AND id=?""",
         t.plusHours(3),org,ledgerProposal)
       val ledgerApproval=approval("FINANCE_AR_JOURNAL_POSTING",
         ledgerProposal,202,ledgerPolicy,issuer)
       val journal=UUID.randomUUID()
       insert("""INSERT INTO finance_ar_posted_journal(
         id,operation_id,organization_id,proposal_id,issued_invoice_id,
         posting_approval_id,fiscal_period_id,ledger_fingerprint_sha256,
         source_fingerprint_sha256,currency,gross_amount,posted_by_user_id,
         posted_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,?,?,'EGP',?,?,?,'mixed-AR-journal')""",
         journal,UUID.randomUUID(),org,ledgerProposal,issuedInvoice,
         ledgerApproval,period,journalSha,commercialSha,money,
         journalRecorder,t.plusHours(4))
       insert("""INSERT INTO finance_ar_posted_journal_line(
         id,organization_id,journal_id,proposal_line_id,line_number,
         gl_account_id,side,amount) VALUES(?,?,?,?,1,?,'DEBIT',?)""",
         UUID.randomUUID(),org,journal,lineDebit,receivable,money)
       insert("""INSERT INTO finance_ar_posted_journal_line(
         id,organization_id,journal_id,proposal_line_id,line_number,
         gl_account_id,side,amount) VALUES(?,?,?,?,2,?,'CREDIT',?)""",
         UUID.randomUUID(),org,journal,lineCredit,sales,money)

       // Signed customer receipts and separate employee movements in one bank statement.
       // The second customer credit remains unallocated until a valid source exists.
       val account=UUID.randomUUID()
       insert("""INSERT INTO finance_bank_account_register(
         id,organization_id,currency,bank_name,masked_account_reference,
         company_approval_reference,state,created_at)
         VALUES(?,?,'EGP','Fixture Bank',?,'COMPANY-BANK-APPROVED','APPROVED',?)""",
         account,org,"EG****${account.toString().take(8)}",t)
       val intakeProposal=UUID.randomUUID()
       val bankEvidence=evidence("FINANCE_BANK_STATEMENT_INTAKE",intakeProposal,bankSha)
       val funding80=BigDecimal("80.000000")
       val return30=BigDecimal("30.000000")
       val expense20=BigDecimal("20.000000")
       val bankLines=(1..2).map {idx ->
         FinanceBankIntakeSourceLine(
           "MIX-BANK-${idx}-${intakeProposal.toString().take(8)}",day,
           FinanceBankFlow.CREDIT,"EGP",bank100,
           if(idx==1)"f".repeat(64) else "9".repeat(64))
       }+listOf(
         FinanceBankIntakeSourceLine("MIX-FUND-$intakeProposal",day,FinanceBankFlow.DEBIT,"EGP",funding80,"1".repeat(64)),
         FinanceBankIntakeSourceLine("MIX-RETURN-$intakeProposal",day,FinanceBankFlow.CREDIT,"EGP",return30,"2".repeat(64)),
         FinanceBankIntakeSourceLine("MIX-EXPENSE-$intakeProposal",day,FinanceBankFlow.DEBIT,"EGP",expense20,"3".repeat(64))
       )
       val digest=FinanceBankIntakeRules.validate(FinanceBankIntakeSource(
         org,account,bankEvidence,bankSha,"EGP",day,day,
         BigDecimal.ZERO,BigDecimal("130.000000"),bankLines,true,true,
       )).sourceLinesSha256
       val intakePolicy="FINANCE_BANK_STATEMENT_INTAKE"
       insert("""INSERT INTO finance_bank_statement_intake_proposal(
         id,operation_id,organization_id,account_id,statement_evidence_id,
         statement_sha256,statement_lines_sha256,date_start,date_end,
         opening_balance,closing_balance,expected_line_count,source_version,
         policy_key,prepared_by_user_id,created_at)
         VALUES(?,?,?,?,?,?,?,?,?,0,130,5,303,?,?,?)""",
         intakeProposal,UUID.randomUUID(),org,account,bankEvidence,bankSha,
         digest,day,day,intakePolicy,bankPreparer,t)
       val bankLineIds=bankLines.mapIndexed {index,part ->
         val id=UUID.randomUUID()
         insert("""INSERT INTO finance_bank_statement_intake_proposal_line(
           id,organization_id,proposal_id,line_number,external_transaction_id,
           booked_on,flow,currency,amount,original_line_sha256)
           VALUES(?,?,?,?,?,?,?,'EGP',?,?)""",
           id,org,intakeProposal,index+1,part.externalTransactionId,
           day,part.flow.name,part.amount,part.originalLineSha256)
         id
       }
       // Check exact prepared immutable rows before recording final bank lines.
       connection.createStatement().use{
         it.execute("SET CONSTRAINTS ALL IMMEDIATE")
         it.execute("SET CONSTRAINTS ALL DEFERRED")
       }
       val bankApproval=approval("FINANCE_BANK_STATEMENT_INTAKE",
         intakeProposal,303,intakePolicy,bankPreparer)
       val intake=UUID.randomUUID()
       insert("""INSERT INTO finance_bank_statement_intake(
         id,operation_id,organization_id,proposal_id,account_id,
         approval_request_id,statement_sha256,statement_lines_sha256,
         statement_evidence_id,currency,date_start,date_end,opening_balance,
         closing_balance,expected_line_count,imported_by_user_id,
         imported_at,correlation_id)
         VALUES(?,?,?,?,?,?,?,?,?,'EGP',?,?,0,130,5,?,?,'mixed-bank-source')""",
         intake,UUID.randomUUID(),org,intakeProposal,account,
         bankApproval,bankSha,digest,bankEvidence,day,day,
         bankImporter,t.plusHours(3))
       val actualBankIds=bankLines.map {part ->
         val id=UUID.randomUUID()
         insert("""INSERT INTO finance_bank_statement_intake_line(
           id,organization_id,intake_id,account_id,external_transaction_id,
           booked_on,flow,currency,amount,original_line_sha256)
           VALUES(?,?,?,?,?,?,?,'EGP',?,?)""",
           id,org,intake,account,part.externalTransactionId,
           day,part.flow.name,part.amount,part.originalLineSha256)
         id
       }
       connection.createStatement().use{
         it.execute("SET CONSTRAINTS ALL IMMEDIATE")
         it.execute("SET CONSTRAINTS ALL DEFERRED")
       }
       // Approved bank allocation of 100 against exact posted AR 150.
       val allocationPolicy="FINANCE_AP_AR_CASH_ALLOCATION"
       fun recordBankAllocation(bankLine:UUID,amount:BigDecimal,recordBy:UUID):UUID {
         val prepared=UUID.randomUUID()
         insert("""INSERT INTO finance_cash_allocation_proposal(
           id,operation_id,organization_id,source_kind,source_journal_id,
           bank_statement_line_id,currency,allocated_amount,
           source_journal_sha256,bank_statement_sha256,
           approval_subject_version,policy_key,allocation_reason,
           prepared_by_user_id,created_at)
           VALUES(?,?,?,'AR',?,?,'EGP',?,?,?,404,?,?,?,?)""",
           prepared,UUID.randomUUID(),org,journal,bankLine,amount,
           journalSha,bankSha,allocationPolicy,
           "SIGNED-RECEIPT-MATCH",creator,t.plusHours(4))
         val review=approval("FINANCE_AP_AR_CASH_ALLOCATION",
           prepared,404,allocationPolicy,creator)
         val approvalFlags=jdbc.queryForMap(
           """SELECT a.state AS approval_state,
             a.subject_type AS approval_subject_type,
             (a.subject_id=?) AS subject_matches,
             (a.subject_version=404) AS version_matches,
             (a.policy_key=?) AS policy_matches,
             (a.requester_user_id=?) AS requester_matches,
             (a.requester_user_id<>?) AS independent_recorder,
             (a.completed_at IS NOT NULL AND a.completed_at<=?) AS completed_before_record,
             EXISTS(SELECT 1 FROM approval_decision d
               WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
               AND d.subject_version=a.subject_version
               AND d.actor_user_id<>a.requester_user_id
               AND d.actor_user_id<>?) AS independent_decision,
             EXISTS(SELECT 1 FROM approval_policy_version v
               WHERE v.organization_id=? AND v.policy_key=a.policy_key
               AND v.version_number=a.policy_version
               AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
               AND v.step_mode='SINGLE'
               AND v.effective_from<=?
               AND (v.effective_to IS NULL OR v.effective_to>?)) AS active_policy
             FROM approval_request a WHERE a.id=? AND a.organization_id=?""",
           prepared,allocationPolicy,creator,recordBy,t.plusHours(5),
           recordBy,org,t.plusHours(5),t.plusHours(5),review,org)
         check(approvalFlags["approval_state"]=="APPROVED" &&
           approvalFlags["approval_subject_type"]=="FINANCE_AP_AR_CASH_ALLOCATION" &&
           listOf("subject_matches","version_matches","policy_matches",
            "requester_matches","independent_recorder","completed_before_record",
            "independent_decision","active_policy").all{approvalFlags[it]==true}
         ){"Mixed bank allocation approval proof failed: $approvalFlags"}
         val record=UUID.randomUUID()
         val attemptSavepoint=connection.setSavepoint()
         try {
         insert("""INSERT INTO finance_cash_allocation_record(
           id,operation_id,organization_id,proposal_id,source_kind,
           source_journal_id,bank_statement_line_id,approval_request_id,
           approval_subject_version,policy_key,currency,allocated_amount,
           recorded_by_user_id,recorded_at,correlation_id)
           VALUES(?,?,?,?,'AR',?,?,?,404,?,'EGP',?,?,?,'mixed-bank-match')""",
           record,UUID.randomUUID(),org,prepared,journal,bankLine,review,
           allocationPolicy,amount,recordBy,t.plusHours(5))
         } catch(ex:DataIntegrityViolationException) {
           connection.rollback(attemptSavepoint)
           if(recordBy==allocationRecorder){
             val actual=jdbc.queryForMap(
               """SELECT p.prepared_by_user_id AS proposal_preparer,
                  p.policy_key AS proposal_policy,
                  p.approval_subject_version AS proposal_version,
                  a.requester_user_id AS requester,
                  a.subject_id AS approved_subject,
                  a.subject_version AS approved_version,
                  a.policy_key AS approved_policy,
                  a.state AS approved_state,
                  a.completed_at AS approved_time,
                  d.actor_user_id AS decision_actor,
                  v.effective_from AS policy_effective
               FROM finance_cash_allocation_proposal p
               JOIN approval_request a ON a.id=?
               JOIN approval_decision d ON d.approval_request_id=a.id
               JOIN approval_policy_version v
                 ON v.organization_id=a.organization_id
                  AND v.policy_key=a.policy_key
                  AND v.version_number=a.policy_version
               WHERE p.id=? AND p.organization_id=?""",
               review,prepared,org)
             throw AssertionError(
               "Unexpected REAL approved cash allocation rejection: " +
               "facts=$approvalFlags db=$actual; actor=$recordBy, " +
               "recordedAt=${t.plusHours(5)}, cause=${ex.mostSpecificCause.message}",
               ex)
           }
           throw ex
         }
         return record
       }
       val bankAllocation=recordBankAllocation(actualBankIds[0],bank100,allocationRecorder)
       assertTrue(bankAllocation.toString().isNotBlank())

       // Approved zero-opening company cashbox, then signed physical receipt.
       val cashbox=UUID.randomUUID()
       val cashCode="MIXCASH_${cashbox.toString().take(8)}"
       val cashOpeningPolicy="FINANCE_CASHBOX_OPENING_REVIEW"
       val openingReference="COMPANY-ZERO-OPENING"
       val openingBindingId=UUID.randomUUID()
       policyBindings[cashOpeningPolicy]=openingBindingId
       val openingVersion=FinanceCashboxOpeningVersion.derive(
         org,cashCode,"EGP",cashCustodian,openingReference,
         1,openingBindingId,1)
       insert("""INSERT INTO finance_cashbox_opening_proposal(
         id,operation_id,organization_id,cashbox_code,currency,
         custodian_user_id,approved_company_reference,opening_balance,
         subject_version,policy_key,prepared_by_user_id,created_at)
         VALUES(?,?,?,?,?,?,?,0,?,?,?,?)""",
         cashbox,UUID.randomUUID(),org,cashCode,"EGP",cashCustodian,
         openingReference,openingVersion,cashOpeningPolicy,cashOpeningPreparer,t)
       val openingApproval=approval("FINANCE_CASHBOX_OPENING",
         cashbox,openingVersion,cashOpeningPolicy,cashOpeningPreparer)
       insert("""INSERT INTO finance_cashbox_register(
         id,organization_id,cashbox_code,currency,custodian_user_id,
         approved_company_reference,opening_balance,status,
         opening_approval_request_id,opening_proposal_id,
         activation_operation_id,created_by_user_id,created_at)
         VALUES(?,?,?,?,?,?,0,'ACTIVE',?,?,?,?,?)""",
         cashbox,org,cashCode,"EGP",cashCustodian,openingReference,
         openingApproval,cashbox,UUID.randomUUID(),cashOpeningPreparer,
         t.plusHours(3))
       val voucherProposal=UUID.randomUUID()
       val voucherPolicy="FINANCE_CASH_VOUCHER"
       insert("""INSERT INTO finance_cash_voucher_proposal(
         id,operation_id,organization_id,cashbox_id,source_kind,
         source_journal_id,direction,currency,amount,source_ledger_sha256,
         voucher_reference,reason_reference,approval_subject_version,
         policy_key,prepared_by_user_id,created_at)
         VALUES(?,?,?,?,'AR',?,'RECEIPT','EGP',?,?,?,?,505,?,?,?)""",
         voucherProposal,UUID.randomUUID(),org,cashbox,journal,cash50,
         journalSha,"MIX-CASH-${voucherProposal.toString().take(8)}",
         "SIGNED-CUSTOMER-RECEIPT",voucherPolicy,creator,t.plusHours(4))
       val voucherEvidence=evidence(
         "FINANCE_CASH_VOUCHER",voucherProposal,"6".repeat(64))
       val voucherApproval=approval("FINANCE_CASH_VOUCHER",
         voucherProposal,505,voucherPolicy,creator)
       val voucherRecord=UUID.randomUUID()
       insert("""INSERT INTO finance_cash_voucher_record(
         id,operation_id,organization_id,proposal_id,cashbox_id,
         source_kind,source_journal_id,direction,amount,currency,
         approval_request_id,custody_evidence_id,recorded_by_user_id,
         recorded_at,correlation_id)
         VALUES(?,?,?,?,?,'AR',?,'RECEIPT',?,'EGP',?,?,?,?,'mixed-physical-cash')""",
         voucherRecord,UUID.randomUUID(),org,voucherProposal,cashbox,
         journal,cash50,voucherApproval,voucherEvidence,cashCustodian,
         t.plusHours(5))
       val balance=jdbc.queryForMap(
         """SELECT gross_amount,allocated_amount,outstanding_amount
            FROM finance_ap_ar_source_balance
            WHERE organization_id=? AND source_kind='AR' AND journal_id=?""",
         org,journal)
       assertEquals(0,(balance["outstanding_amount"] as BigDecimal)
         .compareTo(BigDecimal.ZERO))
       assertEquals(0,(balance["allocated_amount"] as BigDecimal)
         .compareTo(money))
       assertEquals(0,jdbc.queryForObject(
         """SELECT cash_on_hand FROM finance_cashbox_balance
            WHERE organization_id=? AND cashbox_id=?""",
         BigDecimal::class.java,org,cashbox)?.compareTo(cash50))
       // The same native reporting source sees posted double-entry movement and
       // the allocated obligation; allocation does not invent a cash GL journal.
       val desk=FinanceAccountingDeskReadStore(jdbc)
       val report=desk.ledger(org,period,0)
       assertEquals(listOf("AP","AR","AP_REVERSAL"),report.includedSources)
       assertEquals(false,report.isCompanyWideTrialBalance)
       assertEquals(journal,report.journals.single().id)
       assertEquals(2,report.journals.single().lines.size)
       assertEquals(0,report.movements.sumOf { BigDecimal(it.debit) }.compareTo(money))
       assertEquals(0,report.movements.sumOf { BigDecimal(it.credit) }.compareTo(money))
       assertTrue(report.currencyTotals.single().balanced)
       assertEquals("EGP",report.currencyTotals.single().currency)
       assertEquals(0,report.currencyTotals.single().debit.toBigDecimal().compareTo(money))
       assertEquals(0,desk.exposure(org,"AR",day,0).totals.single().knownOutstanding.toBigDecimal().compareTo(BigDecimal.ZERO))
       assertEquals(0,desk.exposure(org,"AR",day,0).records.single().outstanding!!.toBigDecimal().compareTo(BigDecimal.ZERO))
       assertEquals(false,desk.exposure(org,"AR",day,0).isBankBalance)
       assertTrue(desk.exposure(customer,"AR",day,0).records.isEmpty())
       assertTrue(desk.ledger(org,period,50).journals.isEmpty())
       // Actual recorded source -> approved instrument account -> two exact GL lines.
       val settlementStore=FinanceSettlementLedgerStore(jdbc)
       val pairs=listOf("BANK_ALLOCATION" to "AR","CASH_VOUCHER" to "AR","BANK_VOID" to "AR")
       val beforePosting=settlementStore.queue(org,pairs,0)
       assertEquals(2,beforePosting.size)
       assertTrue(beforePosting.all {it.debitAccountId==null && it.creditAccountId==receivable})
       fun treasury(kind:String,code:String,instrument:UUID):UUID {
         val id=UUID.randomUUID()
         val bank=instrument.takeIf {kind=="BANK_ASSET"}
         val cash=instrument.takeIf {kind=="CASH_ASSET"}
         insert("""INSERT INTO finance_gl_account(id,organization_id,account_code,account_name,account_kind,currency,state,
           approved_company_chart_reference,created_at,treasury_bank_account_id,treasury_cashbox_id)
           VALUES(?,?,?,?,?,'EGP','APPROVED','COMPANY-CHART-2026',?,?,?)""",id,org,code,code,kind,t,bank,cash)
         return id
       }
       val bankGl=treasury("BANK_ASSET","BANK-GL",account)
       val cashGl=treasury("CASH_ASSET","CASH-GL",cashbox)
       fun postSettlement(type:String,sourceId:UUID,expectedDebit:UUID,expectedCredit:UUID,expectedAmount:BigDecimal):UUID {
         val source=checkNotNull(settlementStore.source(org,type,sourceId))
         assertEquals(expectedDebit,source.debitAccountId)
         assertEquals(expectedCredit,source.creditAccountId)
         assertEquals(0,BigDecimal(source.amount).compareTo(expectedAmount))
         val request=FinanceSettlementPost(UUID.randomUUID(),type,sourceId,"AR",period,expectedDebit,expectedCredit,source.amount,"EGP")
         val id=UUID.randomUUID()
         val wrong=connection.setSavepoint()
         try {
           assertThrows(DataIntegrityViolationException::class.java) {
             settlementStore.insert(org,journalRecorder,request.copy(debitAccountId=sales),source,UUID.randomUUID(),t.plusHours(6),"wrong-account")
           }
         } finally {connection.rollback(wrong)}
         settlementStore.insert(org,journalRecorder,request,source,id,t.plusHours(6),"mixed-settlement")
         assertEquals(id,settlementStore.receipt(org,request.operationId,false)?.journalId)
         val duplicate=connection.setSavepoint()
         try {
           assertThrows(DataIntegrityViolationException::class.java) {
             settlementStore.insert(org,journalRecorder,request.copy(operationId=UUID.randomUUID()),source,UUID.randomUUID(),t.plusHours(6),"duplicate")
           }
         } finally {connection.rollback(duplicate)}
         return id
       }
       val postedBank=postSettlement("BANK_ALLOCATION",bankAllocation,bankGl,receivable,bank100)
       postSettlement("CASH_VOUCHER",voucherRecord,cashGl,receivable,cash50)
       assertTrue(settlementStore.queue(org,pairs,0).isEmpty())
       val expanded=desk.ledger(org,period,0,true)
       assertEquals(3,expanded.journals.size)
       assertEquals(0,expanded.movements.single {it.accountId==receivable}.credit.toBigDecimal().compareTo(money))
       assertTrue(expanded.currencyTotals.single().balanced)
       assertEquals(3L,jdbc.queryForObject("SELECT journal_count FROM finance_gl_period_close_source WHERE organization_id=? AND period_id=?",Long::class.java,org,period))
       assertEquals(1,desk.ledger(org,period,0).journals.size)
       val denied=connection.setSavepoint()
       try {
         assertThrows(DataIntegrityViolationException::class.java) {
           recordBankAllocation(actualBankIds[1],bank100,alternativeRecorder)
         }
       } finally {connection.rollback(denied)}
       assertEquals(0,jdbc.queryForObject(
         """SELECT outstanding_amount FROM finance_ap_ar_source_balance
            WHERE organization_id=? AND source_kind='AR' AND journal_id=?""",
         BigDecimal::class.java,org,journal)?.compareTo(BigDecimal.ZERO))
       assertTrue(jdbc.queryForObject(
         """SELECT count(*) FROM finance_cash_allocation_effective
            WHERE organization_id=? AND source_journal_id=?""",
         Long::class.java,org,journal)==1L)
       // An independently reviewed match correction reverses the earlier GL effect,
       // not the actual bank statement, and the immutable original remains.
       val correction=UUID.randomUUID()
       val correctionPolicy="FINANCE_CASH_ALLOCATION_VOID"
       insert("""INSERT INTO finance_cash_allocation_void_proposal(id,operation_id,organization_id,original_record_id,
         source_kind,source_journal_id,bank_statement_line_id,currency,amount,source_ledger_sha256,bank_statement_sha256,
         approval_subject_version,policy_key,reason_reference,prepared_by_user_id,created_at)
         VALUES(?,?,?,?,'AR',?,?,'EGP',?,?,?,606,?,?,?,?)""",
         correction,UUID.randomUUID(),org,bankAllocation,journal,actualBankIds[0],bank100,journalSha,bankSha,
         correctionPolicy,"SYNTHETIC-CORRECTION",alternativeRecorder,t.plusHours(6))
       val correctionApproval=approval("FINANCE_CASH_ALLOCATION_VOID",correction,606,correctionPolicy,alternativeRecorder,t.plusHours(7))
       val correctionRecord=UUID.randomUUID()
       insert("""INSERT INTO finance_cash_allocation_void_record(id,operation_id,organization_id,proposal_id,original_record_id,
         source_kind,source_journal_id,bank_statement_line_id,amount,currency,approval_request_id,recorded_by_user_id,recorded_at,correlation_id)
         VALUES(?,?,?,?,?,'AR',?,?,?,'EGP',?,?,?,'synthetic-void')""",correctionRecord,UUID.randomUUID(),org,correction,
         bankAllocation,journal,actualBankIds[0],bank100,correctionApproval,bankImporter,t.plusHours(8))
       val reverse=checkNotNull(settlementStore.source(org,"BANK_VOID",correctionRecord))
       assertEquals(postedBank,reverse.originalSettlementId)
       assertEquals(receivable,reverse.debitAccountId)
       assertEquals(bankGl,reverse.creditAccountId)
       val reverseRequest=FinanceSettlementPost(UUID.randomUUID(),"BANK_VOID",correctionRecord,"AR",period,receivable,bankGl,reverse.amount,"EGP")
       settlementStore.insert(org,journalRecorder,reverseRequest,reverse,UUID.randomUUID(),t.plusHours(9),"synthetic-void-ledger")
       assertTrue(settlementStore.queue(org,pairs,0).isEmpty())
       val corrected=desk.ledger(org,period,0,true)
       assertEquals(4,corrected.journals.size)
       assertTrue(corrected.currencyTotals.single().balanced)
       assertEquals(0,corrected.movements.single{it.accountId==bankGl}.credit.toBigDecimal().compareTo(bank100))
       assertEquals(0,desk.exposure(org,"AR",day,0).totals.single().knownOutstanding.toBigDecimal().compareTo(bank100))
       assertEquals(4L,jdbc.queryForObject("SELECT journal_count FROM finance_gl_period_close_source WHERE organization_id=? AND period_id=?",Long::class.java,org,period))

       // Employee custody has a distinct approval and bank debit, not an expense.
       val employee=UUID.randomUUID()
       val employeePerson=UUID.randomUUID()
       val employeeActor=UUID.randomUUID()
       insert("INSERT INTO person(id,display_name,created_at,updated_at) VALUES(?,'Synthetic custodian',?,?)",employeePerson,t,t)
       insert("""INSERT INTO employee(id,organization_id,person_id,employee_code,state,created_at,updated_at)
           VALUES(?,?,?,'SYNTH-CUSTODY','ACTIVE',?,?)""",employee,org,employeePerson,t,t)
       insert("""INSERT INTO user_identity(id,auth_provider,auth_subject,person_id,status,created_at)
           VALUES(?,'finance-contract',?,?,'ACTIVE',?)""",employeeActor,UUID.randomUUID().toString(),employeePerson,t)
       insert("""INSERT INTO employment(id,employee_id,start_date,state,created_at,updated_at)
           VALUES(?,?,?,'ACTIVE',?,?)""",UUID.randomUUID(),employee,day.minusDays(1),t,t)
       insert("""INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from)
           VALUES(?,?,?,'EMPLOYEE','ACTIVE',?)""",UUID.randomUUID(),org,employeeActor,t)
       val draft=UUID.randomUUID()
       insert("""INSERT INTO finance_financial_imprest_draft(id,operation_id,organization_id,custodian_employee_id,
           requested_amount,currency,purpose,created_by_user_id,created_at,correlation_id)
           VALUES(?,?,?,?,?,'EGP','Synthetic company custody',?,?,'mixed-custody')""",
           draft,UUID.randomUUID(),org,employee,funding80,employeeActor,t)
       val policyApproval=approval("FINANCE_FINANCIAL_IMPREST_POLICY_REVIEW",draft,1,
           "FINANCE_IMPREST_POLICY",employeeActor)
       val origin=checkNotNull(jdbc.queryForObject("""SELECT encode(sha256(convert_to(concat_ws('|',
           'HILTECH_EMPLOYEE_FUNDING_ORIGIN_V1',CAST(? AS text),'FINANCIAL_IMPREST',CAST(? AS text),
           CAST(? AS text),CAST(CAST(? AS numeric(18,6)) AS text),'EGP',CAST(? AS text),'1'),'UTF8')),'hex')""",
           String::class.java,org,draft,employee,funding80,policyApproval))
       val fundingProposal=UUID.randomUUID()
       val fundingPolicy="FINANCE_EMPLOYEE_FUNDING"
       insert("""INSERT INTO finance_employee_funding_proposal(id,operation_id,organization_id,draft_kind,employee_draft_id,
           employee_id,source_policy_approval_id,bank_statement_line_id,amount,currency,source_fingerprint_sha256,
           review_version,policy_key,prepared_by_user_id,created_at)
           VALUES(?,?,?,'FINANCIAL_IMPREST',?,?,?,?,?,'EGP',?,707,?,?,?)""",
           fundingProposal,UUID.randomUUID(),org,draft,employee,policyApproval,actualBankIds[2],funding80,origin,
           fundingPolicy,issuer,t.plusHours(4))
       val fundingApproval=approval("FINANCE_EMPLOYEE_FUNDING",fundingProposal,707,fundingPolicy,issuer,t.plusHours(5))
       val funding=UUID.randomUUID()
       insert("""INSERT INTO finance_employee_funding_record(id,operation_id,organization_id,proposal_id,employee_draft_id,
           draft_kind,employee_id,bank_statement_line_id,approval_request_id,amount,currency,recorded_by_user_id,recorded_at,correlation_id)
           VALUES(?,?,?,?,?,'FINANCIAL_IMPREST',?,?,?,?,'EGP',?,?,'mixed-funded')""",
           funding,UUID.randomUUID(),org,fundingProposal,draft,employee,actualBankIds[2],fundingApproval,funding80,
           journalRecorder,t.plusHours(6))
       fun recordEmployeeSettlement(action:String,bankLine:UUID?,amount:BigDecimal):UUID {
           val proposal=UUID.randomUUID()
           val proofHash="4".repeat(64)
           val proof=evidence("FINANCE_EMPLOYEE_SETTLEMENT",proposal,proofHash)
           val policy="FINANCE_EMPLOYEE_SETTLEMENT"
           insert("""INSERT INTO finance_employee_settlement_proposal(id,operation_id,organization_id,funding_record_id,
               employee_id,action,bank_statement_line_id,settlement_evidence_id,settlement_evidence_sha256,amount,currency,
               review_version,policy_key,prepared_by_user_id,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,'EGP',808,?,?,?)""",
               proposal,UUID.randomUUID(),org,funding,employee,action,bankLine,proof,proofHash,amount,policy,issuer,t.plusHours(7))
           val decision=approval("FINANCE_EMPLOYEE_SETTLEMENT",proposal,808,policy,issuer,t.plusHours(8))
           val id=UUID.randomUUID()
           insert("""INSERT INTO finance_employee_settlement_record(id,operation_id,organization_id,proposal_id,
               funding_record_id,employee_id,action,bank_statement_line_id,settlement_evidence_id,approval_request_id,
               amount,currency,recorded_by_user_id,recorded_at,correlation_id)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,'EGP',?,?,'mixed-custody-settlement')""",
               id,UUID.randomUUID(),org,proposal,funding,employee,action,bankLine,proof,decision,amount,bankImporter,t.plusHours(9))
           return id
       }
       val returned=recordEmployeeSettlement("IMPREST_CASH_RETURN_CONFIRMED",actualBankIds[3],return30)
       val accepted=recordEmployeeSettlement("IMPREST_RECEIPTS_ACCEPTED",null,cash50)
       val custodyGl=chart("EMPLOYEE_IMPREST_ASSET","CUSTODY")
       val advanceGl=chart("EMPLOYEE_ADVANCE_ASSET","ADVANCE")
       val expenseGl=chart("EXPENSE","COST")
       val fundingSource=checkNotNull(settlementStore.source(org,"EMPLOYEE_FUNDING",funding))
       assertEquals("EI",fundingSource.sourceKind)
       assertEquals(funding,fundingSource.employeeFundingRecordId)
       assertEquals(null,fundingSource.sourceJournalId)
       assertEquals(bankGl,fundingSource.creditAccountId)
       assertEquals("EMPLOYEE_IMPREST_ASSET",fundingSource.requiredDebitKind)
       assertEquals(custodyGl,settlementStore.debitChoices(org,"EGP","EMPLOYEE_IMPREST_ASSET").single().id)
       assertTrue(settlementStore.source(org,"EMPLOYEE_RETURN",returned)?.creditAccountId==null)
       val notFunded=checkNotNull(settlementStore.source(org,"EMPLOYEE_RETURN",returned))
       val badReturn=connection.setSavepoint()
       try {
           assertThrows(DataIntegrityViolationException::class.java) {
               settlementStore.insert(org,journalRecorder,FinanceSettlementPost(UUID.randomUUID(),"EMPLOYEE_RETURN",returned,
                   "EI",period,bankGl,custodyGl,notFunded.amount,"EGP"),notFunded,UUID.randomUUID(),t.plusHours(10),"unposted-origin")
           }
       } finally {connection.rollback(badReturn)}
       val fundRequest=FinanceSettlementPost(UUID.randomUUID(),"EMPLOYEE_FUNDING",funding,"EI",period,custodyGl,bankGl,fundingSource.amount,"EGP")
       val badClass=connection.setSavepoint()
       try {
           assertThrows(DataIntegrityViolationException::class.java) {
               settlementStore.insert(org,journalRecorder,fundRequest.copy(debitAccountId=advanceGl),fundingSource,UUID.randomUUID(),t.plusHours(10),"wrong-custody-class")
           }
       } finally {connection.rollback(badClass)}
       settlementStore.insert(org,journalRecorder,fundRequest,fundingSource,UUID.randomUUID(),t.plusHours(10),"custody-ledger")
       val returnSource=checkNotNull(settlementStore.source(org,"EMPLOYEE_RETURN",returned))
       assertEquals(custodyGl,returnSource.creditAccountId)
       assertEquals(bankGl,returnSource.debitAccountId)
       settlementStore.insert(org,journalRecorder,FinanceSettlementPost(UUID.randomUUID(),"EMPLOYEE_RETURN",returned,
           "EI",period,bankGl,custodyGl,returnSource.amount,"EGP"),returnSource,UUID.randomUUID(),t.plusHours(11),"return-ledger")
       val acceptedSource=checkNotNull(settlementStore.source(org,"IMPREST_EXPENSE",accepted))
       assertEquals("COST",acceptedSource.requiredDebitKind)
       assertEquals(custodyGl,acceptedSource.creditAccountId)
       val costRequest=FinanceSettlementPost(UUID.randomUUID(),"IMPREST_EXPENSE",accepted,"EI",period,expenseGl,custodyGl,
           acceptedSource.amount,"EGP","SYNTHETIC-COST-FIRST-RECOGNITION")
       val noTreatment=connection.setSavepoint()
       try {
           assertThrows(DataIntegrityViolationException::class.java) {
               settlementStore.insert(org,journalRecorder,costRequest.copy(accountingTreatmentRef=null),acceptedSource,UUID.randomUUID(),t.plusHours(12),"no-treatment")
           }
       } finally {connection.rollback(noTreatment)}
       settlementStore.insert(org,journalRecorder,costRequest,acceptedSource,UUID.randomUUID(),t.plusHours(12),"receipts-ledger")

       // Reimbursement is not a new payment. The company confirms cost treatment.
       val claim=UUID.randomUUID()
       insert("""INSERT INTO finance_expense_claim_draft(id,operation_id,organization_id,employee_id,amount,currency,
           purpose,merchant,spent_on,created_by_user_id,created_at,correlation_id)
           VALUES(?,?,?,?,?,'EGP','Synthetic personal expense','Fixture merchant',?,?,?,'mixed-claim')""",
           claim,UUID.randomUUID(),org,employee,expense20,day,employeeActor,t)
       val documentation=approval("FINANCE_EXPENSE_DOCUMENTATION_REVIEW",claim,1,"FINANCE_EXPENSE_DOCUMENTATION",employeeActor)
       val reimbursementProposal=UUID.randomUUID()
       val reimbursementPolicy="FINANCE_EXPENSE_REIMBURSEMENT"
       insert("""INSERT INTO finance_expense_reimbursement_proposal(id,operation_id,organization_id,expense_draft_id,employee_id,
           documentation_approval_request_id,documentation_version,bank_statement_line_id,amount,currency,
           review_version,policy_key,prepared_by_user_id,created_at)
           VALUES(?,?,?,?,?,?,1,?,?,'EGP',909,?,?,?)""",reimbursementProposal,UUID.randomUUID(),org,claim,employee,
           documentation,actualBankIds[4],expense20,reimbursementPolicy,issuer,t.plusHours(7))
       val reimbursementApproval=approval("FINANCE_EXPENSE_REIMBURSEMENT",reimbursementProposal,909,reimbursementPolicy,issuer,t.plusHours(8))
       val reimbursement=UUID.randomUUID()
       insert("""INSERT INTO finance_expense_reimbursement_record(id,operation_id,organization_id,proposal_id,expense_draft_id,
           employee_id,bank_statement_line_id,amount,currency,approval_request_id,recorded_by_user_id,recorded_at,correlation_id)
           VALUES(?,?,?,?,?,?,?,?,'EGP',?,?,?,'mixed-reimbursement')""",reimbursement,UUID.randomUUID(),org,reimbursementProposal,
           claim,employee,actualBankIds[4],expense20,reimbursementApproval,bankImporter,t.plusHours(9))
       val expenseSource=checkNotNull(settlementStore.source(org,"EXPENSE_REIMBURSEMENT",reimbursement))
       assertEquals(claim,expenseSource.expenseClaimId)
       assertEquals(bankGl,expenseSource.creditAccountId)
       val expenseRequest=FinanceSettlementPost(UUID.randomUUID(),"EXPENSE_REIMBURSEMENT",reimbursement,"ER",period,expenseGl,bankGl,
           expenseSource.amount,"EGP","SYNTHETIC-EXPENSE-FIRST-RECOGNITION")
       settlementStore.insert(org,journalRecorder,expenseRequest,expenseSource,UUID.randomUUID(),t.plusHours(13),"reimbursement-ledger")
       assertEquals(expenseRequest.accountingTreatmentRef,settlementStore.receipt(org,expenseRequest.operationId,false)?.accountingTreatmentRef)

       val employeePairs=listOf("EMPLOYEE_FUNDING" to "EI","EMPLOYEE_RETURN" to "EI","IMPREST_EXPENSE" to "EI","EXPENSE_REIMBURSEMENT" to "ER")
       assertTrue(settlementStore.queue(org,employeePairs,0).isEmpty())
       assertTrue(settlementStore.queue(customer,employeePairs,0).isEmpty())
       assertEquals(4,desk.ledger(org,period,0,true).journals.size) // previous native contract
       val employeeReport=desk.ledger(org,period,0,true,true)
       assertEquals(8,employeeReport.journals.size)
       assertTrue(employeeReport.currencyTotals.single().balanced)
       val custodyMovement=employeeReport.movements.single{it.accountId==custodyGl}
       assertEquals(0,custodyMovement.debit.toBigDecimal().compareTo(funding80))
       assertEquals(0,custodyMovement.credit.toBigDecimal().compareTo(funding80))
       assertEquals(0,employeeReport.movements.single{it.accountId==expenseGl}.debit.toBigDecimal().compareTo(cash50+expense20))
       assertEquals(8L,jdbc.queryForObject("SELECT journal_count FROM finance_gl_period_close_source WHERE organization_id=? AND period_id=?",Long::class.java,org,period))
     } finally { connection.rollback() }
   }
 }
}
