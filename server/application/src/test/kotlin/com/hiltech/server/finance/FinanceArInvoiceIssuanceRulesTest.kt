package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceArInvoiceIssuanceRulesTest {
    private val s=FinanceArIssuanceSnapshot(
        organizationId=UUID.randomUUID(),candidateId=UUID.randomUUID(),
        customerOrganizationId=UUID.randomUUID(),
        invoiceNumber="EG-COMPANY-2026-00001",
        documentEvidenceId=UUID.randomUUID(),
        commercialSourceHash="f".repeat(64),approvedAmount=BigDecimal("200.000000"),
        currency="EGP",issueDate=LocalDate.parse("2026-11-23"),
        dueDate=LocalDate.parse("2026-12-23"),
        candidatePreparedBy=UUID.randomUUID(),issuer=UUID.randomUUID(),
        sourceCurrent=true,verifiedInvoiceDocument=true,
        numberAssignedByCompany=true,
    )
    private fun rejects(x:FinanceArIssuanceSnapshot) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceArInvoiceIssuanceRules.validate(x)
        }
    }
    @Test fun exactCompanyEvidenceRequired() {
        FinanceArInvoiceIssuanceRules.validate(s)
        rejects(s.copy(sourceCurrent=false))
        rejects(s.copy(verifiedInvoiceDocument=false))
        rejects(s.copy(numberAssignedByCompany=false))
        rejects(s.copy(issuer=s.candidatePreparedBy))
        rejects(s.copy(customerOrganizationId=s.organizationId))
        rejects(s.copy(commercialSourceHash=""))
        rejects(s.copy(dueDate=s.issueDate.minusDays(1)))
        rejects(s.copy(approvedAmount=BigDecimal.ZERO))
    }
    @Test fun reviewVersionChangesOnExactInvoiceAndPolicy() {
        val reviewer=UUID.randomUUID()
        val v=FinanceArInvoiceIssuanceVersion.derive(s,3,reviewer,2)
        assertEquals(v,FinanceArInvoiceIssuanceVersion.derive(s,3,reviewer,2))
        assertNotEquals(v,FinanceArInvoiceIssuanceVersion.derive(
            s.copy(invoiceNumber="EG-COMPANY-2026-00002"),3,reviewer,2))
        assertNotEquals(v,FinanceArInvoiceIssuanceVersion.derive(
            s.copy(documentEvidenceId=UUID.randomUUID()),3,reviewer,2))
        assertNotEquals(v,FinanceArInvoiceIssuanceVersion.derive(s,4,reviewer,2))
    }
}
