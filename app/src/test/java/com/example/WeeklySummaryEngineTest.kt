package com.example

import com.example.analytics.AppUsageCategory
import com.example.analytics.DailyCategoryUsage
import com.example.analytics.WeeklySummaryEngine
import com.example.analytics.WeeklyTrendDirection
import com.example.data.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class WeeklySummaryEngineTest {

    private fun anchorMillis(): Long = Calendar.getInstance(Locale.US).apply {
        set(2026, Calendar.NOVEMBER, 22, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dateKeyOffset(anchor: Long, daysAgo: Int): String {
        val cal = Calendar.getInstance(Locale.US).apply {
            timeInMillis = anchor
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @Test
    fun buildWeeklySummary_detectsDownwardImprovementTrendAndCategoryDeltas() {
        val anchor = anchorMillis()
        val deviceHistory = mutableListOf<DailyCategoryUsage>()

        // Previous week (days 7..13 ago): 120m/day total (80m Video, 40m Games) -> 840m total
        for (offset in 7..13) {
            val key = dateKeyOffset(anchor, offset)
            deviceHistory += DailyCategoryUsage(
                dateKey = key,
                shortDayLabel = "Day",
                totalMinutes = 120,
                lateNightMinutes = 10,
                categoryMinutes = mapOf(
                    AppUsageCategory.VIDEO to 80,
                    AppUsageCategory.GAMES to 40
                ),
                apps = emptyList()
            )
        }
        // This week (days 0..6 ago): 90m/day total (50m Video, 40m Education) -> 630m total (-25%)
        for (offset in 0..6) {
            val key = dateKeyOffset(anchor, offset)
            deviceHistory += DailyCategoryUsage(
                dateKey = key,
                shortDayLabel = "Day",
                totalMinutes = 90,
                lateNightMinutes = 0,
                categoryMinutes = mapOf(
                    AppUsageCategory.VIDEO to 50,
                    AppUsageCategory.EDUCATION to 40
                ),
                apps = emptyList()
            )
        }

        val report = WeeklySummaryEngine.buildWeeklySummary(
            childName = "Mia",
            cloudSummaries = emptyList(),
            deviceHistory = deviceHistory,
            dailyLimitMin = 100,
            nowMillis = anchor,
            locale = Locale.US
        )

        assertEquals(630, report.thisWeekTotalMin)
        assertEquals(840, report.previousWeekTotalMin)
        assertEquals(-210, report.deltaMinutes)
        assertEquals(-25, report.deltaPercent)
        assertEquals(WeeklyTrendDirection.DOWN_IMPROVED, report.trendDirection)
        assertEquals(7, report.daysWithinLimitThisWeek)
        assertEquals(0, report.daysWithinLimitPrevWeek)
        assertEquals(AppUsageCategory.EDUCATION, report.topRisingCategory?.category)
        assertEquals(AppUsageCategory.GAMES, report.topImprovedCategory?.category)
        assertTrue(report.hasAnyData)
        assertTrue(report.hasPreviousWeekBaseline)
        assertEquals("2026-W47", report.isoWeekKey)
    }

    @Test
    fun buildWeeklySummary_detectsUpwardSurgeMergingCloudAndDeviceHistory() {
        val anchor = anchorMillis()
        // Previous week from cloud summaries: 60m/day for 5 days = 300m
        val cloud = (7..11).map { offset ->
            DailySummary(
                childId = "c1",
                dateKey = dateKeyOffset(anchor, offset),
                totalScreenTimeMin = 60,
                lateNightMin = 0,
                topApps = listOf("YouTube|60|VIDEO")
            )
        }
        // This week from on-device UsageStats: 100m/day for 5 days = 500m (+67%)
        val device = (0..4).map { offset ->
            DailyCategoryUsage(
                dateKey = dateKeyOffset(anchor, offset),
                shortDayLabel = "Day",
                totalMinutes = 100,
                lateNightMinutes = 15,
                categoryMinutes = mapOf(AppUsageCategory.VIDEO to 100),
                apps = emptyList()
            )
        }

        val report = WeeklySummaryEngine.buildWeeklySummary(
            childName = "Leo",
            cloudSummaries = cloud,
            deviceHistory = device,
            dailyLimitMin = 90,
            nowMillis = anchor,
            locale = Locale.US
        )

        assertEquals(500, report.thisWeekTotalMin)
        assertEquals(300, report.previousWeekTotalMin)
        assertEquals(200, report.deltaMinutes)
        assertEquals(67, report.deltaPercent)
        assertEquals(WeeklyTrendDirection.UP_INCREASED, report.trendDirection)
        assertEquals(0, report.daysWithinLimitThisWeek)
        assertEquals(5, report.daysWithinLimitPrevWeek)
        assertNotNull(report.topRisingCategory)
        assertEquals(AppUsageCategory.VIDEO, report.topRisingCategory?.category)
    }

    @Test
    fun buildWeeklySummary_detectsSteadyAndFirstWeekAndNoDataStates() {
        val anchor = anchorMillis()

        // Empty state
        val emptyReport = WeeklySummaryEngine.buildWeeklySummary(
            childName = "Ava",
            cloudSummaries = emptyList(),
            deviceHistory = emptyList(),
            dailyLimitMin = 120,
            nowMillis = anchor
        )
        assertEquals(WeeklyTrendDirection.NO_DATA, emptyReport.trendDirection)
        assertFalse(emptyReport.hasAnyData)
        assertNull(emptyReport.deltaPercent)

        // First week only (no previous week baseline)
        val firstWeekDevice = listOf(
            DailyCategoryUsage(
                dateKey = dateKeyOffset(anchor, 0),
                shortDayLabel = "Sun",
                totalMinutes = 75,
                lateNightMinutes = 0,
                categoryMinutes = mapOf(AppUsageCategory.GAMES to 75),
                apps = emptyList()
            )
        )
        val firstWeekReport = WeeklySummaryEngine.buildWeeklySummary(
            childName = "Ava",
            cloudSummaries = emptyList(),
            deviceHistory = firstWeekDevice,
            dailyLimitMin = 120,
            nowMillis = anchor
        )
        assertEquals(WeeklyTrendDirection.FIRST_WEEK, firstWeekReport.trendDirection)
        assertTrue(firstWeekReport.hasAnyData)
        assertFalse(firstWeekReport.hasPreviousWeekBaseline)

        // Steady within +-5%
        val steadyHistory = listOf(
            DailyCategoryUsage(
                dateKey = dateKeyOffset(anchor, 8),
                shortDayLabel = "Sat",
                totalMinutes = 100,
                lateNightMinutes = 0,
                categoryMinutes = mapOf(AppUsageCategory.SOCIAL to 100),
                apps = emptyList()
            ),
            DailyCategoryUsage(
                dateKey = dateKeyOffset(anchor, 1),
                shortDayLabel = "Sat",
                totalMinutes = 103,
                lateNightMinutes = 0,
                categoryMinutes = mapOf(AppUsageCategory.SOCIAL to 103),
                apps = emptyList()
            )
        )
        val steadyReport = WeeklySummaryEngine.buildWeeklySummary(
            childName = "Ava",
            cloudSummaries = emptyList(),
            deviceHistory = steadyHistory,
            dailyLimitMin = 120,
            nowMillis = anchor
        )
        assertEquals(WeeklyTrendDirection.STEADY, steadyReport.trendDirection)
        assertEquals(3, steadyReport.deltaPercent)
    }

    @Test
    fun buildFourteenDayTimeline_prefersChildRecordedSummaryOverUnrelatedHostDeviceUsage() {
        val anchor = anchorMillis()
        val todayKey = dateKeyOffset(anchor, 0)
        val childSummary = listOf(
            DailySummary(
                childId = "maya",
                dateKey = todayKey,
                totalScreenTimeMin = 84,
                lateNightMin = 0,
                topApps = listOf("YouTube|30|VIDEO", "Duolingo|24|EDUCATION", "Minecraft|30|GAMES")
            )
        )
        val hostDeviceHistory = listOf(
            DailyCategoryUsage(
                dateKey = todayKey,
                shortDayLabel = "Sun",
                totalMinutes = 420,
                lateNightMinutes = 60,
                categoryMinutes = mapOf(AppUsageCategory.OTHER to 420),
                apps = emptyList()
            )
        )

        val timeline = WeeklySummaryEngine.buildFourteenDayTimeline(
            cloudSummaries = childSummary,
            deviceHistory = hostDeviceHistory,
            nowMillis = anchor
        )
        assertEquals(84, timeline.last().totalMinutes)
    }
}
