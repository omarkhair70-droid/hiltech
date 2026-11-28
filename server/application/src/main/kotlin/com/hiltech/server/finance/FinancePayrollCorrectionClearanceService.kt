package com.hiltech.server.finance

import com.hiltech.server.payroll.PayrollVerifiedCorrectionAuthorization
import com.hiltech.server.payroll.PayrollVerifiedCorrectionSourcePort
import com.hiltech.server.payroll.PayrollFinanceCorrectionDisposition
import com.hiltech.server.payroll.PayrollInputApproval
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.*
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class FinancePayrollCorrectionCandidate(
    val originalRunVersionId:UUID,val replacementRunVersionId:UUID,
    val periodStart:String,val periodEnd:String,val originalGeneration:Int,val replacementGeneration:Int,
    val currency:String,val originalNet:String,val replacementNet:String,
    val eligibleDisposition:String?,val alreadyCleared:Boolean,val canRecord:Boolean,
)
data class FinancePayrollCorrectionCandidates(val organizationId:UUID,val records:List<FinancePayrollCorrectionCandidate>,val hasMore:Boolean)
data class FinancePayrollCorrectionEmployeeReview(
    val employeeId:UUID,val employeeName:String,val employeeCode:String,
    val originalNet:String,val replacementNet:String,val entitlementDelta:String,
    val postedPayments:String,val postedReturns:String,val recordedNet:String?,val differenceFromRecorded:String?,
    val evidenceState:String,val pendingCount:Long,val unpostedCount:Long,val voidCount:Long,val noResultCount:Long,
)
data class FinancePayrollDeltaPreviewLine(val lineNumber:Int,val kind:String,val componentCode:String?,
    val accountCode:String?,val accountName:String?,val side:String,val amount:String)
data class FinancePayrollDeltaPlan(val state:String,val employeeCount:Long,val blockedCount:Long,
    val additionalDue:String?,val recoveryDue:String?,val grossChange:String,val deductionChange:String,val reimbursementChange:String,
    val lines:List<FinancePayrollDeltaPreviewLine>)
data class FinancePayrollCorrectionReviewPage(val organizationId:UUID,val originalRunVersionId:UUID,val replacementRunVersionId:UUID,
    val currency:String,val offset:Int,val rows:List<FinancePayrollCorrectionEmployeeReview>,val hasMore:Boolean,
    val deltaPlan:FinancePayrollDeltaPlan?=null)
data class FinancePayrollClearanceInput(val operationId:UUID,val originalRunVersionId:UUID,val replacementRunVersionId:UUID,
    val disposition:String,val reasonReference:String,val financeReference:String)

private fun correctionUnavailable():Nothing=throw ProductApiException("FINANCE_PAYROLL_CORRECTION_UNAVAILABLE",
    "Current finance payroll review authority is required.",HttpStatus.NOT_FOUND)
private fun correctionChanged():Nothing=throw ProductApiException("FINANCE_PAYROLL_CORRECTION_CHANGED",
    "The correction source, prior transfers or current clearance changed. Refresh and review.",HttpStatus.CONFLICT)
private fun validReference(value:String)=value.length in 8..240 && value==value.trim() && value.none{it.code<32||it.code==127}
internal fun validPayrollClearance(input:FinancePayrollClearanceInput)=
    input.originalRunVersionId!=input.replacementRunVersionId &&
    input.disposition in setOf("NO_PRIOR_TRANSFER_VERIFIED","REVERSAL_VERIFIED") &&
    validReference(input.reasonReference)&&validReference(input.financeReference)

/** Real Finance-side source for Payroll's existing independently approved release.
 * References are attestations by the assigned reviewer, not machine verification
 * of external documents. Returned payments additionally require recorded bank
 * evidence and posted payment/return journals. */
