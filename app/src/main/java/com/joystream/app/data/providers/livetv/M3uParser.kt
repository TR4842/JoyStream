package com.joystream.app.data.providers.livetv

import java.util.Locale

data class Channel(
    val name: String,
    val url: String,
    val group: String = "Uncategorised",
    val logoUrl: String? = null,
    val tvgId: String? = null,
) {
    val key: String get() = "${name.lowercase(Locale.US)}|$url"
}

/**
 * Tolerant M3U / M3U8 playlist parser.
 * Handles EXTINF attribute quoting, #EXTGRP and playlists with no attributes.
 */
object M3uParser {

    private val attributeRegex = Regex("([a-zA-Z0-9\\-_]+)=\"([^\"]*)\"")

    fun parse(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val seen = mutableSetOf<String>()
        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingId: String? = null
        var pendingGroupFromExtGrp: String? = null

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attributes = attributeRegex.findAll(line).associate { match ->
                        match.groupValues[1].lowercase(Locale.US) to match.groupValues[2]
                    }
                    pendingName = line.substringAfterLast(',', "").trim()
                        .ifBlank { attributes["tvg-name"]?.trim().orEmpty() }
                    pendingGroup = attributes["group-title"]?.trim()
                    pendingLogo = attributes["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() }
                    pendingId = attributes["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroupFromExtGrp = line.substringAfter(':').trim()
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName?.takeIf { it.isNotBlank() }
                    if (name != null && (line.startsWith("http") || line.startsWith("rtsp") ||
                            line.startsWith("rtmp") || line.startsWith("udp") ||
                            line.startsWith("/"))
                    ) {
                        val channel = Channel(
                            name = name,
                            url = line,
                            group = pendingGroup ?: pendingGroupFromExtGrp ?: "Uncategorised",
                            logoUrl = pendingLogo,
                            tvgId = pendingId,
                        )
                        if (seen.add(channel.key)) channels += channel
                    }
                    pendingName = null
                    pendingGroup = null
                    pendingLogo = null
                    pendingId = null
                    pendingGroupFromExtGrp = null
                }
            }
        }

        return channels
    }
}
