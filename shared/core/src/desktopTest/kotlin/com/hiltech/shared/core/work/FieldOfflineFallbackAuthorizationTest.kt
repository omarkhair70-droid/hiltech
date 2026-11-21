package com.hiltech.shared.core.work

import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldOfflineFallbackAuthorizationTest {
    @Test
    fun cacheIsUsedOnlyForNetworkUnavailableAndNeverBypassesServerDenial() = runBlocking {
        val dir = Files.createTempDirectory("hiltech-field-offline-auth").toFile()
        val database = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(dir.resolve("hiltech.db").absolutePath),
        )
        try {
            val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val work = "11111111-1111-4111-8111-111111111111"
            val project = FieldProjectDto(
                "22222222-2222-4222-8222-222222222222",
                "P-01", "Project", "ACTIVE", 1,
            )
            val site = FieldSiteDto(
                "33333333-3333-4333-8333-333333333333",
                "S-01", "Site", "44444444-4444-4444-8444-444444444444",
                lifecycleState = "ACTIVE",
            )
            val assignment = FieldAssignmentDto(
                "55555555-5555-4555-8555-555555555555",
                "USER", actor, "Technician",
                "2026-11-21T03:00:00Z", version = 3,
            )
            val item = FieldTodayItemDto(
                workOrderId = work, workOrderCode = "WO-01", title = "Field work",
                lifecycleState = "ASSIGNED", readinessState = "READY",
                priorityCode = "HIGH", project = project, site = site,
                assignment = assignment, workOrderVersion = 4,
                bundleAsOf = "2026-11-21T03:10:00Z", currentlyActionable = true,
            )
            val bundle = TechnicianJobBundleDto(
                workOrderId = work, workOrderCode = "WO-01", title = "Field work",
                lifecycleState = "ASSIGNED", readinessState = "READY",
                workOrderVersion = 4, project = project, site = site,
                assignment = assignment,
                executionCapabilities = FieldExecutionCapabilitiesDto(
                    true, true, true, true, true, true,
                ),
                asOf = "2026-11-21T03:10:00Z",
            )
            val cache = FieldOfflineReadCache(database)
            cache.saveToday(actor, listOf(item))
            cache.saveBundle(actor, bundle)

            fun repository(status: HttpStatusCode?): FieldAssignedWorkOfflineRepository {
                val client = FieldAssignedWorkApiClient(
                    HiltechApiClient(
                        client = HttpClient(MockEngine {
                            if (status == null) error("Offline transport")
                            respond("", status = status)
                        }),
                        baseUrl = "https://api.hiltech.test",
                        accessTokenProvider = { "verified-session" },
                        correlationIdProvider = { "field-correlation" },
                    ),
                )
                return FieldAssignedWorkOfflineRepository(client, cache)
            }

            val network = repository(null)
            val offlineToday = network.today(actor, "installation")
            assertTrue(offlineToday.cached)
            assertEquals("WO-01", offlineToday.value.single().workOrderCode)
            assertFalse(offlineToday.value.single().currentlyActionable)
            val offlineBundle = network.jobBundle(actor, work, "installation")
            assertTrue(offlineBundle.cached)
            assertFalse(offlineBundle.value.executionCapabilities.startOffline)
            assertFalse(offlineBundle.value.executionCapabilities.submitOffline)

            for (denial in listOf(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
                HttpStatusCode.NotFound,
            )) {
                val denied = repository(denial)
                val todayError = assertFailsWith<HiltechApiException> {
                    denied.today(actor, "installation")
                }
                assertEquals(denial.value, todayError.httpStatus)
                val bundleError = assertFailsWith<HiltechApiException> {
                    denied.jobBundle(actor, work, "installation")
                }
                assertEquals(denial.value, bundleError.httpStatus)
            }
        } finally {
            database.close()
            dir.deleteRecursively()
        }
    }
}
