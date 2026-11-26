package com.hiltech.server.identity

import jakarta.annotation.PreDestroy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

enum class IdentityProvisioningDeliveryMode {
    EMAIL_ACTION_LINK,
    TEMPORARY_PASSWORD_HANDOFF,
}

enum class IdentityProvisioningDeliveryState {
    SENT,
    READY,
    NOT_CONFIGURED,
    FAILED_RETRYABLE,
}

data class EmployeeIdentityProvisioningRequest(
    val invitationId: UUID,
    val personId: UUID,
    val organizationId: UUID,
    val requestedLogin: String,
    val actorIdentityId: UUID,
    val deliveryMode:
        IdentityProvisioningDeliveryMode,
)

sealed interface EmployeeIdentityProvisioningResult {
    data class Ready(
        val issuer: String,
        val providerSubject: String,
        val userIdentityId: UUID,
        val deliveryState:
            IdentityProvisioningDeliveryState,
        val temporaryCredential: String?,
    ) : EmployeeIdentityProvisioningResult

    data class RetryableFailure(
        val safeCode: String,
        val providerSubject: String? = null,
        val deliveryState:
            IdentityProvisioningDeliveryState =
            IdentityProvisioningDeliveryState
                .FAILED_RETRYABLE,
    ) : EmployeeIdentityProvisioningResult
}

interface EmployeeIdentityProvisioningPort {
    fun availableDeliveryModes(): Set<IdentityProvisioningDeliveryMode>

    fun provision(
        request:
            EmployeeIdentityProvisioningRequest,
    ): EmployeeIdentityProvisioningResult
}

internal data class ProviderIdentityHandle(
    val subject: String,
)

internal data class ProviderDeliveryResult(
    val state: IdentityProvisioningDeliveryState,
    val temporaryCredential: String?,
)

internal interface KeycloakIdentityAdminPort {
    fun createOrRecoverDisabledUser(
        invitationId: UUID,
        requestedLogin: String,
    ): ProviderIdentityHandle

    fun enableAndPrepareDelivery(
        handle: ProviderIdentityHandle,
        requestedLogin: String,
        mode: IdentityProvisioningDeliveryMode,
    ): ProviderDeliveryResult
}

@ConfigurationProperties(
    prefix =
        "hiltech.identity.provisioning.keycloak",
)
data class KeycloakProvisioningProperties(
    var enabled: Boolean = false,
    var adminClientId: String = "",
    var adminClientSecret: String = "",
    var emailActionLinkEnabled: Boolean = false,
    var adminBaseUri: String = "",
) {
    fun validateEnabled() {
        if (!enabled) {
            return
        }
        require(adminClientId.isNotBlank()) {
            "hiltech.identity.provisioning.keycloak.admin-client-id must be configured when provisioning is enabled."
        }
        require(adminClientSecret.isNotBlank()) {
            "hiltech.identity.provisioning.keycloak.admin-client-secret must be configured when provisioning is enabled."
        }
        privateAdminBaseUri()
    }

    internal fun privateAdminBaseUri(): URI? {
        if (adminBaseUri.isBlank()) return null
        val base = runCatching { URI.create(adminBaseUri.trim().trimEnd('/')) }.getOrNull()
        require(base != null && base.scheme in setOf("https", "http") && !base.host.isNullOrBlank() &&
            base.userInfo == null && base.query == null && base.fragment == null
        ) {
            "hiltech.identity.provisioning.keycloak.admin-base-uri must be an HTTP(S) service base URI."
        }
        require(base.scheme == "https" || base.host.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]*"))) {
            "HTTP Keycloak administration requires a private network service alias."
        }
        return base
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    KeycloakProvisioningProperties::class,
)
internal class IdentityProvisioningConfiguration

internal class KeycloakProvisioningException(
    val safeCode: String,
    message: String,
) : RuntimeException(message)

