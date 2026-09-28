package com.joshreimer.anonbrowser

import android.content.Context

/** transports whose local SOCKS listener we can start via IPtProxy's Lyrebird integration. */
private val SUPPORTED_TRANSPORTS = setOf("obfs4", "webtunnel", "meek_lite", "scramblesuit", "obfs2", "obfs3")

/** transports IPtProxy also bundles but that need extra per-transport wiring (Snowflake needs
 * broker/ICE config, dnstt needs a resolver domain) that this app doesn't set up yet. */
private val KNOWN_UNSUPPORTED_TRANSPORTS = setOf("snowflake", "dnstt")

sealed class ParsedBridge {
    /** No pluggable transport — Tor connects to this relay directly, it's just unlisted. */
    data class Vanilla(val torrcLine: String) : ParsedBridge()

    /** Needs a pluggable transport we can start (see [SUPPORTED_TRANSPORTS]). */
    data class WithTransport(val torrcLine: String, val transport: String) : ParsedBridge()

    /** Recognized transport keyword, but not one we know how to start. */
    data class Unsupported(val transport: String) : ParsedBridge()
}

/**
 * Parses one line of user-pasted bridge text (the format bridges.torproject.org hands out,
 * optionally still prefixed with the literal "Bridge " some sources include). Returns null for
 * blank lines/comments.
 */
fun parseBridgeLine(input: String): ParsedBridge? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

    val body = if (trimmed.startsWith("Bridge ", ignoreCase = true)) {
        trimmed.removePrefix("Bridge ").trim()
    } else {
        trimmed
    }
    if (body.isEmpty()) return null

    val firstToken = body.substringBefore(' ').lowercase()
    return when (firstToken) {
        in SUPPORTED_TRANSPORTS -> ParsedBridge.WithTransport(body, firstToken)
        in KNOWN_UNSUPPORTED_TRANSPORTS -> ParsedBridge.Unsupported(firstToken)
        else -> ParsedBridge.Vanilla(body)
    }
}

/** Persists the user's bridge configuration across app restarts. */
object BridgePrefs {
    private const val PREFS_NAME = "bridges"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TEXT = "text"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun getText(context: Context): String =
        prefs(context).getString(KEY_TEXT, "") ?: ""

    /** The configured bridge lines, or empty if bridges are disabled. */
    fun getActiveLines(context: Context): List<String> {
        if (!isEnabled(context)) return emptyList()
        return getText(context).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun save(context: Context, enabled: Boolean, text: String) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TEXT, text)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
