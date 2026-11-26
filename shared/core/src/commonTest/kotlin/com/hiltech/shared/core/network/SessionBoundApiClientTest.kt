package com.hiltech.shared.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionBoundApiClientTest {
    @Test
    fun closedWorkspaceCannotAcquireAnotherAccountsToken() = runBlocking {
        var acquired = 0
        var sent = 0
        val http = HttpClient(MockEngine { sent++; error("No request expected") })
        try {
            val api = sessionBoundHiltechApiClient(http, "https://example.invalid", { false },
                accessToken = { acquired++; "other-account-token" }, correlationId = { "test" })
            assertFailsWith<CancellationException> { api.request(HttpMethod.Post, "/command", decode = { it }) }
            assertEquals(0, acquired)
            assertEquals(0, sent)
        } finally { http.close() }
    }

    @Test
    fun accountChangeDuringTokenRefreshPreventsTheHttpCommand() = runBlocking {
        var current = true
        var sent = 0
        val http = HttpClient(MockEngine { sent++; error("No request expected") })
        try {
            val api = sessionBoundHiltechApiClient(http, "https://example.invalid", { current },
                accessToken = { current = false; "replacement-account-token" }, correlationId = { "test" })
            assertFailsWith<CancellationException> { api.request(HttpMethod.Post, "/command", decode = { it }) }
            assertEquals(0, sent)
        } finally { http.close() }
    }

    @Test
    fun initiatingTokenIsUsedAndLaterRequestsAfterAccountChangeAreStopped() = runBlocking {
        var current = true
        var token = "initiating-token"
        var sent = 0
        val http = HttpClient(MockEngine { request ->
            sent++
            assertEquals("Bearer initiating-token", request.headers[HttpHeaders.Authorization])
            current = false
            token = "replacement-token"
            respond("acknowledged")
        })
        try {
            val api = sessionBoundHiltechApiClient(http, "https://example.invalid", { current },
                accessToken = { token }, correlationId = { "test" })
            assertEquals("acknowledged", api.request(HttpMethod.Post, "/command", decode = { it }))
            assertFailsWith<CancellationException> { api.request(HttpMethod.Post, "/command", decode = { it }) }
            assertEquals(1, sent)
        } finally { http.close() }
    }
}