@Component
internal class JdbcEmployeeIdentityProvisioning(
    private val jdbc: JdbcTemplate,
    transactionManager:
        PlatformTransactionManager,
    private val oidc:
        HiltechOidcProperties,
    private val properties: KeycloakProvisioningProperties,
    private val provider:
        ObjectProvider<KeycloakIdentityAdminPort>,
    private val clock: Clock,
) : EmployeeIdentityProvisioningPort {
    private val transaction =
        TransactionTemplate(
            transactionManager,
        )

    override fun availableDeliveryModes(): Set<IdentityProvisioningDeliveryMode> {
        if (!oidc.enabled || oidc.issuerUri.isBlank() || !properties.enabled) return emptySet()
        return buildSet {
            add(IdentityProvisioningDeliveryMode.TEMPORARY_PASSWORD_HANDOFF)
            if (properties.emailActionLinkEnabled) add(IdentityProvisioningDeliveryMode.EMAIL_ACTION_LINK)
        }
    }

    override fun provision(
        request:
            EmployeeIdentityProvisioningRequest,
    ): EmployeeIdentityProvisioningResult {
        val issuer =
            oidc.issuerUri
                .trim()
                .trimEnd('/')

        if (
            !oidc.enabled ||
            issuer.isEmpty()
        ) {
            return EmployeeIdentityProvisioningResult
                .RetryableFailure(
                    safeCode =
                        "IDENTITY_PROVIDER_NOT_CONFIGURED",
                    deliveryState =
                        IdentityProvisioningDeliveryState
                            .NOT_CONFIGURED,
                )
        }

        val admin =
            provider.ifAvailable
                ?: return EmployeeIdentityProvisioningResult
                    .RetryableFailure(
                        safeCode =
                            "IDENTITY_PROVIDER_NOT_CONFIGURED",
                        deliveryState =
                            IdentityProvisioningDeliveryState
                                .NOT_CONFIGURED,
                    )

        var providerSubject: String? = null

        return try {
            val handle =
                admin.createOrRecoverDisabledUser(
                    invitationId =
                        request.invitationId,
                    requestedLogin =
                        request.requestedLogin,
                )
            providerSubject = handle.subject

            val userIdentityId =
                requireNotNull(
                    transaction.execute {
                        createOrRecoverLocalPending(
                            request = request,
                            issuer = issuer,
                            providerSubject =
                                handle.subject,
                        )
                    },
                )

            val delivery =
                admin.enableAndPrepareDelivery(
                    handle = handle,
                    requestedLogin =
                        request.requestedLogin,
                    mode = request.deliveryMode,
                )

            if (delivery.state !in setOf(IdentityProvisioningDeliveryState.SENT, IdentityProvisioningDeliveryState.READY)) {
                return EmployeeIdentityProvisioningResult.RetryableFailure(
                    safeCode = "IDENTITY_DELIVERY_INCOMPLETE",
                    providerSubject = providerSubject,
                    deliveryState = delivery.state,
                )
            }

            transaction.executeWithoutResult {
                activateLocalIdentity(
                    userIdentityId =
                        userIdentityId,
                    organizationId =
                        request.organizationId,
                    invitationId = request.invitationId,
                    personId = request.personId,
                )
            }

            EmployeeIdentityProvisioningResult
                .Ready(
                    issuer = issuer,
                    providerSubject =
                        handle.subject,
                    userIdentityId =
                        userIdentityId,
                    deliveryState =
                        delivery.state,
                    temporaryCredential =
                        delivery
                            .temporaryCredential,
                )
        } catch (
            failure:
                KeycloakProvisioningException,
        ) {
            EmployeeIdentityProvisioningResult
                .RetryableFailure(
                    safeCode =
                        failure.safeCode,
                    providerSubject =
                        providerSubject,
                )
        }
    }

    private fun createOrRecoverLocalPending(
        request:
            EmployeeIdentityProvisioningRequest,
        issuer: String,
        providerSubject: String,
    ): UUID {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val eligibleEmployee = jdbc.query(
            """SELECT p.id FROM person p JOIN employee e ON e.person_id = p.id
               JOIN organization o ON o.id = e.organization_id
               WHERE p.id = ? AND e.organization_id = ? AND e.state IN ('PREBOARDING','ACTIVE')
                 AND o.status = 'ACTIVE' AND o.organization_type = 'HILTECH'
               FOR UPDATE OF p, e""".trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) }, request.personId, request.organizationId,
        ).singleOrNull()
        if (eligibleEmployee == null) {
            throw KeycloakProvisioningException("IDENTITY_EMPLOYEE_NOT_ELIGIBLE", "The employee is no longer eligible for account setup.")
        }
        val existing =
            jdbc.query(
                """
                SELECT
                    id,
                    person_id,
                    status
                FROM user_identity
                WHERE auth_provider = ?
                  AND auth_subject = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    ExistingIdentity(
                        id =
                            rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                        personId =
                            rs.getObject(
                                "person_id",
                                UUID::class.java,
                            ),
                        status =
                            rs.getString(
                                "status",
                            ),
                    )
                },
                issuer,
                providerSubject,
            ).singleOrNull()

        if (existing != null && existing.status !in setOf("PENDING", "ACTIVE")) {
            throw KeycloakProvisioningException("IDENTITY_ACCOUNT_RESTRICTED", "The existing account is restricted.")
        }
        // Creating an account cannot replace another account, including revoked history.
        val otherIdentity = jdbc.queryForObject(
            """SELECT EXISTS (SELECT 1 FROM user_identity WHERE person_id = ?
               AND id <> ?)""".trimIndent(),
            Boolean::class.java, request.personId, existing?.id ?: UUID(0L, 0L),
        ) == true
        if (otherIdentity) {
            throw KeycloakProvisioningException("IDENTITY_PERSON_ALREADY_LINKED", "The employee already has a different account.")
        }

        val identityId =
            if (existing != null) {
                if (
                    existing.personId != null &&
                    existing.personId !=
                    request.personId
                ) {
                    throw KeycloakProvisioningException(
                        safeCode =
                            "IDENTITY_PROVIDER_SUBJECT_CONFLICT",
                        message =
                            "Provider subject is already linked to another Person.",
                    )
                }
                existing.id
            } else {
                UUID.nameUUIDFromBytes(
                    (
                        "identity:" +
                            request.invitationId
                    ).toByteArray(
                        StandardCharsets.UTF_8,
                    ),
                ).also {
                    identityId ->
                    jdbc.update(
                        """
                        INSERT INTO user_identity (
                            id,
                            auth_provider,
                            auth_subject,
                            person_id,
                            status,
                            primary_organization_id,
                            created_at,
                            last_authenticated_at,
                            version
                        )
                        VALUES (
                            ?, ?, ?, ?,
                            'PENDING',
                            ?, ?, NULL, 1
                        )
                        """.trimIndent(),
                        identityId,
                        issuer,
                        providerSubject,
                        request.personId,
                        request.organizationId,
                        now,
                    )
                }
            }

        val identityLinked = jdbc.update(
            """
            UPDATE user_identity
            SET person_id = ?,
                primary_organization_id = ?,
                version = version + 1
            WHERE id = ?
              AND status IN (
                  'PENDING',
                  'ACTIVE'
              )
              AND (
                  person_id IS NULL
                  OR person_id = ?
              )
            """.trimIndent(),
            request.personId,
            request.organizationId,
            identityId,
            request.personId,
        )
        if (identityLinked != 1) {
            throw KeycloakProvisioningException("IDENTITY_ACCOUNT_RESTRICTED", "The existing account cannot be linked.")
        }

        val membershipId =
            UUID.nameUUIDFromBytes(
                (
                    "identity-membership:" +
                        request.invitationId
                ).toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )

        // Resume only the membership owned by this invitation. An ended or suspended
        // membership cannot be bypassed by inserting a second one for the same account.
        val existingMemberships = jdbc.query(
            """SELECT id FROM organization_membership WHERE organization_id = ?
               AND user_identity_id = ? FOR UPDATE""".trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) }, request.organizationId, identityId,
        )
        if (existingMemberships.isNotEmpty() && membershipId !in existingMemberships) {
            throw KeycloakProvisioningException("IDENTITY_MEMBERSHIP_CONFLICT", "The existing membership belongs to another account setup.")
        }

        jdbc.update(
            """
            INSERT INTO organization_membership (
                id,
                organization_id,
                user_identity_id,
                membership_type,
                role_label,
                state,
                valid_from,
                valid_until,
                invited_by,
                version
            )
            VALUES (
                ?, ?, ?,
                'EMPLOYEE',
                NULL,
                'PENDING',
                ?, NULL, ?, 1
            )
            ON CONFLICT (id)
            DO NOTHING
            """.trimIndent(),
            membershipId,
            request.organizationId,
            identityId,
            now,
            request.actorIdentityId,
        )

        val validMembership =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM organization_membership
                    WHERE id = ?
                      AND organization_id = ?
                      AND user_identity_id = ?
                      AND state IN (
                          'PENDING',
                          'ACTIVE'
                      )
                      AND membership_type = 'EMPLOYEE'
                      AND valid_from <= ?
                      AND (valid_until IS NULL OR valid_until > ?)
                )
                """.trimIndent(),
                Boolean::class.java,
                membershipId,
                request.organizationId,
                identityId,
                now,
                now,
            ) == true

        if (!validMembership) {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_MEMBERSHIP_CONFLICT",
                message =
                    "Identity membership could not be recovered safely.",
            )
        }

        return identityId
    }

    private fun activateLocalIdentity(
        userIdentityId: UUID,
        organizationId: UUID,
        invitationId: UUID,
        personId: UUID,
    ) {
        val identityUpdated =
            jdbc.update(
                """
                UPDATE user_identity
                SET status = 'ACTIVE',
                    version = version + 1
                WHERE id = ?
                  AND person_id = ?
                  AND status IN (
                      'PENDING',
                      'ACTIVE'
                  )
                  AND EXISTS (
                      SELECT 1 FROM employee e JOIN organization o ON o.id = e.organization_id
                      WHERE e.person_id = ? AND e.organization_id = ? AND e.state IN ('PREBOARDING','ACTIVE')
                        AND o.status = 'ACTIVE' AND o.organization_type = 'HILTECH'
                  )
                """.trimIndent(),
                userIdentityId,
                personId,
                personId,
                organizationId,
            )

        if (identityUpdated != 1) {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_LOCAL_ACTIVATION_FAILED",
                message =
                    "Local identity could not be activated.",
            )
        }

        val membershipUpdated =
            jdbc.update(
                """
                UPDATE organization_membership
                SET state = 'ACTIVE',
                    version = version + 1
                WHERE organization_id = ?
                  AND user_identity_id = ?
                  AND id = ?
                  AND membership_type = 'EMPLOYEE'
                  AND state IN (
                      'PENDING',
                      'ACTIVE'
                  )
                  AND valid_from <= ?
                  AND (valid_until IS NULL OR valid_until > ?)
                """.trimIndent(),
                organizationId,
                userIdentityId,
                UUID.nameUUIDFromBytes(("identity-membership:" + invitationId).toByteArray(StandardCharsets.UTF_8)),
                clock.instant().atOffset(ZoneOffset.UTC),
                clock.instant().atOffset(ZoneOffset.UTC),
            )

        if (membershipUpdated != 1) {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_MEMBERSHIP_ACTIVATION_FAILED",
                message =
                    "Local organization membership could not be activated.",
            )
        }
    }

    private data class ExistingIdentity(
        val id: UUID,
        val personId: UUID?,
        val status: String,
    )
}

