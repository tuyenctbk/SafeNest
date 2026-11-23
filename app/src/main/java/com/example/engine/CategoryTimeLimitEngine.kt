package com.example.engine

import com.example.analytics.AppUsageCategory
import com.example.analytics.CategorizedAppUsage
import com.example.analytics.DailyCategoryUsage

enum class CategoryLimitPreset {
    SCHOOL_NIGHT,
    BALANCED,
    WEEKEND
}

data class CategoryLimitRule(
    val category: AppUsageCategory,
    val enabled: Boolean,
    val limitMinutes: Int
)

data class CategoryLimitStatus(
    val category: AppUsageCategory,
    val enabled: Boolean,
    val limitMinutes: Int,
    val usedMinutes: Int,
    val remainingMinutes: Int,
    val progressFraction: Float,
    val isLimitReached: Boolean,
    val enforcedDomainPatterns: List<String>,
    val topApps: List<CategorizedAppUsage> = emptyList()
)

data class CategoryEnforcementSnapshot(
    val statuses: List<CategoryLimitStatus> = emptyList(),
    val exceededCategories: Set<AppUsageCategory> = emptySet(),
    val autoBlockedPatterns: Set<String> = emptySet(),
    val lastEvaluatedMs: Long = 0L
) {
    val activeRuleCount: Int
        get() = statuses.count { it.enabled }
}

/**
 * Pure-Kotlin enforcement policy engine mapping AppUsageCategory hard daily limits
 * to real-time UsageStats progress and automatic VpnService DNS block patterns.
 */
object CategoryTimeLimitEngine {

    const val MIN_LIMIT_MINUTES = 5
    const val MAX_LIMIT_MINUTES = 240

    private val defaultMinutesByCategory: Map<AppUsageCategory, Int> = mapOf(
        AppUsageCategory.VIDEO to 60,
        AppUsageCategory.GAMES to 45,
        AppUsageCategory.SOCIAL to 30,
        AppUsageCategory.BROWSING to 60,
        AppUsageCategory.EDUCATION to 120,
        AppUsageCategory.OTHER to 60
    )

    private val categoryDomainPolicies: Map<AppUsageCategory, List<String>> = mapOf(
        AppUsageCategory.VIDEO to listOf(
            "*.youtube.com",
            "*.googlevideo.com",
            "*.ytimg.com",
            "*.tiktok.com",
            "*.tiktokv.com",
            "*.netflix.com",
            "*.nflxvideo.net",
            "*.twitch.tv",
            "*.ttvnw.net",
            "*.disneyplus.com",
            "*.hulu.com",
            "*.crunchyroll.com",
            "*.vimeo.com",
            "*.bilibili.com"
        ),
        AppUsageCategory.GAMES to listOf(
            "*.roblox.com",
            "*.rbxcdn.com",
            "*.minecraft.net",
            "*.mojang.com",
            "*.epicgames.com",
            "*.supercell.com",
            "*.riotgames.com",
            "*.steampowered.com",
            "*.garena.com",
            "*.hoyoverse.com",
            "*.mihoyo.com",
            "*.ea.com",
            "*.activision.com"
        ),
        AppUsageCategory.SOCIAL to listOf(
            "*.instagram.com",
            "*.cdninstagram.com",
            "*.facebook.com",
            "*.fbcdn.net",
            "*.snapchat.com",
            "*.sc-cdn.net",
            "*.x.com",
            "*.twitter.com",
            "*.twimg.com",
            "*.reddit.com",
            "*.redd.it",
            "*.discord.com",
            "*.discordapp.com",
            "*.threads.net",
            "*.pinterest.com"
        ),
        AppUsageCategory.BROWSING to listOf(
            "*.bing.com",
            "*.duckduckgo.com",
            "*.yahoo.com",
            "*.yandex.com",
            "*.baidu.com",
            "*.brave.com",
            "*.opera.com"
        ),
        AppUsageCategory.EDUCATION to listOf(
            "*.duolingo.com",
            "*.khanacademy.org",
            "*.quizlet.com",
            "*.coursera.org",
            "*.udemy.com",
            "*.wikipedia.org",
            "*.brainly.com"
        ),
        AppUsageCategory.OTHER to listOf(
            "*.9gag.com",
            "*.imgur.com",
            "*.giphy.com",
            "*.tumblr.com",
            "*.quora.com"
        )
    )

    fun defaultMinutesFor(category: AppUsageCategory): Int =
        defaultMinutesByCategory[category] ?: 60

    fun domainPatternsForCategory(category: AppUsageCategory): List<String> =
        categoryDomainPolicies[category].orEmpty()

    fun resolveCategoryForPattern(pattern: String): AppUsageCategory? {
        val normalized = pattern.trim().lowercase()
        return categoryDomainPolicies.entries
            .firstOrNull { (_, patterns) -> patterns.contains(normalized) }
            ?.key
    }

