package com.example

import com.example.analytics.AppUsageCategory
import com.example.analytics.DailyCategoryUsage
import com.example.data.LocalFilterSnapshot
import com.example.engine.CategoryLimitPreset
import com.example.engine.CategoryLimitRule
import com.example.engine.CategoryTimeLimitEngine
import com.example.engine.DnsVerdict
import com.example.engine.LocalDnsFilterEngine
import com.example.service.SafeNestDnsVpnService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTimeLimitEngineTest {

    @After
    fun tearDown() {
        SafeNestDnsVpnService.updateCategoryLimitEnforcement(emptySet(), emptySet())
        SafeNestDnsVpnService.updateInMemoryBlocklist(emptySet())
    }

    @Test
    fun encodeAndDecodeRoundTripsAllCategoryRules() {
        val rules = CategoryTimeLimitEngine.presetRules(CategoryLimitPreset.SCHOOL_NIGHT)
        val encoded = CategoryTimeLimitEngine.encodeRules(rules)
        val decoded = CategoryTimeLimitEngine.decodeRules(encoded)

        assertEquals(true, decoded[AppUsageCategory.VIDEO]?.enabled)
        assertEquals(30, decoded[AppUsageCategory.VIDEO]?.limitMinutes)
        assertEquals(true, decoded[AppUsageCategory.GAMES]?.enabled)
        assertEquals(30, decoded[AppUsageCategory.GAMES]?.limitMinutes)
        assertEquals(true, decoded[AppUsageCategory.SOCIAL]?.enabled)
        assertEquals(20, decoded[AppUsageCategory.SOCIAL]?.limitMinutes)
        assertEquals(false, decoded[AppUsageCategory.EDUCATION]?.enabled)
    }

    @Test
    fun evaluateTriggersAutoBlockPatternsOnlyForExceededEnabledCategories() {
        val rules = CategoryTimeLimitEngine.defaultRuleMap().toMutableMap().apply {
            this[AppUsageCategory.VIDEO] = CategoryLimitRule(AppUsageCategory.VIDEO, enabled = true, limitMinutes = 30)
            this[AppUsageCategory.GAMES] = CategoryLimitRule(AppUsageCategory.GAMES, enabled = true, limitMinutes = 60)
            this[AppUsageCategory.SOCIAL] = CategoryLimitRule(AppUsageCategory.SOCIAL, enabled = false, limitMinutes = 15)
        }
        val todayUsage = DailyCategoryUsage(
            dateKey = "2026-11-21",
            shortDayLabel = "Sat",
            totalMinutes = 110,
            lateNightMinutes = 0,
            categoryMinutes = mapOf(
                AppUsageCategory.VIDEO to 35,  // Exceeded enabled 30m limit
                AppUsageCategory.GAMES to 40,  // Within enabled 60m limit
                AppUsageCategory.SOCIAL to 35  // Disabled rule -> must NOT block
            ),
            apps = emptyList()
        )

        val snapshot = CategoryTimeLimitEngine.evaluate(rules, todayUsage)

        assertEquals(setOf(AppUsageCategory.VIDEO), snapshot.exceededCategories)
        assertTrue(snapshot.autoBlockedPatterns.contains("*.youtube.com"))
        assertTrue(snapshot.autoBlockedPatterns.contains("*.tiktok.com"))
        assertFalse(snapshot.autoBlockedPatterns.contains("*.roblox.com"))
        assertFalse(snapshot.autoBlockedPatterns.contains("*.instagram.com"))

        val gamesStatus = snapshot.statuses.first { it.category == AppUsageCategory.GAMES }
        assertEquals(20, gamesStatus.remainingMinutes)
        assertFalse(gamesStatus.isLimitReached)
    }

    @Test
    fun vpnServiceEnforcesExceededCategoryBlocklistWithoutModifyingCustomDomains() {
        SafeNestDnsVpnService.updateInMemoryBlocklist(setOf("custom-block.example"))

        val rules = CategoryTimeLimitEngine.presetRules(CategoryLimitPreset.SCHOOL_NIGHT)
        val todayUsage = DailyCategoryUsage(
            dateKey = "2026-11-21",
            shortDayLabel = "Sat",
            totalMinutes = 45,
            lateNightMinutes = 0,
            categoryMinutes = mapOf(AppUsageCategory.GAMES to 45),
            apps = emptyList()
        )
        val evaluated = CategoryTimeLimitEngine.evaluate(rules, todayUsage)
        SafeNestDnsVpnService.updateCategoryLimitEnforcement(
            exceededCategories = evaluated.exceededCategories,
            patterns = evaluated.autoBlockedPatterns
        )

        assertEquals(setOf(AppUsageCategory.GAMES), SafeNestDnsVpnService.runtimeState.value.enforcedCategories)
        assertTrue(SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist("play.roblox.com").blocked)

        val dnsEval = LocalDnsFilterEngine.evaluateDomain(
            rawDomain = "web.roblox.com",
            rules = LocalFilterSnapshot(blockAdult = false, blockGambling = false, blockMalware = false),
            categoryLimitBlocklist = SafeNestDnsVpnService.getCategoryLimitBlocklist()
        )
        assertTrue(dnsEval.blocked)
        assertEquals(DnsVerdict.BLOCKED_CATEGORY_LIMIT, dnsEval.verdict)

        // Clearing category enforcement lifts roblox.com while keeping manual custom blocklist intact
        SafeNestDnsVpnService.updateCategoryLimitEnforcement(emptySet(), emptySet())
        assertFalse(SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist("play.roblox.com").blocked)
        assertTrue(SafeNestDnsVpnService.testDomainAgainstInMemoryBlocklist("custom-block.example").blocked)
    }
}