@Component
@ConditionalOnProperty(
    prefix =
        "hiltech.identity.provisioning.keycloak",
    name = ["enabled"],
    havingValue = "true",
)
internal class KeycloakIdentityAdminClient(
    private val properties:
        KeycloakProvisioningProperties,
    private val oidc:
        HiltechOidcProperties,
) : KeycloakIdentityAdminPort, KeycloakCredentialReplacementGateway {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }
    private val secureRandom =
        SecureRandom()
    // Provider mutations must be dispatched once. A 503, 429 or lost response
    // may follow an applied password change; recovery belongs to the recorded
    // application operation, never the HTTP client's automatic retry strategy.
    private val requestFactory = HttpComponentsClientHttpRequestFactory(
        HttpClients.custom()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(10)).build())
                .build())
            .build(),
    ).apply {
        setConnectionRequestTimeout(Duration.ofSeconds(5))
        setReadTimeout(Duration.ofSeconds(20))
    }
    private val http = RestClient.builder().requestFactory(requestFactory)
        .defaultStatusHandler({ status -> !status.is2xxSuccessful }) { _, _ ->
            throw KeycloakProvisioningException("IDENTITY_PROVIDER_REQUEST_UNCONFIRMED", "Identity provider request did not complete.")
        }.build()

    @PreDestroy
    fun close() = requestFactory.destroy()

    override fun replaceCurrentPassword(subject: String): EmployeeCredentialReplacementResult {
        val token: String
        val user: JsonObject
        try {
            validate()
            token = adminToken()
            val response = http.get().uri(adminBase() + "/users/" + encode(subject))
                .header("Authorization", "Bearer $token").retrieve().body(String::class.java)
            user = json.parseToJsonElement(requireNotNull(response)).jsonObject
        } catch (_: Exception) {
            return EmployeeCredentialReplacementResult.Rejected("ACCOUNT_CREDENTIAL_PROVIDER_UNAVAILABLE")
        }
        if (user["id"]?.jsonPrimitive?.content != subject || user["enabled"]?.jsonPrimitive?.booleanOrNull != true) {
            return EmployeeCredentialReplacementResult.Rejected("ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED")
        }
        val temporary = temporaryCredential()
        return try {
            http.put().uri(adminBase() + "/users/" + encode(subject) + "/reset-password")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer $token")
                .body(buildJsonObject { put("type", "password"); put("value", temporary); put("temporary", true) }.toString())
                .retrieve().toBodilessEntity()
            EmployeeCredentialReplacementResult.Issued(temporary)
        } catch (_: Exception) {
            // The server may have applied the password before the response was lost.
            EmployeeCredentialReplacementResult.Unconfirmed("ACCOUNT_CREDENTIAL_PROVIDER_OUTCOME_UNCONFIRMED")
        }
    }

    override fun createOrRecoverDisabledUser(
        invitationId: UUID,
        requestedLogin: String,
    ): ProviderIdentityHandle {
        validate()

        findByProvisioningMarker(
            invitationId,
        )?.let {
            return ProviderIdentityHandle(
                subject = it,
            )
        }

        val token = adminToken()
        val location =
            try {
                http.post()
                    .uri(
                        adminBase() +
                            "/users",
                    )
                    .contentType(
                        MediaType.APPLICATION_JSON,
                    )
                    .header(
                        "Authorization",
                        "Bearer $token",
                    )
                    .body(
                        buildJsonObject {
                            put(
                                "username",
                                requestedLogin,
                            )
                            if (
                                requestedLogin
                                    .contains("@")
                            ) {
                                put(
                                    "email",
                                    requestedLogin,
                                )
                            }
                            put(
                                "enabled",
                                false,
                            )
                            put(
                                "requiredActions",
                                buildJsonArray {
                                    add(
                                        JsonPrimitive(
                                            "UPDATE_PASSWORD",
                                        ),
                                    )
                                },
                            )
                            put(
                                "attributes",
                                buildJsonObject {
                                    put(
                                        "hiltechProvisioningId",
                                        buildJsonArray {
                                            add(
                                                JsonPrimitive(
                                                    invitationId
                                                        .toString(),
                                                ),
                                            )
                                        },
                                    )
                                },
                            )
                        }.toString(),
                    )
                    .retrieve()
                    .toBodilessEntity()
                    .headers
                    .location
            } catch (
                failure: Exception,
            ) {
                findByProvisioningMarker(
                    invitationId,
                )?.let {
                    return ProviderIdentityHandle(
                        subject = it,
                    )
                }

                throw KeycloakProvisioningException(
                    safeCode =
                        "IDENTITY_PROVIDER_CREATE_FAILED",
                    message =
                        "Keycloak user creation failed.",
                )
            }

        val subject =
            location?.path
                ?.substringAfterLast('/')
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: findByProvisioningMarker(
                    invitationId,
                )
                ?: throw KeycloakProvisioningException(
                    safeCode =
                        "IDENTITY_PROVIDER_CREATE_UNCONFIRMED",
                    message =
                        "Keycloak user creation could not be confirmed.",
                )

        return ProviderIdentityHandle(
            subject = subject,
        )
    }

    override fun enableAndPrepareDelivery(
        handle: ProviderIdentityHandle,
        requestedLogin: String,
        mode: IdentityProvisioningDeliveryMode,
    ): ProviderDeliveryResult {
        validate()
        val token = adminToken()

        when (mode) {
            IdentityProvisioningDeliveryMode
                .TEMPORARY_PASSWORD_HANDOFF -> {
                val temporary =
                    temporaryCredential()

                putJson(
                    uri =
                        adminBase() +
                            "/users/" +
                            encode(
                                handle.subject,
                            ) +
                            "/reset-password",
                    token = token,
                    body =
                        buildJsonObject {
                            put(
                                "type",
                                "password",
                            )
                            put(
                                "value",
                                temporary,
                            )
                            put(
                                "temporary",
                                true,
                            )
                        },
                )
                enableUser(
                    subject =
                        handle.subject,
                    token = token,
                )

                return ProviderDeliveryResult(
                    state =
                        IdentityProvisioningDeliveryState
                            .READY,
                    temporaryCredential =
                        temporary,
                )
            }

            IdentityProvisioningDeliveryMode
                .EMAIL_ACTION_LINK -> {
                enableUser(
                    subject =
                        handle.subject,
                    token = token,
                )

                if (
                    !properties
                        .emailActionLinkEnabled
                ) {
                    return ProviderDeliveryResult(
                        state =
                            IdentityProvisioningDeliveryState
                                .NOT_CONFIGURED,
                        temporaryCredential = null,
                    )
                }

                if (
                    !requestedLogin
                        .contains("@")
                ) {
                    return ProviderDeliveryResult(
                        state =
                            IdentityProvisioningDeliveryState
                                .FAILED_RETRYABLE,
                        temporaryCredential = null,
                    )
                }

                try {
                    http.put()
                        .uri {
                            builder ->
                            builder
                                .scheme(
                                    providerBaseUri()
                                        .scheme,
                                )
                                .host(
                                    providerBaseUri()
                                        .host,
                                )
                                .port(
                                    providerBaseUri()
                                        .port,
                                )
                                .path(
                                    providerBaseUri()
                                        .path +
                                        "/admin/realms/" +
                                        encode(
                                            realm(),
                                        ) +
                                        "/users/" +
                                        encode(
                                            handle.subject,
                                        ) +
                                        "/execute-actions-email",
                                )
                                .queryParam(
                                    "client_id",
                                    oidc.nativeClientId,
                                )
                                .build()
                        }
                        .contentType(
                            MediaType.APPLICATION_JSON,
                        )
                        .header(
                            "Authorization",
                            "Bearer $token",
                        )
                        .body(
                            buildJsonArray {
                                add(
                                    JsonPrimitive(
                                        "UPDATE_PASSWORD",
                                    ),
                                )
                            }.toString(),
                        )
                        .retrieve()
                        .toBodilessEntity()
                } catch (
                    failure: Exception,
                ) {
                    return ProviderDeliveryResult(
                        state =
                            IdentityProvisioningDeliveryState
                                .FAILED_RETRYABLE,
                        temporaryCredential = null,
                    )
                }

                return ProviderDeliveryResult(
                    state =
                        IdentityProvisioningDeliveryState
                            .SENT,
                    temporaryCredential = null,
                )
            }
        }
    }

    private fun findByProvisioningMarker(
        invitationId: UUID,
    ): String? {
        validate()
        val token = adminToken()
        val body =
            try {
                http.get()
                    .uri {
                        builder ->
                        builder
                            .scheme(
                                providerBaseUri()
                                    .scheme,
                            )
                            .host(
                                providerBaseUri()
                                    .host,
                            )
                            .port(
                                providerBaseUri()
                                    .port,
                            )
                            .path(
                                providerBaseUri()
                                    .path +
                                    "/admin/realms/" +
                                    encode(realm()) +
                                    "/users",
                            )
                            .queryParam(
                                "q",
                                "hiltechProvisioningId:" +
                                    invitationId,
                            )
                            .queryParam(
                                "max",
                                2,
                            )
                            .build()
                    }
                    .header(
                        "Authorization",
                        "Bearer $token",
                    )
                    .retrieve()
                    .body(
                        String::class.java,
                    )
                    .orEmpty()
            } catch (
                failure: Exception,
            ) {
                throw KeycloakProvisioningException(
                    safeCode =
                        "IDENTITY_PROVIDER_LOOKUP_FAILED",
                    message =
                        "Keycloak user lookup failed.",
                )
            }

        val users =
            runCatching {
                json.parseToJsonElement(
                    body,
                ).jsonArray
            }.getOrElse {
                throw KeycloakProvisioningException(
                    safeCode =
                        "IDENTITY_PROVIDER_RESPONSE_INVALID",
                    message =
                        "Keycloak returned an invalid user response.",
                )
            }

        if (users.size > 1) {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_PROVIDER_DUPLICATE_MARKER",
                message =
                    "Multiple Keycloak users share one HILTECH provisioning marker.",
            )
        }

        return users
            .singleOrNull()
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.content
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun enableUser(
        subject: String,
        token: String,
    ) {
        putJson(
            uri =
                adminBase() +
                    "/users/" +
                    encode(subject),
            token = token,
            body =
                buildJsonObject {
                    put(
                        "enabled",
                        true,
                    )
                    put(
                        "requiredActions",
                        buildJsonArray {
                            add(
                                JsonPrimitive(
                                    "UPDATE_PASSWORD",
                                ),
                            )
                        },
                    )
                },
        )
    }

    private fun putJson(
        uri: String,
        token: String,
        body: JsonObject,
    ) {
        try {
            http.put()
                .uri(uri)
                .contentType(
                    MediaType.APPLICATION_JSON,
                )
                .header(
                    "Authorization",
                    "Bearer $token",
                )
                .body(body.toString())
                .retrieve()
                .toBodilessEntity()
        } catch (
            failure: Exception,
        ) {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_PROVIDER_FINALIZE_FAILED",
                message =
                    "Keycloak account finalization failed.",
            )
        }
    }

    private fun adminToken(): String {
        val body =
            "grant_type=client_credentials" +
                "&client_id=" +
                encode(
                    properties.adminClientId,
                ) +
                "&client_secret=" +
                encode(
                    properties.adminClientSecret,
                )

        val response =
            try {
                http.post()
                    .uri(
                        providerBaseUri().toString().trimEnd('/') +
                            "/realms/" + encode(realm()) + "/protocol/openid-connect/token",
                    )
                    .contentType(
                        MediaType
                            .APPLICATION_FORM_URLENCODED,
                    )
                    .body(body)
                    .retrieve()
                    .body(
                        String::class.java,
                    )
                    .orEmpty()
            } catch (
                failure: Exception,
            ) {
                throw KeycloakProvisioningException(
                    safeCode =
                        "IDENTITY_PROVIDER_AUTH_FAILED",
                    message =
                        "Keycloak admin authentication failed.",
                )
            }

        return runCatching {
            json.parseToJsonElement(
                response,
            ).jsonObject[
                "access_token"
            ]!!.jsonPrimitive.content
        }.getOrElse {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_PROVIDER_AUTH_RESPONSE_INVALID",
                message =
                    "Keycloak admin authentication response was invalid.",
            )
        }
    }

    private fun validate() {
        properties.validateEnabled()
        oidc.validateEnabledConfiguration()
        realm()
    }

    private fun issuer(): String =
        oidc.issuerUri
            .trim()
            .trimEnd('/')

    private fun providerBaseUri(): URI {
        return properties.privateAdminBaseUri()
            ?: URI.create(issuer().substringBeforeLast("/realms/"))
    }

    private fun realm(): String {
        val path =
            URI.create(
                issuer(),
            ).path
                ?.trimEnd('/')
                .orEmpty()
        val marker = "/realms/"
        val index =
            path.lastIndexOf(marker)

        if (
            index < 0 ||
            index + marker.length >=
            path.length
        ) {
            throw KeycloakProvisioningException(
                safeCode =
                    "IDENTITY_PROVIDER_ISSUER_INVALID",
                message =
                    "OIDC issuer is not a Keycloak realm issuer.",
            )
        }

        return path.substring(
            index + marker.length,
        )
    }

    private fun adminBase(): String {
        val base =
            providerBaseUri()
                .toString()
                .trimEnd('/')
        return base +
            "/admin/realms/" +
            encode(realm())
    }

    private fun temporaryCredential():
        String {
        val bytes =
            ByteArray(24)
        secureRandom.nextBytes(bytes)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    private fun encode(
        value: String,
    ): String =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8,
        ).replace(
            "+",
            "%20",
        )
}
