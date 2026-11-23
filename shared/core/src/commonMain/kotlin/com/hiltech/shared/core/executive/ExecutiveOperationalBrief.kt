package com.hiltech.shared.core.executive

/**
 * Phase 13B: an on-demand operating brief over the SAME permitted 13A source
 * snapshot. No persisted read model, generated narrative, background delivery,
 * invented company totals, cross-domain authority or new query is introduced.
 *
 * The data is strictly a visible-window preview. Each card retains its
 * source-owned reference and can only navigate through the existing allowlist.
 */
enum class ExecutiveBriefKind { NEEDS_YOU, PROJECT_RISK, ASSET_SERVICE }

enum class ExecutiveBriefPriority { SOURCE_CRITICAL, ACTION_VISIBLE, UNVERIFIED }

data class ExecutiveBriefEntry(
    val kind: ExecutiveBriefKind,
    val title: String,
    val detail: String,
    val priority: ExecutiveBriefPriority,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long?,
    val observedAt: String?,
    val destination: ExecutiveDestination?,
)

data class ExecutiveBriefLane(
    val kind: ExecutiveBriefKind,
    val label: String,
    val entries: List<ExecutiveBriefEntry>,
    // This says whether the underlying SOURCE snapshot is complete, not
    // whether the first three items in this brief are all the visible items.
    val sourceStatus: String,
    val incomplete: Boolean,
    val extraVisibleItems: Boolean,
    val sourceAsOf: String?,
    val missingReason: String?,
)

data class ExecutiveOperationalBrief(
    val assembledAt: String,
    val needsYouScope: String,
    val lanes: List<ExecutiveBriefLane>,
)

fun buildExecutiveOperationalBrief(
    home: ExecutiveHomeDto,
    maxPerLane: Int = 3,
): ExecutiveOperationalBrief {
    require(maxPerLane in 1..10) { "A bounded number of preview items is required." }

    val needs = home.needsYou
    val needsEntries = if (needs.status == "UNAVAILABLE") emptyList() else
        needs.items.map { item ->
            ExecutiveBriefEntry(
                kind = ExecutiveBriefKind.NEEDS_YOU,
                title = item.safeTitleCode,
                detail = item.safeSummary ?: item.attentionClass,
                priority = ExecutiveBriefPriority.ACTION_VISIBLE,
                sourceType = item.reference.sourceType,
                sourceId = item.reference.sourceId,
                sourceVersion = item.reference.sourceVersion,
                observedAt = item.updatedAt,
                destination = resolveExecutiveDestination(item.reference),
            )
        }

    val projects = home.projects
    val projectEntries = if (projects.status == "UNAVAILABLE") emptyList() else
        orderExecutiveProjectsByHealth(projects.items)
            .filter { it.healthState != "HEALTHY" || it.signals.isNotEmpty() }
            .map { project ->
                val priority = when (project.healthState) {
                    "CRITICAL" -> ExecutiveBriefPriority.SOURCE_CRITICAL
                    "ATTENTION", "ON_HOLD" -> ExecutiveBriefPriority.ACTION_VISIBLE
                    else -> ExecutiveBriefPriority.UNVERIFIED
                }
                // Health signals are owned by the Project Health API. Navigate
                // to the enclosing project only, never to the signal's UUID.
                val signalled = project.signals.firstOrNull()?.let { signal ->
                    resolveExecutiveProjectSignalDestination(signal.reference, project.projectId)
                }
                ExecutiveBriefEntry(
                    kind = ExecutiveBriefKind.PROJECT_RISK,
                    title = project.projectCode + " · " + project.name,
                    detail = "صحة المشروع: " + project.healthState +
                        if (project.reasonCodes.isEmpty()) "" else
                            " · أسباب المصدر: " + project.reasonCodes.joinToString("، "),
                    priority = priority,
                    sourceType = project.reference.sourceType,
                    sourceId = project.projectId,
                    sourceVersion = project.projectVersion,
                    observedAt = project.healthAsOf ?: project.projectUpdatedAt,
                    destination = signalled ?: resolveExecutiveDestination(project.reference),
                )
            }

    val assets = home.assetExceptions
    val assetEntries = if (assets.status == "UNAVAILABLE") emptyList() else
        assets.items.map { asset ->
            ExecutiveBriefEntry(
                kind = ExecutiveBriefKind.ASSET_SERVICE,
                title = asset.assetCode + (asset.name?.let { " · " + it } ?: ""),
                detail = "الإتاحة: " + asset.availabilityState +
                    " · المعايرة: " + asset.calibrationStatus +
                    " · الصيانة: " + asset.maintenanceStatus,
                // The asset service queue itself defines exception membership;
                // do not invent a criticality score across source domains.
                priority = ExecutiveBriefPriority.ACTION_VISIBLE,
                sourceType = asset.reference.sourceType,
                sourceId = asset.reference.sourceId,
                sourceVersion = asset.reference.sourceVersion,
                observedAt = asset.evaluatedAt,
                destination = resolveExecutiveDestination(asset.reference),
            )
        }

    fun lane(
        kind: ExecutiveBriefKind,
        label: String,
        source: ExecutiveSectionDto<*>,
        visible: List<ExecutiveBriefEntry>,
    ) = ExecutiveBriefLane(
        kind = kind,
        label = label,
        entries = visible.take(maxPerLane),
        sourceStatus = source.status,
        incomplete = source.partial || source.status != "CURRENT",
        extraVisibleItems = visible.size > maxPerLane,
        sourceAsOf = source.sourceAsOf,
        missingReason = source.unavailableReason,
    )

    return ExecutiveOperationalBrief(
        assembledAt = home.asOf,
        needsYouScope = home.needsYouScope,
        lanes = listOf(
            lane(ExecutiveBriefKind.NEEDS_YOU, "قراراتك الظاهرة", needs, needsEntries),
            lane(ExecutiveBriefKind.PROJECT_RISK, "مخاطر المشاريع الظاهرة", projects, projectEntries),
            lane(ExecutiveBriefKind.ASSET_SERVICE, "استثناءات المعدات الظاهرة", assets, assetEntries),
        ),
    )
}
