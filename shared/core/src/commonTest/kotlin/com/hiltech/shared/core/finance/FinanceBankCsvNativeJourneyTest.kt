package com.hiltech.shared.core.finance

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinanceBankCsvNativeJourneyTest {
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val bank = "11111111-2222-4333-8444-555555555555"
    private val proposal = "22222222-3333-4444-8555-666666666666"
    private val intakeOp = "33333333-4444-4555-8666-777777777777"
    private val reserveOp = "44444444-5555-4666-8777-888888888888"
    private val finalizeOp = "55555555-6666-4777-8888-999999999999"
    private val evidenceId = "66666666-7777-4888-8999-aaaaaaaaaaaa"
    private val session = "77777777-8888-4999-8aaa-bbbbbbbbbbbb"
    private val hash = "a".repeat(64)
    private val file = FinanceBankCsvSelectedFile(
        ("external_transaction_id,booked_on,flow,currency,amount\n" +
            "BANK-BOOK-0001,2026-11-24,CREDIT,EGP,100.000000\n"
        ).toByteArray(),
        sha256 = hash, originalFileName = "bank.csv",
        capturedAt = "2026-11-24T00:00:00Z",
    )
    private fun evidenceJson(state: String) = """
        {"evidenceId":"${evidenceId}","uploadSessionId":"${session}",
        "targetType":"FINANCE_BANK_STATEMENT_INTAKE","targetId":"${proposal}",
        "evidenceRequirementKey":"FINANCE_BANK_STATEMENT_INTAKE_BINARY",
        "evidenceTypeCode":"FINANCE_BANK_STATEMENT_INTAKE",
        "contentType":"text/csv","sizeBytes":${file.bytes.size},
        "sha256":"${hash}","storageState":"${state}",
        "classificationCode":"HIGHLY_RESTRICTED",
        "clientVisibilityMode":"INTERNAL_ONLY","evidenceVersion":1}
    """.trimIndent()
    private fun MockRequestHandleScope.ok(body: String) = respond(
        body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
    )
    private fun uploader(
        api: HttpClient, binary: HttpClient,
    ): FinanceBankCsvEvidenceUploader {
        val source = HiltechApiClient(
            api, "https://hiltech.test",
            accessTokenProvider = { "company-finance-token" },
            correlationIdProvider = { "native-bank-journey" },
        )
        return FinanceBankCsvEvidenceUploader(
            EvidenceApiClient(source, binary),
            FinanceBankStatementIntakeApiClient(source),
        )
    }
    private fun input() = FinanceBankCsvEvidenceUploadInput(
        org, bank, intakeOp, proposal, reserveOp, finalizeOp, file,
    )

    @Test
    fun signedEvidenceUploadAndPrepareUseSameSourceAndSha() = runBlocking {
        val steps = mutableListOf<String>()
        val api = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/evidence/reservations" -> {
                    steps += "reserve"
                    assertEquals("POST", request.method.value)
                    assertEquals(reserveOp, request.headers["Idempotency-Key"])
                    ok("""
                        {"evidence":${evidenceJson("RESERVED")},
                         "upload":{"uploadUrl":"https://signed-bank.test/put",
                           "requiredHeaders":{},"expiresAt":"2026-11-25T00:00:00Z",
                           "expectedSizeBytes":${file.bytes.size}},
                         "correlationId":"source","replayed":false}
                    """)
                }
                "/v1/evidence/${evidenceId}/finalize" -> {
                    steps += "finalize"
                    assertEquals(finalizeOp, request.headers["Idempotency-Key"])
                    ok("""{"evidence":${evidenceJson("READY")},
                        "correlationId":"source","replayed":false}""")
                }
                "/v1/finance/organizations/${org}/bank-statement-intakes/prepare-csv" -> {
                    steps += "prepare"
                    assertEquals(intakeOp, request.headers["Idempotency-Key"])
                    ok("""
                      {"proposalId":"${proposal}","organizationId":"${org}",
                       "bankAccountId":"${bank}","statementHash":"${hash}",
                       "lineHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                       "lineCount":1,"state":"PROPOSED"}
                    """)
                }
                else -> error("Unexpected Finance URL")
            }
        })
        val binary = HttpClient(MockEngine { req ->
            steps += "signed-put"
            assertEquals("PUT", req.method.value)
            assertEquals("/put", req.url.encodedPath)
            respond("", HttpStatusCode.OK)
        })
        val sut = uploader(api, binary)
        val ready = sut.upload(input(), "android-installation")
        assertEquals(evidenceId, ready.evidenceId)
        val outcome = sut.prepareSource(
            ready, file, "2026-11-24", "2026-11-24",
            "0", "100", "android-installation",
        )
        assertEquals("PROPOSED", outcome.state)
        assertEquals(proposal, outcome.proposalId)
        assertEquals(listOf("reserve", "signed-put", "finalize", "prepare"), steps)
    }

    @Test
    fun quarantinedSourceIsNotAnUploadSuccess() = runBlocking {
        val api = HttpClient(MockEngine { request ->
            assertEquals("/v1/evidence/reservations", request.url.encodedPath)
            ok("""
                {"evidence":${evidenceJson("QUARANTINED")},
                 "upload":{"uploadUrl":"https://signed-bank.test/put",
                    "requiredHeaders":{},"expiresAt":"2026-11-25T00:00:00Z",
                    "expectedSizeBytes":${file.bytes.size}},
                 "correlationId":"source","replayed":false}
            """)
        })
        val binary = HttpClient(MockEngine { error("No upload for quarantined Evidence") })
        assertFailsWith<FinanceBankCsvEvidenceNotReady> {
            uploader(api, binary).upload(input(), "android-installation")
        }
    }

    @Test
    fun nativeFormRejectsBadMoneyAndDate() {
        assertTrue(financeBankMoneyIsValid("0"))
        assertTrue(financeBankMoneyIsValid("100.000001"))
        assertFalse(financeBankMoneyIsValid("-1"))
        assertFalse(financeBankMoneyIsValid("0.0000001"))
        assertFalse(financeBankMoneyIsValid("1e10"))
        assertTrue(financeBankDateRangeIsValid("2026-11-01", "2026-11-24"))
        assertFalse(financeBankDateRangeIsValid("2026-11-24", "2026-11-01"))
    }
}
