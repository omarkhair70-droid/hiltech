package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiErrorWriter
import com.hiltech.server.platform.ProductApiExceptionHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.util.UUID

/** Exercises HTTP handlers with synthetic rows using the integration test's PostgreSQL and OpenFGA. */
internal class WarehouseImportOperatorScenario(
    persistence: WarehousePersistencePort,
    authorization: WarehouseAuthorizationPort,
    idempotency: IdempotentCommandExecutor,
    audit: AuditEventWriter,
    private val clock: Clock,
) {
    private val mvc = MockMvcBuilders.standaloneSetup(
        WarehouseImportCreateController(WarehouseImportCreateService(persistence, authorization, idempotency, audit, clock)),
        WarehouseImportRowCreateController(WarehouseImportRowCreateService(persistence, authorization, idempotency, audit, clock)),
        WarehouseImportReadController(WarehouseImportReadService(persistence, authorization)),
        WarehouseImportRowReviewController(WarehouseImportRowReviewService(persistence, authorization, idempotency, audit, clock)),
    ).setControllerAdvice(ProductApiExceptionHandler(ProductApiErrorWriter())).build()
    private val sessionId = UUID.randomUUID()

    fun stageAndReview(organizationId: UUID, actor: UUID): String {
        val layouts = listOf(
            Layout("Main", """{"PART NUMBER":"NONE","تاريخ الخروج":"2026-01-01","UNIT PRICE":"hidden"}""",
                "PART NUMBER", "NONE", "LEGACY_DATE_SEMANTICS_AMBIGUOUS"),
            Layout("FIBER", """{"DESC":"Synthetic fiber cable","UOM":"PC","UNIT PRICE":"hidden"}""",
                "UOM", "PC", "UNKNOWN_UOM"),
            Layout("SAMPLE", """{"DESC":"Synthetic switch","DESCRPTION":"Template cable","UNIT PRICE":"hidden"}""",
                "DESC", "Synthetic switch", "DESCRIPTION_CONFLICT"),
            Layout("أكسسوارات", """{"الرقم التسلسلي":"LOCAL","العدد":"0","السعر الإجمالي":"hidden"}""",
                "الرقم التسلسلي", "LOCAL", "LEGACY_COLUMN_SEMANTICS_AMBIGUOUS"),
        )
        return layouts.mapIndexed { index, layout ->
            val digest = (index + 1).toString().repeat(64)
            val batch = command("/v1/inventory-imports", actor, mapOf(
                "organizationId" to organizationId.toString(),
                "sourceFileName" to "synthetic-warehouse-${index + 1}.xlsx",
                "sourceSha256" to digest,
                "sourceType" to "LEGACY_EXCEL",
            )).andExpect(status().is2xxSuccessful()).andReturn()
            val batchId = Json.parseToJsonElement(batch.response.contentAsString)
                .jsonObject.getValue("batchId").jsonPrimitive.content
            val rowOperation = UUID.randomUUID()
            val stagedFields = mapOf("sourceSheet" to layout.sheet, "rawPayloadJson" to layout.payload)
            val row = command("/v1/inventory-imports/$batchId/rows", actor, stagedFields, rowOperation, "sourceRowNumber" to 7)
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.issueCodes[?(@ == '${layout.issue}')]").isNotEmpty())
                .andReturn()
            val rowId = Json.parseToJsonElement(row.response.contentAsString)
                .jsonObject.getValue("rowId").jsonPrimitive.content
            command("/v1/inventory-imports/$batchId/rows", actor, stagedFields, rowOperation, "sourceRowNumber" to 7)
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.rowId").value(rowId))
                .andExpect(jsonPath("$.replayed").value(true))

            val review = mapOf(
                "candidateClass" to "STOCK_QUANTITY", "candidateUom" to "PC",
                "reviewState" to "NEEDS_REVIEW", "reviewNotes" to "Synthetic candidate reviewed.",
            )
            command("/v1/inventory-imports/$batchId/rows/$rowId/review", actor, review, integer = "baseVersion" to 1)
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.issueCodes[?(@ == '${layout.issue}')]").isNotEmpty())
            command("/v1/inventory-imports/$batchId/rows/$rowId/review", actor,
                review + ("reviewState" to "APPROVED"), integer = "baseVersion" to 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_ROW_REVIEW_BLOCKED"))
            detail(batchId, actor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceSha256").value(digest))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].sourceRowNumber").value(7))
                .andExpect(jsonPath("$.rows[0].sourcePreview['${layout.visibleColumn}']").value(layout.visibleValue))
                .andExpect(jsonPath("$.rows[0].sourcePreview['UNIT PRICE']").doesNotExist())
                .andExpect(jsonPath("$.rows[0].sourcePreview['السعر الإجمالي']").doesNotExist())
                .andExpect(jsonPath("$.rows[0].rawPayloadJson").doesNotExist())
            batchId
        }.last()
    }

    fun assertHidden(batchId: String, actor: UUID) {
        detail(batchId, actor)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INVENTORY_IMPORT_NOT_FOUND"))
    }

    private fun detail(batchId: String, actor: UUID) = mvc.perform(
        get("/v1/inventory-imports/$batchId").with {
            HiltechRequestContext.bindAuthenticatedIdentity(it, actor, sessionId)
            it
        },
    )

    private fun command(
        path: String,
        actor: UUID,
        fields: Map<String, String>,
        operationId: UUID = UUID.randomUUID(),
        integer: Pair<String, Int>? = null,
    ) = mvc.perform(
        post(path)
            .header("Idempotency-Key", operationId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(buildJsonObject {
                put("operationId", operationId.toString())
                put("clientOccurredAt", clock.instant().toString())
                fields.forEach { (key, value) -> put(key, value) }
                integer?.let { (key, value) -> put(key, value) }
            }.toString())
            .with {
                HiltechRequestContext.bindAuthenticatedIdentity(it, actor, sessionId)
                it
            },
    )

    private data class Layout(
        val sheet: String,
        val payload: String,
        val visibleColumn: String,
        val visibleValue: String,
        val issue: String,
    )
}
