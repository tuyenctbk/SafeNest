package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.analytics.WeeklySummaryEngine
import com.example.analytics.WeeklySummaryReport
import com.example.analytics.WeeklyTrendDirection
import kotlin.math.abs

object WeeklySummaryNotifier {

    const val CHANNEL_ID = "safenest_weekly_summary"
    const val NOTIFICATION_ID = 4107
    const val EXTRA_OPEN_DEST = "safenest_open_dest"
    const val DEST_WEEKLY_SUMMARY = "weekly_summary"

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.weekly_notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.weekly_notif_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun showWeeklySummaryNotification(
        context: Context,
        report: WeeklySummaryReport
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureChannel(context)

        val title = context.getString(R.string.weekly_notif_title, report.childName)
        val thisWeekText = WeeklySummaryEngine.formatCompactDuration(report.thisWeekTotalMin)
        val summaryLine = when (report.trendDirection) {
            WeeklyTrendDirection.DOWN_IMPROVED -> context.getString(
                R.string.weekly_notif_summary_down,
                thisWeekText,
                abs(report.deltaPercent ?: 0)
            )
            WeeklyTrendDirection.UP_INCREASED -> context.getString(
                R.string.weekly_notif_summary_up,
                thisWeekText,
                abs(report.deltaPercent ?: 0)
            )
            WeeklyTrendDirection.STEADY -> context.getString(
                R.string.weekly_notif_summary_steady,
                thisWeekText
            )
            WeeklyTrendDirection.FIRST_WEEK -> context.getString(
                R.string.weekly_notif_summary_first_week,
                thisWeekText
            )
            WeeklyTrendDirection.NO_DATA -> context.getString(
                R.string.weekly_notif_summary_empty
            )
        }

        val details = buildList {
            add(summaryLine)
            if (report.hasAnyData) {
                val avgText = WeeklySummaryEngine.formatCompactDuration(report.thisWeekDailyAvgMin)
                if (report.hasPreviousWeekBaseline) {
                    val prevText = WeeklySummaryEngine.formatCompactDuration(report.previousWeekTotalMin)
                    add(context.getString(R.string.weekly_notif_detail_compare, avgText, prevText))
                } else {
                    add(context.getString(R.string.weekly_notif_detail_avg_only, avgText))
                }
            }
            if (report.activeDaysThisWeek > 0) {
                add(
                    context.getString(
                        R.string.weekly_notif_detail_on_target,
                        report.daysWithinLimitThisWeek,
                        report.activeDaysThisWeek
                    )
                )
            }
            val biggestShift = listOfNotNull(report.topRisingCategory, report.topImprovedCategory)
                .maxByOrNull { abs(it.deltaMinutes) }
            if (biggestShift != null) {
                val catLabel = context.getString(biggestShift.category.labelRes)
                val sign = if (biggestShift.deltaMinutes >= 0) "+" else "-"
                val shiftValue = "$sign${WeeklySummaryEngine.formatCompactDuration(biggestShift.deltaMinutes)}"
                add(context.getString(R.string.weekly_notif_detail_category_shift, catLabel, shiftValue))
            }
        }.joinToString(separator = "\n")

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_DEST, DEST_WEEKLY_SUMMARY)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            pendingFlags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_safenest_shield)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
