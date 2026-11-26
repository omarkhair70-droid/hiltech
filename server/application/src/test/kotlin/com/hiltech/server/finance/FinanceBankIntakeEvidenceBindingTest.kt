package com.hiltech.server.finance

import com.hiltech.server.documents.FinanceBankIntakeEvidenceBinding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class FinanceBankIntakeEvidenceBindingTest {
    private val bankOp = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val otherOp = UUID.fromString("22222222-3333-4444-8555-666666666666")
    private val canonical = (
        "external_transaction_id,booked_on,flow,currency,amount\r\n" +
            "BANK-BOOKING-0001,2026-11-24,CREDIT,EGP,100.000000\r\n"
        ).toByteArray(Charsets.UTF_8)

    @Test
    fun evidenceTargetExactlyMatchesTheFutureProposalFromOriginalOperation() {
        val target = FinanceBankIntakeEvidenceBinding.proposalId(bankOp)
        assertEquals(
            UUID.nameUUIDFromBytes(
                "hiltech:bank-statement-intake:$bankOp".toByteArray(Charsets.UTF_8),
            ),
            target,
        )
        assertEquals(target, FinanceBankIntakeEvidenceBinding.proposalId(bankOp))
        assertNotEquals(target, FinanceBankIntakeEvidenceBinding.proposalId(otherOp))
        assertNotEquals(target, bankOp)
    }

    @Test
    fun canonicalFileBytesBindToEvidenceShaAndRejectChangesBeforePrepare() {
        val sha = MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val parsed = FinanceBankCsvParser.decodeAndValidate(
            Base64.getEncoder().encodeToString(canonical), sha,
        )
        assertEquals(sha, parsed.sourceSha256)
        assertEquals(1, parsed.lines.size)
        assertEquals("100.000000", parsed.lines.single().amount)
        val modified = canonical.copyOf()
        modified[modified.size - 3] = '1'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankCsvParser.decodeAndValidate(
                Base64.getEncoder().encodeToString(modified), sha,
            )
        }
    }
}
