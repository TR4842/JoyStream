package com.joystream.app

import com.joystream.app.core.util.compareVersions
import com.joystream.app.core.util.extractYear
import com.joystream.app.data.providers.livetv.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="bbc1" tvg-logo="https://cdn.example.com/bbc1.png" group-title="News",BBC One HD
        https://stream.example.com/bbc1/index.m3u8
        #EXTINF:-1 group-title="Movies",Star Movies
        #EXTGRP:Entertainment
        https://stream.example.com/star/index.m3u8
        #EXTINF:-1,Plain Channel
        http://stream.example.com/plain.ts
        #EXTINF:-1 group-title="Streams",Unsupported scheme
        udp://239.0.0.1:1234
    """.trimIndent()

    @Test
    fun `parses channels with attributes`() {
        val channels = M3uParser.parse(playlist)
        assertEquals(4, channels.size)

        val bbc = channels.first()
        assertEquals("BBC One HD", bbc.name)
        assertEquals("News", bbc.group)
        assertEquals("bbc1", bbc.tvgId)
        assertEquals("https://cdn.example.com/bbc1.png", bbc.logoUrl)

        assertEquals("Star Movies", channels[1].name)
        assertEquals("Movies", channels[1].group)
        assertEquals("Plain Channel", channels[2].name)
        assertEquals("Uncategorised", channels[2].group)
        assertEquals("udp://239.0.0.1:1234", channels[3].url)
    }

    @Test
    fun `ignores duplicate entries`() {
        val duplicated = """
            #EXTINF:-1,Dup
            https://a.example.com/1.m3u8
            #EXTINF:-1,Dup
            https://a.example.com/1.m3u8
        """.trimIndent()
        assertEquals(1, M3uParser.parse(duplicated).size)
    }

    @Test
    fun `empty playlist yields no channels`() {
        assertTrue(M3uParser.parse("#EXTM3U").isEmpty())
        assertTrue(M3uParser.parse("").isEmpty())
    }

    @Test
    fun `version comparison handles numeric segments`() {
        assertTrue(compareVersions("1.2.10", "1.2.9") > 0)
        assertTrue(compareVersions("v1.0.0", "1.0.0") == 0)
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun `year extraction finds four digit years`() {
        assertEquals("2024", extractYear("Released 2024-05-01"))
        assertEquals("1999", extractYear("The Matrix (1999)"))
        assertTrue(extractYear("no year here") == null)
    }
}
