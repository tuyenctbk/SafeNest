package com.example.data

import kotlinx.coroutines.flow.Flow

/**
 * Unified repository contract implemented by both [FamilyRepository] (Cloud Firestore)
 * and [LocalFamilyRepository] (on-device Jetpack DataStore private mode).
 */
interface SafeNestRepository {
    fun observeParentProfile(userId: String): Flow<ParentProfile?>

    suspend fun ensureParentProfile(userId: String, displayName: String): Result<Unit>

    suspend fun updateParentSettings(
        userId: String,
        pinHash: String? = null,
        childModeEnabled: Boolean? = null,
        activeChildId: String? = null,
        fallbackDisplayName: String = "Parent"
    ): Result<Unit>

    fun observeChildren(userId: String): Flow<List<ChildProfile>>

    suspend fun createChild(
        userId: String,
        name: String,
        age: Long,
        deviceModel: String,
        avatarTone: AvatarTone,
        dailyLimitMin: Long,
        bedtimeStart: String,
        bedtimeEnd: String,
        initialDnsSnapshot: LocalFilterSnapshot? = null
    ): Result<String>

    suspend fun updateChildRules(
        userId: String,
        childId: String,
        updates: Map<String, Any>
    ): Result<Unit>

    suspend fun updateChildTelemetry(
        userId: String,
        childId: String,
        todayScreenTimeMin: Long,
        blockedAttemptsToday: Long,
        batteryPercent: Long
    ): Result<Unit>

    suspend fun deleteChild(userId: String, childId: String): Result<Unit>

    fun observeAlerts(userId: String): Flow<List<SafetyAlert>>

    suspend fun createAlert(
        userId: String,
        childId: String,
        childName: String,
        alertType: AlertType,
        severity: AlertSeverity,
        appSource: String,
        redactedSnippet: String,
        confidence: Double
    ): Result<String>

    suspend fun setAlertAcknowledged(
        userId: String,
        alertId: String,
        acknowledged: Boolean
    ): Result<Unit>

    suspend fun acknowledgeAllAlerts(
        userId: String,
        alertIds: List<String>
    ): Result<Unit>

    suspend fun deleteAlert(userId: String, alertId: String): Result<Unit>

    fun observeDailySummaries(userId: String): Flow<List<DailySummary>>

    suspend fun upsertDailySummary(
        userId: String,
        childId: String,
        dateKey: String,
        totalScreenTimeMin: Long,
        lateNightMin: Long,
        blockedAttempts: Long,
        anomalyNote: String,
        topApps: List<String>
    ): Result<Unit>
}
