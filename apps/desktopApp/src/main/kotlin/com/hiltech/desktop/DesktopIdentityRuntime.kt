package com.hiltech.desktop

import com.hiltech.shared.core.identity.DeviceRegistrationDto
import com.hiltech.shared.core.identity.IdentityApiClient
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentityDeviceSecurityDto
import com.hiltech.shared.core.identity.IdentitySecuritySnapshot
import com.hiltech.shared.core.identity.IdentitySessionDto
import com.hiltech.shared.core.identity.auth.InMemoryOidcTokenStore
import com.hiltech.shared.core.identity.auth.NativeOidcConfig
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.identity.auth.NativeOidcSessionManager
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.createPlatformHttpClient
import com.hiltech.shared.core.people.ChangeWorkforceAssignmentRequestDto
import com.hiltech.shared.core.people.CreateEmployeeRequestDto
import com.hiltech.shared.core.people.EmployeeCommandResponseDto
import com.hiltech.shared.core.people.EmployeeDetailDto
import com.hiltech.shared.core.people.EmployeeDirectoryDto
import com.hiltech.shared.core.people.CertificationListDto
import com.hiltech.shared.core.people.EmployeeDocumentListDto
import com.hiltech.shared.core.people.HrDocumentsApiClient
import com.hiltech.shared.core.people.OnboardingApiClient
import com.hiltech.shared.core.people.OnboardingCaseDto
import com.hiltech.shared.core.people.PeopleApiClient
import com.hiltech.shared.core.people.WorkforceAssignmentApiClient
import com.hiltech.shared.core.people.WorkforceAssignmentDto
import com.hiltech.shared.core.people.WorkforceStructureDto
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

