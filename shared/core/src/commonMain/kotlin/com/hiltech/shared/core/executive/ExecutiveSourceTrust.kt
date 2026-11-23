package com.hiltech.shared.core.executive

/**
 * Fail-closed presentation contract. An unknown status or misidentified
 * producer is not a trustworthy executive window, even when items are present.
 * This is a client consistency check, not a replacement for source/server ACL.
 */
const val EXECUTIVE_INBOX_SOURCE = "INBOX_WORK_QUEUE"
const val EXECUTIVE_PROJECT_SOURCE = "PROJECTS_AND_PROJECT_HEALTH"
const val EXECUTIVE_ASSET_SOURCE = "ASSET_SERVICE_QUEUE"
const val EXECUTIVE_ACTOR_SCOPE = "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS"

fun executiveSectionTrusted(section: ExecutiveSectionDto<*>, expectedSource: String): Boolean =
    section.source == expectedSource &&
        (section.status == "CURRENT" || section.status == "PARTIAL")

fun executiveDecisionsTrusted(home: ExecutiveHomeDto): Boolean =
    home.needsYouScope == EXECUTIVE_ACTOR_SCOPE &&
        executiveSectionTrusted(home.needsYou, EXECUTIVE_INBOX_SOURCE)
