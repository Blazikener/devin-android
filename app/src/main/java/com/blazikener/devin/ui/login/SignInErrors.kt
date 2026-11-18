package com.blazikener.devin.ui.login

import com.blazikener.devin.data.ApiException
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Returns a message for an unusable API base URL, or null when it looks fine. */
internal fun baseUrlProblem(raw: String): String? {
    val url = raw.trim().toHttpUrlOrNull()
        ?: return "Enter the full API URL, starting with https:// (for example https://api.devin.ai)."
    if (url.host.isEmpty()) return "Enter the full API URL, starting with https://."
    return null
}

/** Returns a message when the pasted key can't be a Devin API key (e.g. smart quotes, spaces inside). */
internal fun apiKeyProblem(key: String): String? =
    if (key.any { it <= ' ' || it > '~' }) "That key contains spaces or characters an API key can't have. Copy it again from Devin." else null

/** Turns a failed `/v3/self` check into something a person can act on. */
internal fun signInErrorMessage(e: Throwable, baseUrl: String): String {
    val host = baseUrl.toHttpUrlOrNull()?.let { if (it.port == 443 || it.port == 80) it.host else "${it.host}:${it.port}" } ?: baseUrl
    return when (e) {
        // Devin answers an unknown key with 403 "Unauthorized" on /v3/self, not 401.
        is ApiException -> when {
            e.isAuth -> "That API key was rejected. Check that you copied the whole key and that it hasn't been revoked."
            e.code == 404 -> "$host doesn't look like a Devin API. Check the API base URL."
            e.code == 429 -> "Too many attempts. Wait a moment and try again."
            e.code in 500..599 -> "Devin's API is having trouble (${e.code}). Try again shortly."
            else -> e.message ?: "Sign-in failed (${e.code})."
        }
        is UnknownHostException -> "Couldn't reach $host. Check your internet connection."
        is ConnectException -> "Couldn't connect to $host. Check your connection and the API base URL."
        is SocketTimeoutException -> "$host took too long to respond. Check your connection and try again."
        is SSLException -> "Couldn't make a secure connection to $host."
        is SerializationException -> "$host answered, but not like a Devin API. Check the API base URL."
        is IOException -> if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true)
            "This build only connects over https://."
        else "Couldn't reach $host. Check your connection and try again."
        else -> "Couldn't sign in. Try again."
    }
}
