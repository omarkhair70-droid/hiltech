package com.hiltech.server.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
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
class JdbcEmployeeIdentityProvisioning(
    private val jdbc: JdbcTemplate,
    transactionManager:
        PlatformTransactionManager,
    private val oidc:
        HiltechOidcProperties,
    private val provider:
        ObjectProvider<KeycloakIdentityAdminPort>,
    private val clock: Clock,
) : EmployeeIdentityProvisioningPort {
    private val transaction =
        TransactionTemplate(
            transactionManager,
        )

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

            transaction.executeWithoutResult {
                activateLocalIdentity(
                    userIdentityId =
                        userIdentityId,
                    organizationId =
                        request.organizationId,
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
                        clock.instant()
                            .atOffset(
                                ZoneOffset.UTC,
                            ),
                    )
                }
            }

        jdbc.update(
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

        val membershipId =
            UUID.nameUUIDFromBytes(
                (
                    "identity-membership:" +
                        request.invitationId
                ).toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )

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
            clock.instant()
                .atOffset(ZoneOffset.UTC),
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
                )
                """.trimIndent(),
                Boolean::class.java,
                membershipId,
                request.organizationId,
                identityId,
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
    ) {
        val identityUpdated =
            jdbc.update(
                """
                UPDATE user_identity
                SET status = 'ACTIVE',
                    version = version + 1
                WHERE id = ?
                  AND status IN (
                      'PENDING',
                      'ACTIVE'
                  )
                """.trimIndent(),
                userIdentityId,
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
                  AND state IN (
                      'PENDING',
                      'ACTIVE'
                  )
                """.trimIndent(),
                organizationId,
                userIdentityId,
            )

        if (membershipUpdated < 1) {
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
) : KeycloakIdentityAdminPort {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }
    private val secureRandom =
        SecureRandom()
    private val http =
        RestClient.create()

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
                        issuer() +
                            "/protocol/openid-connect/token",
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
        val uri = URI.create(
            issuer(),
        )
        return URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "",
            null,
            null,
        )
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
