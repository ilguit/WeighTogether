package com.palixander.weightogether

import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.RoutingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainViewModelIgnoreUnknownPolicyTest {
    @Test
    fun `resolver policy draft mirrors saved setting only for NoMatch`() {
        assertEquals(false, resolverIgnoreUnknownPolicySelection(RoutingDecision.NoMatch, false))
        assertEquals(true, resolverIgnoreUnknownPolicySelection(RoutingDecision.NoMatch, true))
        assertNull(
            resolverIgnoreUnknownPolicySelection(
                RoutingDecision.AssignPrimary(AccountId("primary")),
                true,
            ),
        )
    }
}
