package com.example

import com.example.data.LocalFilterSnapshot
import com.example.engine.BlockedWebsiteSource
import com.example.engine.DnsVerdict
import com.example.engine.InMemoryDomainBlocklist
import com.example.engine.LocalDnsFilterEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LocalDnsFilterEngineTest {

    @Test
    fun inMemoryBlocklistMatchesExactAndSubdomainsOnly() {
        val blocklist = InMemoryDomainBlocklist(setOf("bet365.com", "ads.example.org"))

        assertTrue(blocklist.isBlocked("bet365.com"))
        assertTrue(blocklist.isBlocked("WWW.BET365.COM."))
        assertTrue(blocklist.isBlocked("mobile.live.bet365.com"))
        assertTrue(blocklist.isBlocked("tracker.ads.example.org"))

        assertFalse(blocklist.isBlocked("notbet365.com"))
        assertFalse(blocklist.isBlocked("example.org"))
        assertFalse(blocklist.isBlocked("khanacademy.org"))
    }

    @Test
    fun parseBatchDomainInputs_normalizesUrlsWildcardsAndDeduplicates() {
        val parsed = LocalDnsFilterEngine.parseBatchDomainInputs(
            "https://tiktok.com/watch?v=1, *.roblox.com;\nnot-a-domain  TIKTOK.COM  reddit.com/r/all"
        )
        assertEquals(listOf("tiktok.com", "*.roblox.com", "reddit.com"), parsed)
    }

    @Test
    fun getActiveBlockedWebsites_combinesCustomLimitsAndEnabledShields() {
        val rules = LocalFilterSnapshot(
            blockAdult = true,
            blockGambling = false,
            blockMalware = true,
            blockSocial = false,
            customBlockedDomains = setOf("tiktok.com", "roblox.com")
        )
        val limitList = InMemoryDomainBlocklist(setOf("*.youtube.com"))
        val entries = LocalDnsFilterEngine.getActiveBlockedWebsites(
            rules = rules,
            customBlocklist = InMemoryDomainBlocklist(rules.customBlockedDomains),
            categoryLimitBlocklist = limitList
        )

        assertTrue(entries.any { it.domain == "tiktok.com" && it.source == BlockedWebsiteSource.CUSTOM && it.removable })
        assertTrue(entries.any { it.domain == "roblox.com" && it.source == BlockedWebsiteSource.CUSTOM && it.removable })
        assertTrue(entries.any { it.domain == "*.youtube.com" && it.source == BlockedWebsiteSource.CATEGORY_LIMIT && !it.removable })
        assertTrue(entries.any { it.source == BlockedWebsiteSource.ADULT_SHIELD && !it.removable })
        assertTrue(entries.any { it.source == BlockedWebsiteSource.MALWARE_SHIELD && !it.removable })
        assertFalse(entries.any { it.source == BlockedWebsiteSource.GAMBLING_SHIELD })
        assertFalse(entries.any { it.source == BlockedWebsiteSource.SOCIAL_SHIELD })

        val customEval = LocalDnsFilterEngine.evaluateDomain("m.tiktok.com", rules)
        assertEquals(DnsVerdict.BLOCKED_CUSTOM, customEval.verdict)

        val adultEval = LocalDnsFilterEngine.evaluateDomain("pornhub.com", rules)
        assertEquals(DnsVerdict.BLOCKED_ADULT, adultEval.verdict)

        val gamblingEval = LocalDnsFilterEngine.evaluateDomain("bet365.com", rules)
        assertEquals(DnsVerdict.ALLOWED, gamblingEval.verdict)
    }

    @Test
    fun parseAndBuildIpv4SinkholeReturnsZeroIpAnswer() {
        val query = buildTestDnsQuery(txId = 0x1A2B, domain = "m.bet365.com", qType = LocalDnsFilterEngine.QTYPE_A)
        val question = LocalDnsFilterEngine.parseDnsQuestion(query)

        assertNotNull(question)
        assertEquals("m.bet365.com", question!!.domain)
        assertEquals(LocalDnsFilterEngine.QTYPE_A, question.qType)

        val response = LocalDnsFilterEngine.buildSinkholeResponse(query, question)

        // Transaction ID preserved
        assertEquals(0x1A.toByte(), response[0])
        assertEquals(0x2B.toByte(), response[1])
        // QR = 1 (response), RCODE = 0 (NOERROR)
        assertTrue((response[2].toInt() and 0x80) != 0)
        assertEquals(0, response[3].toInt() and 0x0F)
        // ANCOUNT = 1
        assertEquals(0.toByte(), response[6])
        assertEquals(1.toByte(), response[7])
        // Last 4 bytes = 0.0.0.0
        val rdata = response.copyOfRange(response.size - 4, response.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), rdata)
    }

    @Test
    fun parseAndBuildIpv6SinkholeReturnsUnspecifiedAddress() {
        val query = buildTestDnsQuery(txId = 0x4455, domain = "pornhub.com", qType = LocalDnsFilterEngine.QTYPE_AAAA)
        val question = LocalDnsFilterEngine.parseDnsQuestion(query)!!
        val response = LocalDnsFilterEngine.buildSinkholeResponse(query, question)

        // ANCOUNT = 1 and trailing 16 bytes are all zero (::)
        assertEquals(1.toByte(), response[7])
        val rdata = response.copyOfRange(response.size - 16, response.size)
        assertArrayEquals(ByteArray(16), rdata)
    }

    private fun buildTestDnsQuery(txId: Int, domain: String, qType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // Header (12 bytes)
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01); out.write(0x00) // Standard query, RD=1
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x00) // ANCOUNT=0
        out.write(0x00); out.write(0x00) // NSCOUNT=0
        out.write(0x00); out.write(0x00) // ARCOUNT=0
        // QNAME labels
        for (label in domain.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // Root terminator
        // QTYPE & QCLASS (IN = 1)
        out.write((qType ushr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }
}
