package com.hiltech.server.projects

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class ProjectAuthorityPrincipalType {
    USER,
    TEAM,
}

data class ProjectAuthorityBindingContext(
    val bindingId: UUID,
    val organizationId: UUID,
    val principalType: ProjectAuthorityPrincipalType,
    val principalId: UUID,
    val version: Long,
    val current: Boolean,
)

interface ProjectAuthoritySourcePort {
    fun currentAdminBindings(
        organizationId: UUID,
        at: Instant,
    ): List<ProjectAuthorityBindingContext>

    fun binding(
        bindingId: UUID,
        at: Instant,
    ): ProjectAuthorityBindingContext?
}

@Component
class JdbcProjectAuthoritySource(
    private val jdbc: JdbcTemplate,
) : ProjectAuthoritySourcePort {
    override fun currentAdminBindings(
        organizationId: UUID,
        at: Instant,
    ): List<ProjectAuthorityBindingContext> =
        readBindings(
            whereSql =
                "pab.organization_id = ?",
            args =
                arrayOf(organizationId),
            at = at,
        ).filter { it.current }

    override fun binding(
        bindingId: UUID,
        at: Instant,
    ): ProjectAuthorityBindingContext? =
        readBindings(
            whereSql = "pab.id = ?",
            args = arrayOf(bindingId),
            at = at,
        ).singleOrNull()

    private fun readBindings(
        whereSql: String,
        args: Array<out Any>,
        at: Instant,
    ): List<ProjectAuthorityBindingContext> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT
                pab.id,
                pab.organization_id,
                pab.principal_type,
                pab.principal_user_id,
                pab.principal_team_id,
                pab.version,
                (
                    pab.active = true
                    AND o.status = 'ACTIVE'
                    AND pab.effective_from <= ?
                    AND (
                        pab.effective_to IS NULL
                        OR pab.effective_to >= ?
                    )
                    AND (
                        (
                            pab.principal_type = 'USER'
                            AND EXISTS (
                                SELECT 1
                                FROM user_identity ui
                                JOIN organization_membership om
                                  ON om.user_identity_id = ui.id
                                WHERE ui.id = pab.principal_user_id
                                  AND ui.status = 'ACTIVE'
                                  AND om.organization_id = pab.organization_id
                                  AND om.state = 'ACTIVE'
                                  AND om.valid_from <= ?
                                  AND (
                                      om.valid_until IS NULL
                                      OR om.valid_until >= ?
                                  )
                            )
                        )
                        OR (
                            pab.principal_type = 'TEAM'
                            AND t.organization_id = pab.organization_id
                            AND t.active = true
                        )
                    )
                ) AS authority_current
            FROM project_authority_binding pab
            JOIN organization o
              ON o.id = pab.organization_id
            LEFT JOIN team t
              ON t.id = pab.principal_team_id
            WHERE pab.authority_key = 'PROJECT_ADMIN'
              AND $whereSql
            """.trimIndent(),
            { rs, _ ->
                val type =
                    ProjectAuthorityPrincipalType.valueOf(
                        rs.getString("principal_type"),
                    )
                ProjectAuthorityBindingContext(
                    bindingId =
                        rs.getObject("id", UUID::class.java),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    principalType = type,
                    principalId =
                        when (type) {
                            ProjectAuthorityPrincipalType.USER ->
                                rs.getObject(
                                    "principal_user_id",
                                    UUID::class.java,
                                )
                            ProjectAuthorityPrincipalType.TEAM ->
                                rs.getObject(
                                    "principal_team_id",
                                    UUID::class.java,
                                )
                        },
                    version =
                        rs.getLong("version"),
                    current =
                        rs.getBoolean("authority_current"),
                )
            },
            timestamp,
            timestamp,
            timestamp,
            timestamp,
            *args,
        )
    }
}

object ProjectAuthorizationRelations {
    fun projectAdmin(
        binding: ProjectAuthorityBindingContext,
    ): OpenFgaTuple =
        when (binding.principalType) {
            ProjectAuthorityPrincipalType.USER ->
                OpenFgaTuple(
                    subjectType = "user",
                    subjectId =
                        binding.principalId.toString(),
                    relation = "project_admin",
                    objectType = "organization",
                    objectId =
                        binding.organizationId.toString(),
                )

            ProjectAuthorityPrincipalType.TEAM ->
                OpenFgaTuple(
                    subjectType = "team",
                    subjectId =
                        binding.principalId.toString() +
                            "#member",
                    relation = "project_admin",
                    objectType = "organization",
                    objectId =
                        binding.organizationId.toString(),
                )
        }

    fun canManageProjects(
        actorUserId: UUID,
        organizationId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = "can_manage_projects",
            objectType = "organization",
            objectId = organizationId.toString(),
        )

    fun projectOrganization(
        projectId: UUID,
        organizationId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "organization",
            subjectId = organizationId.toString(),
            relation = "organization",
            objectType = "project",
            objectId = projectId.toString(),
        )

    fun projectManager(
        responsibility:
            ProjectResponsibilitySnapshot,
    ): OpenFgaTuple? =
        when (responsibility.principalType) {
            ProjectResponsibilityPrincipalType.EMPLOYEE ->
                responsibility.linkedUserIdentityId
                    ?.let {
                        OpenFgaTuple(
                            subjectType = "user",
                            subjectId = it.toString(),
                            relation = "project_manager",
                            objectType = "project",
                            objectId =
                                responsibility.projectId
                                    .toString(),
                        )
                    }

            ProjectResponsibilityPrincipalType.TEAM ->
                OpenFgaTuple(
                    subjectType = "team",
                    subjectId =
                        responsibility.principalId
                            .toString() +
                            "#member",
                    relation = "project_manager",
                    objectType = "project",
                    objectId =
                        responsibility.projectId
                            .toString(),
                )
        }

    fun projectAction(
        actorUserId: UUID,
        projectId: UUID,
        relation: String,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = relation,
            objectType = "project",
            objectId = projectId.toString(),
        )

    fun siteOrganization(
        siteId: UUID,
        organizationId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "organization",
            subjectId = organizationId.toString(),
            relation = "organization",
            objectType = "site",
            objectId = siteId.toString(),
        )

    fun siteProject(
        siteId: UUID,
        projectId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "project",
            subjectId = projectId.toString(),
            relation = "project",
            objectType = "site",
            objectId = siteId.toString(),
        )

    fun siteAction(
        actorUserId: UUID,
        siteId: UUID,
        relation: String,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = relation,
            objectType = "site",
            objectId = siteId.toString(),
        )
}

@Component
class ProjectAuthorizationProjectionBridge(
    private val source:
        ProjectAuthoritySourcePort,
    private val writer:
        AuthorizationProjectionIntentWriter,
    private val clock: Clock,
) {
    @Transactional(
        propagation = Propagation.MANDATORY,
    )
    fun syncAuthorityBinding(
        bindingId: UUID,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val at =
            clock.instant()
        val binding =
            source.binding(bindingId, at)
                ?: error(
                    "Project authority binding $bindingId does not exist.",
                )

        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    ProjectAuthorizationRelations
                        .projectAdmin(binding),
                desiredState =
                    if (binding.current) {
                        AuthorizationDesiredState.PRESENT
                    } else {
                        AuthorizationDesiredState.ABSENT
                    },
                sourceType =
                    "ProjectAuthorityBinding",
                sourceId =
                    binding.bindingId.toString(),
                sourceVersion =
                    binding.version,
                eventType =
                    "PROJECT_AUTHORITY_CHANGED",
                occurredAt = at,
            ),
        )
    }

    fun projectCreated(
        projectId: UUID,
        organizationId: UUID,
        sourceVersion: Long,
        eventId: UUID,
        occurredAt: Instant,
    ) {
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    ProjectAuthorizationRelations
                        .projectOrganization(
                            projectId,
                            organizationId,
                        ),
                desiredState =
                    AuthorizationDesiredState.PRESENT,
                sourceType = "Project",
                sourceId = projectId.toString(),
                sourceVersion = sourceVersion,
                eventType =
                    "PROJECT_ORGANIZATION_LINKED",
                occurredAt = occurredAt,
            ),
        )
    }

    fun siteCreated(
        siteId: UUID,
        organizationId: UUID,
        sourceVersion: Long,
        eventId: UUID,
        occurredAt: Instant,
    ) {
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    ProjectAuthorizationRelations
                        .siteOrganization(
                            siteId,
                            organizationId,
                        ),
                desiredState =
                    AuthorizationDesiredState.PRESENT,
                sourceType = "Site",
                sourceId = siteId.toString(),
                sourceVersion = sourceVersion,
                eventType =
                    "SITE_ORGANIZATION_LINKED",
                occurredAt = occurredAt,
            ),
        )
    }

    fun projectSiteAttached(
        projectSite: ProjectSiteSnapshot,
        eventId: UUID,
        occurredAt: Instant,
    ) {
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    ProjectAuthorizationRelations
                        .siteProject(
                            siteId =
                                projectSite.site.siteId,
                            projectId =
                                projectSite.projectId,
                        ),
                desiredState =
                    AuthorizationDesiredState.PRESENT,
                sourceType = "ProjectSite",
                sourceId =
                    projectSite.projectSiteId.toString(),
                sourceVersion =
                    projectSite.version,
                eventType =
                    "PROJECT_SITE_LINKED",
                occurredAt = occurredAt,
            ),
        )
    }

    fun responsibility(
        responsibility:
            ProjectResponsibilitySnapshot,
        desiredState:
            AuthorizationDesiredState,
        eventId: UUID,
        occurredAt: Instant,
    ) {
        val tuple =
            ProjectAuthorizationRelations
                .projectManager(responsibility)
                ?: return

        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple = tuple,
                desiredState = desiredState,
                sourceType =
                    "ProjectResponsibility",
                sourceId =
                    responsibility
                        .responsibilityId
                        .toString(),
                sourceVersion =
                    responsibility.version,
                eventType =
                    "PROJECT_RESPONSIBILITY_CHANGED",
                occurredAt = occurredAt,
            ),
        )
    }
}

interface ProjectAuthorizationPort {
    fun canManageProjects(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean

    fun canViewProject(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean

    fun canManageProject(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean

    fun canCreateWork(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean

    fun canAssignWork(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean

    fun canReviewWork(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean

    fun canManageSite(
        actorUserId: UUID,
        site: SiteSnapshot,
    ): Boolean
}

@Component
class SpringProjectAuthorization(
    private val authorizationProvider:
        ObjectProvider<AuthorizationCheckPort>,
    private val sourceAuthority:
        RoleTeamSourceAuthorityPort,
    private val projectAuthoritySource:
        ProjectAuthoritySourcePort,
    private val clock: Clock,
) : ProjectAuthorizationPort {
    override fun canManageProjects(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false
        val at =
            clock.instant()

        if (
            !sourceAuthority.isOrganizationMemberCurrent(
                identityId = actorUserId,
                organizationId = organizationId,
                at = at,
            )
        ) {
            return false
        }

        val binding =
            currentAdminBinding(
                actorUserId,
                organizationId,
                at,
            ) ?: return false

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    ProjectAuthorizationRelations
                        .canManageProjects(
                            actorUserId,
                            organizationId,
                        ),
                failClosedGuardTuples =
                    adminGuards(
                        actorUserId,
                        binding,
                    ),
            ),
        )
    }

    override fun canViewProject(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean =
        checkProjectAction(
            actorUserId,
            project,
            "can_view",
        )

    override fun canManageProject(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean =
        checkProjectAction(
            actorUserId,
            project,
            "can_manage",
        )

    override fun canCreateWork(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean =
        checkProjectAction(
            actorUserId,
            project,
            "can_create_work",
        )

    override fun canAssignWork(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean =
        checkProjectAction(
            actorUserId,
            project,
            "can_assign_work",
        )

    override fun canReviewWork(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ): Boolean =
        checkProjectAction(
            actorUserId,
            project,
            "can_review_work",
        )

    override fun canManageSite(
        actorUserId: UUID,
        site: SiteSnapshot,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false
        val at =
            clock.instant()
        val binding =
            currentAdminBinding(
                actorUserId,
                site.organizationId,
                at,
            ) ?: return false

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    ProjectAuthorizationRelations
                        .siteAction(
                            actorUserId,
                            site.siteId,
                            "can_manage",
                        ),
                failClosedGuardTuples =
                    adminGuards(
                        actorUserId,
                        binding,
                    ) +
                        ProjectAuthorizationRelations
                            .siteOrganization(
                                site.siteId,
                                site.organizationId,
                            ),
            ),
        )
    }

    private fun checkProjectAction(
        actorUserId: UUID,
        project: ProjectSnapshot,
        relation: String,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false
        val at =
            clock.instant()

        if (
            !sourceAuthority.isOrganizationMemberCurrent(
                identityId = actorUserId,
                organizationId =
                    project.organizationId,
                at = at,
            )
        ) {
            return false
        }

        val admin =
            currentAdminBinding(
                actorUserId,
                project.organizationId,
                at,
            )

        val guards =
            if (admin != null) {
                adminGuards(
                    actorUserId,
                    admin,
                ) +
                    ProjectAuthorizationRelations
                        .projectOrganization(
                            project.projectId,
                            project.organizationId,
                        )
            } else {
                managerGuards(
                    actorUserId,
                    project,
                    at,
                ) ?: return false
            }

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    ProjectAuthorizationRelations
                        .projectAction(
                            actorUserId,
                            project.projectId,
                            relation,
                        ),
                failClosedGuardTuples = guards,
            ),
        )
    }

    private fun currentAdminBinding(
        actorUserId: UUID,
        organizationId: UUID,
        at: Instant,
    ): ProjectAuthorityBindingContext? =
        projectAuthoritySource
            .currentAdminBindings(
                organizationId,
                at,
            )
            .firstOrNull {
                when (it.principalType) {
                    ProjectAuthorityPrincipalType.USER ->
                        it.principalId ==
                            actorUserId
                    ProjectAuthorityPrincipalType.TEAM ->
                        sourceAuthority
                            .isTeamMemberCurrent(
                                identityId =
                                    actorUserId,
                                teamId =
                                    it.principalId,
                                at = at,
                            )
                }
            }

    private fun adminGuards(
        actorUserId: UUID,
        binding:
            ProjectAuthorityBindingContext,
    ): List<OpenFgaTuple> =
        buildList {
            add(
                RoleTeamAuthorizationRelations
                    .organizationMember(
                        actorUserId,
                        binding.organizationId,
                    ),
            )
            add(
                ProjectAuthorizationRelations
                    .projectAdmin(binding),
            )
            if (
                binding.principalType ==
                ProjectAuthorityPrincipalType.TEAM
            ) {
                add(
                    RoleTeamAuthorizationRelations
                        .teamMember(
                            actorUserId,
                            binding.principalId,
                        ),
                )
            }
        }

    private fun managerGuards(
        actorUserId: UUID,
        project: ProjectSnapshot,
        at: Instant,
    ): List<OpenFgaTuple>? {
        val responsibility =
            project.responsibility
                ?: return null
        val manager =
            ProjectAuthorizationRelations
                .projectManager(
                    responsibility,
                )
                ?: return null

        return when (responsibility.principalType) {
            ProjectResponsibilityPrincipalType.EMPLOYEE -> {
                if (
                    responsibility.linkedUserIdentityId !=
                    actorUserId
                ) {
                    null
                } else {
                    listOf(
                        RoleTeamAuthorizationRelations
                            .organizationMember(
                                actorUserId,
                                project.organizationId,
                            ),
                        manager,
                    )
                }
            }

            ProjectResponsibilityPrincipalType.TEAM -> {
                if (
                    !sourceAuthority.isTeamMemberCurrent(
                        identityId = actorUserId,
                        teamId =
                            responsibility.principalId,
                        at = at,
                    )
                ) {
                    null
                } else {
                    listOf(
                        RoleTeamAuthorizationRelations
                            .organizationMember(
                                actorUserId,
                                project.organizationId,
                            ),
                        RoleTeamAuthorizationRelations
                            .teamMember(
                                actorUserId,
                                responsibility.principalId,
                            ),
                        manager,
                    )
                }
            }
        }
    }
}
