package com.blazikener.devin.ui.navigation

import com.blazikener.devin.data.SessionFilter
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class RoutesTest {
    @Test fun sharedPromptCannotBecomeAnotherNavigationArgument() {
        val prompt = "Review a/b?x=1&mode=fast + naïve\n#heading"
        val route = Routes.create(prompt)
        assertEquals(1, route.count { it == '?' })
        assertFalse(route.contains("&"))
        assertEquals(prompt, URLDecoder.decode(route.substringAfter("prompt="), "UTF-8"))
    }

    @Test fun fileIdentityAndVersionStayIndependentOfOrigin() {
        val route = Routes.reader("devin-123", "outputs/retry plan.md?signature=a&b=2", "v1/#2", SessionTab.Files)
        val args = route.substringAfter('?').split('&').associate {
            val (key, value) = it.split('=', limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals("outputs/retry plan.md?signature=a&b=2", args["file"])
        assertEquals("v1/#2", args["version"])
        assertEquals("Files", args["origin"])
    }

    @Test fun needsYouIsAFilterNotADestination() {
        assertEquals("sessions?filter=NeedsYou&search=false", Routes.sessions(SessionFilter.NeedsYou))
        assertEquals("sessions?filter=&search=true", Routes.sessions(search = true))
    }

    @Test fun sessionIdsRemainOneOpaquePathSegment() {
        assertEquals("session/opaque%2Fid%3Ftab%3DFiles", Routes.session("opaque/id?tab=Files"))
    }
}
