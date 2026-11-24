package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class FinanceGlAccountChoice(
    val id:UUID,val accountKind:String,val currency:String?,
)
data class FinanceGlPeriodChoice(
    val id:UUID,val dateStart:String,val dateEnd:String,
)
data class FinanceAccountingChoices(
    val organizationId:UUID,val kind:String,
    val approvedAccounts:List<FinanceGlAccountChoice>,
    val openPeriods:List<FinanceGlPeriodChoice>,
    val hasMoreAccounts:Boolean,val hasMorePeriods:Boolean,
)

/**
 * Read-only canonical chart and open-period choices for existing AP/AR preparers.
 * No new role, status mutation, GL posting or cross-company visibility.
 */
@Service
class FinanceAccountingChoicesService(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
) {
    fun choices(actor:UUID,org:UUID,kind:String):FinanceAccountingChoices {
        val enabled=when(kind) {
            "AP" -> "hiltech.finance.ap-posting.enabled"
            "AR" -> "hiltech.finance.receivables.ar-posting.enabled"
            else -> unavailable()
        }
        val authorityKeyName=when(kind) {
            "AP" -> "hiltech.finance.ap-posting.preparer-authority-key"
            "AR" -> "hiltech.finance.receivables.ar-posting.preparer-authority-key"
            else -> unavailable()
        }
        if(!environment.getProperty(enabled,Boolean::class.java,false))unavailable()
        val authorityKey=environment.getProperty(authorityKeyName,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(authorityKey))unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val binding=approvals.resolveCurrentAuthority(org,authorityKey,now) ?:unavailable()
        if(binding.organizationId!=org || binding.authorityKey!=authorityKey ||
            !approvals.actorMatchesAuthority(actor,binding,now))unavailable()

        val today=LocalDate.ofInstant(now,ZoneOffset.UTC)
        val accounts=jdbc.query(
            """SELECT id,account_kind,currency FROM finance_gl_account
               WHERE organization_id=? AND state='APPROVED'
               ORDER BY account_kind,id LIMIT 101""",
            {rs,_->FinanceGlAccountChoice(
                rs.getObject("id",UUID::class.java),
                rs.getString("account_kind"),rs.getString("currency"))},org,
        )
        val periods=jdbc.query(
            """SELECT p.id,p.date_start,p.date_end FROM finance_gl_period p
               WHERE p.organization_id=? AND p.state='OPEN'
                 AND p.date_start<=? AND p.date_end>=?
                 AND NOT EXISTS (
                   SELECT 1 FROM finance_gl_period_close c
                   WHERE c.organization_id=p.organization_id AND c.period_id=p.id)
               ORDER BY p.date_start,p.id LIMIT 101""",
            {rs,_->FinanceGlPeriodChoice(
                rs.getObject("id",UUID::class.java),
                rs.getDate("date_start").toLocalDate().toString(),
                rs.getDate("date_end").toLocalDate().toString())},
            org,java.sql.Date.valueOf(today),java.sql.Date.valueOf(today),
        )
        return FinanceAccountingChoices(org,kind,accounts.take(100),periods.take(100),
            accounts.size>100,periods.size>100)
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_ACCOUNTING_CHOICES_UNAVAILABLE",
        "Accounting choices are only available to the current Finance preparer.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceAccountingChoicesController(
    private val choices:FinanceAccountingChoicesService,
) {
    @GetMapping("/v1/finance/organizations/{orgId}/accounting-choices")
    fun get(http:HttpServletRequest,@PathVariable orgId:UUID,
        @RequestParam kind:String):FinanceAccountingChoices {
        val actor=HiltechRequestContext.current(http).identityId
            ?.let {runCatching {UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Company Finance identity required.",HttpStatus.UNAUTHORIZED)
        return choices.choices(actor,orgId,kind)
    }
}
