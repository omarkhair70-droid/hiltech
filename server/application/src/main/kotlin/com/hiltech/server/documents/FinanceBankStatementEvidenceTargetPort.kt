package com.hiltech.server.documents

import java.util.UUID

/** The canonical future Finance proposal is bound to the original operation before upload. */
object FinanceBankIntakeEvidenceBinding {
    const val MAX_BYTES = 2 * 1024 * 1024

    fun proposalId(bankIntakeOperationId: UUID): UUID =
        UUID.nameUUIDFromBytes(
            "hiltech:bank-statement-intake:$bankIntakeOperationId".toByteArray(Charsets.UTF_8),
        )
}

data class FinanceBankEvidenceAccount(
    val id: UUID,
    val organizationId: UUID,
    val currency: String,
    val state: String,
)

/** Documents-owned port. Finance supplies its existing authority and canonical CSV validator. */
interface FinanceBankStatementEvidenceTargetPort {
    fun loadApprovedAccount(accountId: UUID): FinanceBankEvidenceAccount?
    fun canReserve(actor: UUID, account: FinanceBankEvidenceAccount): Boolean
    fun canAccess(actor: UUID, organizationId: UUID, uploader: UUID): Boolean
    fun validateCanonicalCsv(bytes: ByteArray, expectedSha256: String): Boolean
}