class DesktopIdentityRuntime(
    private val apiBaseUrl: String,
    oidcIssuer: String,
    oidcClientId: String,
    private val clientVersion: String = "0.1.0",
) : AutoCloseable {
    private val httpClient = createPlatformHttpClient()
    private val tokenStore = InMemoryOidcTokenStore()

    val configured: Boolean =
        apiBaseUrl.isNotBlank() &&
            oidcIssuer.isNotBlank() &&
            oidcClientId.isNotBlank()

    private val session =
        if (configured) {
            NativeOidcSessionManager(
                client = httpClient,
                config = NativeOidcConfig(
                    issuer = oidcIssuer,
                    clientId = oidcClientId,
                ),
                tokenStore = tokenStore,
            )
        } else {
            null
        }

    private val installationId =
        DesktopInstallationIdStore().getOrCreate()

    private val identityApi = IdentityApiClient(
        client = httpClient,
        baseUrl = apiBaseUrl,
        accessTokenProvider = {
            session?.currentAccessToken()
        },
        correlationIdProvider = {
            UUID.randomUUID().toString()
        },
    )

    private val productApi =
        HiltechApiClient(
            client = httpClient,
            baseUrl = apiBaseUrl,
            accessTokenProvider = {
                session?.currentAccessToken()
            },
            correlationIdProvider = {
                UUID.randomUUID().toString()
            },
        )

    private val peopleApi =
        PeopleApiClient(productApi)

    private val workforceAssignmentApi =
        WorkforceAssignmentApiClient(
            productApi,
        )

    private val onboardingApi =
        OnboardingApiClient(productApi)

    private val hrDocumentsApi =
        HrDocumentsApiClient(productApi)

    suspend fun signIn(
        forceReauthentication: Boolean = false,
    ): IdentityBootstrapDto {
        ensureConfigured()

        val callbackFuture =
            CompletableFuture<String>()

        val server = HttpServer.create(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                0,
            ),
            0,
        )
        val port = server.address.port
        val redirectUri =
            "http://127.0.0.1:$port/callback"

        server.createContext("/callback") { exchange ->
            handleCallback(
                exchange = exchange,
                port = port,
                callbackFuture = callbackFuture,
            )
        }
        server.start()

        try {
            val activeSession = requireNotNull(session)
            val attempt = activeSession.beginAuthorization(
                redirectUri = redirectUri,
                forceReauthentication =
                    forceReauthentication,
            )

            openSystemBrowser(
                attempt.authorizationUrl,
            )

            val callbackUri = withContext(Dispatchers.IO) {
                callbackFuture.get(
                    CALLBACK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }

            val tokens =
                activeSession.completeAuthorization(
                    callbackUri = callbackUri,
                    attempt = attempt,
                )

            val identity = bootstrapCurrentIdentity()

            if (attempt.forceReauthentication) {
                val idToken = tokens.idToken
                    ?.takeIf { it.isNotBlank() }
                    ?: throw NativeOidcException(
                        code = "OIDC_ID_TOKEN_MISSING",
                        message =
                            "Fresh authentication did not return an ID token.",
                    )
                identityApi.completeReauthentication(
                    idToken = idToken,
                    installationId = installationId,
                )
            }

            return identity
        } finally {
            server.stop(0)
        }
    }

    suspend fun restoreIdentity():
        IdentityBootstrapDto? {
        ensureConfigured()

        val activeSession = session
            ?: return null
        if (activeSession.currentAccessToken() == null) {
            return null
        }
        return bootstrapCurrentIdentity()
    }

    suspend fun loadSecuritySnapshot():
        IdentitySecuritySnapshot {
        ensureConfigured()
        return IdentitySecuritySnapshot(
            sessions =
                identityApi.sessions(
                    installationId,
                ),
            devices =
                identityApi.devices(
                    installationId,
                ),
        )
    }

    suspend fun revokeSession(
        sessionId: String,
    ): IdentitySessionDto {
        ensureConfigured()
        return identityApi.revokeSession(
            sessionId = sessionId,
            installationId = installationId,
        )
    }

    suspend fun revokeDevice(
        deviceId: String,
    ): IdentityDeviceSecurityDto {
        ensureConfigured()
        return identityApi.revokeDevice(
            deviceId = deviceId,
            installationId = installationId,
        )
    }

    suspend fun peopleDirectory(
        organizationId: String,
    ): EmployeeDirectoryDto {
        ensureConfigured()
        return peopleApi.directory(
            organizationId =
                organizationId,
            installationId =
                installationId,
        )
    }

    suspend fun ownEmployee(
        organizationId: String,
    ): EmployeeDetailDto {
        ensureConfigured()
        return peopleApi.own(
            organizationId =
                organizationId,
            installationId =
                installationId,
        )
    }

    suspend fun employeeDetail(
        employeeId: String,
    ): EmployeeDetailDto {
        ensureConfigured()
        return peopleApi.detail(
            employeeId =
                employeeId,
            installationId =
                installationId,
        )
    }

    suspend fun createEmployee(
        organizationId: String,
        displayName: String,
        employeeCode: String,
        startDate: String,
        employmentTypeCode: String?,
    ): EmployeeCommandResponseDto {
        ensureConfigured()
        return peopleApi.create(
            request =
                CreateEmployeeRequestDto(
                    operationId =
                        UUID.randomUUID()
                            .toString(),
                    organizationId =
                        organizationId,
                    displayName =
                        displayName,
                    employeeCode =
                        employeeCode,
                    startDate =
                        startDate,
                    employmentTypeCode =
                        employmentTypeCode,
                ),
            installationId =
                installationId,
        )
    }

    suspend fun employeeWorkforceAssignment(
        employeeId: String,
    ): WorkforceAssignmentDto {
        ensureConfigured()
        return workforceAssignmentApi
            .currentForEmployee(
                employeeId =
                    employeeId,
                installationId =
                    installationId,
            )
    }

    suspend fun employeeWorkforceHistory(
        employeeId: String,
    ): WorkforceStructureDto {
        ensureConfigured()
        return workforceAssignmentApi.history(
            employeeId =
                employeeId,
            installationId =
                installationId,
        )
    }

    suspend fun changeWorkforceAssignment(
        employeeId: String,
        currentAssignmentId: String,
        baseAssignmentVersion: Long,
        teamId: String?,
        roleCode: String,
        roleLabel: String?,
        reportsToEmployeeId: String?,
    ): WorkforceAssignmentDto {
        ensureConfigured()
        return workforceAssignmentApi.change(
            employeeId =
                employeeId,
            request =
                ChangeWorkforceAssignmentRequestDto(
                    operationId =
                        UUID.randomUUID()
                            .toString(),
                    currentAssignmentId =
                        currentAssignmentId,
                    baseAssignmentVersion =
                        baseAssignmentVersion,
                    teamId =
                        teamId,
                    roleCode =
                        roleCode,
                    roleLabel =
                        roleLabel,
                    reportsToEmployeeId =
                        reportsToEmployeeId,
                ),
            installationId =
                installationId,
        ).assignment
    }

    suspend fun ownWorkforceAssignment(
        organizationId: String,
    ): WorkforceAssignmentDto {
        ensureConfigured()
        return workforceAssignmentApi.own(
            organizationId =
                organizationId,
            installationId =
                installationId,
        )
    }

    suspend fun workforceStructure(
        organizationId: String,
    ): WorkforceStructureDto {
        ensureConfigured()
        return workforceAssignmentApi
            .structure(
                organizationId =
                    organizationId,
                installationId =
                    installationId,
            )
    }

    suspend fun ownOnboarding(
        organizationId: String,
    ): OnboardingCaseDto {
        ensureConfigured()
        return onboardingApi.own(
            organizationId =
                organizationId,
            installationId =
                installationId,
        )
    }

    suspend fun employeeOnboarding(
        employeeId: String,
    ): OnboardingCaseDto {
        ensureConfigured()
        return onboardingApi.admin(
            employeeId =
                employeeId,
            installationId =
                installationId,
        )
    }

    suspend fun ownDocuments(
        organizationId: String,
    ): EmployeeDocumentListDto {
        ensureConfigured()
        return hrDocumentsApi.ownDocuments(
            organizationId =
                organizationId,
            installationId =
                installationId,
        )
    }

    suspend fun ownCertifications(
        organizationId: String,
    ): CertificationListDto {
        ensureConfigured()
        return hrDocumentsApi.ownCertifications(
            organizationId =
                organizationId,
            installationId =
                installationId,
        )
    }

    suspend fun logout() {
        if (!configured) {
            tokenStore.clear()
            return
        }
        requireNotNull(session).logout()
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun bootstrapCurrentIdentity():
        IdentityBootstrapDto {
        identityApi.registerDevice(
            DeviceRegistrationDto(
                installationId = installationId,
                platform = "WINDOWS",
                deviceName = System.getenv(
                    "COMPUTERNAME",
                ) ?: "Windows Desktop",
                appVersion = clientVersion,
                osVersion =
                    System.getProperty("os.version"),
            ),
        )

        return identityApi.bootstrap(
            installationId = installationId,
        )
    }

    private fun openSystemBrowser(
        authorizationUrl: String,
    ) {
        if (
            !Desktop.isDesktopSupported() ||
            !Desktop.getDesktop().isSupported(
                Desktop.Action.BROWSE,
            )
        ) {
            throw NativeOidcException(
                code = "OIDC_SYSTEM_BROWSER_UNAVAILABLE",
                message = "The Windows system browser is unavailable.",
            )
        }

        Desktop.getDesktop().browse(
            URI.create(authorizationUrl),
        )
    }

    private fun handleCallback(
        exchange: HttpExchange,
        port: Int,
        callbackFuture: CompletableFuture<String>,
    ) {
        try {
            if (
                exchange.requestMethod != "GET" ||
                exchange.requestURI.path != "/callback"
            ) {
                exchange.sendResponseHeaders(
                    404,
                    -1,
                )
                return
            }

            val body = """
                <!doctype html>
                <html>
                  <head><meta charset="utf-8"></head>
                  <body>
                    <h1>HILTECH sign-in received</h1>
                    <p>You can return to the HILTECH application.</p>
                  </body>
                </html>
            """.trimIndent().toByteArray(
                StandardCharsets.UTF_8,
            )

            exchange.responseHeaders.add(
                "Content-Type",
                "text/html; charset=utf-8",
            )
            exchange.sendResponseHeaders(
                200,
                body.size.toLong(),
            )
            exchange.responseBody.use {
                it.write(body)
            }

            val callbackUri =
                "http://127.0.0.1:$port" +
                    exchange.requestURI.toString()
            callbackFuture.complete(callbackUri)
        } catch (failure: Throwable) {
            callbackFuture.completeExceptionally(
                failure,
            )
        } finally {
            exchange.close()
        }
    }

    private fun ensureConfigured() {
        if (!configured) {
            throw IllegalStateException(
                "HILTECH API/OIDC runtime is not configured.",
            )
        }
    }

    companion object {
        private const val CALLBACK_TIMEOUT_SECONDS =
            120L

        fun isAccessDenied(
            failure: Throwable,
        ): Boolean =
            failure is IdentityApiException &&
                failure.httpStatus in
                    setOf(403, 404, 409)
    }
}

private class DesktopInstallationIdStore {
    private val preferences =
        Preferences.userRoot().node(
            "com/hiltech/desktop/installation",
        )

    fun getOrCreate(): String {
        val existing =
            preferences.get(
                INSTALLATION_ID_KEY,
                "",
            )
        if (existing.isNotBlank()) {
            return existing
        }

        val created = UUID.randomUUID().toString()
        preferences.put(
            INSTALLATION_ID_KEY,
            created,
        )
        preferences.flush()
        return created
    }

    private companion object {
        const val INSTALLATION_ID_KEY =
            "installation_id"
    }
}
