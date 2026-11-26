package com.hiltech.shared.core.executive

/**
 * Presentation-only order of already source-authorized project records.
 * These are the actual Project Health domain states. The order is not a
 * numeric company risk score and does not imply portfolio completeness.
 */
fun orderExecutiveProjectsByHealth(items: List<ExecutiveProjectDto>): List<ExecutiveProjectDto> =
    items.sortedWith(
        compareBy<ExecutiveProjectDto>(
            { project ->
                when (project.healthState) {
                    "CRITICAL" -> 0
                    "ON_HOLD" -> 1
                    "ATTENTION" -> 2
                    "UNKNOWN", "UNAVAILABLE" -> 3
                    "HEALTHY" -> 4
                    else -> 3 // New / unrecognized source state is never assumed healthy.
                }
            },
            { it.projectCode },
        ),
    )
