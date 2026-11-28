package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.util.UUID

data class FinanceObligationChoice(val id:UUID,val counterpartyName:String,val currency:String,val outstanding:String,
    val dueDate:String?,val openingDocumentReference:String?)
data class FinanceObligationChoices(val organizationId:UUID,val kind:String,val channel:String,val offset:Int,
    val records:List<FinanceObligationChoice>,val hasMore:Boolean)

@Service
class FinanceObligationChoicesService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,private val approvals:ApprovalPersistencePort,private val environment:Environment,
    private val clock:Clock,private val operationalAuthority:OperationalAuthorityPort?=null,
) {
    @Transactional(readOnly=true)
    fun choices(actor:UUID,org:UUID,kind:String,channel:String,offset:Int):FinanceObligationChoices {
        if(kind !in setOf("AP","AR")||offset !in 0..1_000_000)denied()
        val (enabled,key)=when(channel){
            "BANK"->"hiltech.finance.cash-allocation.enabled" to "hiltech.finance.cash-allocation.preparer-authority-key"
            "CASH"->"hiltech.finance.cashbox.enabled" to "hiltech.finance.cashbox.voucher-preparer-authority-key"
            else->denied()
        }
        if(!environment.getProperty(enabled,Boolean::class.java,false)||
            currentOperationalAuthority(operationalAuthority,approvals,actor,org,key,environment.getProperty(key),clock.instant())==null)denied()
        val rows=jdbc.query("""
            SELECT b.journal_id,o.display_name,b.currency,b.outstanding_amount,b.contractual_due_date,legacy.document_reference
            FROM finance_ap_ar_source_balance b JOIN organization o ON o.id=b.counterparty_id
            LEFT JOIN finance_gl_opening_obligation legacy ON legacy.organization_id=b.organization_id
                AND legacy.source_kind=b.source_kind AND legacy.id=b.journal_id
            WHERE b.organization_id=? AND b.source_kind=? AND NOT b.reversed AND b.outstanding_amount>0
            ORDER BY b.contractual_due_date NULLS LAST,b.recognized_at,b.journal_id LIMIT 51 OFFSET ?
        """.trimIndent(),{r,_->FinanceObligationChoice(r.getObject(1,UUID::class.java),r.getString(2),
            r.getString(3).trim(),r.getBigDecimal(4).toPlainString(),r.getDate(5)?.toString(),r.getString(6))},org,kind,offset)
        return FinanceObligationChoices(org,kind,channel,offset,rows.take(50),rows.size>50)
    }
    private fun denied():Nothing=throw ProductApiException("FINANCE_OBLIGATION_CHOICES_UNAVAILABLE",
        "Current settlement preparation authority is required.",HttpStatus.NOT_FOUND)
}
@RestController
@RequestMapping("/v1/finance/organizations/{orgId}/obligation-choices")
class FinanceObligationChoicesController(private val service:FinanceObligationChoicesService){
    @GetMapping
    fun choices(request:HttpServletRequest,@PathVariable orgId:UUID,@RequestParam kind:String,@RequestParam channel:String,
        @RequestParam(defaultValue="0") offset:Int):ResponseEntity<FinanceObligationChoices> {
        val actor=HiltechRequestContext.current(request).identityId?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException("UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.choices(actor,orgId,kind,channel,offset))
    }
}
