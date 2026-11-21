package com.hiltech.shared.core.work

import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldOfflineReadCacheTest {
    @Test
    fun cachedSafeTodayAndBundleSurviveRestartAndCannotCrossIdentity() = runBlocking {
        val dir = Files.createTempDirectory("hiltech-phase6-cache").toFile()
        val path = dir.resolve("hiltech.db").absolutePath
        val actorA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val actorB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val workId = "11111111-1111-4111-8111-111111111111"
        val project = FieldProjectDto("22222222-2222-4222-8222-222222222222", "P-1", "Project", "ACTIVE", 2)
        val site = FieldSiteDto("33333333-3333-4333-8333-333333333333", "S-1", "Site", "44444444-4444-4444-8444-444444444444", lifecycleState = "ACTIVE")
        val assignment = FieldAssignmentDto(
            "55555555-5555-4555-8555-555555555555", "USER", actorA, "Tech",
            "2026-11-20T10:00:00Z", version = 7,
        )
        val today = FieldTodayItemDto(
            workOrderId = workId, workOrderCode = "WO-1", title = "Fiber job",
            lifecycleState = "ASSIGNED", readinessState = "READY", priorityCode = "HIGH",
            project = project, site = site, assignment = assignment, workOrderVersion = 11,
            bundleAsOf = "2026-11-21T04:00:00Z", currentlyActionable = true,
        )
        val bundle = TechnicianJobBundleDto(
            workOrderId = workId, workOrderCode = "WO-1", title = "Fiber job",
            lifecycleState = "ASSIGNED", readinessState = "READY", workOrderVersion = 11,
            project = project, site = site, assignment = assignment,
            executionCapabilities = FieldExecutionCapabilitiesDto(true, true, true, true, true, true),
            asOf = "2026-11-21T04:00:00Z",
        )
        try {
            val first = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                val cache = FieldOfflineReadCache(first, nowEpochMs = { 1_700_000_000_000L })
                cache.saveToday(actorA, listOf(today))
                cache.saveBundle(actorA, bundle)
                assertNull(cache.cachedToday(actorB))
                assertNull(cache.cachedBundle(actorB, workId))
                assertFalse(assertNotNull(cache.cachedToday(actorA)).value.single().currentlyActionable)
            } finally {
                first.close()
            }
            val reopened = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                val cache = FieldOfflineReadCache(reopened)
                val savedToday = assertNotNull(cache.cachedToday(actorA))
                val savedBundle = assertNotNull(cache.cachedBundle(actorA, workId))
                assertEquals("WO-1", savedToday.value.single().workOrderCode)
                assertEquals(1_700_000_000_000L, savedToday.fetchedAtEpochMs)
                assertEquals(11L, savedBundle.value.workOrderVersion)
                assertEquals(7L, savedBundle.value.assignment.version)
                assertTrue(savedBundle.cached)
                assertFalse(savedBundle.value.executionCapabilities.startOffline)
                assertFalse(savedBundle.value.executionCapabilities.authoritativeOfflineQueue)
                assertNull(cache.cachedBundle(actorB, workId))
            } finally {
                reopened.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
