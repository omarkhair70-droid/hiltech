package com.hiltech.android.sync

import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.sync.AccessTokenProvider
import com.hiltech.shared.core.sync.CorrelationIdProvider
import com.hiltech.shared.core.sync.KtorPendingCommandTransport
import com.hiltech.shared.core.sync.NativeClientMetadata
import com.hiltech.shared.core.sync.PendingCommandReplayEngine
import com.hiltech.shared.core.sync.ReplaySummary
import com.hiltech.shared.core.sync.TraceParentProvider
import io.ktor.client.HttpClient
import java.util.UUID

fun interface AndroidSessionTokenProvider {
    suspend fun currentAccessToken(): String?
}

sealed interface AndroidSyncRunResult {
    data class Completed(
        val summary: ReplaySummary,
    ) : AndroidSyncRunResult

    data object Retry : AndroidSyncRunResult
    data object ReauthenticationRequired : AndroidSyncRunResult
    data object RuntimeNotConfigured : AndroidSyncRunResult
}

class AndroidSyncRuntime(
    private val database: HiltechLocalDatabase,
    private val httpClient: HttpClient,
    private val apiBaseUrl: String,
    private val sessionTokenProvider: AndroidSessionTokenProvider,
    private val installationId: String,
    private val clientVersion: String,
) {
    suspend fun runOnce(): AndroidSyncRunResult {
        if (apiBaseUrl.isBlank()) {
            return AndroidSyncRunResult.RuntimeNotConfigured
        }

        val accessToken = sessionTokenProvider.currentAccessToken()
            ?.takeIf { it.isNotBlank() }
            ?: return AndroidSyncRunResult.ReauthenticationRequired

        val transport = KtorPendingCommandTransport(
            client = httpClient,
            baseUrl = apiBaseUrl,
            tokenProvider = AccessTokenProvider { accessToken },
            correlationIdProvider = CorrelationIdProvider {
                UUID.randomUUID().toString()
            },
            traceParentProvider = TraceParentProvider { null },
            clientMetadata = NativeClientMetadata(
                platform = "android",
                version = clientVersion,
                installationId = installationId,
            ),
        )

        val summary = PendingCommandReplayEngine(
            database = database,
            transport = transport,
        ).replayOnce()

        if (
            database.pendingCommandDao()
                .terminalCountByResultCode("REAUTH_REQUIRED") > 0
        ) {
            return AndroidSyncRunResult.ReauthenticationRequired
        }

        if (
            summary.retryable > 0 ||
            database.pendingCommandDao().earliestRetryAtEpochMs() != null
        ) {
            return AndroidSyncRunResult.Retry
        }

        return AndroidSyncRunResult.Completed(summary)
    }
}
