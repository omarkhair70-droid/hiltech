package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class FinanceEmployeeMoneyApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val draft="22222222-2222-2222-2222-222222222222"
    private val approval="33333333-3333-3333-3333-333333333333"
    private val bank="44444444-4444-4444-4444-444444444444"
    private val operation="55555555-5555-5555-5555-555555555555"
    private val proposal="66666666-6666-6666-6666-666666666666"
    private val installation="77777777-7777-7777-7777-777777777777"
    private fun service(http:HttpClient)=FinanceEmployeeMoneyApiClient(
        HiltechApiClient(
            http,"https://finance.hiltech.test",
            accessTokenProvider={"finance-money-token"},
            correlationIdProvider={"finance-money-correlation"},
        ),
    )
    @Test fun companyImprestFundingIsNotInitiatingBankTransfer()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine{req->
            calls++
            assertEquals("POST",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/employee-funding/FINANCIAL_IMPREST/$draft/prepare",
                req.url.encodedPath,
            )
            assertEquals("Bearer finance-money-token",
                req.headers[HttpHeaders.Authorization])
            respond(
                """{"proposalId":"$proposal","organizationId":"$org",
                "kind":"FINANCIAL_IMPREST","draftId":"$draft",
                "employeeId":"$approval","bankStatementLineId":"$bank",
                "amount":"250.000000","currency":"EGP","state":"PROPOSED",
                "fundingEvidenceRecorded":false,
                "bankTransferInitiated":false,
                "personalLoanRecognized":false,
                "cashCustodyRecognized":false,"employeeSettled":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=service(http).prepareFunding(
            org,"FINANCIAL_IMPREST",draft,operation,approval,bank,installation)
        assertEquals(1,calls)
        assertFalse(result.fundingEvidenceRecorded)
        assertFalse(result.bankTransferInitiated)
        assertFalse(result.personalLoanRecognized)
        assertFalse(result.cashCustodyRecognized)
        assertFalse(result.employeeSettled)
    }
    @Test fun imprestReceiptHasNoBankTransferAndNoPayrollDeduction()=runBlocking {
        val http=HttpClient(MockEngine{req->
            assertEquals(
                "/v1/finance/organizations/$org/employee-funded/$draft/settlements/prepare",
                req.url.encodedPath,
            )
            respond(
                """{"proposalId":"$proposal","organizationId":"$org",
                "fundingRecordId":"$draft","employeeId":"$approval",
                "action":"IMPREST_RECEIPTS_ACCEPTED","amount":"100.000000",
                "currency":"EGP","state":"PROPOSED",
                "settlementRecorded":false,
                "bankTransferInitiated":false,
                "payrollDeductionInitiated":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=service(http).prepareSettlement(
            org,draft,
            FinanceEmployeeSettlementPrepareDto(
                operationId=operation,action="IMPREST_RECEIPTS_ACCEPTED",
                amount="100.000000",settlementEvidenceId=approval,
                settlementEvidenceSha256="f".repeat(64),
                bankStatementLineId=null,
            ),installation,
        )
        assertFalse(result.settlementRecorded)
        assertFalse(result.bankTransferInitiated)
        assertFalse(result.payrollDeductionInitiated)
    }
    @Test fun fakeCashReturnCannotBeSubmittedWithoutBankSource()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine{calls++;error("Unexpected external call")})
        assertFailsWith<IllegalArgumentException> {
            service(http).prepareSettlement(
                org,draft,
                FinanceEmployeeSettlementPrepareDto(
                    operationId=operation,
                    action="IMPREST_CASH_RETURN_CONFIRMED",
                    amount="100.000000",
                    settlementEvidenceId=approval,
                    settlementEvidenceSha256="f".repeat(64),
                    bankStatementLineId=null,
                ),installation,
            )
        }
        assertEquals(0,calls)
    }
}
