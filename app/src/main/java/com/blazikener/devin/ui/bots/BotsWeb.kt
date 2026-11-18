package com.blazikener.devin.ui.bots

private val orgInUrl = Regex("""app\.devin\.ai/org/([A-Za-z0-9._-]+)""")
private val orgName = Regex("""[A-Za-z0-9._-]+""")
private val sessionInUrl = Regex("""app\.devin\.ai/(?:org/[^/]+/)?sessions/(devin-[0-9a-fA-F]{32}|[0-9a-fA-F]{32})(?:[/?#]|$)""")

/** Accepts an org name ("acme") or any app.devin.ai/org/<name>/... URL. */
fun parseWebOrg(input: String): String? {
    val t = input.trim().trimEnd('/')
    orgInUrl.find(t)?.let { return it.groupValues[1] }
    return t.takeIf { orgName.matches(it) }
}

fun botsUrl(org: String) = "https://app.devin.ai/org/$org/bots"

/** The v3 session id for a Devin web session page, so it can open natively. */
fun sessionIdFromWebUrl(url: String): String? =
    sessionInUrl.find(url)?.groupValues?.get(1)?.let { if (it.startsWith("devin-")) it else "devin-$it" }
