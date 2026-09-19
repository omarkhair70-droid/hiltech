package com.hiltech.server.identity

import com.hiltech.server.platform.HiltechRequestContextFilter
import com.hiltech.server.platform.ProductApiErrorWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import java.time.Clock

@ConfigurationProperties(prefix = "hiltech.identity.oidc")
data class HiltechOidcProperties(
    var enabled: Boolean = false,
    var issuerUri: String = "",
    var audience: String = "",
    var nativeClientId: String = "hiltech-native",
) {
    fun validateEnabledConfiguration() {
        if (!enabled) {
            return
        }

        require(issuerUri.isNotBlank()) {
            "hiltech.identity.oidc.issuer-uri must be configured when OIDC is enabled."
        }
        require(nativeClientId.isNotBlank()) {
            "hiltech.identity.oidc.native-client-id must be configured when OIDC is enabled."
        }
    }
}

data class AuthenticatedOidcSubject(
    val issuer: String,
    val subject: String,
)

object OidcSubjectResolver {
    fun from(jwt: Jwt): AuthenticatedOidcSubject =
        fromClaims(
            issuer = jwt.issuer?.toString(),
            subject = jwt.subject,
        )

    fun fromClaims(
        issuer: String?,
        subject: String?,
    ): AuthenticatedOidcSubject {
        val normalizedIssuer = issuer?.trim().orEmpty()
        val normalizedSubject = subject?.trim().orEmpty()

        require(normalizedIssuer.isNotEmpty()) {
            "Authenticated OIDC token is missing issuer."
        }
        require(normalizedSubject.isNotEmpty()) {
            "Authenticated OIDC token is missing subject."
        }

        return AuthenticatedOidcSubject(
            issuer = normalizedIssuer,
            subject = normalizedSubject,
        )
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    HiltechOidcProperties::class,
    HiltechIdentitySessionProperties::class,
)
class IdentitySecurityConfiguration {
    @Bean
    fun hiltechClock(): Clock =
        Clock.systemUTC()

    @Bean
    fun hiltechSecurityFilterChain(
        http: HttpSecurity,
        properties: HiltechOidcProperties,
        jwtDecoderProvider: ObjectProvider<JwtDecoder>,
        requestContextFilter: HiltechRequestContextFilter,
        identityAccessFilter: IdentityAccessEnforcementFilter,
        errorWriter: ProductApiErrorWriter,
    ): SecurityFilterChain {
        properties.validateEnabledConfiguration()

        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authorizeHttpRequests { authorization ->
                authorization
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                    )
                    .permitAll()

                if (properties.enabled) {
                    authorization.anyRequest().authenticated()
                } else {
                    authorization.anyRequest().denyAll()
                }
            }
            .exceptionHandling { failures ->
                failures.authenticationEntryPoint {
                        request,
                        response,
                        _,
                    ->
                    errorWriter.write(
                        request = request,
                        response = response,
                        status =
                            org.springframework.http.HttpStatus.UNAUTHORIZED,
                        code = "UNAUTHENTICATED",
                        message =
                            "Authentication is required.",
                    )
                }
                failures.accessDeniedHandler {
                        request,
                        response,
                        _,
                    ->
                    errorWriter.write(
                        request = request,
                        response = response,
                        status =
                            org.springframework.http.HttpStatus.FORBIDDEN,
                        code = "PERMISSION_DENIED",
                        message =
                            "This action is not permitted.",
                    )
                }
            }

        http.addFilterBefore(
            requestContextFilter,
            BearerTokenAuthenticationFilter::class.java,
        )

        if (properties.enabled) {
            val decoder = jwtDecoderProvider.getIfAvailable()
                ?: error("OIDC is enabled but no JwtDecoder is available.")

            http.oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt ->
                    jwt.decoder(decoder)
                }
            }
            http.addFilterAfter(
                identityAccessFilter,
                BearerTokenAuthenticationFilter::class.java,
            )
        }

        return http.build()
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "hiltech.identity.oidc",
    name = ["enabled"],
    havingValue = "true",
)
class OidcJwtDecoderConfiguration {
    @Bean
    fun hiltechJwtDecoder(
        properties: HiltechOidcProperties,
    ): JwtDecoder {
        properties.validateEnabledConfiguration()

        val decoder = NimbusJwtDecoder
            .withIssuerLocation(properties.issuerUri)
            .build()

        val issuerValidator = JwtValidators.createDefaultWithIssuer(
            properties.issuerUri,
        )

        val audience = properties.audience.trim()
        decoder.setJwtValidator(
            if (audience.isEmpty()) {
                issuerValidator
            } else {
                DelegatingOAuth2TokenValidator(
                    issuerValidator,
                    JwtAudienceValidator(audience),
                )
            },
        )

        return decoder
    }
}
