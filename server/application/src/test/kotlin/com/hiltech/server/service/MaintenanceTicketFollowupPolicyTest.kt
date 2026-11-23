package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaintenanceTicketFollowupPolicyTest {
    @Test
    fun linkOnlyExistingUnresolvedServiceCases() {
        assertTrue(MaintenanceTicketFollowupPolicy.canLinkToCase("OPEN"))
        assertTrue(MaintenanceTicketFollowupPolicy.canLinkToCase("INVESTIGATING"))
        assertFalse(MaintenanceTicketFollowupPolicy.canLinkToCase("RESOLVED_INTERNAL"))
        assertFalse(MaintenanceTicketFollowupPolicy.canLinkToCase("CLOSED_INTERNAL"))
        assertFalse(MaintenanceTicketFollowupPolicy.canLinkToCase("CLIENT_ACCEPTED"))
    }
}
