package com.blazikener.devin.ui.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotsWebTest {
    @Test
    fun parsesOrgFromNameOrUrl() {
        assertEquals("blazikener", parseWebOrg(" blazikener "))
        assertEquals("blazikener", parseWebOrg("https://app.devin.ai/org/blazikener/bots"))
        assertEquals("my-org", parseWebOrg("app.devin.ai/org/my-org"))
        assertNull(parseWebOrg("not an org"))
        assertNull(parseWebOrg(""))
        assertEquals("https://app.devin.ai/org/blazikener/bots", botsUrl("blazikener"))
    }

    @Test
    fun extractsSessionIdsFromWebUrls() {
        val hex = "0123456789abcdef0123456789abcdef"
        assertEquals("devin-$hex", sessionIdFromWebUrl("https://app.devin.ai/sessions/$hex"))
        assertEquals("devin-$hex", sessionIdFromWebUrl("https://app.devin.ai/org/blazikener/sessions/$hex?tab=chat"))
        assertEquals("devin-$hex", sessionIdFromWebUrl("https://app.devin.ai/sessions/devin-$hex"))
        assertNull(sessionIdFromWebUrl("https://app.devin.ai/org/blazikener/bots/bot-1"))
        assertNull(sessionIdFromWebUrl("https://app.devin.ai/sessions/${hex}ff"))
    }
}
