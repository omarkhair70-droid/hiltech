package com.hiltech.server.finance

import com.hiltech.server.documents.FinanceBankStatementEvidenceReservationInsert
import com.hiltech.server.documents.persistence.JdbcEvidencePersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/** Bank CSV Evidence cannot become a generic invoice/work-order or public object. */
class FinanceBankEvidencePersistenceTest {
    @Test
    fun bankCsvCreatesPrivateExactTargetAndCanonicalUploadSession() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val org = UUID.randomUUID()
        val proposal = FinanceBankIntakeEvidenceBinding.proposalId(UUID.randomUUID())
        val evidence = UUID.randomUUID()
        JdbcEvidencePersistence(jdbc).insertFinanceBankStatementReservation(
            FinanceBankStatementEvidenceReservationInsert(
                evidenceId = evidence,
                uploadSessionId = UUID.randomUUID(),
                operationId = UUID.randomUUID(),
                objectKey = "org/$org/evidence/$evidence/objects/${UUID.randomUUID()}",
                organizationId = org,
                bankProposalId = proposal,
                contentType = "text/csv",
                originalFileName = "bank.csv",
                sizeBytes = 1024L,
                sha256 = "a".repeat(64),
                capturedAt = Instant.parse("2026-11-24T00:00:00Z"),
                clientOccurredAt = null,
                capturedByUserId = UUID.randomUUID(),
                uploadExpiresAt = Instant.parse("2026-11-24T01:00:00Z"),
                createdAt = Instant.parse("2026-11-24T00:00:00Z"),
            ),
        )
        val calls = Mockito.mockingDetails(jdbc).invocations
            .filter { it.method.name == "update" }
        assertEquals(2, calls.size)
        val arguments = calls.flatMap { it.arguments.toList() }
        assertTrue(arguments.contains("FINANCE_BANK_STATEMENT_INTAKE"))
        assertTrue(arguments.contains("FINANCE_BANK_STATEMENT_INTAKE_BINARY"))
        assertTrue(arguments.contains("BANK_CANONICAL_CSV"))
        assertTrue(arguments.contains(proposal))
        assertTrue(arguments.contains(org))
        assertTrue(calls.any { it.arguments.first().toString().contains("'HIGHLY_RESTRICTED'") })
        assertTrue(calls.any { it.arguments.first().toString().contains("'INTERNAL_ONLY'") })
    }
}
