package com.hiltech.server.security

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcAuthorizationProjectionIntentWriter(
    private val jdbc: JdbcTemplate,
    private val properties: HiltechOpenFgaProperties,
) : AuthorizationProjectionIntentWriter {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun write(intent: AuthorizationProjectionIntent) {
        properties.validateEnabledConfiguration()

        val now = intent.occurredAt.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO authorization_relation_projection (
                relation_key,
                subject_type,
                subject_id,
                relation,
                object_type,
                object_id,
                desired_state,
                source_type,
                source_id,
                source_version,
                projection_state,
                authorization_model_id,
                last_attempt_at,
                applied_at,
                last_error_code,
                retry_count,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL, NULL, NULL, 0, ?, ?)
            ON CONFLICT (relation_key) DO UPDATE SET
                subject_type = EXCLUDED.subject_type,
                subject_id = EXCLUDED.subject_id,
                relation = EXCLUDED.relation,
                object_type = EXCLUDED.object_type,
                object_id = EXCLUDED.object_id,
                desired_state = EXCLUDED.desired_state,
                source_type = EXCLUDED.source_type,
                source_id = EXCLUDED.source_id,
                source_version = EXCLUDED.source_version,
                projection_state = 'PENDING',
                authorization_model_id = EXCLUDED.authorization_model_id,
                last_attempt_at = NULL,
                applied_at = NULL,
                last_error_code = NULL,
                retry_count = 0,
                updated_at = EXCLUDED.updated_at
            WHERE authorization_relation_projection.source_version <= EXCLUDED.source_version
            """.trimIndent(),
            intent.tuple.relationKey,
            intent.tuple.subjectType,
            intent.tuple.subjectId,
            intent.tuple.relation,
            intent.tuple.objectType,
            intent.tuple.objectId,
            intent.desiredState.name,
            intent.sourceType,
            intent.sourceId,
            intent.sourceVersion,
            properties.authorizationModelId,
            now,
            now,
        )

        jdbc.update(
            """
            INSERT INTO authorization_projection_outbox (
                id,
                relation_key,
                source_version,
                event_type,
                created_at,
                claimed_at,
                completed_at,
                attempt_count,
                last_error_code
            )
            VALUES (?, ?, ?, ?, ?, NULL, NULL, 0, NULL)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            intent.eventId,
            intent.tuple.relationKey,
            intent.sourceVersion,
            intent.eventType,
            now,
        )
    }
}

@Component
class JdbcAuthorizationProjectionGuard(
    private val jdbc: JdbcTemplate,
) : AuthorizationProjectionGuardPort {
    override fun evaluate(tuple: OpenFgaTuple): ProjectionGuardDecision {
        val rows = jdbc.query(
            """
            SELECT desired_state, projection_state
            FROM authorization_relation_projection
            WHERE relation_key = ?
            """.trimIndent(),
            { rs, _ ->
                rs.getString("desired_state") to
                    rs.getString("projection_state")
            },
            tuple.relationKey,
        )

        val state = rows.singleOrNull()
            ?: return ProjectionGuardDecision.PROCEED_TO_OPENFGA

        val desiredState = AuthorizationDesiredState.valueOf(state.first)
        val projectionState = AuthorizationProjectionState.valueOf(state.second)

        return when {
            projectionState == AuthorizationProjectionState.FAILED ->
                ProjectionGuardDecision.DENY_FAIL_CLOSED

            desiredState == AuthorizationDesiredState.PRESENT &&
                projectionState != AuthorizationProjectionState.APPLIED ->
                ProjectionGuardDecision.DENY_FAIL_CLOSED

            desiredState == AuthorizationDesiredState.ABSENT &&
                projectionState != AuthorizationProjectionState.APPLIED ->
                ProjectionGuardDecision.DENY_FAIL_CLOSED

            else ->
                ProjectionGuardDecision.PROCEED_TO_OPENFGA
        }
    }
}

