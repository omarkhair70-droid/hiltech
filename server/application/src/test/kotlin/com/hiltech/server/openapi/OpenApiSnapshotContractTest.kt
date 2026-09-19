package com.hiltech.server.openapi

import com.hiltech.server.activity.WorkOrderActivityController
import com.hiltech.server.activity.WorkOrderActivityService
import com.hiltech.server.approval.ApprovalController
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalReadService
import com.hiltech.server.documents.EvidenceLifecycleController
import com.hiltech.server.documents.EvidenceLifecycleService
import com.hiltech.server.identity.IdentityBootstrapController
import com.hiltech.server.identity.IdentityRuntimeRepository
import com.hiltech.server.identity.IdentitySessionSecurityController
import com.hiltech.server.identity.IdentitySessionSecurityService
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.organizations.OrganizationIdentityContextPort
import com.hiltech.server.inbox.InboxController
import com.hiltech.server.inbox.InboxService
import com.hiltech.server.inbox.WorkQueueController
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path

@EnabledIfEnvironmentVariable(
    named = "HILTECH_OPENAPI_CONTRACT_TEST",
    matches = "1",
)
@SpringBootTest(
    classes = [
        OpenApiContractTestApplication::class,
    ],
    properties = [
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.version=OPENAPI_3_1",
        "spring.main.banner-mode=off",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration," +
            "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration," +
            "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration",
    ],
)
@AutoConfigureMockMvc(addFilters = false)
class OpenApiSnapshotContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var activityService:
        WorkOrderActivityService

    @MockitoBean
    lateinit var approvalReadService:
        ApprovalReadService

    @MockitoBean
    lateinit var approvalEngineService:
        ApprovalEngineService

    @MockitoBean
    lateinit var evidenceLifecycleService:
        EvidenceLifecycleService

    @MockitoBean
    lateinit var identityRuntimeRepository:
        IdentityRuntimeRepository

    @MockitoBean
    lateinit var organizationIdentityContext:
        OrganizationIdentityContextPort

    @MockitoBean
    lateinit var identitySessionService:
        IdentitySessionService

    @MockitoBean
    lateinit var identitySessionSecurityService:
        IdentitySessionSecurityService

    @MockitoBean
    lateinit var inboxService:
        InboxService

    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    @Test
    fun generatedOpenApiMatchesCommittedSnapshot() {
        val raw =
            mockMvc.perform(
                get("/v3/api-docs"),
            )
                .andExpect(
                    status().isOk,
                )
                .andReturn()
                .response
                .contentAsString

        val normalizedElement =
            normalize(
                Json.parseToJsonElement(raw),
            )
        val normalized =
            json.encodeToString(
                JsonElement.serializer(),
                normalizedElement,
            ) + "\n"

        val root =
            normalizedElement as JsonObject
        val openApiVersion =
            root["openapi"]
                ?.toString()
                ?.trim('"')
                .orEmpty()
        assertTrue(
            openApiVersion.startsWith("3.1"),
            "The generated review contract must be OpenAPI 3.1; got $openApiVersion.",
        )

        val paths =
            root["paths"] as? JsonObject
                ?: error(
                    "Generated OpenAPI has no paths object.",
                )

        listOf(
            "/v1/me/bootstrap",
            "/v1/me/device",
            "/v1/me/reauth/complete",
            "/v1/me/sessions",
            "/v1/evidence/reservations",
            "/v1/work-orders/{workOrderId}/activity",
            "/v1/approvals/assigned",
            "/v1/work-queue",
            "/v1/inbox",
        ).forEach { route ->
            assertTrue(
                route in paths,
                "Generated OpenAPI is missing production route $route.",
            )
        }

        val output =
            Path.of(
                System.getenv(
                    "HILTECH_OPENAPI_OUTPUT",
                ) ?: "build/reports/openapi/hiltech-v1.openapi.json",
            )
        Files.createDirectories(
            output.parent,
        )
        Files.writeString(
            output,
            normalized,
        )

        if (
            System.getenv(
                "HILTECH_OPENAPI_COMPARE",
            ) == "1"
        ) {
            val snapshot =
                Path.of(
                    System.getenv(
                        "HILTECH_OPENAPI_SNAPSHOT",
                    ) ?: "contracts/http/hiltech-v1.openapi.json",
                )
            assertTrue(
                Files.isRegularFile(snapshot),
                "Committed OpenAPI snapshot is missing: $snapshot",
            )
            assertEquals(
                Files.readString(snapshot),
                normalized,
                "Generated OpenAPI drifted from the committed contract snapshot.",
            )
        }
    }

    private fun normalize(
        element: JsonElement,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.entries
                        .sortedBy { it.key }
                        .associate {
                            it.key to
                                normalize(it.value)
                        },
                )

            is JsonArray ->
                JsonArray(
                    element.map(::normalize),
                )

            else ->
                element
        }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    WorkOrderActivityController::class,
    ApprovalController::class,
    EvidenceLifecycleController::class,
    IdentityBootstrapController::class,
    IdentitySessionSecurityController::class,
    WorkQueueController::class,
    InboxController::class,
)
class OpenApiContractTestApplication
