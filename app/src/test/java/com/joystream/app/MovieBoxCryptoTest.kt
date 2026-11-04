package com.joystream.app

import com.joystream.app.data.providers.moviebox.MovieBoxCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieBoxCryptoTest {

    @Test
    fun `md5 matches a known digest`() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            MovieBoxCrypto.md5Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `client token is timestamp plus md5 of reversed timestamp`() {
        val token = MovieBoxCrypto.generateClientToken(1700000000000L)
        val parts = token.split(',')
        assertEquals(2, parts.size)
        assertEquals("1700000000000", parts[0])
        assertEquals(32, parts[1].length)
    }

    @Test
    fun `canonical url sorts query parameters`() {
        val url = "https://api.example.com/endpoint?b=2&a=1&c=3"
        assertEquals("/endpoint?a=1&b=2&c=3", MovieBoxCrypto.canonicalUrl(url))
    }

    @Test
    fun `canonical url keeps path when there is no query`() {
        assertEquals(
            "/wefeed-mobile-bff/subject-api/get",
            MovieBoxCrypto.canonicalUrl("https://api.example.com/wefeed-mobile-bff/subject-api/get"),
        )
    }

    @Test
    fun `canonical string embeds body hash and length`() {
        val canonical = MovieBoxCrypto.buildCanonicalString(
            method = "post",
            accept = "application/json",
            contentType = "application/json",
            url = "https://api.example.com/search/v2?page=1",
            body = "{\"keyword\":\"dune\"}",
            timestampMs = 1700000000000L,
        )
        val lines = canonical.split("\n")
        assertEquals("POST", lines[0])
        assertEquals("application/json", lines[1])
        assertEquals("application/json", lines[2])
        assertEquals("18", lines[3])
        assertEquals("1700000000000", lines[4])
        assertEquals(32, lines[5].length)
        assertEquals("/search/v2?page=1", lines[6])
    }

    @Test
    fun `signature has the expected timestamp and version prefix`() {
        val signature = MovieBoxCrypto.generateSignature(
            method = "GET",
            accept = "application/json",
            contentType = "application/json",
            url = "https://api.example.com/path?tab=1&page=2",
            body = null,
            timestampMs = 1700000000000L,
        )
        val parts = signature.split('|')
        assertEquals(3, parts.size)
        assertEquals("1700000000000", parts[0])
        assertEquals("2", parts[1])
        assertTrue(parts[2].isNotBlank())
    }

    @Test
    fun `client identity looks like the official android client`() {
        val identity = MovieBoxCrypto.generateClientIdentity()
        assertTrue(identity.userAgent.startsWith("com.community.oneroom/"))
        assertTrue(identity.userAgent.contains("Cronet/"))
        assertTrue(identity.clientInfo.contains("\"package_name\":\"com.community.oneroom\""))
        assertTrue(identity.clientInfo.contains("\"sp_code\":\"40401\""))
        val octets = identity.spoofedIp.split('.')
        assertEquals(4, octets.size)
    }
}
