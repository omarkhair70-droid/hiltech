package com.hiltech.shared.core.executive

/**
 * The server provides provenance, not arbitrary navigation privileges.
 * Shell integrations must resolve references through this allowlist, then
 * open source-owned screens that fetch detail using current authentication.
 *
 * This deliberately rejects external URLs, query strings, unknown domains,
 * mismatched source IDs, and guessed financial/payroll destinations.
 */
enum class ExecutiveDestinationKind {
    APPROVAL_DETAIL,
    PROJECT_DETAIL,
    PROJECT_HEALTH,
    ASSET_AVAILABILITY,
}

data class ExecutiveDestination(
    val kind: ExecutiveDestinationKind,
    val entityId: String,
)

fun resolveExecutiveDestination(reference: ExecutiveSourceReferenceDto): ExecutiveDestination? {
    val sourceId = reference.sourceId
    val expectedId = canonicalExecutiveUuid(sourceId)
    val path = reference.readPath

    if (reference.module == "INBOX" && reference.sourceType == "APPROVAL_REQUEST" && expectedId != null &&
        path == "/v1/approvals/$sourceId"
    ) {
        return ExecutiveDestination(ExecutiveDestinationKind.APPROVAL_DETAIL, sourceId)
    }

    if (reference.module == "PROJECTS") {
        if (reference.sourceType == "PROJECT" && expectedId != null &&
            path == "/v1/projects/$sourceId"
        ) {
            return ExecutiveDestination(ExecutiveDestinationKind.PROJECT_DETAIL, sourceId)
        }
        // Signals carry their own source ID; health belongs to the enclosing
        // project. The URL itself must be strict, local and source-owned.
        val match = Regex("^/v1/projects/([0-9a-fA-F-]{36})/health$").matchEntire(path)
        if (reference.sourceType in setOf("WORK_ORDER", "MILESTONE", "WORK_BLOCKER") &&
            expectedId != null &&
            match != null && canonicalExecutiveUuid(match.groupValues[1]) != null
        ) {
            return ExecutiveDestination(ExecutiveDestinationKind.PROJECT_HEALTH, match.groupValues[1])
        }
    }

    if (reference.module == "WAREHOUSE" && reference.sourceType == "ASSET" && expectedId != null &&
        path == "/v1/assets/$sourceId/availability"
    ) {
        return ExecutiveDestination(ExecutiveDestinationKind.ASSET_AVAILABILITY, sourceId)
    }

    return null
}

private fun canonicalExecutiveUuid(value: String): String? {
    val isCanonical = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    ).matches(value)
    return if (isCanonical) value else null
}
