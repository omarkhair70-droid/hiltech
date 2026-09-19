package com.hiltech.server.identity

import com.hiltech.server.organizations.ActiveOrganizationMembership
import com.hiltech.server.organizations.ActiveTeamMembership
import com.hiltech.server.organizations.OrganizationIdentityContextPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IdentityBootstrapServiceTest {
    private val now = Instant.parse("2026-09-19T00:00:00Z")
    private val identityId = UUID.randomUUID()
    private val organizationId = UUID.randomUUID()
    private val installationId = UUID.randomUUID()

    @Test
    fun activeIdentityAndMembershipProducePermissionSafeBootstrap() {
        val identityRepository = FakeIdentityRepository(
            identity = activeIdentity(),
        )
        val service = service(identityRepository)

        val result = service.bootstrap(
            subject = subject(),
            installationId = null,
        )

        assertEquals(identityId.toString(), result.identityId)
        assertEquals("ACTIVE", result.identityStatus)
        assertEquals(1, result.organizations.size)
        assertTrue(result.organizations.single().primary)
        assertEquals(1, result.teams.size)
        assertEquals(now, identityRepository.lastAuthenticatedAt)
    }

    @Test
    fun unknownAndInactiveIdentitiesFailClosed() {
        val missing = service(
            FakeIdentityRepository(identity = null),
        )

        assertEquals(
            "IDENTITY_NOT_PROVISIONED",
            assertThrows<IdentityAccessException> {
                missing.bootstrap(subject(), null)
            }.code,
        )

        val revoked = service(
            FakeIdentityRepository(
                identity = activeIdentity().copy(
                    status = "REVOKED",
                ),
            ),
        )

        assertEquals(
            "IDENTITY_NOT_ACTIVE",
            assertThrows<IdentityAccessException> {
                revoked.bootstrap(subject(), null)
            }.code,
        )
    }

    @Test
    fun noActiveMembershipFailsClosed() {
        val service = IdentityBootstrapService(
            identityRepository = FakeIdentityRepository(
                identity = activeIdentity(),
            ),
            organizationContext =
                FakeOrganizationContext(
                    memberships = emptyList(),
                ),
            clock = fixedClock(),
        )

        assertEquals(
            "NO_ACTIVE_ORGANIZATION_MEMBERSHIP",
            assertThrows<IdentityAccessException> {
                service.bootstrap(subject(), null)
            }.code,
        )
    }

    @Test
    fun revokedOrForeignDeviceCannotContinueBootstrap() {
        val revokedRepository = FakeIdentityRepository(
            identity = activeIdentity(),
            device = device(
                userIdentityId = identityId,
                revokedAt = now.minusSeconds(1),
            ),
        )

        assertEquals(
            "DEVICE_REVOKED",
            assertThrows<IdentityAccessException> {
                service(revokedRepository).bootstrap(
                    subject(),
                    installationId,
                )
            }.code,
        )

        val foreignRepository = FakeIdentityRepository(
            identity = activeIdentity(),
            device = device(
                userIdentityId = UUID.randomUUID(),
                revokedAt = null,
            ),
        )

        assertEquals(
            "DEVICE_INSTALLATION_CONFLICT",
            assertThrows<IdentityAccessException> {
                service(foreignRepository).bootstrap(
                    subject(),
                    installationId,
                )
            }.code,
        )
    }

    @Test
    fun deviceRegistrationNeverResurrectsRevokedInstallation() {
        val repository = FakeIdentityRepository(
            identity = activeIdentity(),
            registrationOutcome =
                DeviceRegistrationOutcome.Revoked,
        )
        val service = service(repository)

        val error = assertThrows<IdentityAccessException> {
            service.registerDevice(
                subject(),
                DeviceRegistrationRequest(
                    installationId =
                        installationId.toString(),
                    platform = "android",
                    deviceName = "Field Phone",
                    appVersion = "0.1.0",
                    osVersion = "16",
                ),
            )
        }

        assertEquals("DEVICE_REVOKED", error.code)
        assertFalse(repository.registrationTouched)
    }

    private fun service(
        identityRepository: FakeIdentityRepository,
    ): IdentityBootstrapService =
        IdentityBootstrapService(
            identityRepository = identityRepository,
            organizationContext = FakeOrganizationContext(),
            clock = fixedClock(),
        )

    private fun activeIdentity() =
        UserIdentityRuntime(
            id = identityId,
            status = "ACTIVE",
            primaryOrganizationId = organizationId,
            version = 3,
        )

    private fun subject() =
        AuthenticatedOidcSubject(
            issuer = "https://id.hiltech.test/realms/hiltech",
            subject = "subject-42",
        )

    private fun device(
        userIdentityId: UUID,
        revokedAt: Instant?,
    ) =
        DeviceRuntime(
            id = UUID.randomUUID(),
            userIdentityId = userIdentityId,
            installationId = installationId,
            platform = "ANDROID",
            deviceName = "Field Phone",
            appVersion = "0.1.0",
            osVersion = "16",
            lastSeenAt = now.minusSeconds(60),
            revokedAt = revokedAt,
            version = 2,
        )

    private fun fixedClock(): Clock =
        Clock.fixed(now, ZoneOffset.UTC)

    private inner class FakeIdentityRepository(
        private val identity: UserIdentityRuntime?,
        private val device: DeviceRuntime? = null,
        private val registrationOutcome:
            DeviceRegistrationOutcome =
                DeviceRegistrationOutcome.Active(
                    device(
                        userIdentityId = identityId,
                        revokedAt = null,
                    ),
                ),
    ) : IdentityRuntimeRepository {
        var lastAuthenticatedAt: Instant? = null
        var registrationTouched = false

        override fun findByOidcSubject(
            issuer: String,
            subject: String,
        ): UserIdentityRuntime? = identity

        override fun markAuthenticated(
            identityId: UUID,
            authenticatedAt: Instant,
        ) {
            lastAuthenticatedAt = authenticatedAt
        }

        override fun findDevice(
            installationId: UUID,
        ): DeviceRuntime? = device

        override fun registerOrTouchDevice(
            identityId: UUID,
            registration: DeviceRegistration,
            seenAt: Instant,
        ): DeviceRegistrationOutcome {
            if (
                registrationOutcome
                    !is DeviceRegistrationOutcome.Revoked
            ) {
                registrationTouched = true
            }
            return registrationOutcome
        }
    }

    private inner class FakeOrganizationContext(
        private val memberships:
            List<ActiveOrganizationMembership> =
                listOf(
                    ActiveOrganizationMembership(
                        membershipId = UUID.randomUUID(),
                        organizationId = organizationId,
                        organizationCode = "HILTECH",
                        organizationDisplayName = "HILTECH",
                        organizationType = "HILTECH",
                        membershipType = "EMPLOYEE",
                        roleLabel = "Technician",
                        membershipVersion = 2,
                    ),
                ),
        private val teams:
            List<ActiveTeamMembership> =
                listOf(
                    ActiveTeamMembership(
                        teamMembershipId =
                            UUID.randomUUID(),
                        teamId = UUID.randomUUID(),
                        organizationId = organizationId,
                        code = "FIELD",
                        name = "Field",
                        roleInTeam = "MEMBER",
                    ),
                ),
    ) : OrganizationIdentityContextPort {
        override fun activeMemberships(
            identityId: UUID,
            at: Instant,
        ): List<ActiveOrganizationMembership> =
            memberships

        override fun activeTeams(
            identityId: UUID,
            at: Instant,
        ): List<ActiveTeamMembership> =
            teams
    }
}
