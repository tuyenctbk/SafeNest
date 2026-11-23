package com.example.data

import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class AvatarTone(@StringRes val labelRes: Int) {
    TEAL(R.string.tone_teal),
    AMBER(R.string.tone_amber),
    CORAL(R.string.tone_coral),
    INDIGO(R.string.tone_indigo),
    SAGE(R.string.tone_sage);

    companion object {
        fun fromRaw(raw: String?): AvatarTone =
            entries.firstOrNull { it.name == raw } ?: TEAL
    }
}

enum class AlertType(@StringRes val labelRes: Int) {
    CYBERBULLYING(R.string.alert_type_cyberbullying),
    SELF_HARM(R.string.alert_type_self_harm),
    GROOMING_EXPLICIT(R.string.alert_type_grooming),
    SCAM_GAMBLING(R.string.alert_type_scam),
    WEB_BLOCKED(R.string.alert_type_web_blocked),
    ANOMALY(R.string.alert_type_anomaly);

    companion object {
        fun fromRaw(raw: String?): AlertType =
            entries.firstOrNull { it.name == raw } ?: WEB_BLOCKED
    }
}

enum class AlertSeverity(@StringRes val labelRes: Int) {
    HIGH(R.string.severity_high),
    MEDIUM(R.string.severity_medium),
    LOW(R.string.severity_low);

    companion object {
        fun fromRaw(raw: String?): AlertSeverity =
            entries.firstOrNull { it.name == raw } ?: MEDIUM
    }
}

@Keep
@IgnoreExtraProperties
data class ParentProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val pinHash: String = "",
    val childModeEnabled: Boolean = false,
    val activeChildId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChildProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val age: Long = 10L,
    val deviceModel: String = "Android Device",
    val avatarColor: String = AvatarTone.TEAL.name,
    val dailyLimitMin: Long = 120L,
    val bedtimeStart: String = "21:30",
    val bedtimeEnd: String = "06:30",
    val downtimePaused: Boolean = false,
    val safeSearchEnabled: Boolean = true,
    val aiProtectionEnabled: Boolean = true,
    val vpnFilterEnabled: Boolean = true,
    val blockAdult: Boolean = true,
    val blockGambling: Boolean = true,
    val blockMalware: Boolean = true,
    val blockSocial: Boolean = false,
    val customBlockedDomains: List<String> = emptyList(),
    val blockedAppPackages: List<String> = emptyList(),
    val todayScreenTimeMin: Long = 0L,
    val blockedAttemptsToday: Long = 0L,
    val batteryPercent: Long = 100L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun tone(): AvatarTone = AvatarTone.fromRaw(avatarColor)
}

@Keep
@IgnoreExtraProperties
data class SafetyAlert(
    @DocumentId val id: String = "",
    val userId: String = "",
    val childId: String = "",
    val childName: String = "",
    val alertType: String = AlertType.WEB_BLOCKED.name,
    val severity: String = AlertSeverity.MEDIUM.name,
    val appSource: String = "",
    val redactedSnippet: String = "",
    val confidence: Double = 0.0,
    val acknowledged: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun parsedType(): AlertType = AlertType.fromRaw(alertType)
    fun parsedSeverity(): AlertSeverity = AlertSeverity.fromRaw(severity)
}

@Keep
@IgnoreExtraProperties
data class DailySummary(
    @DocumentId val id: String = "",
    val userId: String = "",
    val childId: String = "",
    val dateKey: String = "",
    val totalScreenTimeMin: Long = 0L,
    val lateNightMin: Long = 0L,
    val blockedAttempts: Long = 0L,
    val anomalyNote: String = "",
    val topApps: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun parsedTopApps(): List<Pair<String, Int>> = topApps.mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size >= 2) {
            val mins = parts[1].toIntOrNull() ?: 0
            parts[0] to mins
        } else null
    }
}
