package com.blazikener.devin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {
    @Test
    fun errorsTakePriority() {
        assertEquals(SessionState.Error, SessionState.of("error", null))
        assertEquals(SessionState.Error, SessionState.of("running", "error"))
    }

    @Test
    fun usageLimitsAreBlocked() {
        listOf(
            "usage_limit_exceeded",
            "out_of_credits",
            "out_of_quota",
            "no_quota_allocation",
            "payment_declined",
            "org_usage_limit_exceeded",
            "user_usage_limit_exceeded",
            "total_session_limit_exceeded",
            "contract_expired",
        ).forEach { detail ->
            assertEquals(detail, SessionState.Blocked, SessionState.of("running", detail))
        }
    }

    @Test
    fun attentionStatesAreMapped() {
        assertEquals(SessionState.NeedsYou, SessionState.of("running", "waiting_for_user"))
        assertEquals(SessionState.NeedsApproval, SessionState.of("running", "waiting_for_approval"))
    }

    @Test
    fun finishedAndSleepingStatesAreMapped() {
        assertEquals(SessionState.Finished, SessionState.of("running", "finished"))
        assertEquals(SessionState.Finished, SessionState.of("exit", null))
        assertEquals(SessionState.Sleeping, SessionState.of("running", "inactivity"))
        assertEquals(SessionState.Sleeping, SessionState.of("running", "user_request"))
        assertEquals(SessionState.Sleeping, SessionState.of("suspended", null))
    }

    @Test
    fun startingAndWorkingFallbacksAreMapped() {
        listOf("new", "claimed", "resuming").forEach { status ->
            assertEquals(status, SessionState.Starting, SessionState.of(status, null))
        }
        assertEquals(SessionState.Working, SessionState.of("running", "working"))
        assertEquals(SessionState.Working, SessionState.of("unknown", null))
    }
}
