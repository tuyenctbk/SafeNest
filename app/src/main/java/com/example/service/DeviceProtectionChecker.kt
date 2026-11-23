package com.example.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.example.analytics.AppCategoryAnalytics
import com.example.analytics.AppUsageCategory
import com.example.analytics.CategorizedAppUsage
import com.example.analytics.DailyCategoryUsage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DeviceHealthStatus(
    val vpnRunning: Boolean,
    val usageAccessGranted: Boolean,
    val notificationAccessGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val batteryPercent: Int,
    val deviceModel: String,
    val manufacturer: String
) {
    val readinessScore: Int
        get() {
            var score = 0
            if (vpnRunning) score += 30
            if (usageAccessGranted) score += 25
            if (notificationAccessGranted) score += 25
            if (batteryOptimizationIgnored) score += 20
            return score
        }
}

data class InstalledLauncherApp(
    val packageName: String,
    val label: String
)

data class DeviceUsageSnapshot(
    val dateKey: String,
    val totalMinutes: Int,
    val lateNightMinutes: Int,
    val topApps: List<Pair<String, Int>>,
    val categorizedApps: List<CategorizedAppUsage> = emptyList(),
    val usagePermissionGranted: Boolean
)

class DeviceProtectionChecker(private val context: Context) {

    private val appContext = context.applicationContext

