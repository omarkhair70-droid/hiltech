package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceDailyQueueTest {
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val id = "11111111-2222-4333-8444-555555555555"
    private val approval = "22222222-3333-4444-8555-666666666666"
    private val result = "33333333-4444-4555-8666-777777777777"
    private fun source(kind: FinanceCompletionKind = FinanceCompletionKind.AP_JOURNAL) =
        FinanceCompletionSource(kind, id, org, "APPROVED", approval, null,
            listOf("source", "1", "hash", "1200.25", "EGP"), listOf("Synthetic source"))
    private fun denied() = HiltechApiException("FORBIDDEN", "test", 403, false,
        ErrorEnvelope("FORBIDDEN", "Test", "test"), "Test")

    @Test
    fun authorizedQueuesAreCombinedWithoutTreatingDenialOrFailureAsZero() = runBlocking {
        val snapshot = financeDailyQueues(org) { kind ->
            when (kind) {
                FinanceCompletionKind.AP_JOURNAL -> listOf(source())
                FinanceCompletionKind.AR_JOURNAL -> emptyList()
                FinanceCompletionKind.FUNDING -> error("Source unreachable")
                else -> throw denied()
            }
        }
        assertEquals(setOf(FinanceCompletionKind.AP_JOURNAL, FinanceCompletionKind.AR_JOURNAL), snapshot.available.keys)
        assertEquals(setOf(FinanceCompletionKind.FUNDING), snapshot.unavailable)
        assertEquals(listOf(source()), snapshot.ready)
        assertEquals("1200.25 EGP", financeDailyAmount(snapshot.ready.single()))
        assertNull(financeDailyAmount(source(FinanceCompletionKind.PERIOD_CLOSE)))
    }

    @Test
    fun duplicateOrWrongScopeSourcesCannotEnterDailyQueue() = runBlocking {
        val snapshot = financeDailyQueues(org) { kind ->
            when (kind) {
                FinanceCompletionKind.AP_JOURNAL -> listOf(source(), source())
                FinanceCompletionKind.AR_JOURNAL -> listOf(source(kind).copy(org = result))
                else -> throw denied()
            }
        }
        assertTrue(snapshot.available.isEmpty())
        assertEquals(setOf(FinanceCompletionKind.AP_JOURNAL, FinanceCompletionKind.AR_JOURNAL), snapshot.unavailable)
    }

    @Test
    fun uncertainRecordingReadbackRequiresExactApprovedSourceAndTerminalRecord() {
        val expected = source()
        val actual = expected.copy(state = expected.kind.terminal, resultId = result)
        assertTrue(financeRecordedSourceMatches(expected, actual))
        assertFalse(financeRecordedSourceMatches(expected, actual.copy(approvalId = result)))
        assertFalse(financeRecordedSourceMatches(expected, actual.copy(fingerprintParts = listOf("different"))))
        assertFalse(financeRecordedSourceMatches(expected, actual.copy(resultId = null)))
        assertFalse(financeRecordedSourceMatches(expected, expected))
    }
}
