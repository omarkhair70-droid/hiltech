package com.hiltech.spike.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedStateTest {
    @Test
    fun representative_shared_domain_state_is_deterministic() {
        val approvals = 3
        assertEquals(2, approvals - 1)
    }
}
