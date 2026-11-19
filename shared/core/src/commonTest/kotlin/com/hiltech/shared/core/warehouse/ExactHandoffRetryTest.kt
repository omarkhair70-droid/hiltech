package com.hiltech.shared.core.warehouse

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactHandoffRetryTest {
    @Test
    fun retryAfterUncertainSubmissionKeepsOriginalPreparedVersionsAndCommand() = runBlocking {
        val retry = ExactHandoffRetry()
        var preparations = 0
        var version = 7
        val sent = mutableListOf<Int>()
        suspend fun attempt(): String = retry.execute("reservation", listOf("employee", 2L)) {
            preparations++
            val originalVersion = version
            val send: suspend () -> String = {
                sent.add(originalVersion)
                if (sent.size == 1) error("response lost after submission")
                "receipt"
            }
            send
        }
        assertFailsWith<IllegalStateException> { attempt() }
        version = 8
        assertEquals("receipt", attempt())
        assertEquals(1, preparations)
        assertEquals(listOf(7, 7), sent)
    }

    @Test
    fun changedIntentCannotReplaceAnUnconfirmedHandoff() = runBlocking {
        val retry = ExactHandoffRetry()
        assertFailsWith<IllegalStateException> {
            retry.execute("reservation", listOf("employee-a")) {
                val send: suspend () -> String = { error("response lost") }
                send
            }
        }
        var prepared = false
        assertFailsWith<IllegalStateException> {
            retry.execute("reservation", listOf("employee-b")) {
                prepared = true
                val send: suspend () -> String = { "different receipt" }
                send
            }
        }
        assertEquals(false, prepared)
        assertEquals("independent receipt", retry.execute("other-reservation", listOf("employee-b")) {
            val send: suspend () -> String = { "independent receipt" }
            send
        })
    }

    @Test
    fun preparationFailureDoesNotRetainAnUnsentCommand() = runBlocking {
        val retry = ExactHandoffRetry()
        assertFailsWith<IllegalStateException> {
            retry.execute("reservation", listOf("employee-a")) { error("read failed before submit") }
        }
        assertEquals("receipt", retry.execute("reservation", listOf("employee-b")) {
            val send: suspend () -> String = { "receipt" }
            send
        })
    }
}
