package com.hiltech.server.procurement

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ServicePurchaseTeamOption(
    val id: UUID, val organizationId: UUID, val name: String, val version: Long,
    val canRequest: Boolean, val canReview: Boolean,
)

data class CreateServicePurchaseNeedRequest(
    val operationId: UUID, val teamVersion: Long,
    val specification: String, val acceptanceCriteria: String, val justification: String,
)

data class ReviseServicePurchaseNeedRequest(
    val operationId: UUID, val expectedVersion: Long,
    val specification: String, val acceptanceCriteria: String, val justification: String,
)

data class ServicePurchaseNeedAction(
    val operationId: UUID, val expectedVersion: Long, val reason: String? = null,
)

data class ServicePurchaseNeedReview(
    val operationId: UUID, val expectedVersion: Long,
    val decision: String, val reason: String? = null,
)

data class ServicePurchaseNeedView(
    val id: UUID, val organizationId: UUID, val teamId: UUID, val teamName: String,
    val teamVersion: Long, val specification: String, val acceptanceCriteria: String,
    val justification: String, val requestedBy: UUID,
    val state: String, val version: Long, val lastReviewReason: String?,
    val sourceCurrent: Boolean, val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    // These flags prevent calling internal request review an actual service order.
    val supplierContacted: Boolean = false,
    val serviceOrdered: Boolean = false,
    val serviceAccepted: Boolean = false,
    val invoiceMatched: Boolean = false,
    val payableCreated: Boolean = false,
)
data class ServicePurchaseNeedReceipt(val need: ServicePurchaseNeedView, val replayed: Boolean)

/**
 * Non-stock external service demand only.
 * Existing Team/Organization and ApprovalEngine authority are the source truth.
 * No StockItem, Warehouse receiving, supplier dispatch, PO, payment or Finance side effect.
 * The order/acceptance/2-way invoice contract must be a separate governed slice.
 */