@Component
class JdbcAuthorizationProjectionStore(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val properties: HiltechOpenFgaProperties,
    private val retryPolicy: AuthorizationProjectionRetryPolicy,
) : AuthorizationProjectionStore {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun claimNext(now: Instant): AuthorizationProjectionWork? =
        transactionTemplate.execute {
            val leaseCutoff = now
                .minusSeconds(properties.claimLeaseSeconds)
                .atOffset(ZoneOffset.UTC)

            val candidates = jdbc.query(
                """
                SELECT
                    o.id AS outbox_id,
                    o.source_version AS outbox_source_version,
                    p.relation_key,
                    p.subject_type,
                    p.subject_id,
                    p.relation,
                    p.object_type,
                    p.object_id,
                    p.desired_state,
                    p.source_type,
                    p.source_id,
                    p.source_version,
                    p.projection_state,
                    p.authorization_model_id,
                    p.last_attempt_at,
                    p.retry_count
                FROM authorization_projection_outbox o
                JOIN authorization_relation_projection p
                  ON p.relation_key = o.relation_key
                WHERE o.completed_at IS NULL
                  AND p.projection_state <> 'FAILED'
                  AND (o.claimed_at IS NULL OR o.claimed_at < ?)
                ORDER BY o.created_at ASC
                LIMIT 50
                FOR UPDATE OF o, p SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> rs.toProjectionWork() },
                leaseCutoff,
            )

            val work = candidates.firstOrNull { candidate ->
                candidate.outboxSourceVersion != candidate.projection.sourceVersion ||
                    candidate.projection.projectionState == AuthorizationProjectionState.APPLIED ||
                    retryPolicy.isDue(candidate.projection, now)
            } ?: return@execute null

            val claimed = jdbc.update(
                """
                UPDATE authorization_projection_outbox
                SET claimed_at = ?,
                    attempt_count = attempt_count + 1
                WHERE id = ?
                  AND completed_at IS NULL
                """.trimIndent(),
                now.atOffset(ZoneOffset.UTC),
                work.outboxId,
            )

            if (claimed != 1) {
                return@execute null
            }

            if (
                work.outboxSourceVersion == work.projection.sourceVersion &&
                work.projection.projectionState != AuthorizationProjectionState.APPLIED
            ) {
                jdbc.update(
                    """
                    UPDATE authorization_relation_projection
                    SET projection_state = 'APPLYING',
                        last_attempt_at = ?,
                        updated_at = ?
                    WHERE relation_key = ?
                      AND source_version = ?
                    """.trimIndent(),
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC),
                    work.projection.relationKey,
                    work.projection.sourceVersion,
                )
            }

            work.copy(
                projection = work.projection.copy(
                    projectionState = if (
                        work.outboxSourceVersion == work.projection.sourceVersion &&
                        work.projection.projectionState != AuthorizationProjectionState.APPLIED
                    ) {
                        AuthorizationProjectionState.APPLYING
                    } else {
                        work.projection.projectionState
                    },
                    lastAttemptAt = if (
                        work.outboxSourceVersion == work.projection.sourceVersion &&
                        work.projection.projectionState != AuthorizationProjectionState.APPLIED
                    ) {
                        now
                    } else {
                        work.projection.lastAttemptAt
                    },
                ),
            )
        }

    override fun completeOutbox(
        work: AuthorizationProjectionWork,
        now: Instant,
        resultCode: String,
    ) {
        jdbc.update(
            """
            UPDATE authorization_projection_outbox
            SET completed_at = ?,
                last_error_code = ?
            WHERE id = ?
              AND completed_at IS NULL
            """.trimIndent(),
            now.atOffset(ZoneOffset.UTC),
            resultCode,
            work.outboxId,
        )
    }

    override fun markApplied(
        work: AuthorizationProjectionWork,
        now: Instant,
    ) {
        transactionTemplate.executeWithoutResult {
            val updatedProjection = jdbc.update(
                """
                UPDATE authorization_relation_projection
                SET projection_state = 'APPLIED',
                    applied_at = ?,
                    last_error_code = NULL,
                    updated_at = ?
                WHERE relation_key = ?
                  AND source_version = ?
                """.trimIndent(),
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                work.projection.relationKey,
                work.projection.sourceVersion,
            )

            completeOutbox(
                work = work,
                now = now,
                resultCode = if (updatedProjection == 1) {
                    "APPLIED"
                } else {
                    "SUPERSEDED_AFTER_FGA_APPLY"
                },
            )
        }
    }

    override fun markRetryable(
        work: AuthorizationProjectionWork,
        now: Instant,
        errorCode: String,
    ) {
        transactionTemplate.executeWithoutResult {
            val updatedProjection = jdbc.update(
                """
                UPDATE authorization_relation_projection
                SET projection_state = 'PENDING',
                    retry_count = retry_count + 1,
                    last_error_code = ?,
                    updated_at = ?
                WHERE relation_key = ?
                  AND source_version = ?
                """.trimIndent(),
                errorCode,
                now.atOffset(ZoneOffset.UTC),
                work.projection.relationKey,
                work.projection.sourceVersion,
            )

            if (updatedProjection == 1) {
                jdbc.update(
                    """
                    UPDATE authorization_projection_outbox
                    SET claimed_at = NULL,
                        last_error_code = ?
                    WHERE id = ?
                      AND completed_at IS NULL
                    """.trimIndent(),
                    errorCode,
                    work.outboxId,
                )
            } else {
                completeOutbox(
                    work = work,
                    now = now,
                    resultCode = "SUPERSEDED_BEFORE_RETRY",
                )
            }
        }
    }

    override fun markFailed(
        work: AuthorizationProjectionWork,
        now: Instant,
        errorCode: String,
    ) {
        transactionTemplate.executeWithoutResult {
            jdbc.update(
                """
                UPDATE authorization_relation_projection
                SET projection_state = 'FAILED',
                    retry_count = retry_count + 1,
                    last_error_code = ?,
                    updated_at = ?
                WHERE relation_key = ?
                  AND source_version = ?
                """.trimIndent(),
                errorCode,
                now.atOffset(ZoneOffset.UTC),
                work.projection.relationKey,
                work.projection.sourceVersion,
            )

            completeOutbox(
                work = work,
                now = now,
                resultCode = errorCode,
            )
        }
    }

    private fun ResultSet.toProjectionWork(): AuthorizationProjectionWork {
        val tuple = OpenFgaTuple(
            subjectType = getString("subject_type"),
            subjectId = getString("subject_id"),
            relation = getString("relation"),
            objectType = getString("object_type"),
            objectId = getString("object_id"),
        )

        return AuthorizationProjectionWork(
            outboxId = getObject("outbox_id", UUID::class.java),
            outboxSourceVersion = getLong("outbox_source_version"),
            projection = AuthorizationRelationProjection(
                relationKey = getString("relation_key"),
                tuple = tuple,
                desiredState = AuthorizationDesiredState.valueOf(
                    getString("desired_state"),
                ),
                sourceType = getString("source_type"),
                sourceId = getString("source_id"),
                sourceVersion = getLong("source_version"),
                projectionState = AuthorizationProjectionState.valueOf(
                    getString("projection_state"),
                ),
                authorizationModelId = getString("authorization_model_id"),
                lastAttemptAt = getObject(
                    "last_attempt_at",
                    OffsetDateTime::class.java,
                )?.toInstant(),
                retryCount = getInt("retry_count"),
            ),
        )
    }
}
