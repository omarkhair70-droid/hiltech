package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.StorageLocationSnapshot
import com.hiltech.server.warehouse.WarehousePersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

data class OfficeStockSourceSnapshot(
    val teamId: UUID, val organizationId: UUID,
    val teamCode: String, val teamName: String, val teamVersion: Long,
    val delivery: StorageLocationSnapshot,
)

data class OfficeTeamOption(
    val teamId: UUID, val organizationId: UUID, val code: String,
    val name: String, val version: Long, val canRequest: Boolean, val canReview: Boolean,
)
data class OfficeDestinationOption(
    val locationId: UUID, val code: String, val name: String, val version: Long,
)

/** HILTECH-only real team source; current membership and configured authority
 * are checked on every command, and cannot be inferred from a role label. */
@Component
class OfficeStockSource @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val warehouse: WarehousePersistencePort,
    private val approvals: ApprovalPersistencePort,
    private val clock: Clock,
    @Value("\${hiltech.procurement.office-stock.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.office-stock.request-authority-key:}")
    private val requestKey: String = "",
    @Value("\${hiltech.procurement.office-stock.review-authority-key:}")
    private val reviewKey: String = "",
) {
    fun load(teamId: UUID, locationId: UUID): OfficeStockSourceSnapshot? {
        if (!enabled) return null
        val team = jdbc.query(
            """
            SELECT t.id, t.organization_id, t.code, t.name, t.version
            FROM team t JOIN organization o ON o.id=t.organization_id
            WHERE t.id=? AND t.active=true AND o.status='ACTIVE'
              AND o.organization_type='HILTECH'
            """.trimIndent(),
            { rs, _ -> OfficeTeamOption(
                rs.getObject("id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getString("code"), rs.getString("name"), rs.getLong("version"),
                false, false,
            ) }, teamId,
        ).singleOrNull() ?: return null
        val storage = warehouse.storageLocation(locationId) ?: return null
        val parent = storage.warehouseId?.let { warehouse.warehouse(it) } ?: return null
        val now = clock.instant()
        if (storage.organizationId != team.organizationId ||
            storage.kind.name !in setOf("MAIN_WAREHOUSE", "WAREHOUSE") ||
            storage.projectId != null || storage.siteId != null ||
            storage.lifecycleState != StorageLocationLifecycle.ACTIVE ||
            (storage.activeFrom != null && storage.activeFrom > now) ||
            (storage.activeUntil != null && storage.activeUntil <= now) ||
            !parent.active || parent.organizationId != team.organizationId
        ) return null
        return OfficeStockSourceSnapshot(
            team.teamId, team.organizationId, team.code, team.name, team.version, storage,
        )
    }

    fun canRequest(actor: UUID, source: OfficeStockSourceSnapshot): Boolean =
        authority(actor, source.organizationId, requestKey) &&
            jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM team_membership m
                    WHERE m.team_id=? AND m.user_identity_id=?
                      AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?)
                )
                """.trimIndent(), Boolean::class.java,
                source.teamId, actor, clock.instant(), clock.instant(),
            ) == true

    fun canReview(actor: UUID, source: OfficeStockSourceSnapshot): Boolean =
        authority(actor, source.organizationId, reviewKey)

    private fun authority(actor: UUID, org: UUID, rawKey: String): Boolean {
        val key = rawKey.trim()
        if (!enabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) return false
        val now = clock.instant()
        if (!approvals.isActiveOrganizationMember(org, actor, now)) return false
        val binding = approvals.resolveCurrentAuthority(org, key, now) ?: return false
        return binding.organizationId == org && binding.authorityKey == key &&
            approvals.actorMatchesAuthority(actor, binding, now)
    }

    /** Authorized sourcing/PO currentness, never a boolean supplied by the UI. */
    fun current(root: PurchaseRequirementDraftRecord,
                last: PurchaseRequirementTransitionRecord?): Boolean {
        if (root.sourceType != "OFFICE_TEAM_STOCK" ||
            root.workOrderId != null || root.warehouseStorageLocationId != null ||
            root.assetId != null
        ) return false
        val source = load(root.officeTeamId ?: return false,
            root.officeDeliveryLocationId ?: return false) ?: return false
        return source.organizationId == root.organizationId &&
            source.teamVersion == (last?.revised?.workVersion ?: root.officeTeamVersion) &&
            source.delivery.version == root.officeDeliveryLocationVersion
    }

    fun destination(root: PurchaseRequirementDraftRecord,
                    last: PurchaseRequirementTransitionRecord?): UUID? =
        if (current(root,last)) root.officeDeliveryLocationId else null

    /** Show only current company teams for which this actor has an actual
     * requester membership or authorized reviewer role. */
    fun teams(actor: UUID, organizationId: UUID): List<OfficeTeamOption> {
        if (!enabled || !approvals.isActiveOrganizationMember(
            organizationId, actor, clock.instant(),
        )) return emptyList()
        val canReview = authority(actor, organizationId, reviewKey)
        val requestAuthority = authority(actor, organizationId, requestKey)
        if (!canReview && !requestAuthority) return emptyList()
        return jdbc.query(
            """
            SELECT t.id, t.code, t.name, t.version,
              EXISTS(SELECT 1 FROM team_membership m
                WHERE m.team_id=t.id AND m.user_identity_id=?
                  AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?))
                  AS in_team
            FROM team t JOIN organization o ON o.id=t.organization_id
            WHERE t.organization_id=? AND t.active=true
              AND o.status='ACTIVE' AND o.organization_type='HILTECH'
            ORDER BY t.name, t.id LIMIT 200
            """.trimIndent(),
            { rs, _ ->
                val member = rs.getBoolean("in_team")
                OfficeTeamOption(
                    rs.getObject("id", UUID::class.java), organizationId,
                    rs.getString("code"), rs.getString("name"), rs.getLong("version"),
                    requestAuthority && member, canReview,
                )
            }, actor, clock.instant(), clock.instant(), organizationId,
        ).filter { it.canRequest || it.canReview }
    }

    fun destinations(actor: UUID, teamId: UUID): List<OfficeDestinationOption> {
        val sourceTeam = teamsForKnownActor(actor, teamId) ?: return emptyList()
        return jdbc.query(
            """
            SELECT l.id, l.code, l.name, l.version
            FROM storage_location l JOIN warehouse w
              ON w.id=l.warehouse_id AND w.organization_id=l.organization_id
            WHERE l.organization_id=? AND l.lifecycle_state='ACTIVE'
              AND l.kind IN ('MAIN_WAREHOUSE','WAREHOUSE') AND w.active=true
              AND l.project_id IS NULL AND l.site_id IS NULL
              AND (l.active_from IS NULL OR l.active_from<=?)
              AND (l.active_until IS NULL OR l.active_until>?)
            ORDER BY l.name, l.id LIMIT 200
            """.trimIndent(),
            { rs, _ -> OfficeDestinationOption(
                rs.getObject("id", UUID::class.java), rs.getString("code"),
                rs.getString("name"), rs.getLong("version"),
            ) }, sourceTeam.organizationId, clock.instant(), clock.instant(),
        )
    }

    fun teamsForKnownActor(actor: UUID, teamId: UUID): OfficeTeamOption? {
        val org = jdbc.query(
            "SELECT organization_id FROM team WHERE id=?",
            { rs, _ -> rs.getObject("organization_id", UUID::class.java) }, teamId,
        ).singleOrNull() ?: return null
        return teams(actor, org).singleOrNull { it.teamId == teamId }
    }
}