    fun defaultRuleMap(): Map<AppUsageCategory, CategoryLimitRule> =
        AppUsageCategory.entries.associateWith { cat ->
            CategoryLimitRule(
                category = cat,
                enabled = false,
                limitMinutes = defaultMinutesFor(cat)
            )
        }

    fun presetRules(preset: CategoryLimitPreset): Map<AppUsageCategory, CategoryLimitRule> {
        val base = defaultRuleMap().toMutableMap()
        when (preset) {
            CategoryLimitPreset.SCHOOL_NIGHT -> {
                base[AppUsageCategory.VIDEO] = CategoryLimitRule(AppUsageCategory.VIDEO, true, 30)
                base[AppUsageCategory.GAMES] = CategoryLimitRule(AppUsageCategory.GAMES, true, 30)
                base[AppUsageCategory.SOCIAL] = CategoryLimitRule(AppUsageCategory.SOCIAL, true, 20)
            }
            CategoryLimitPreset.BALANCED -> {
                base[AppUsageCategory.VIDEO] = CategoryLimitRule(AppUsageCategory.VIDEO, true, 60)
                base[AppUsageCategory.GAMES] = CategoryLimitRule(AppUsageCategory.GAMES, true, 45)
                base[AppUsageCategory.SOCIAL] = CategoryLimitRule(AppUsageCategory.SOCIAL, true, 45)
                base[AppUsageCategory.BROWSING] = CategoryLimitRule(AppUsageCategory.BROWSING, true, 60)
            }
            CategoryLimitPreset.WEEKEND -> {
                base[AppUsageCategory.VIDEO] = CategoryLimitRule(AppUsageCategory.VIDEO, true, 120)
                base[AppUsageCategory.GAMES] = CategoryLimitRule(AppUsageCategory.GAMES, true, 90)
                base[AppUsageCategory.SOCIAL] = CategoryLimitRule(AppUsageCategory.SOCIAL, true, 60)
            }
        }
        return base
    }

    /**
     * Evaluates all configured hard category time limits against today's real UsageStats totals
     * and computes the exact DNS blocklist patterns to enforce in SafeNestDnsVpnService.
     */
    fun evaluate(
        rules: Map<AppUsageCategory, CategoryLimitRule>,
        todayUsage: DailyCategoryUsage?,
        evaluatedAtMs: Long = System.currentTimeMillis()
    ): CategoryEnforcementSnapshot {
        val exceeded = linkedSetOf<AppUsageCategory>()
        val autoBlocked = linkedSetOf<String>()
        val usageMap = todayUsage?.categoryMinutes.orEmpty()
        val appsByCategory = todayUsage?.apps.orEmpty().groupBy { it.category }

        val statuses = AppUsageCategory.entries.map { category ->
            val rule = rules[category] ?: CategoryLimitRule(
                category = category,
                enabled = false,
                limitMinutes = defaultMinutesFor(category)
            )
            val safeLimit = rule.limitMinutes.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)
            val used = (usageMap[category] ?: 0).coerceAtLeast(0)
            val remaining = (safeLimit - used).coerceAtLeast(0)
            val progress = if (safeLimit > 0) (used.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f) else 0f
            val reached = rule.enabled && used >= safeLimit
            val patterns = domainPatternsForCategory(category)

            if (reached) {
                exceeded.add(category)
                autoBlocked.addAll(patterns)
            }

            CategoryLimitStatus(
                category = category,
                enabled = rule.enabled,
                limitMinutes = safeLimit,
                usedMinutes = used,
                remainingMinutes = remaining,
                progressFraction = progress,
                isLimitReached = reached,
                enforcedDomainPatterns = patterns,
                topApps = appsByCategory[category].orEmpty().take(3)
            )
        }

        return CategoryEnforcementSnapshot(
            statuses = statuses,
            exceededCategories = exceeded,
            autoBlockedPatterns = autoBlocked,
            lastEvaluatedMs = evaluatedAtMs
        )
    }

    fun encodeRules(rules: Map<AppUsageCategory, CategoryLimitRule>): String {
        return AppUsageCategory.entries.joinToString("|") { category ->
            val rule = rules[category] ?: CategoryLimitRule(
                category = category,
                enabled = false,
                limitMinutes = defaultMinutesFor(category)
            )
            val flag = if (rule.enabled) 1 else 0
            val mins = rule.limitMinutes.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)
            "${category.name}:$flag:$mins"
        }
    }

    fun decodeRules(raw: String?): Map<AppUsageCategory, CategoryLimitRule> {
        val defaults = defaultRuleMap().toMutableMap()
        if (raw.isNullOrBlank()) return defaults
        raw.split("|").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 3) {
                val category = AppUsageCategory.fromRaw(parts[0]) ?: return@forEach
                val enabled = parts[1] == "1"
                val mins = parts[2].toIntOrNull()?.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)
                    ?: defaultMinutesFor(category)
                defaults[category] = CategoryLimitRule(
                    category = category,
                    enabled = enabled,
                    limitMinutes = mins
                )
            }
        }
        return defaults
    }
}
