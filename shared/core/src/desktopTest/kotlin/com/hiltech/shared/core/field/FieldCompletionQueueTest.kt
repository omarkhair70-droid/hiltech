package com.hiltech.shared.core.field

import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import com.hiltech.shared.core.sync.CommandReplayScope
import com.hiltech.shared.core.sync.CommandTransportResult
import com.hiltech.shared.core.sync.PendingCommandReplayEngine
import com.hiltech.shared.core.sync.PendingCommandTransport
import com.hiltech.shared.core.sync.SyncClock
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldCompletionGateDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FieldCompletionQueueTest {
    private val actorId = "11111111-1111-4111-8111-111111111111"
    private val installId = "22222222-2222-4222-8222-222222222222"
    private val workId = "33333333-3333-4333-8333-333333333333"
    private val assignmentId = "44444444-4444-4444-8444-444444444444"
    private val operationId = "55555555-5555-4555-8555-555555555555"
    private val now = 1_700_000_000_000L

    @Test
    fun durableCompletionPersistsFrozenSourceAndPreventsDuplicate() = runBlocking {
        val dir = Files.createTempDirectory("field-completion-durable").toFile()
        val path = dir.resolve("field.db").absolutePath
        try {
            val db = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                val queue = FieldCompletionQueue(db)
                val saved = queue.queue(
                    identityId = actorId,
                    installationId = installId,
                    bundle = bundle(),
                    operationId = operationId,
                    nowEpochMs = now,
                    verifiedOnlineThisSession = true,
                )
                assertEquals(PendingCommandStates.PENDING, saved.state)
                val row = assertNotNull(db.pendingCommandDao().get(operationId))
                assertEquals("SubmitWorkCompletion", row.commandType)
                assertEquals(workId, row.targetId)
                assertEquals(11L, row.baseVersion)
                assertEquals("binding:2", row.policyBindingRef)
                assertEquals(
                    "{\"assignmentId\":\"$assignmentId\",\"assignmentVersion\":3,\"bindingRevision\":2}",
                    row.payloadJson,
                )
                assertEquals(operationId, db.pendingCommandDao().outstandingCompletion(actorId, workId).single().operationId)
                assertEquals(operationId, FieldExecutionQueue(db).outstanding(actorId, workId)?.operationId)
                assertFailsWith<IllegalArgumentException> {
                    queue.queue(
                        actorId, installId, bundle(),
                        "66666666-6666-4666-8666-666666666666",
                        now + 10, true,
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    FieldExecutionQueue(db).queue(
                        actorId, installId, bundle(lifecycle = "IN_PROGRESS"),
                        FieldExecutionIntent.BLOCK,
                        "77777777-7777-4777-8777-777777777777",
                        now + 11, true,
                        explanation = "Damaged route",
                    )
                }
            } finally {
                db.close()
            }
            val reopened = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                assertEquals(
                    PendingCommandStates.PENDING,
                    reopened.pendingCommandDao().get(operationId)?.state,
                )
            } finally {
                reopened.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnverifiedOrUnsatisfiedServerSource() = runBlocking {
        withDb("completion-gate-reject") { db ->
            val queue = FieldCompletionQueue(db)
            assertFailsWith<IllegalArgumentException> {
                queue.queue(actorId, installId, bundle(), operationId, now, false)
            }
            assertFailsWith<IllegalArgumentException> {
                queue.queue(actorId, installId, bundle(satisfied = false), operationId, now, true)
            }
            assertFailsWith<IllegalArgumentException> {
                queue.queue(actorId, installId, bundle(submitAllowed = false), operationId, now, true)
            }
            assertEquals(null, db.pendingCommandDao().get(operationId))
        }
    }

    @Test
    fun genericEarlyReplayCannotConsumeSubmissionBeforeLateStage() = runBlocking {
        withDb("completion-replay-order") { db ->
            FieldCompletionQueue(db).queue(
                actorId, installId, bundle(), operationId, now, true,
            )
            val original = assertNotNull(db.pendingCommandDao().get(operationId))
            val other = "88888888-8888-4888-8888-888888888888"
            db.pendingCommandDao().insert(
                original.copy(
                    operationId = other,
                    commandType = "StartWork",
                    targetId = "99999999-9999-4999-8999-999999999999",
                    localSequence = original.localSequence + 1,
                ),
            )

            val applied = mutableListOf<String>()
            val transport = PendingCommandTransport {
                applied += it.commandType
                CommandTransportResult.Applied(currentVersion = 12, correlationId = "test")
            }
            val replayClock = SyncClock { now + 1000 }
            val scope = CommandReplayScope(actorId, installId)
            val first = PendingCommandReplayEngine(
                database = db,
                transport = transport,
                clock = replayClock,
                replayScope = scope,
                excludeCommandTypes = setOf("SubmitWorkCompletion"),
            ).replayOnce()

            assertEquals(1, first.applied)
            assertEquals(listOf("StartWork"), applied)
            assertEquals(
                PendingCommandStates.PENDING,
                db.pendingCommandDao().get(operationId)?.state,
            )

            val last = PendingCommandReplayEngine(
                database = db,
                transport = transport,
                clock = replayClock,
                replayScope = scope,
                includeCommandTypes = setOf("SubmitWorkCompletion"),
            ).replayOnce()

            assertEquals(1, last.applied)
            assertEquals(
                listOf("StartWork", "SubmitWorkCompletion"),
                applied,
            )
            assertEquals(
                PendingCommandStates.APPLIED,
                db.pendingCommandDao().get(operationId)?.state,
            )
        }
    }

    private suspend fun <T> withDb(
        prefix: String,
        body: suspend (HiltechLocalDatabase) -> T,
    ): T {
        val dir = Files.createTempDirectory(prefix).toFile()
        val db = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(dir.resolve("field.db").absolutePath),
        )
        return try {
            body(db)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun bundle(
        lifecycle: String = "IN_PROGRESS",
        satisfied: Boolean = true,
        submitAllowed: Boolean = true,
    ): TechnicianJobBundleDto {
        val ref = FieldConfigRefDto(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "CONFIG", "Config", 1,
        )
        return TechnicianJobBundleDto(
            workOrderId = workId,
            workOrderCode = "WO-11",
            title = "Fiber installation",
            lifecycleState = lifecycle,
            readinessState = "READY",
            workOrderVersion = 11,
            project = FieldProjectDto(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "PRJ", "Project", "ACTIVE", 1,
            ),
            site = FieldSiteDto(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "SITE", "Site",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                "PS", "ACTIVE",
            ),
            assignment = FieldAssignmentDto(
                assignmentId, "USER", actorId, "Tech",
                "2026-11-21T01:00:00Z", version = 3,
            ),
            binding = FieldWorkBindingDto(
                bindingRevision = 2,
                workType = ref,
                assignmentPolicy = ref,
                readinessPolicy = ref,
                evidencePolicy = ref,
                reviewPolicy = ref,
            ),
            executionCapabilities = FieldExecutionCapabilitiesDto(
                startOffline = true,
                blockOffline = true,
                resumeOffline = true,
                evidenceCaptureOffline = true,
                submitOffline = submitAllowed,
                authoritativeOfflineQueue = false,
            ),
            completionGate = FieldCompletionGateDto(
                available = true,
                satisfied = satisfied,
                reasonCodes = if (satisfied) emptyList() else listOf("EVIDENCE_MISSING"),
            ),
            asOf = "2026-11-21T06:00:00Z",
        )
    }
}