@Component
class FinancePayrollCorrectionSource(private val jdbc:JdbcTemplate):PayrollVerifiedCorrectionSourcePort {
    override fun verifiedFor(organizationId:UUID,originalRunVersionId:UUID,replacementRunVersionId:UUID):PayrollVerifiedCorrectionAuthorization? =
        jdbc.query("""
            SELECT c.* FROM finance_payroll_correction_candidate v
            JOIN LATERAL (SELECT * FROM finance_payroll_correction_clearance r
                WHERE r.organization_id=v.organization_id AND r.replacement_run_version_id=v.replacement_run_version_id
                ORDER BY r.revision DESC LIMIT 1) c ON true
            WHERE v.organization_id=? AND v.original_run_version_id=? AND v.replacement_run_version_id=?
              AND c.original_run_version_id=v.original_run_version_id
              AND c.original_fingerprint=v.original_fingerprint AND c.replacement_fingerprint=v.replacement_fingerprint
              AND c.disposition=v.eligible_disposition AND c.reviewed_by_user_id<>v.draft_creator
              AND finance_payroll_current_authority(c.organization_id,c.reviewed_by_user_id,'REVIEW')
        """.trimIndent(),{r,_->
            PayrollVerifiedCorrectionAuthorization(organizationId,originalRunVersionId,replacementRunVersionId,
                r.getString("original_fingerprint").trim(),r.getString("replacement_fingerprint").trim(),PayrollInputApproval.APPROVED,
                "FINANCE_CORRECTION_REVIEWED",r.getString("reason_reference"),r.getLong("revision"),
                PayrollFinanceCorrectionDisposition.valueOf(r.getString("disposition")),
                "FINANCE_CLEARANCE:"+r.getObject("id",UUID::class.java),r.getLong("revision"))
        },organizationId,originalRunVersionId,replacementRunVersionId).singleOrNull()
}

