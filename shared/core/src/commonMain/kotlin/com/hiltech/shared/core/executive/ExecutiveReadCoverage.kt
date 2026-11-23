package com.hiltech.shared.core.executive

/**
 * Phase 13B source-integrity ledger: states exactly what a *single*
 * permissioned, bounded ExecutiveHome snapshot can and cannot establish.
 *
 * No "green company" from a capped query, no synthetic KPI, freshness SLA,
 * extra data fetch, persistent executive copy or elevated read permission.
 */
enum class ExecutiveCoverageState {
    VISIBLE_WINDOW,
    INCOMPLETE,
    UNAVAILABLE,
    AWAITING_SOURCE_CONTRACT,
}

data class ExecutiveCoverageLine(
    val label: String,
    val state: ExecutiveCoverageState,
    val statement: String,
    val visibleCount: Int?,
    val sourceAsOf: String?,
    val caveats: List<String>,
)

fun executiveReadCoverage(home: ExecutiveHomeDto): List<ExecutiveCoverageLine> {
    fun <T> line(
        label: String,
        section: ExecutiveSectionDto<T>,
        expectedSource: String,
        extraCaveats: List<String> = emptyList(),
        scopeTrusted: Boolean = true,
    ): ExecutiveCoverageLine {
        val validStatus = section.status == "CURRENT" || section.status == "PARTIAL"
        val expected = section.source == expectedSource
        val available = executiveSectionTrusted(section, expectedSource) && scopeTrusted
        val bounded = section.partial || section.status == "PARTIAL" || section.nextCursor != null
        val caveats = buildList {
            if (section.status == "UNAVAILABLE") add("المصدر غير متاح الآن، وليس صفرًا.")
            else if (!validStatus) add("حالة المصدر غير معروفة؛ يلزم مراجعة العقد.")
            if (!expected) add("هوية المصدر لا تطابق العقد المتوقع.")
            if (!scopeTrusted) add("نطاق البيانات لا يطابق صلاحية القراءة المعتمدة.")
            if (bounded) add("القراءة محدودة وقد توجد عناصر خارج النافذة الحالية.")
            if (section.unavailableReason != null) add("كود نقص المصدر: " + section.unavailableReason)
            addAll(extraCaveats)
        }
        val state = when {
            !available -> ExecutiveCoverageState.UNAVAILABLE
            bounded || caveats.isNotEmpty() -> ExecutiveCoverageState.INCOMPLETE
            else -> ExecutiveCoverageState.VISIBLE_WINDOW
        }
        return ExecutiveCoverageLine(
            label = label,
            state = state,
            statement = when (state) {
                ExecutiveCoverageState.UNAVAILABLE -> "لا توجد قراءة موثوقة لهذا المصدر."
                ExecutiveCoverageState.INCOMPLETE -> "توجد قراءة مرئية، لكن التغطية غير مكتملة أو تحتاج مراجعة."
                ExecutiveCoverageState.VISIBLE_WINDOW -> "النافذة المسترجعة من المصدر متاحة؛ ليست إجمالي الشركة."
                ExecutiveCoverageState.AWAITING_SOURCE_CONTRACT -> "بانتظار عقد قراءة وصلاحيات معتمد."
            },
            // Do not count malformed/untrusted producer data as genuine rows.
            visibleCount = if (available) section.items.size else null,
            sourceAsOf = if (available) section.sourceAsOf else null,
            caveats = caveats,
        )
    }

    val inboxCaveats = buildList {
        // Current Inbox producer is actor-wide, not selected-organization scoped.
        if (home.needsYouScope == "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS") {
            add("القرارات تخص المستخدم عبر عضوياته المصرح بها؛ ليست إجمالي المؤسسة المحددة.")
        } else {
            add("نطاق قرارات المستخدم غير معروف أو تغيّر عن العقد المراجع.")
        }
    }

    val projects = home.projects.items
    val projectHealthUncertain = projects.count { it.healthState !in
        setOf("HEALTHY", "ATTENTION", "CRITICAL", "ON_HOLD") }
    val projectHealthNoAsOf = projects.count { it.healthAsOf == null }
    val projectCaveats = buildList {
        if (projectHealthUncertain > 0)
            add("بعض المشاريع الظاهرة ليس لها تقييم صحة مؤكد.")
        if (projectHealthNoAsOf > 0)
            add("بعض تقييمات صحة المشاريع ليس لها وقت مصدر موثوق.")
        // ProjectsService.myProjects bounds the DB query before source
        // ACL filtering; fewer displayed records prove nothing global.
        add("اختيار المشاريع محدود قبل التحقق من ظهور كل مشروع؛ لا يمثل الحافظة كاملة.")
    }

    val assetCaveats = buildList {
        if (home.assetExceptions.items.any { it.evaluatedAt.isBlank() })
            add("وقت تقييم مصدر بعض المعدات غير متاح.")
    }

    return buildList {
        add(line(
            "قرارات المستخدم", home.needsYou, EXECUTIVE_INBOX_SOURCE, inboxCaveats,
            scopeTrusted = home.needsYouScope == EXECUTIVE_ACTOR_SCOPE,
        ))
        add(line("صحة المشاريع", home.projects, EXECUTIVE_PROJECT_SOURCE, projectCaveats))
        add(line("استثناءات المعدات", home.assetExceptions, EXECUTIVE_ASSET_SOURCE, assetCaveats))
        // Pending source domains are disclosures, never a source outage or
        // evidence of a healthy/empty domain.
        home.pendingDomains.distinctBy { it.domain }.forEach { pending ->
            add(
                ExecutiveCoverageLine(
                    label = pending.domain,
                    state = ExecutiveCoverageState.AWAITING_SOURCE_CONTRACT,
                    statement = "هذا النطاق غير موصول كمصدر إداري معتمد حتى الآن.",
                    visibleCount = null,
                    sourceAsOf = null,
                    caveats = listOf("حالة الاعتماد: " + pending.status),
                ),
            )
        }
    }
}
