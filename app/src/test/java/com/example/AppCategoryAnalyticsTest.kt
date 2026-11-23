package com.example

import com.example.analytics.AppCategoryAnalytics
import com.example.analytics.AppUsageCategory
import com.example.analytics.BehaviorInsightCode
import com.example.analytics.DailyCategoryUsage
import com.example.data.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCategoryAnalyticsTest {

    @Test
    fun classifyApp_mapsKnownAppsToExpectedCategories() {
        assertEquals(AppUsageCategory.VIDEO, AppCategoryAnalytics.classifyApp("com.google.android.youtube", "YouTube"))
        assertEquals(AppUsageCategory.GAMES, AppCategoryAnalytics.classifyApp("com.roblox.client", "Roblox"))
        assertEquals(AppUsageCategory.SOCIAL, AppCategoryAnalytics.classifyApp("com.discord", "Discord"))
        assertEquals(AppUsageCategory.EDUCATION, AppCategoryAnalytics.classifyApp("com.duolingo", "Duolingo"))
        assertEquals(AppUsageCategory.BROWSING, AppCategoryAnalytics.classifyApp("com.android.chrome", "Chrome"))
        assertEquals(AppUsageCategory.OTHER, AppCategoryAnalytics.classifyApp("com.android.calculator2", "Calculator"))
    }

    @Test
    fun fromDailySummary_parsesThreePartAndLegacyTwoPartEntries() {
        val summary = DailySummary(
            id = "s1",
            childId = "c1",
            dateKey = "2026-11-21",
            totalScreenTimeMin = 100L,
            lateNightMin = 15L,
            topApps = listOf(
                "YouTube|40|VIDEO",
                "Duolingo|25",
                "Minecraft|20|GAMES"
            )
        )

        val day = AppCategoryAnalytics.fromDailySummary(summary)
        assertEquals(100, day.totalMinutes)
        assertEquals(40, day.categoryMinutes[AppUsageCategory.VIDEO])
        assertEquals(25, day.categoryMinutes[AppUsageCategory.EDUCATION])
        assertEquals(20, day.categoryMinutes[AppUsageCategory.GAMES])
        // Remaining 15 minutes from totalScreenTimeMin roll into OTHER
        assertEquals(15, day.categoryMinutes[AppUsageCategory.OTHER])
    }

    @Test
    fun buildReport_computesSharesBalanceScoreAndBehavioralInsights() {
        val heavyDay = DailyCategoryUsage(
            dateKey = "2026-11-21",
            shortDayLabel = "Sat",
            totalMinutes = 150,
            lateNightMinutes = 25,
            categoryMinutes = mapOf(
                AppUsageCategory.VIDEO to 90,
                AppUsageCategory.GAMES to 30,
                AppUsageCategory.EDUCATION to 30
            ),
            apps = emptyList()
        )

        val report = AppCategoryAnalytics.buildReport(
            days = listOf(heavyDay),
            dailyLimitMin = 120,
            todayOnly = true
        )

        assertTrue(report.hasData)
        assertEquals(AppUsageCategory.VIDEO, report.dominantCategory)
        assertEquals(60, report.slices.first().sharePercent)
        assertTrue(report.balanceScore in 15..99)
        val codes = report.insights.map { it.code }.toSet()
        assertTrue(codes.contains(BehaviorInsightCode.OVER_DAILY_LIMIT))
        assertTrue(codes.contains(BehaviorInsightCode.LATE_NIGHT_ACTIVITY))
        assertTrue(codes.contains(BehaviorInsightCode.DOMINANT_ENTERTAINMENT))
        assertTrue(codes.contains(BehaviorInsightCode.STRONG_LEARNING_BALANCE))
    }

    @Test
    fun buildSevenDayTimeline_returnsAlwaysSevenOrderedDays() {
        val timeline = AppCategoryAnalytics.buildSevenDayTimeline(
            cloudSummaries = emptyList(),
            deviceHistory = emptyList()
        )
        assertEquals(7, timeline.size)
        assertTrue(timeline.all { it.totalMinutes == 0 })
    }
}
