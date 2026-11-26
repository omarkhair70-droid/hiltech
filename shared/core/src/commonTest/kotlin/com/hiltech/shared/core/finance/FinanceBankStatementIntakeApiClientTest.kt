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

class FinanceBankStatementIntakeApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val account="22222222-2222-2222-2222-222222222222"
    private val evidence="33333333-3333-3333-3333-333333333333"
    private val operation="44444444-4444-4444-4444-444444444444"
    private val installation="55555555-5555-5555-5555-555555555555"
    private fun service(http:HttpClient)=FinanceBankStatementIntakeApiClient(
        HiltechApiClient(
            http,"https://finance.hiltech.test",
            accessTokenProvider={"bank-finance-token"},
            correlationIdProvider={"bank-finance-correlation"},
        ),
    )
    private val body=FinanceBankIntakePrepareDto(
        operationId=operation,bankAccountId=account,statementEvidenceId=evidence,
        statementSha256="a".repeat(64),
        periodStart="2026-11-01",periodEnd="2026-11-30",
        openingBalance="100.000000",closingBalance="200.000000",
        lines=listOf(FinanceBankIntakeLineDto(
            externalTransactionId="BANK-EG-TXN-0123",
            bookedOn="2026-11-23",flow="CREDIT",amount="100.000000",
            originalLineSha256="b".repeat(64),
        )),
    )
    @Test fun manualStatementIsNotClaimedAsBankTransferOrReconciliation()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine {req->
            calls++
            assertEquals("POST",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/bank-statement-intakes/prepare",
                req.url.encodedPath,
            )
            assertEquals("Bearer bank-finance-token",
                req.headers[HttpHeaders.Authorization])
            respond(
                """{"proposalId":"66666666-6666-6666-6666-666666666666",
                  "organizationId":"$org","bankAccountId":"$account",
                  "statementHash":"${"a".repeat(64)}","lineHash":"${"b".repeat(64)}",
                  "lineCount":1,"state":"PROPOSED",
                  "internalBankStatementRecorded":false,
                  "bankTransactionInitiated":false,"bankReconciled":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=service(http).prepare(org,body,installation)
        assertEquals(1,calls)
        assertFalse(result.internalBankStatementRecorded)
        assertFalse(result.bankTransactionInitiated)
        assertFalse(result.bankReconciled)
    }
    @Test fun missingBankDocumentFailsBeforeFinanceNetwork()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine {calls++;error("Unexpected call")})
        assertFailsWith<IllegalArgumentException> {
            service(http).prepare(
                org,body.copy(statementSha256="not-a-final-document"),installation)
        }
        assertEquals(0,calls)
    }
}
