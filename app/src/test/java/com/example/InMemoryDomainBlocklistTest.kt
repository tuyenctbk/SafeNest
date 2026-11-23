package com.example

import com.example.data.LocalFilterSnapshot
import com.example.engine.DnsVerdict
import com.example.engine.DomainPatternType
import com.example.engine.InMemoryDomainBlocklist
import com.example.engine.LocalDnsFilterEngine
import com.example.service.SafeNestDnsVpnService
import com.example.ui.components.PatternComposerMode
import com.example.ui.components.formatPatternCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryDomainBlocklistTest {

    @After
    fun tearDown() {
        SafeNestDnsVpnService.updateInMemoryBlocklist(emptySet())
    }

    @Test
    fun patternValidationAcceptsSafePatternsAndRejectsCatchAlls() {
        assertEquals("tiktok.com", InMemoryDomainBlocklist.normalizeAndValidatePattern("https://TiKToK.com/video/123"))
        assertEquals("*.doubleclick.net", InMemoryDomainBlocklist.normalizeAndValidatePattern("*.DoubleClick.net."))
        assertEquals("*casino*", InMemoryDomainBlocklist.normalizeAndValidatePattern("  **CASINO** "))
        assertEquals("ads.*.example.com", InMemoryDomainBlocklist.normalizeAndValidatePattern("ads.*.example.com"))

        // Dangerous or malformed catch-alls must be rejected
        assertNull(InMemoryDomainBlocklist.normalizeAndValidatePattern("*"))
        assertNull(InMemoryDomainBlocklist.normalizeAndValidatePattern("*.*"))
        assertNull(InMemoryDomainBlocklist.normalizeAndValidatePattern("*.com"))
        assertNull(InMemoryDomainBlocklist.normalizeAndValidatePattern("*ab*"))
        assertNull(InMemoryDomainBlocklist.normalizeAndValidatePattern("invalid host.com"))
        assertNull(InMemoryDomainBlocklist.normalizeAndValidatePattern(".example.com"))
    }

    @Test
    fun blocklistMatchesExactSubdomainWildcardAndKeywordPatterns() {
        val blocklist = InMemoryDomainBlocklist(
            setOf(
                "roblox.com",
                "*.doubleclick.net",
                "ads.*.example.org",
                "*casino*"
            )
        )

        val exactMatch = blocklist.match("play.roblox.com")
        assertTrue(exactMatch.blocked)
        assertEquals("roblox.com", exactMatch.matchedPattern)
        assertEquals(DomainPatternType.DOMAIN_SUFFIX, exactMatch.patternType)

        val subdomainMatch = blocklist.match("stats.g.doubleclick.net")
        assertTrue(subdomainMatch.blocked)
        assertEquals("*.doubleclick.net", subdomainMatch.matchedPattern)
        assertEquals(DomainPatternType.SUBDOMAIN_WILDCARD, subdomainMatch.patternType)

        val wildcardMatch = blocklist.match("ads.eu-west.example.org")
        assertTrue(wildcardMatch.blocked)
        assertEquals("ads.*.example.org", wildcardMatch.matchedPattern)
        assertEquals(DomainPatternType.WILDCARD, wildcardMatch.patternType)

        val keywordMatch = blocklist.match("promo.super-casino-live.io")
        assertTrue(keywordMatch.blocked)
        assertEquals("*casino*", keywordMatch.matchedPattern)
        assertEquals(DomainPatternType.KEYWORD, keywordMatch.patternType)

        assertFalse(blocklist.isBlocked("khanacademy.org"))
        assertFalse(blocklist.isBlocked("news.example.org"))
    }

    @Test
    fun liveVpnServiceUpdatesInMemoryBlocklistAtomically() {
        SafeNestDnsVpnService.updateInMemoryBlocklist(setOf("*.tiktok.com"))

        assertEquals(listOf("*.tiktok.com"), SafeNestDnsVpnService.runtimeState.value.activeInMemoryPatterns)
        assertTrue(SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist("v16.tiktok.com").blocked)

        val added = SafeNestDnsVpnService.addInMemoryPattern("*betting*")
        assertNotNull(added)
        assertEquals(listOf("*betting*", "*.tiktok.com").sorted(), SafeNestDnsVpnService.runtimeState.value.activeInMemoryPatterns)
        assertTrue(SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist("fast-betting-odds.net").blocked)

        val removed = SafeNestDnsVpnService.removeInMemoryPattern("*.tiktok.com")
        assertTrue(removed)
        assertFalse(SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist("v16.tiktok.com").blocked)
        assertEquals(listOf("*betting*"), SafeNestDnsVpnService.runtimeState.value.activeInMemoryPatterns)
    }

    @Test
    fun localDnsFilterEngineReportsMatchedCustomPattern() {
        val customList = InMemoryDomainBlocklist(setOf("*.ads-tracker.example"))
        val eval = LocalDnsFilterEngine.evaluateDomain(
            rawDomain = "pixel.ads-tracker.example",
            rules = LocalFilterSnapshot(blockAdult = false, blockGambling = false, blockMalware = false),
            customBlocklist = customList
        )

        assertTrue(eval.blocked)
        assertEquals(DnsVerdict.BLOCKED_CUSTOM, eval.verdict)
        assertEquals("*.ads-tracker.example", eval.matchedPattern)
    }

    @Test
    fun patternComposerFormatsModesWithoutBreakingExplicitWildcards() {
        assertEquals("*.tiktok.com", formatPatternCandidate("https://tiktok.com/feed", PatternComposerMode.SUBDOMAIN_WILDCARD))
        assertEquals("*casino*", formatPatternCandidate("casino", PatternComposerMode.KEYWORD))
        assertEquals("ads.*.example.com", formatPatternCandidate("ads.*.example.com", PatternComposerMode.KEYWORD))
        assertEquals("roblox.com", formatPatternCandidate("roblox.com", PatternComposerMode.DOMAIN))
    }
}