@Service
class ServicePurchaseNeedService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val approval: ApprovalPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.service-needs.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.service-needs.request-authority-key:PROCUREMENT_NEED_REQUEST}")
    private val requesterKey: String = "",
    @Value("\${hiltech.procurement.service-needs.review-authority-key:PROCUREMENT_NEED_REVIEW}")
    private val reviewerKey: String = "",
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    private data class Team(val id: UUID, val org: UUID, val name: String, val version: Long)
    private data class Need(
        val id: UUID, val org: UUID, val team: UUID, val teamVersion: Long,
        val specification: String, val criteria: String, val why: String, val owner: UUID,
    )
    private data class Event(
        val version: Long, val state: String, val reason: String?,
    )
    private data class Revision(
        val specification: String, val criteria: String, val why: String,
    )

    fun teams(actor: UUID, organizationId: UUID): List<ServicePurchaseTeamOption> {
        active()
        if (!approval.isActiveOrganizationMember(organizationId, actor, clock.instant()))
            return emptyList()
        val requester = authority(actor, organizationId, "hiltech.procurement.service-needs.request-authority-key", requesterKey)
        val reviewer = authority(actor, organizationId, "hiltech.procurement.service-needs.review-authority-key", reviewerKey)
        if (!requester && !reviewer) return emptyList()
        return jdbc.query(
            """
            SELECT t.id,t.organization_id,t.name,t.version,
                EXISTS(SELECT 1 FROM team_membership m
                  WHERE m.team_id=t.id AND m.user_identity_id=?
                    AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?))
                  AS is_member
            FROM team t JOIN organization o ON o.id=t.organization_id
            WHERE t.organization_id=? AND t.active=true AND o.status='ACTIVE'
              AND o.organization_type='HILTECH'
            ORDER BY t.name,t.id LIMIT 200
            """.trimIndent(),
            { rs, _ ->
                val member = rs.getBoolean("is_member")
                ServicePurchaseTeamOption(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getString("name"), rs.getLong("version"),
                    requester && member, reviewer,
                )
            }, actor, clock.instant(), clock.instant(), organizationId,
        ).filter { it.canRequest || it.canReview }
    }

    fun create(ctx: ProcurementDraftContext, teamId: UUID,
               input: CreateServicePurchaseNeedRequest): ServicePurchaseNeedReceipt {
        active()
        if (input.operationId != ctx.operationId || input.teamVersion < 1) invalid()
        val specification = text(input.specification, 20, 2000)
        val criteria = text(input.acceptanceCriteria, 20, 2000)
        val why = text(input.justification, 12, 500)
        val source = team(teamId) ?: hidden()
        if (!canRequest(ctx.actor, source)) hidden()
        if (source.version != input.teamVersion) stale()
        val id = uuid(ctx.operationId)
        val result = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_SERVICE_NEED_CREATED",
            "PROCUREMENT_SERVICE_NEED", id,
            fingerprint(teamId, input.teamVersion, specification, criteria, why),
            ctx.correlationId,
        )) {
            val locked = team(teamId, lock = true) ?: hidden()
            if (!canRequest(ctx.actor, locked)) hidden()
            if (locked.version != input.teamVersion || locked.org != source.org) stale()
            val at = clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_need (
                  id,operation_id,organization_id,team_id,team_version,
                  specification,acceptance_criteria,justification,
                  requested_by_user_id,created_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,locked.org,locked.id,locked.version,
                specification,criteria,why,ctx.actor,at.atOffset(ZoneOffset.UTC),
                ctx.correlationId,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_SERVICE_NEED_CREATED",
                targetType = "PROCUREMENT_SERVICE_NEED", targetId = id,
                occurredAt = at, correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("SERVICE_NEED_CREATED", "{\"id\":\"" + id + "\"}")
        }
        return ServicePurchaseNeedReceipt(detail(ctx.actor, id), result.replayed)
    }

    fun list(actor: UUID, teamId: UUID): List<ServicePurchaseNeedView> {
        active()
        val source = team(teamId) ?: hidden()
        val asRequester = canRequest(actor, source)
        val asReviewer = canReview(actor, source)
        if (!asRequester && !asReviewer) hidden()
        val ids = jdbc.query(
            """
            SELECT id FROM procurement_service_need
            WHERE organization_id=? AND team_id=?
              AND (requested_by_user_id=? OR ?)
            ORDER BY created_at DESC,id DESC LIMIT 100
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            source.org, teamId, actor, asReviewer,
        )
        return ids.map { detail(actor, it) }
    }

    fun detail(actor: UUID, id: UUID): ServicePurchaseNeedView {
        active()
        val root = need(id) ?: hidden()
        val source = team(root.team) ?: hidden()
        if (root.org != source.org) hidden()
        val requester = actor == root.owner && canRequest(actor, source)
        val reviewer = actor != root.owner && canReview(actor, source)
        if (!requester && !reviewer) hidden()
        val last = latest(id)
        val revised = revision(id)
        val state = last?.state ?: "DRAFT"
        val version = last?.version ?: 1L
        val current = source.version == root.teamVersion
        return ServicePurchaseNeedView(
            root.id, root.org, root.team, source.name, root.teamVersion,
            revised?.specification ?: root.specification,
            revised?.criteria ?: root.criteria,
            revised?.why ?: root.why, root.owner,
            state, version, if (state == "NEEDS_INFO") last?.reason else null,
            current,
            requester && current && state == "DRAFT",
            requester && current && state in setOf("DRAFT","NEEDS_INFO"),
            requester && current && state in setOf("DRAFT","SUBMITTED","NEEDS_INFO"),
            reviewer && current && state == "SUBMITTED",
        )
    }

    fun submit(ctx: ProcurementDraftContext, id: UUID,
               input: ServicePurchaseNeedAction): ServicePurchaseNeedReceipt =
        transition(ctx,id,input.operationId,input.expectedVersion,"SUBMIT","SUBMITTED",null,null)

    fun withdraw(ctx: ProcurementDraftContext, id: UUID,
                 input: ServicePurchaseNeedAction): ServicePurchaseNeedReceipt =
        transition(ctx,id,input.operationId,input.expectedVersion,"WITHDRAW","WITHDRAWN",
            input.reason,null)

    fun review(ctx: ProcurementDraftContext, id: UUID,
               input: ServicePurchaseNeedReview): ServicePurchaseNeedReceipt {
        val type = when (input.decision) {
            "READY_FOR_SOURCING" -> "TECHNICAL_READY"
            "NEEDS_INFO" -> "NEEDS_INFO"
            "REJECTED" -> "REJECT"
            else -> invalid()
        }
        return transition(ctx,id,input.operationId,input.expectedVersion,type,
            input.decision,input.reason,null)
    }

    fun revise(ctx: ProcurementDraftContext, id: UUID,
               input: ReviseServicePurchaseNeedRequest): ServicePurchaseNeedReceipt =
        transition(ctx,id,input.operationId,input.expectedVersion,"REVISE","DRAFT",null,
            Revision(text(input.specification,20,2000),
                text(input.acceptanceCriteria,20,2000),
                text(input.justification,12,500)))

    private fun transition(
        ctx: ProcurementDraftContext, id: UUID, op: UUID, expected: Long,
        type: String, target: String, rawReason: String?, change: Revision?,
    ): ServicePurchaseNeedReceipt {
        active()
        if (op != ctx.operationId || expected < 1) invalid()
        val reason = rawReason?.let { text(it,3,500) }
        if (type in setOf("NEEDS_INFO","REJECT") && reason == null) invalid()
        val root = need(id) ?: hidden()
        val source = team(root.team) ?: hidden()
        if (root.org != source.org) hidden()
        val reviewer = type in setOf("TECHNICAL_READY","NEEDS_INFO","REJECT")
        val allowed = if (reviewer) ctx.actor != root.owner && canReview(ctx.actor,source)
                      else ctx.actor == root.owner && canRequest(ctx.actor,source)
        if (!allowed) hidden()
        if (source.version != root.teamVersion) stale()
        val result = commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_NEED_" + type,
            "PROCUREMENT_SERVICE_NEED",id,
            fingerprint(id,expected,type,target,reason,change?.specification,
                change?.criteria,change?.why),ctx.correlationId,
        )) {
            val lockedRoot = need(id,lock = true) ?: hidden()
            val lockedSource = team(lockedRoot.team,lock = true) ?: hidden()
            if (lockedRoot.org != lockedSource.org ||
                lockedSource.version != lockedRoot.teamVersion) stale()
            if (if (reviewer) ctx.actor == lockedRoot.owner ||
                    !canReview(ctx.actor,lockedSource)
                else ctx.actor != lockedRoot.owner ||
                    !canRequest(ctx.actor,lockedSource)) hidden()
            val old = latest(id)
            val priorVersion = old?.version ?: 1L
            val priorState = old?.state ?: "DRAFT"
            if (priorVersion != expected) stale()
            val valid = when (type) {
                "SUBMIT" -> priorState == "DRAFT"
                "REVISE" -> priorState in setOf("DRAFT","NEEDS_INFO") && change != null
                "WITHDRAW" -> priorState in setOf("DRAFT","SUBMITTED","NEEDS_INFO")
                "TECHNICAL_READY","NEEDS_INFO","REJECT" -> priorState == "SUBMITTED"
                else -> false
            }
            if (!valid) invalid()
            val at = clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_need_event (
                  id,operation_id,organization_id,need_id,source_version,result_version,
                  transition_type,from_state,to_state,reason,
                  revised_specification,revised_acceptance_criteria,revised_justification,
                  actor_user_id,occurred_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                uuid(op),op,lockedRoot.org,id,priorVersion,priorVersion+1,
                type,priorState,target,reason,
                change?.specification,change?.criteria,change?.why,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId=ctx.actor,action="PROCUREMENT_SERVICE_NEED_" + type,
                targetType="PROCUREMENT_SERVICE_NEED",targetId=id,
                occurredAt=at,correlationId=ctx.correlationId,
            ))
            IdempotentCommandOutcome("SERVICE_NEED_" + type, "{\"id\":\"" + id + "\"}")
        }
        return ServicePurchaseNeedReceipt(detail(ctx.actor,id),result.replayed)
    }

    private fun team(id: UUID, lock: Boolean = false): Team? = jdbc.query(
        """
        SELECT t.id,t.organization_id,t.name,t.version
        FROM team t JOIN organization o ON o.id=t.organization_id
        WHERE t.id=? AND t.active=true
          AND o.status='ACTIVE' AND o.organization_type='HILTECH'
        """.trimIndent() + if (lock) " FOR SHARE OF t" else "",
        { rs, _ -> Team(rs.getObject("id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getString("name"),rs.getLong("version")) },id,
    ).singleOrNull()

    private fun need(id: UUID, lock: Boolean = false): Need? = jdbc.query(
        """
        SELECT id,organization_id,team_id,team_version,specification,
               acceptance_criteria,justification,requested_by_user_id
        FROM procurement_service_need WHERE id=?
        """.trimIndent() + if (lock) " FOR UPDATE" else "",
        { rs, _ -> Need(rs.getObject("id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("team_id",UUID::class.java),rs.getLong("team_version"),
            rs.getString("specification"),rs.getString("acceptance_criteria"),
            rs.getString("justification"),rs.getObject("requested_by_user_id",UUID::class.java))
        },id,
    ).singleOrNull()

    private fun latest(id: UUID): Event? = jdbc.query(
        """
        SELECT result_version,to_state,reason FROM procurement_service_need_event
        WHERE need_id=? ORDER BY result_version DESC LIMIT 1
        """.trimIndent(),
        { rs, _ -> Event(rs.getLong("result_version"),
            rs.getString("to_state"),rs.getString("reason")) },id,
    ).singleOrNull()

    private fun revision(id: UUID): Revision? = jdbc.query(
        """
        SELECT revised_specification,revised_acceptance_criteria,revised_justification
        FROM procurement_service_need_event WHERE need_id=? AND transition_type='REVISE'
        ORDER BY result_version DESC LIMIT 1
        """.trimIndent(),
        { rs, _ -> Revision(rs.getString("revised_specification"),
            rs.getString("revised_acceptance_criteria"),
            rs.getString("revised_justification")) },id,
    ).singleOrNull()

    private fun canRequest(actor: UUID, team: Team): Boolean =
        authority(actor,team.org,"hiltech.procurement.service-needs.request-authority-key",requesterKey) &&
        jdbc.queryForObject(
            """
            SELECT EXISTS(SELECT 1 FROM team_membership m
              WHERE m.team_id=? AND m.user_identity_id=?
                AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?))
            """.trimIndent(),Boolean::class.java,
            team.id,actor,clock.instant(),clock.instant(),
        ) == true

    private fun canReview(actor: UUID, team: Team) =
        authority(actor,team.org,"hiltech.procurement.service-needs.review-authority-key",reviewerKey)

    private fun authority(actor: UUID, org: UUID, property: String, rawKey: String): Boolean {
        if (!enabled) return false
        return currentOperationalAuthority(operationalAuthority, approval, actor, org, property, rawKey, clock.instant()) != null
    }

    private fun text(raw: String, min: Int, max: Int): String =
        raw.trim().takeIf { it.length in min..max &&
            it.none { ch -> ch.code < 32 || ch.code == 127 } } ?: invalid()

    private fun fingerprint(vararg values: Any?): String =
        IdempotencyKeyContract.fingerprint(values.joinToString("") {
            val s = it?.toString() ?: "(null)"; s.length.toString() + ":" + s
        })
    private fun uuid(operationId: UUID): UUID = UUID.nameUUIDFromBytes(
        ("hiltech:procurement:service-need:" + operationId).toByteArray(Charsets.UTF_8),
    )
    private fun active() { if (!enabled) hidden() }
    private fun hidden(): Nothing = throw ProductApiException(
        "SERVICE_NEED_UNAVAILABLE","No current authorized external-service purchasing source.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "SERVICE_NEED_SOURCE_CHANGED","Service source or version changed.",
        HttpStatus.CONFLICT,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "SERVICE_NEED_INVALID","Invalid service specification or lifecycle action.",
        HttpStatus.BAD_REQUEST,
    )
}
