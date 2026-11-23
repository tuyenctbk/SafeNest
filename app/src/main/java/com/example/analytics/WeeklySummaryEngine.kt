package com.example.analytics

import com.example.data.DailySummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class WeeklyTrendDirection {
    DOWN_IMPROVED,
    UP_INCREASED,
    STEADY,
    FIRST_WEEK,
    NO_DATA
}

data class CategoryWeeklyDelta(
    val category: AppUsageCategory,
    val thisWeekMinutes: Int,
    val previousWeekMinutes: Int,
    val deltaMinutes: Int
)

data class WeeklySummaryReport(
    val childName: String,
    val isoWeekKey: String,
    val weekRangeLabel: String,
    val thisWeekDays: List<DailyCategoryUsage>,
    val previousWeekDays: List<DailyCategoryUsage>,
    val thisWeekTotalMin: Int,
    val previousWeekTotalMin: Int,
    val thisWeekDailyAvgMin: Int,
    val previousWeekDailyAvgMin: Int,
    val deltaMinutes: Int,
    val deltaPercent: Int?,
    val trendDirection: WeeklyTrendDirection,
    val dailyLimitMin: Int,
    val activeDaysThisWeek: Int,
    val daysWithinLimitThisWeek: Int,
    val daysWithinLimitPrevWeek: Int,
    val lateNightThisWeekMin: Int,
    val lateNightPrevWeekMin: Int,
    val categoryDeltas: List<CategoryWeeklyDelta>,
    val topRisingCategory: CategoryWeeklyDelta?,
    val topImprovedCategory: CategoryWeeklyDelta?
) {
    val hasAnyData: Boolean
        get() = thisWeekTotalMin > 0 || previousWeekTotalMin > 0

    val hasPreviousWeekBaseline: Boolean
        get() = previousWeekTotalMin > 0
}

object WeeklySummaryEngine {

    private const val STEADY_THRESHOLD_PERCENT = 5

    /**
     * Merges cloud-synced DailySummaries with real on-device UsageStatsManager 14-day history
     * into a continuous 14-day timeline (days 13..7 ago = previous week, days 6..0 ago = this week).
     */
    fun buildFourteenDayTimeline(
        cloudSummaries: List<DailySummary>,
        deviceHistory: List<DailyCategoryUsage>,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault()
    ): List<DailyCategoryUsage> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cloudByDate = cloudSummaries
            .map { AppCategoryAnalytics.fromDailySummary(it) }
            .associateBy { it.dateKey }
        val deviceByDate = deviceHistory.associateBy { it.dateKey }