@Service
class FinancePayrollCorrectionClearanceService(
    private val jdbc:JdbcTemplate,private val authority:FinancePayrollAuthorityPort,
    private val commands:IdempotentCommandExecutor,private val audit:AuditEventWriter,private val clock:Clock,
) {
    private fun requireReview(actor:UUID,org:UUID) { if(!authority.canReview(actor,org))correctionUnavailable() }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun candidates(actor:UUID,org:UUID):FinancePayrollCorrectionCandidates {
        requireReview(actor,org)
        val rows=jdbc.query("""
            SELECT v.*,(c.disposition=v.eligible_disposition AND c.original_run_version_id=v.original_run_version_id
              AND c.original_fingerprint=v.original_fingerprint AND c.replacement_fingerprint=v.replacement_fingerprint
              AND finance_payroll_current_authority(c.organization_id,c.reviewed_by_user_id,'REVIEW')) AS cleared
            FROM finance_payroll_correction_candidate v
            LEFT JOIN LATERAL (SELECT * FROM finance_payroll_correction_clearance r
                WHERE r.organization_id=v.organization_id AND r.replacement_run_version_id=v.replacement_run_version_id
                ORDER BY r.revision DESC LIMIT 1) c ON true
            WHERE v.organization_id=? ORDER BY v.end_date DESC,v.replacement_generation DESC,v.replacement_run_version_id LIMIT 101
        """.trimIndent(),{r,_->
            val disposition=r.getString("eligible_disposition")
            val cleared=r.getBoolean("cleared")
            FinancePayrollCorrectionCandidate(r.getObject("original_run_version_id",UUID::class.java),r.getObject("replacement_run_version_id",UUID::class.java),
                r.getDate("start_date").toString(),r.getDate("end_date").toString(),r.getInt("original_generation"),r.getInt("replacement_generation"),
                r.getString("currency").trim(),r.getBigDecimal("original_net").toPlainString(),r.getBigDecimal("replacement_net").toPlainString(),
                disposition,cleared,disposition!=null&&!cleared&&r.getObject("draft_creator",UUID::class.java)!=actor)
        },org)
        return FinancePayrollCorrectionCandidates(org,rows.take(100),rows.size>100)
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun employeeReview(actor:UUID,org:UUID,replacement:UUID,offset:Int):FinancePayrollCorrectionReviewPage {
        requireReview(actor,org)
        if(offset !in 0..1_000_000)correctionChanged()
        val candidate=jdbc.query("""SELECT original_run_version_id,currency FROM finance_payroll_correction_candidate
            WHERE organization_id=? AND replacement_run_version_id=?""",
            {r,_->r.getObject(1,UUID::class.java) to r.getString(2).trim()},org,replacement).singleOrNull()?:correctionChanged()
        val rows=jdbc.query("""SELECT employee_id,employee_name,employee_code,original_net,replacement_net,entitlement_delta,
            posted_payments,posted_returns,recorded_net,difference_from_recorded,evidence_state,pending_count,unposted_count,void_count,no_result_count
            FROM finance_payroll_correction_employee_review WHERE organization_id=? AND replacement_run_version_id=?
            ORDER BY employee_name,employee_code,employee_id LIMIT 51 OFFSET ?""",
            {r,_->FinancePayrollCorrectionEmployeeReview(r.getObject(1,UUID::class.java),r.getString(2),r.getString(3),
                r.getBigDecimal(4).toPlainString(),r.getBigDecimal(5).toPlainString(),r.getBigDecimal(6).toPlainString(),
                r.getBigDecimal(7).toPlainString(),r.getBigDecimal(8).toPlainString(),r.getBigDecimal(9)?.toPlainString(),
                r.getBigDecimal(10)?.toPlainString(),r.getString(11),r.getLong(12),r.getLong(13),r.getLong(14),r.getLong(15))},org,replacement,offset)
        val deltaPlan=jdbc.query("""SELECT plan_state,employee_count,blocked_count,additional_due,recovery_due,gross_change,deduction_change,reimbursement_change
            FROM finance_payroll_settled_delta_plan WHERE organization_id=? AND replacement_run_version_id=?""",
            {r,_->FinancePayrollDeltaPlan(r.getString(1),r.getLong(2),r.getLong(3),r.getBigDecimal(4)?.toPlainString(),
                r.getBigDecimal(5)?.toPlainString(),r.getBigDecimal(6).toPlainString(),r.getBigDecimal(7).toPlainString(),
                r.getBigDecimal(8).toPlainString(),emptyList())},org,replacement).singleOrNull()
        val preview=deltaPlan?.let{plan->
            val lines=if(plan.state in setOf("REVIEW_READY","RECOVERY_ACCOUNT_REQUIRED"))jdbc.query("""
                SELECT line_number,line_kind,component_code,account_code,account_name,side,amount
                FROM finance_payroll_settled_delta_preview_line WHERE organization_id=? AND replacement_run_version_id=? ORDER BY line_number
            """,{r,_->FinancePayrollDeltaPreviewLine(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                r.getString(6),r.getBigDecimal(7).toPlainString())},org,replacement)else emptyList()
            plan.copy(lines=lines)
        }
        return FinancePayrollCorrectionReviewPage(org,candidate.first,replacement,candidate.second,offset,rows.take(50),rows.size>50,preview)
    }

    fun record(actor:UUID,org:UUID,input:FinancePayrollClearanceInput,correlation:String):FinancePayrollCommandReceipt {
        requireReview(actor,org)
        if(!validPayrollClearance(input))correctionChanged()
        val id=UUID.nameUUIDFromBytes(("finance-payroll-clearance:"+input.operationId).toByteArray(Charsets.UTF_8))
        val fingerprint=IdempotencyKeyContract.fingerprint(buildJsonObject{
            put("organizationId",org.toString());put("originalRunVersionId",input.originalRunVersionId.toString())
            put("replacementRunVersionId",input.replacementRunVersionId.toString());put("disposition",input.disposition)
            put("reasonReference",input.reasonReference);put("financeReference",input.financeReference)
        }.toString())
        val result=try{commands.execute(IdempotentCommandSpec(input.operationId,actor,"FINANCE_PAYROLL_CORRECTION_CLEARANCE",
            "FINANCE_PAYROLL_CORRECTION_CLEARANCE",id,fingerprint,correlation)){
            requireReview(actor,org)
            val period=jdbc.query("""SELECT p.id FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
                WHERE r.organization_id=? AND r.id=? FOR UPDATE OF p""",
                {r,_->r.getObject(1,UUID::class.java)},org,input.replacementRunVersionId).singleOrNull()?:correctionChanged()
            val at=clock.instant()
            val inserted=jdbc.update("""
                INSERT INTO finance_payroll_correction_clearance(id,operation_id,organization_id,original_run_version_id,replacement_run_version_id,
                    original_fingerprint,replacement_fingerprint,revision,disposition,reason_reference,finance_reference,reviewed_by_user_id,reviewed_at)
                SELECT ?,?,v.organization_id,v.original_run_version_id,v.replacement_run_version_id,v.original_fingerprint,v.replacement_fingerprint,
                    (SELECT coalesce(max(r.revision),0)+1 FROM finance_payroll_correction_clearance r WHERE r.organization_id=v.organization_id AND r.replacement_run_version_id=v.replacement_run_version_id),
                    ?,?,?,?,? FROM finance_payroll_correction_candidate v
                WHERE v.organization_id=? AND v.original_run_version_id=? AND v.replacement_run_version_id=? AND v.eligible_disposition=?
                  AND v.draft_creator<>?
                  AND NOT EXISTS(SELECT 1 FROM finance_payroll_correction_clearance c WHERE c.organization_id=v.organization_id
                    AND c.replacement_run_version_id=v.replacement_run_version_id AND c.disposition=v.eligible_disposition
                    AND c.revision=(SELECT max(r.revision) FROM finance_payroll_correction_clearance r WHERE r.organization_id=v.organization_id AND r.replacement_run_version_id=v.replacement_run_version_id)
                    AND finance_payroll_current_authority(c.organization_id,c.reviewed_by_user_id,'REVIEW'))
            """.trimIndent(),id,input.operationId,input.disposition,input.reasonReference,input.financeReference,actor,at.atOffset(ZoneOffset.UTC),
                org,input.originalRunVersionId,input.replacementRunVersionId,input.disposition,actor)
            if(inserted!=1)correctionChanged()
            audit.append(AuditEventRecord(actorUserId=actor,action="FINANCE_PAYROLL_CORRECTION_CLEARED",targetType="FINANCE_PAYROLL_CORRECTION_CLEARANCE",
                targetId=id,occurredAt=at,correlationId=correlation))
            IdempotentCommandOutcome("FINANCE_PAYROLL_CORRECTION_CLEARED","""{"id":"$id"}""")
        }}catch(_:DataIntegrityViolationException){correctionChanged()}
        requireReview(actor,org)
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM finance_payroll_correction_clearance WHERE organization_id=? AND id=? AND operation_id=?)",
            Boolean::class.java,org,id,input.operationId)!=true)correctionChanged()
        return FinancePayrollCommandReceipt(input.operationId,org,id,"CLEARED",result.replayed)
    }
}