    fun inspectHealth(vpnRunning: Boolean): DeviceHealthStatus {
        return DeviceHealthStatus(
            vpnRunning = vpnRunning,
            usageAccessGranted = hasUsageStatsPermission(),
            notificationAccessGranted = hasNotificationListenerPermission(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
            batteryPercent = readBatteryPercent(),
            deviceModel = Build.MODEL.ifBlank { "Android Device" }.take(60),
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        )
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotificationListenerPermission(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val expectedComponent = ComponentName(appContext, SafeNestNotificationListener::class.java)
        return enabledListeners.contains(expectedComponent.flattenToString()) ||
            enabledListeners.contains(expectedComponent.flattenToShortString())
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun readBatteryPercent(): Int {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else 100
    }

    /**
     * Reads real daily foreground app usage via UsageStatsManager when Usage Access is granted.
     */
    fun readTodayUsage(): DeviceUsageSnapshot {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (!hasUsageStatsPermission()) {
            return DeviceUsageSnapshot(
                dateKey = dateKey,
                totalMinutes = 0,
                lateNightMinutes = 0,
                topApps = emptyList(),
                categorizedApps = emptyList(),
                usagePermissionGranted = false
            )
        }

        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return DeviceUsageSnapshot(dateKey, 0, 0, emptyList(), emptyList(), false)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val dayUsage = queryDayCategoryUsage(usm, startOfDay, now, dateKey, includeLateNight = true)
        val topApps = dayUsage.apps.take(8).map { it.appName.take(40) to it.minutes }

        return DeviceUsageSnapshot(
            dateKey = dateKey,
            totalMinutes = dayUsage.totalMinutes,
            lateNightMinutes = dayUsage.lateNightMinutes,
            topApps = topApps,
            categorizedApps = dayUsage.apps.take(10),
            usagePermissionGranted = true
        )
    }

    /**
     * Reads today's live categorized foreground app usage since midnight via UsageStatsManager.
     * Returns null when Usage Access permission is not granted.
     */
    fun readTodayCategoryUsage(): DailyCategoryUsage? {
        if (!hasUsageStatsPermission()) return null
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        return queryDayCategoryUsage(usm, cal.timeInMillis, now, dateKey, includeLateNight = true)
    }

    /**
     * Reads real per-day, per-category foreground app usage for the last [days] days via UsageStatsManager.
     */
    fun readRecentDailyCategoryUsage(days: Int = 7): List<DailyCategoryUsage> {
        if (!hasUsageStatsPermission()) return emptyList()
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val results = mutableListOf<DailyCategoryUsage>()
        for (offset in (days - 1) downTo 0) {
            val dayStartCal = (todayStart.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -offset)
            }
            val startMs = dayStartCal.timeInMillis
            val endMs = if (offset == 0) {
                now
            } else {
                (dayStartCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis - 1L
            }
            val dateKey = dateFormat.format(dayStartCal.time)
            results += queryDayCategoryUsage(usm, startMs, endMs, dateKey, includeLateNight = offset == 0)
        }
        return results
    }

    private fun queryDayCategoryUsage(
        usm: UsageStatsManager,
        startOfDay: Long,
        endOfDay: Long,
        dateKey: String,
        includeLateNight: Boolean
    ): DailyCategoryUsage {
        val pm = appContext.packageManager
        val excludedPkgs = buildExcludedPackages(pm)
        val launcherPkgs = loadLauncherPackageSet(pm)

        // Primary: exact per-app foreground time strictly within [startOfDay, endOfDay] via UsageEvents.
        // Avoids Android's notorious INTERVAL_DAILY multi-day bucket overlap and idle home-launcher inflation.
        val perAppMs = measurePerAppForegroundMs(
            usm = usm,
            windowStart = startOfDay,
            windowEnd = endOfDay,
            excludedPkgs = excludedPkgs,
            launcherPkgs = launcherPkgs
        ).toMutableMap()

        // Fallback for older historical days (>24h ago) where UsageEvents retention may have expired.
        val isHistoricalDay = (System.currentTimeMillis() - endOfDay) > 24L * 60L * 60L * 1000L
        if (perAppMs.isEmpty() && isHistoricalDay) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, endOfDay).orEmpty()
            for (entry in stats) {
                val pkg = entry.packageName ?: continue
                if (pkg in excludedPkgs) continue
                if (launcherPkgs.isNotEmpty() && pkg !in launcherPkgs) continue
                val fgMs = entry.totalTimeInForeground
                if (fgMs < 60_000L || fgMs > 12L * 60L * 60L * 1000L) continue
                if (entry.firstTimeStamp < startOfDay - 3_600_000L || entry.lastTimeStamp > endOfDay + 3_600_000L) continue
                if (entry.lastTimeUsed < startOfDay || entry.lastTimeUsed > endOfDay) continue
                perAppMs[pkg] = (perAppMs[pkg] ?: 0L) + fgMs
            }
        }

        val perAppMap = linkedMapOf<String, CategorizedAppUsage>()
        val categoryMinutes = linkedMapOf<AppUsageCategory, Int>()
        var totalMs = 0L

        for ((pkg, fgMs) in perAppMs) {
            if (fgMs < 60_000L) continue
            totalMs += fgMs
            val (label, androidCat) = resolveAppInfo(pm, pkg)
            val category = AppCategoryAnalytics.classifyApp(
                packageName = pkg,
                appName = label,
                androidCategory = androidCat
            )
            val mins = (fgMs / 60_000L).toInt()
            val cleanLabel = label.take(40)
            val existing = perAppMap[cleanLabel]
            perAppMap[cleanLabel] = CategorizedAppUsage(
                appName = cleanLabel,
                packageName = pkg,
                minutes = (existing?.minutes ?: 0) + mins,
                category = category
            )
            categoryMinutes[category] = (categoryMinutes[category] ?: 0) + mins
        }

        val totalMinutes = (totalMs / 60_000L).toInt().coerceIn(0, 1440)
        var lateNightMinutes = 0
        if (includeLateNight && endOfDay > startOfDay) {
            val cal = Calendar.getInstance().apply { timeInMillis = startOfDay }
            val fiveAm = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 5) }.timeInMillis
            val tenPm = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 22) }.timeInMillis
            var lateNightMs = 0L
            val earlyEnd = minOf(endOfDay, fiveAm)
            if (earlyEnd > startOfDay) {
                lateNightMs += measurePerAppForegroundMs(usm, startOfDay, earlyEnd, excludedPkgs, launcherPkgs).values.sum()
            }
            if (endOfDay > tenPm) {
                lateNightMs += measurePerAppForegroundMs(usm, tenPm, endOfDay, excludedPkgs, launcherPkgs).values.sum()
            }
            lateNightMinutes = (lateNightMs / 60_000L).toInt().coerceIn(0, minOf(totalMinutes, 600))
        }

        val sortedApps = perAppMap.values.sortedByDescending { it.minutes }
        return DailyCategoryUsage(
            dateKey = dateKey,
            shortDayLabel = AppCategoryAnalytics.formatShortDayLabel(dateKey),
            totalMinutes = totalMinutes,
            lateNightMinutes = lateNightMinutes,
            categoryMinutes = categoryMinutes,
            apps = sortedApps
        )
    }

    private fun buildExcludedPackages(pm: PackageManager): Set<String> {
        val excluded = HashSet<String>(
            listOf(
                appContext.packageName,
                "android",
                "com.android.systemui",
                "com.android.settings",
                "com.android.vending",
                "com.android.launcher",
                "com.android.launcher3",
                "com.google.android.apps.nexuslauncher",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.sec.android.app.launcher",
                "com.miui.home",
                "com.oppo.launcher",
                "com.huawei.android.launcher",
                "com.oneplus.launcher"
            )
        )
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, 0).forEach { info ->
                info.activityInfo?.packageName?.let { excluded.add(it) }
            }
        }
        return excluded
    }

    private fun loadLauncherPackageSet(pm: PackageManager): Set<String> {
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Measures exact per-package foreground activity time strictly within [windowStart, windowEnd] via UsageEvents,
     * excluding home launchers and system UI packages.
     */
    private fun measurePerAppForegroundMs(
        usm: UsageStatsManager,
        windowStart: Long,
        windowEnd: Long,
        excludedPkgs: Set<String>,
        launcherPkgs: Set<String>
    ): Map<String, Long> {
        if (windowEnd <= windowStart) return emptyMap()
        val events = runCatching { usm.queryEvents(windowStart, windowEnd) }.getOrNull() ?: return emptyMap()
        val event = UsageEvents.Event()
        val activeStartByPkg = HashMap<String, Long>()
        val durationByPkg = HashMap<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg in excludedPkgs) continue
            if (launcherPkgs.isNotEmpty() && pkg !in launcherPkgs) continue
            val ts = event.timeStamp.coerceIn(windowStart, windowEnd)
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    activeStartByPkg.putIfAbsent(pkg, ts)
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val startedAt = activeStartByPkg.remove(pkg)
                    if (startedAt != null && ts > startedAt) {
                        durationByPkg[pkg] = (durationByPkg[pkg] ?: 0L) + (ts - startedAt)
                    }
                }
            }
        }
        // Close any session still active at the end of the window (bounded to <= 2 hours to avoid stuck events)
        activeStartByPkg.forEach { (pkg, startedAt) ->
            if (windowEnd > startedAt) {
                val delta = minOf(windowEnd - startedAt, 2L * 60L * 60L * 1000L)
                durationByPkg[pkg] = (durationByPkg[pkg] ?: 0L) + delta
            }
        }
        return durationByPkg
    }

    private fun resolveAppInfo(pm: PackageManager, packageName: String): Pair<String, Int?> {
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(ai).toString().ifBlank { packageName.substringAfterLast('.') }
            val cat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ai.category else null
            label to cat
        } catch (_: Exception) {
            packageName.substringAfterLast('.') to null
        }
    }

    /**
     * Queries installed launcher apps using Play-policy compliant ACTION_MAIN/CATEGORY_LAUNCHER intent.
     */
    fun loadInstalledLauncherApps(): List<InstalledLauncherApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pm = appContext.packageManager
        return try {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == appContext.packageName) return@mapNotNull null
                    val label = resolveInfo.loadLabel(pm).toString().ifBlank { pkg }
                    InstalledLauncherApp(packageName = pkg, label = label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
