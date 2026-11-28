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
