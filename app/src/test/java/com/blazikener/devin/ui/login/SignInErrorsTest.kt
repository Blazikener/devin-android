package com.blazikener.devin.ui.login

import com.blazikener.devin.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class SignInErrorsTest {
    private val base = "https://api.devin.ai"

    @Test fun rejectedKeyOn401Or403() {
        val a = signInErrorMessage(ApiException(401, "x"), base)
        val b = signInErrorMessage(ApiException(403, "Unauthorized"), base)
        assertEquals(a, b)
        assertTrue(a.startsWith("That API key was rejected"))
    }

    @Test fun offlineNamesHost() {
        assertEquals("Couldn't reach api.devin.ai. Check your internet connection.", signInErrorMessage(UnknownHostException("x"), base))
        assertTrue(signInErrorMessage(ConnectException("Failed to connect to /10.0.2.2:8787"), "http://10.0.2.2:8787").contains("10.0.2.2:8787"))
    }

    @Test fun cleartextBlocked() {
        assertEquals("This build only connects over https://.", signInErrorMessage(IOException("CLEARTEXT communication to 10.0.2.2 not permitted"), "http://10.0.2.2:8787"))
    }

    @Test fun baseUrlValidation() {
        assertNotNull(baseUrlProblem("api.devin.ai"))
        assertNotNull(baseUrlProblem(""))
        assertNull(baseUrlProblem("https://api.devin.ai"))
        assertNull(baseUrlProblem("http://10.0.2.2:8787"))
    }
}
