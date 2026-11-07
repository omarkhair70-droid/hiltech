package com.hiltech.server.warehouse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

internal object LegacyInventorySource {
    private val inventoryColumns = setOf(
        "ITEM#", "DESCRPTION", "DESCRIPTION", "BRAND", "PART NUMBER", "DESC", "UOM", "QNTY",
    )
    private val accessoriesColumns = setOf("رقم", "اسم الصنف", "العدد", "الرقم التسلسلي")

    fun preview(
        sourceType: InventoryImportSourceType,
        row: InventoryImportRowSnapshot,
    ): Map<String, String> {
        if (sourceType != InventoryImportSourceType.LEGACY_EXCEL) return emptyMap()
        val columns = when (row.sourceSheet.trim().uppercase(Locale.ROOT)) {
            "MAIN", "FIBER", "SAMPLE" -> inventoryColumns
            "أكسسوارات", "ACCESSORIES" -> accessoriesColumns
            else -> return emptyMap()
        }
        val payload = Json.parseToJsonElement(row.rawPayloadJson) as? JsonObject ?: return emptyMap()
        return payload.entries.mapNotNull { (label, cell) ->
            if (label.trim().uppercase(Locale.ROOT) !in columns) return@mapNotNull null
            val value = (cell as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            label to value.take(512)
        }.toMap()
    }

    fun issues(
        sourceType: InventoryImportSourceType,
        sourceSheet: String,
        rawPayloadJson: String,
    ): Set<InventoryImportIssueCode> {
        val payload = Json.parseToJsonElement(rawPayloadJson) as? JsonObject ?: return emptySet()
        val cells = payload.entries.associate { (label, value) ->
            label.trim().uppercase(Locale.ROOT) to (value as? JsonPrimitive)?.contentOrNull?.trim()
        }
        return buildSet {
            if (cells.keys.any { it.replace(" ", "").replace("_", "") in serialLabels }) {
                add(InventoryImportIssueCode.LEGACY_COLUMN_SEMANTICS_AMBIGUOUS)
            }
            if (sourceType != InventoryImportSourceType.LEGACY_EXCEL) return@buildSet

            val legacyDescription = cells["DESCRPTION"] ?: cells["DESCRIPTION"]
            val itemDescription = cells["DESC"]
            if (!legacyDescription.isNullOrBlank() && !itemDescription.isNullOrBlank() &&
                !legacyDescription.equals(itemDescription, ignoreCase = true)
            ) {
                add(InventoryImportIssueCode.DESCRIPTION_CONFLICT)
            }
            if (sourceSheet.trim().equals("FIBER", ignoreCase = true) &&
                cells["UOM"].equals("PC", ignoreCase = true) &&
                sequenceOf(itemDescription, legacyDescription)
                    .any { it?.contains("cable", ignoreCase = true) == true }
            ) {
                add(InventoryImportIssueCode.UNKNOWN_UOM)
            }
            if (cells.any { (label, value) -> label in legacyDateLabels && !value.isNullOrBlank() }) {
                add(InventoryImportIssueCode.LEGACY_DATE_SEMANTICS_AMBIGUOUS)
            }
        }
    }

    private val serialLabels = setOf("SERIAL", "SERIALNUMBER", "الرقمالتسلسلي")
    private val legacyDateLabels = setOf("تاريخ الفاتوره", "تاريخ الفاتورة", "تاريخ الخروج")
}