@RestController
@RequestMapping("/v1/finance/payroll/organizations/{orgId}/disbursements/corrections")
class FinancePayrollCorrectionClearanceController(private val service:FinancePayrollCorrectionClearanceService,private val sessions:IdentitySessionService) {
    @GetMapping
    fun candidates(request:HttpServletRequest,@PathVariable orgId:UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.candidates(actor(request),orgId))
    @GetMapping("/{replacementRunVersionId}/employees")
    fun employeeReview(request:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable replacementRunVersionId:UUID,
        @RequestParam(defaultValue="0") offset:Int)=ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(service.employeeReview(actor(request),orgId,replacementRunVersionId,offset))
    @PostMapping
    fun record(request:HttpServletRequest,@PathVariable orgId:UUID,@AuthenticationPrincipal jwt:Jwt,
        @RequestHeader("X-Device-Installation-Id") installation:UUID,
        @RequestBody body:FinancePayrollClearanceInput):ResponseEntity<FinancePayrollCommandReceipt> {
        val context=HiltechRequestContext.current(request)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey,body.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt,installation))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.record(actor(request),orgId,body,context.correlationId))
    }
    private fun actor(request:HttpServletRequest):UUID=HiltechRequestContext.current(request).identityId
        ?.let{runCatching{UUID.fromString(it)}.getOrNull()}?:throw ProductApiException("UNAUTHENTICATED","Authentication required.",HttpStatus.UNAUTHORIZED)
}
