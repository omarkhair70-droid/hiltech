package com.hiltech.shared.core.network

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bind a native workspace to its initiating authenticated session. Token refresh
 * may suspend, so check the session on both sides before any HTTP request starts.
 * The returned token is then local to that request; a later sign-in cannot lend
 * its credentials to a command retained by the previous workspace.
 */
fun sessionBoundHiltechApiClient(
    httpClient: HttpClient,
    baseUrl: String,
    sessionCurrent: () -> Boolean,
    accessToken: suspend () -> String?,
    correlationId: () -> String,
): HiltechApiClient = HiltechApiClient(
    client = httpClient,
    baseUrl = baseUrl,
    accessTokenProvider = {
        currentCoroutineContext().ensureActive()
        if (!sessionCurrent()) throw CancellationException("Workspace session changed")
        val token = accessToken()
        currentCoroutineContext().ensureActive()
        if (!sessionCurrent()) throw CancellationException("Workspace session changed")
        token
    },
    correlationIdProvider = correlationId,
)
