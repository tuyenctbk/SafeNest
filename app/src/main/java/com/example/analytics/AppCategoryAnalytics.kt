package com.example.analytics

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R
import com.example.data.DailySummary
import com.example.ui.theme.AvatarAmber
import com.example.ui.theme.AvatarCoral
import com.example.ui.theme.AvatarIndigo
import com.example.ui.theme.AvatarSage
import com.example.ui.theme.AvatarTeal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class AppUsageCategory(
    @StringRes val labelRes: Int,
    val chartColor: Color,
    val icon: ImageVector
) {
    VIDEO(R.string.category_video, AvatarCoral, Icons.Filled.PlayCircle),
    GAMES(R.string.category_games, AvatarAmber, Icons.Filled.SportsEsports),
    SOCIAL(R.string.category_social, AvatarIndigo, Icons.Filled.Forum),
    EDUCATION(R.string.category_education, AvatarSage, Icons.Filled.School),
    BROWSING(R.string.category_browsing, AvatarTeal, Icons.Filled.Language),
    OTHER(R.string.category_other, Color(0xFF6B7F7B), Icons.Filled.Apps);

    companion object {
        fun fromRaw(raw: String?): AppUsageCategory? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class CategorizedAppUsage(
    val appName: String,
    val packageName: String? = null,
    val minutes: Int,
    val category: AppUsageCategory
)

data class DailyCategoryUsage(
    val dateKey: String,
    val shortDayLabel: String,
    val totalMinutes: Int,
    val lateNightMinutes: Int,
    val categoryMinutes: Map<AppUsageCategory, Int>,
    val apps: List<CategorizedAppUsage>
)

data class CategorySlice(
    val category: AppUsageCategory,
    val minutes: Int,
    val sharePercent: Int,
    val apps: List<CategorizedAppUsage>
)

enum class BehaviorInsightCode {
    DOMINANT_ENTERTAINMENT,
    LATE_NIGHT_ACTIVITY,
    OVER_DAILY_LIMIT,
    STRONG_LEARNING_BALANCE,
    HEALTHY_WITHIN_LIMIT
}

data class BehaviorInsight(
    val code: BehaviorInsightCode,
    val isWarning: Boolean,
    val primaryArg: Int = 0,
    val secondaryArg: Int = 0,
    val category: AppUsageCategory? = null
)

data class UsageInsightsReport(
    val days: List<DailyCategoryUsage>,
    val totalMinutes: Int,
    val averageDailyMinutes: Int,
    val lateNightMinutes: Int,
    val dailyLimitMin: Int,
    val slices: List<CategorySlice>,
    val dominantCategory: AppUsageCategory?,
    val balanceScore: Int,
    val insights: List<BehaviorInsight>
) {
    val hasData: Boolean
        get() = totalMinutes > 0 || slices.any { it.minutes > 0 }
}

object AppCategoryAnalytics {

    private val videoKeywords = listOf(
        "youtube", "netflix", "disney", "hulu", "twitch", "primevideo", "tiktok",
        "musically", "vimeo", "crunchyroll", "bilibili", "video", "player", "movies"
    )
    private val gameKeywords = listOf(
        "game", "roblox", "minecraft", "mojang", "supercell", "clash", "brawl",
        "pubg", "garena", "freefire", "genshin", "mihoyo", "pokemon", "niantic",
        "amongus", "subway", "candy", "unity", "epicgames", "ea.", "gameloft", "chess"
    )
    private val socialKeywords = listOf(
        "instagram", "facebook", "orca", "messenger", "whatsapp", "snapchat",
        "discord", "telegram", "twitter", "x.", "reddit", "pinterest", "zalo",
        "line", "kakaotalk", "wechat", "viber", "signal", "threads", "skype", "chat"
    )
    private val educationKeywords = listOf(
        "duolingo", "khan", "classroom", "docs", "sheets", "slides", "quizlet",
        "coursera", "udemy", "photomath", "brainly", "kindle", "books", "wikipedia",
        "anki", "notion", "study", "learn", "math", "school", "edu", "dictionary", "translate"
    )
    private val browsingKeywords = listOf(
        "chrome", "firefox", "opera", "brave", "edge", "browser", "duckduckgo",
        "samsung.android.app.sbrowser", "googlequicksearchbox", "search", "web"
    )

    fun classifyApp(
        packageName: String?,
        appName: String,
        androidCategory: Int? = null
    ): AppUsageCategory {
        val pkg = packageName.orEmpty().lowercase(Locale.US)
        val label = appName.lowercase(Locale.US)
        val combined = "$pkg $label"

        // High-precision keyword overrides first (e.g., TikTok & YouTube are Video, Duolingo is Education)
        if (educationKeywords.any { combined.contains(it) }) return AppUsageCategory.EDUCATION
        if (videoKeywords.any { combined.contains(it) }) return AppUsageCategory.VIDEO
        if (gameKeywords.any { combined.contains(it) }) return AppUsageCategory.GAMES
        if (socialKeywords.any { combined.contains(it) }) return AppUsageCategory.SOCIAL
        if (browsingKeywords.any { combined.contains(it) }) return AppUsageCategory.BROWSING

        // Fall back to Android PackageManager ApplicationInfo.category (API 26+)
        if (androidCategory != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (androidCategory) {
                ApplicationInfo.CATEGORY_GAME -> return AppUsageCategory.GAMES
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> return AppUsageCategory.VIDEO
                ApplicationInfo.CATEGORY_SOCIAL -> return AppUsageCategory.SOCIAL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppUsageCategory.EDUCATION
                ApplicationInfo.CATEGORY_NEWS -> return AppUsageCategory.BROWSING
            }
        }

        return AppUsageCategory.OTHER
    }

    /**
     * Parses a Firestore DailySummary entry into a DailyCategoryUsage bucket.
     * Supports both 3-part "AppName|minutes|CATEGORY" and legacy 2-part "AppName|minutes".
     */
    fun fromDailySummary(summary: DailySummary): DailyCategoryUsage {
        val parsedApps = summary.topApps.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size >= 2) {
                val name = parts[0].trim()
                val mins = parts[1].trim().toIntOrNull() ?: 0
                if (name.isBlank() || mins <= 0) return@mapNotNull null
                val explicitCat = parts.getOrNull(2)?.let { AppUsageCategory.fromRaw(it) }
                val cat = explicitCat ?: classifyApp(packageName = null, appName = name)
                CategorizedAppUsage(appName = name, minutes = mins, category = cat)
            } else {
                null
            }
        }

        val catMap = linkedMapOf<AppUsageCategory, Int>()
        parsedApps.forEach { app ->
            catMap[app.category] = (catMap[app.category] ?: 0) + app.minutes
        }

        val appsSum = catMap.values.sum()
        val total = maxOf(summary.totalScreenTimeMin.toInt(), appsSum)
        val remainder = (total - appsSum).coerceAtLeast(0)
        if (remainder > 0) {
            catMap[AppUsageCategory.OTHER] = (catMap[AppUsageCategory.OTHER] ?: 0) + remainder
        }

        return DailyCategoryUsage(
            dateKey = summary.dateKey,
            shortDayLabel = formatShortDayLabel(summary.dateKey),
            totalMinutes = total,
            lateNightMinutes = summary.lateNightMin.toInt().coerceAtLeast(0),
            categoryMinutes = catMap,
            apps = parsedApps.sortedByDescending { it.minutes }
        )
    }

    /**
     * Merges cloud-synced DailySummaries with real on-device UsageStatsManager 7-day history
     * so parents see continuous 7-day category trends without duplicating today's totals.
     */
    fun buildSevenDayTimeline(
        cloudSummaries: List<DailySummary>,
        deviceHistory: List<DailyCategoryUsage>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<DailyCategoryUsage> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cloudByDate = cloudSummaries
            .map { fromDailySummary(it) }
            .associateBy { it.dateKey }
        val deviceByDate = deviceHistory.associateBy { it.dateKey }

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val timeline = mutableListOf<DailyCategoryUsage>()
        for (offset in 6 downTo 0) {
            val dayCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = dateFormat.format(dayCal.time)
            val deviceEntry = deviceByDate[key]
            val cloudEntry = cloudByDate[key]

            val chosen = when {
                deviceEntry != null && cloudEntry != null -> {
                    if (deviceEntry.totalMinutes >= cloudEntry.totalMinutes) deviceEntry else cloudEntry
                }
                cloudEntry != null -> cloudEntry
                deviceEntry != null -> deviceEntry
                else -> DailyCategoryUsage(
                    dateKey = key,
                    shortDayLabel = formatShortDayLabel(key),
                    totalMinutes = 0,
                    lateNightMinutes = 0,
                    categoryMinutes = emptyMap(),
                    apps = emptyList()
                )
            }
            timeline += chosen
        }
        return timeline
    }

    fun buildReport(
        days: List<DailyCategoryUsage>,
        dailyLimitMin: Int,
        todayOnly: Boolean
    ): UsageInsightsReport {
        val scopedDays = if (todayOnly) listOfNotNull(days.lastOrNull()) else days
        val activeDayCount = scopedDays.count { it.totalMinutes > 0 }.coerceAtLeast(1)
        val totalMinutes = scopedDays.sumOf { it.totalMinutes }
        val averageDailyMinutes = if (todayOnly) {
            totalMinutes
        } else {
            (totalMinutes.toFloat() / activeDayCount.toFloat()).roundToInt()
        }
        val lateNightMinutes = scopedDays.sumOf { it.lateNightMinutes }

        val categoryTotals = linkedMapOf<AppUsageCategory, Int>()
        val appsByCategory = linkedMapOf<AppUsageCategory, MutableMap<String, Int>>()
        scopedDays.forEach { day ->
            day.categoryMinutes.forEach { (cat, mins) ->
                if (mins > 0) {
                    categoryTotals[cat] = (categoryTotals[cat] ?: 0) + mins
                }
            }
            day.apps.forEach { app ->
                val map = appsByCategory.getOrPut(app.category) { linkedMapOf() }
                map[app.appName] = (map[app.appName] ?: 0) + app.minutes
            }
        }

        val totalForShares = categoryTotals.values.sum().coerceAtLeast(1)
        val slices = categoryTotals.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { (cat, mins) ->
                val pct = ((mins.toFloat() / totalForShares.toFloat()) * 100f).roundToInt().coerceIn(1, 100)
                val catApps = appsByCategory[cat].orEmpty()
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { (name, m) -> CategorizedAppUsage(appName = name, minutes = m, category = cat) }
                CategorySlice(
                    category = cat,
                    minutes = mins,
                    sharePercent = pct,
                    apps = catApps
                )
            }

        val dominant = slices.firstOrNull()?.category
        val safeLimit = dailyLimitMin.coerceAtLeast(15)

        // Digital Balance Score (0–100)
        var score = 100
        val limitRatio = averageDailyMinutes.toFloat() / safeLimit.toFloat()
        if (limitRatio > 1f) {
            score -= ((limitRatio - 1f) * 45f).roundToInt().coerceAtMost(40)
        } else if (limitRatio > 0.85f) {
            score -= 8
        }
        val avgLateNight = lateNightMinutes.toFloat() / activeDayCount.toFloat()
        if (avgLateNight >= 30f) {
            score -= 22
        } else if (avgLateNight >= 10f) {
            score -= 10
        }
        val entertainmentMins = (categoryTotals[AppUsageCategory.VIDEO] ?: 0) +
            (categoryTotals[AppUsageCategory.GAMES] ?: 0) +
            (categoryTotals[AppUsageCategory.SOCIAL] ?: 0)
        val entertainmentShare = if (totalMinutes > 0) {
            (entertainmentMins * 100) / totalMinutes
        } else {
            0
        }
        if (entertainmentShare >= 75 && totalMinutes >= 45) {
            score -= 14
        }
        val educationMins = categoryTotals[AppUsageCategory.EDUCATION] ?: 0
        val educationShare = if (totalMinutes > 0) (educationMins * 100) / totalMinutes else 0
        if (educationShare >= 20) {
            score += 6
        }
        val finalScore = if (totalMinutes == 0) 100 else score.coerceIn(15, 100)

        // Behavioral insights for parents
        val insights = mutableListOf<BehaviorInsight>()
        if (totalMinutes > 0) {
            if (averageDailyMinutes > safeLimit) {
                insights += BehaviorInsight(
                    code = BehaviorInsightCode.OVER_DAILY_LIMIT,
                    isWarning = true,
                    primaryArg = averageDailyMinutes,
                    secondaryArg = safeLimit
                )
            } else {
                insights += BehaviorInsight(
                    code = BehaviorInsightCode.HEALTHY_WITHIN_LIMIT,
                    isWarning = false,
                    primaryArg = averageDailyMinutes,
                    secondaryArg = safeLimit
                )
            }

            if (lateNightMinutes >= 15) {
                insights += BehaviorInsight(
                    code = BehaviorInsightCode.LATE_NIGHT_ACTIVITY,
                    isWarning = true,
                    primaryArg = lateNightMinutes
                )
            }

            val topSlice = slices.firstOrNull()
            if (topSlice != null &&
                topSlice.sharePercent >= 50 &&
                topSlice.category in setOf(AppUsageCategory.VIDEO, AppUsageCategory.GAMES, AppUsageCategory.SOCIAL)
            ) {
                insights += BehaviorInsight(
                    code = BehaviorInsightCode.DOMINANT_ENTERTAINMENT,
                    isWarning = true,
                    primaryArg = topSlice.sharePercent,
                    category = topSlice.category
                )
            }

            if (educationShare >= 15) {
                insights += BehaviorInsight(
                    code = BehaviorInsightCode.STRONG_LEARNING_BALANCE,
                    isWarning = false,
                    primaryArg = educationShare,
                    secondaryArg = educationMins
                )
            }
        }

        return UsageInsightsReport(
            days = days,
            totalMinutes = totalMinutes,
            averageDailyMinutes = averageDailyMinutes,
            lateNightMinutes = lateNightMinutes,
            dailyLimitMin = safeLimit,
            slices = slices,
            dominantCategory = dominant,
            balanceScore = finalScore,
            insights = insights
        )
    }

    fun formatShortDayLabel(dateKey: String, locale: Locale = Locale.getDefault()): String {
        return try {
            val parsed: Date? = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
            if (parsed != null) {
                SimpleDateFormat("EEE", locale).format(parsed)
            } else {
                dateKey.takeLast(2)
            }
        } catch (_: Exception) {
            dateKey.takeLast(2)
        }
    }
}