        val anchor = Calendar.getInstance(Locale.US).apply { timeInMillis = nowMillis }
        val timeline = ArrayList<DailyCategoryUsage>(14)
        for (offset in 13 downTo 0) {
            val dayCal = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
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
                    shortDayLabel = AppCategoryAnalytics.formatShortDayLabel(key, locale),
                    totalMinutes = 0,
                    lateNightMinutes = 0,
                    categoryMinutes = emptyMap(),
                    apps = emptyList()
                )
            }
            timeline += chosen.copy(
                shortDayLabel = AppCategoryAnalytics.formatShortDayLabel(key, locale)
            )
        }
        return timeline
    }

    fun buildWeeklySummary(
        childName: String,
        cloudSummaries: List<DailySummary>,
        deviceHistory: List<DailyCategoryUsage>,
        dailyLimitMin: Int,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault()
    ): WeeklySummaryReport {
        val timeline = buildFourteenDayTimeline(
            cloudSummaries = cloudSummaries,
            deviceHistory = deviceHistory,
            nowMillis = nowMillis,
            locale = locale
        )
        val previousWeekDays = timeline.subList(0, 7)
        val thisWeekDays = timeline.subList(7, 14)

        val safeLimit = dailyLimitMin.coerceAtLeast(15)
        val thisWeekTotal = thisWeekDays.sumOf { it.totalMinutes }
        val prevWeekTotal = previousWeekDays.sumOf { it.totalMinutes }

        val activeDaysThisWeek = thisWeekDays.count { it.totalMinutes > 0 }
        val activeDaysPrevWeek = previousWeekDays.count { it.totalMinutes > 0 }

        val thisWeekAvg = if (activeDaysThisWeek > 0) {
            (thisWeekTotal.toFloat() / activeDaysThisWeek.toFloat()).roundToInt()
        } else {
            0
        }
        val prevWeekAvg = if (activeDaysPrevWeek > 0) {
            (prevWeekTotal.toFloat() / activeDaysPrevWeek.toFloat()).roundToInt()
        } else {
            0
        }

        val deltaMinutes = thisWeekTotal - prevWeekTotal
        val deltaPercent: Int? = if (prevWeekTotal > 0) {
            ((deltaMinutes.toFloat() / prevWeekTotal.toFloat()) * 100f).roundToInt()
        } else {
            null
        }

        val trendDirection = when {
            thisWeekTotal == 0 && prevWeekTotal == 0 -> WeeklyTrendDirection.NO_DATA
            prevWeekTotal == 0 -> WeeklyTrendDirection.FIRST_WEEK
            deltaPercent != null && deltaPercent <= -STEADY_THRESHOLD_PERCENT -> WeeklyTrendDirection.DOWN_IMPROVED
            deltaPercent != null && deltaPercent >= STEADY_THRESHOLD_PERCENT -> WeeklyTrendDirection.UP_INCREASED
            else -> WeeklyTrendDirection.STEADY
        }

        val daysWithinLimitThisWeek = thisWeekDays.count { it.totalMinutes in 1..safeLimit }
        val daysWithinLimitPrevWeek = previousWeekDays.count { it.totalMinutes in 1..safeLimit }

        val lateNightThisWeek = thisWeekDays.sumOf { it.lateNightMinutes }
        val lateNightPrevWeek = previousWeekDays.sumOf { it.lateNightMinutes }

        val thisWeekByCat = linkedMapOf<AppUsageCategory, Int>()
        thisWeekDays.forEach { day ->
            day.categoryMinutes.forEach { (cat, mins) ->
                if (mins > 0) thisWeekByCat[cat] = (thisWeekByCat[cat] ?: 0) + mins
            }
        }
        val prevWeekByCat = linkedMapOf<AppUsageCategory, Int>()
        previousWeekDays.forEach { day ->
            day.categoryMinutes.forEach { (cat, mins) ->
                if (mins > 0) prevWeekByCat[cat] = (prevWeekByCat[cat] ?: 0) + mins
            }
        }

        val categoryDeltas = AppUsageCategory.entries.mapNotNull { cat ->
            val cur = thisWeekByCat[cat] ?: 0
            val prev = prevWeekByCat[cat] ?: 0
            if (cur == 0 && prev == 0) {
                null
            } else {
                CategoryWeeklyDelta(
                    category = cat,
                    thisWeekMinutes = cur,
                    previousWeekMinutes = prev,
                    deltaMinutes = cur - prev
                )
            }
        }.sortedByDescending { abs(it.deltaMinutes) }

        val topRisingCategory = if (prevWeekTotal > 0) {
            categoryDeltas.filter { it.deltaMinutes > 0 }.maxByOrNull { it.deltaMinutes }
        } else {
            null
        }
        val topImprovedCategory = if (prevWeekTotal > 0) {
            categoryDeltas.filter { it.deltaMinutes < 0 }.minByOrNull { it.deltaMinutes }
        } else {
            null
        }

        return WeeklySummaryReport(
            childName = childName,
            isoWeekKey = buildIsoWeekKey(nowMillis),
            weekRangeLabel = formatWeekRangeLabel(
                startDateKey = thisWeekDays.firstOrNull()?.dateKey.orEmpty(),
                endDateKey = thisWeekDays.lastOrNull()?.dateKey.orEmpty(),
                locale = locale
            ),
            thisWeekDays = thisWeekDays,
            previousWeekDays = previousWeekDays,
            thisWeekTotalMin = thisWeekTotal,
            previousWeekTotalMin = prevWeekTotal,
            thisWeekDailyAvgMin = thisWeekAvg,
            previousWeekDailyAvgMin = prevWeekAvg,
            deltaMinutes = deltaMinutes,
            deltaPercent = deltaPercent,
            trendDirection = trendDirection,
            dailyLimitMin = safeLimit,
            activeDaysThisWeek = activeDaysThisWeek,
            daysWithinLimitThisWeek = daysWithinLimitThisWeek,
            daysWithinLimitPrevWeek = daysWithinLimitPrevWeek,
            lateNightThisWeekMin = lateNightThisWeek,
            lateNightPrevWeekMin = lateNightPrevWeek,
            categoryDeltas = categoryDeltas,
            topRisingCategory = topRisingCategory,
            topImprovedCategory = topImprovedCategory
        )
    }

    fun buildIsoWeekKey(nowMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(Locale.US).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = nowMillis
        }
        val weekYear = if (cal.isWeekDateSupported) cal.weekYear else cal.get(Calendar.YEAR)
        val weekOfYear = cal.get(Calendar.WEEK_OF_YEAR)
        return String.format(Locale.US, "%04d-W%02d", weekYear, weekOfYear)
    }

    fun formatCompactDuration(minutes: Int): String {
        val safe = abs(minutes)
        if (safe >= 60) {
            val hours = safe / 60
            val rem = safe % 60
            return if (rem == 0) "${hours}h" else "${hours}h ${rem}m"
        }
        return "${safe}m"
    }

    private fun formatWeekRangeLabel(
        startDateKey: String,
        endDateKey: String,
        locale: Locale
    ): String {
        if (startDateKey.isBlank() || endDateKey.isBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val formatter = SimpleDateFormat("MMM d", locale)
            val start = parser.parse(startDateKey)
            val end = parser.parse(endDateKey)
            if (start != null && end != null) {
                "${formatter.format(start)} – ${formatter.format(end)}"
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
