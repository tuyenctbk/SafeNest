package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.data.AlertSeverity
import com.example.data.AlertType
import com.example.data.LocalPinStore
import com.example.engine.OnDeviceSafetyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class LiveDetectedSafetyEvent(
    val alertType: AlertType,
    val severity: AlertSeverity,
    val appSource: String,
    val redactedSnippet: String,
    val confidence: Double
)

/**
 * On-Device Notification Safety Listener.
 * Scans incoming chat/social notifications completely offline using OnDeviceSafetyEngine.
 * Full message contents NEVER leave the device; only redacted snippets emit on high-confidence flags.
 */
class SafeNestNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var aiProtectionEnabled: Boolean = true

    override fun onCreate() {
        super.onCreate()
        val store = LocalPinStore(applicationContext)
        scope.launch {
            store.filterSnapshotFlow.collect { snapshot ->
                aiProtectionEnabled = snapshot.aiProtectionEnabled
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !aiProtectionEnabled) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg.startsWith("com.android.systemui")) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $body".trim()
        if (combined.length < 4) return

        scope.launch {
            val result = OnDeviceSafetyEngine.analyzeText(combined, deduplicate = true)
            val category = result.topCategory
            if (result.flagged && category != null) {
                val appLabel = resolveAppLabel(pkg)
                Log.i(TAG, "On-device AI flagged ${category.name} (${"%.2f".format(result.confidence)}) from $appLabel")
                _detectedEvents.tryEmit(
                    LiveDetectedSafetyEvent(
                        alertType = category,
                        severity = result.severity,
                        appSource = appLabel,
                        redactedSnippet = result.redactedSnippet,
                        confidence = result.confidence
                    )
                )
            }
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString().take(60)
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.take(60)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SafeNestNotifListener"
        private val _detectedEvents = MutableSharedFlow<LiveDetectedSafetyEvent>(extraBufferCapacity = 32)
        val detectedEvents: SharedFlow<LiveDetectedSafetyEvent> = _detectedEvents.asSharedFlow()
    }
}
