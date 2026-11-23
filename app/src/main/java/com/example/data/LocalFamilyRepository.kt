package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Context.localFamilyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safenest_local_family_db"
)

/**
 * 100% On-Device persistent implementation of [SafeNestRepository] backed by Jetpack DataStore JSON.
 * Enables full privacy-first parental controls without requiring cloud setup or a Google account.
 */
class LocalFamilyRepository(private val context: Context) : SafeNestRepository {

    private val parentKey = stringPreferencesKey("local_parent_profile_json")
    private val childrenKey = stringPreferencesKey("local_children_json")
    private val alertsKey = stringPreferencesKey("local_alerts_json")
    private val summariesKey = stringPreferencesKey("local_summaries_json")

    override fun observeParentProfile(userId: String): Flow<ParentProfile?> =
        context.localFamilyDataStore.data.map { prefs ->
            prefs[parentKey]?.let { decodeParent(it) }?.takeIf { it.userId == userId }
        }

    override suspend fun ensureParentProfile(userId: String, displayName: String): Result<Unit> = runCatching {
        context.localFamilyDataStore.edit { prefs ->
            val existing = prefs[parentKey]?.let { decodeParent(it) }
            if (existing == null || existing.userId != userId) {
                val now = Timestamp.now()
                val created = ParentProfile(
                    id = userId,
                    userId = userId,
                    displayName = displayName.trim().ifBlank { "Parent" }.take(80),
                    childModeEnabled = false,
                    createdAt = now,
                    updatedAt = now
                )
                prefs[parentKey] = encodeParent(created)
            }
        }
    }

    override suspend fun updateParentSettings(
        userId: String,
        pinHash: String?,
        childModeEnabled: Boolean?,
        activeChildId: String?,
        fallbackDisplayName: String
    ): Result<Unit> = runCatching {
        context.localFamilyDataStore.edit { prefs ->
            val now = Timestamp.now()
            val base = prefs[parentKey]?.let { decodeParent(it) }
                ?.takeIf { it.userId == userId }
                ?: ParentProfile(
                    id = userId,
                    userId = userId,
                    displayName = fallbackDisplayName.trim().ifBlank { "Parent" }.take(80),
                    createdAt = now,
                    updatedAt = now
                )
            val updated = base.copy(
                pinHash = pinHash?.take(128) ?: base.pinHash,
                childModeEnabled = childModeEnabled ?: base.childModeEnabled,
                activeChildId = activeChildId?.take(128) ?: base.activeChildId,
                updatedAt = now
            )
            prefs[parentKey] = encodeParent(updated)
        }
    }

    override fun observeChildren(userId: String): Flow<List<ChildProfile>> =
        context.localFamilyDataStore.data.map { prefs ->
            decodeChildren(prefs[childrenKey])
                .filter { it.userId == userId }
                .sortedBy { it.createdAt ?: Timestamp(0, 0) }
        }

    override suspend fun createChild(
        userId: String,
        name: String,
        age: Long,
        deviceModel: String,
        avatarTone: AvatarTone,
        dailyLimitMin: Long,
        bedtimeStart: String,
        bedtimeEnd: String,
        initialDnsSnapshot: LocalFilterSnapshot?
    ): Result<String> = runCatching {
        val cleanName = name.trim().take(40)
        require(cleanName.isNotEmpty()) { "Child name is required" }
        val newId = "child_${UUID.randomUUID().toString().take(12)}"
        val now = Timestamp.now()
        val child = ChildProfile(
            id = newId,
            userId = userId,
            name = cleanName,
            age = age.coerceIn(3L, 18L),
            deviceModel = deviceModel.trim().ifBlank { "Android Device" }.take(60),
            avatarColor = avatarTone.name,
            dailyLimitMin = dailyLimitMin.coerceIn(15L, 720L),
            bedtimeStart = if (TIME_REGEX.matches(bedtimeStart)) bedtimeStart else "21:30",
            bedtimeEnd = if (TIME_REGEX.matches(bedtimeEnd)) bedtimeEnd else "06:30",
            downtimePaused = false,
            safeSearchEnabled = initialDnsSnapshot?.safeSearchEnabled ?: true,
            aiProtectionEnabled = initialDnsSnapshot?.aiProtectionEnabled ?: true,
            vpnFilterEnabled = true,
            blockAdult = initialDnsSnapshot?.blockAdult ?: true,
            blockGambling = initialDnsSnapshot?.blockGambling ?: true,
            blockMalware = initialDnsSnapshot?.blockMalware ?: true,
            blockSocial = initialDnsSnapshot?.blockSocial ?: false,
            customBlockedDomains = initialDnsSnapshot?.customBlockedDomains?.sorted().orEmpty(),
            blockedAppPackages = emptyList(),
            todayScreenTimeMin = 0L,
            blockedAttemptsToday = 0L,
            batteryPercent = 100L,
            createdAt = now,
            updatedAt = now
        )
        context.localFamilyDataStore.edit { prefs ->
            val current = decodeChildren(prefs[childrenKey]).toMutableList()
            current.add(child)
            prefs[childrenKey] = encodeChildren(current)
        }
        newId
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun updateChildRules(
        userId: String,
        childId: String,
        updates: Map<String, Any>
    ): Result<Unit> = runCatching {
        require(childId.isNotBlank()) { "Child ID must not be blank" }
        context.localFamilyDataStore.edit { prefs ->
            val updated = decodeChildren(prefs[childrenKey]).map { child ->
                if (child.id != childId || child.userId != userId) {
                    child
                } else {
                    child.copy(
                        dailyLimitMin = (updates["dailyLimitMin"] as? Number)?.toLong()?.coerceIn(15L, 720L) ?: child.dailyLimitMin,
                        bedtimeStart = (updates["bedtimeStart"] as? String)?.takeIf { TIME_REGEX.matches(it) } ?: child.bedtimeStart,
                        bedtimeEnd = (updates["bedtimeEnd"] as? String)?.takeIf { TIME_REGEX.matches(it) } ?: child.bedtimeEnd,
                        downtimePaused = (updates["downtimePaused"] as? Boolean) ?: child.downtimePaused,
                        safeSearchEnabled = (updates["safeSearchEnabled"] as? Boolean) ?: child.safeSearchEnabled,
                        aiProtectionEnabled = (updates["aiProtectionEnabled"] as? Boolean) ?: child.aiProtectionEnabled,
                        vpnFilterEnabled = (updates["vpnFilterEnabled"] as? Boolean) ?: child.vpnFilterEnabled,
                        blockAdult = (updates["blockAdult"] as? Boolean) ?: child.blockAdult,
                        blockGambling = (updates["blockGambling"] as? Boolean) ?: child.blockGambling,
                        blockMalware = (updates["blockMalware"] as? Boolean) ?: child.blockMalware,
                        blockSocial = (updates["blockSocial"] as? Boolean) ?: child.blockSocial,
                        customBlockedDomains = (updates["customBlockedDomains"] as? List<*>)
                            ?.mapNotNull { it as? String } ?: child.customBlockedDomains,
                        blockedAppPackages = (updates["blockedAppPackages"] as? List<*>)
                            ?.mapNotNull { it as? String } ?: child.blockedAppPackages,
                        updatedAt = Timestamp.now()
                    )
                }
            }
            prefs[childrenKey] = encodeChildren(updated)
        }
    }

    override suspend fun updateChildTelemetry(
        userId: String,
        childId: String,
        todayScreenTimeMin: Long,
        blockedAttemptsToday: Long,
        batteryPercent: Long
    ): Result<Unit> = runCatching {
        require(childId.isNotBlank()) { "Child ID must not be blank" }
        context.localFamilyDataStore.edit { prefs ->
            val updated = decodeChildren(prefs[childrenKey]).map { child ->
                if (child.id != childId || child.userId != userId) {
                    child
                } else {
                    child.copy(
                        todayScreenTimeMin = todayScreenTimeMin.coerceIn(0L, 1440L),
                        blockedAttemptsToday = blockedAttemptsToday.coerceIn(0L, 10000L),
                        batteryPercent = batteryPercent.coerceIn(0L, 100L),
                        updatedAt = Timestamp.now()
                    )
                }
            }
            prefs[childrenKey] = encodeChildren(updated)
        }
    }

    override suspend fun deleteChild(userId: String, childId: String): Result<Unit> = runCatching {
        require(childId.isNotBlank()) { "Child ID must not be blank" }
        context.localFamilyDataStore.edit { prefs ->
            val remaining = decodeChildren(prefs[childrenKey])
                .filterNot { it.id == childId && it.userId == userId }
            prefs[childrenKey] = encodeChildren(remaining)
            val remainingAlerts = decodeAlerts(prefs[alertsKey])
                .filterNot { it.childId == childId && it.userId == userId }
            prefs[alertsKey] = encodeAlerts(remainingAlerts)
            val remainingSummaries = decodeSummaries(prefs[summariesKey])
                .filterNot { it.childId == childId && it.userId == userId }
            prefs[summariesKey] = encodeSummaries(remainingSummaries)
        }
    }

    override fun observeAlerts(userId: String): Flow<List<SafetyAlert>> =
        context.localFamilyDataStore.data.map { prefs ->
            decodeAlerts(prefs[alertsKey])
                .filter { it.userId == userId }
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }

    override suspend fun createAlert(
        userId: String,
        childId: String,
        childName: String,
        alertType: AlertType,
        severity: AlertSeverity,
        appSource: String,
        redactedSnippet: String,
        confidence: Double
    ): Result<String> = runCatching {
        require(childId.isNotBlank()) { "Child ID is required for alerts" }
        val newId = "alert_${UUID.randomUUID().toString().take(12)}"
        val now = Timestamp.now()
        val alert = SafetyAlert(
            id = newId,
            userId = userId,
            childId = childId.take(128),
            childName = childName.trim().ifBlank { "Child" }.take(40),
            alertType = alertType.name,
            severity = severity.name,
            appSource = appSource.trim().ifBlank { "On-Device Shield" }.take(60),
            redactedSnippet = redactedSnippet.trim().ifBlank { "Safety event flagged on device." }.take(140),
            confidence = confidence.coerceIn(0.0, 1.0),
            acknowledged = false,
            createdAt = now,
            updatedAt = now
        )
        context.localFamilyDataStore.edit { prefs ->
            val updated = (listOf(alert) + decodeAlerts(prefs[alertsKey])).take(120)
            prefs[alertsKey] = encodeAlerts(updated)
        }
        newId
    }

    override suspend fun setAlertAcknowledged(
        userId: String,
        alertId: String,
        acknowledged: Boolean
    ): Result<Unit> = runCatching {
        context.localFamilyDataStore.edit { prefs ->
            val updated = decodeAlerts(prefs[alertsKey]).map { alert ->
                if (alert.id == alertId && alert.userId == userId) {
                    alert.copy(acknowledged = acknowledged, updatedAt = Timestamp.now())
                } else alert
            }
            prefs[alertsKey] = encodeAlerts(updated)
        }
    }

    override suspend fun acknowledgeAllAlerts(userId: String, alertIds: List<String>): Result<Unit> = runCatching {
        val idSet = alertIds.toSet()
        context.localFamilyDataStore.edit { prefs ->
            val updated = decodeAlerts(prefs[alertsKey]).map { alert ->
                if (alert.userId == userId && alert.id in idSet) {
                    alert.copy(acknowledged = true, updatedAt = Timestamp.now())
                } else alert
            }
            prefs[alertsKey] = encodeAlerts(updated)
        }
    }

    override suspend fun deleteAlert(userId: String, alertId: String): Result<Unit> = runCatching {
        context.localFamilyDataStore.edit { prefs ->
            val updated = decodeAlerts(prefs[alertsKey])
                .filterNot { it.id == alertId && it.userId == userId }
            prefs[alertsKey] = encodeAlerts(updated)
        }
    }

    override fun observeDailySummaries(userId: String): Flow<List<DailySummary>> =
        context.localFamilyDataStore.data.map { prefs ->
            decodeSummaries(prefs[summariesKey])
                .filter { it.userId == userId }
                .sortedByDescending { it.dateKey }
        }

    override suspend fun upsertDailySummary(
        userId: String,
        childId: String,
        dateKey: String,
        totalScreenTimeMin: Long,
        lateNightMin: Long,
        blockedAttempts: Long,
        anomalyNote: String,
        topApps: List<String>
    ): Result<Unit> = runCatching {
        val safeDate = if (DATE_REGEX.matches(dateKey)) {
            dateKey
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }
        val summaryId = "${childId}_${safeDate}".replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val now = Timestamp.now()
        val entry = DailySummary(
            id = summaryId,
            userId = userId,
            childId = childId.take(128),
            dateKey = safeDate,
            totalScreenTimeMin = totalScreenTimeMin.coerceIn(0L, 1440L),
            lateNightMin = lateNightMin.coerceIn(0L, 600L),
            blockedAttempts = blockedAttempts.coerceIn(0L, 10000L),
            anomalyNote = anomalyNote.take(200),
            topApps = topApps.take(10).map { it.take(120) },
            createdAt = now,
            updatedAt = now
        )
        context.localFamilyDataStore.edit { prefs ->
            val list = decodeSummaries(prefs[summariesKey]).filterNot { it.id == summaryId }.toMutableList()
            list.add(entry)
            prefs[summariesKey] = encodeSummaries(list.sortedByDescending { it.dateKey }.take(90))
        }
    }

    private fun encodeParent(p: ParentProfile): String = JSONObject().apply {
        put("id", p.id)
        put("userId", p.userId)
        put("displayName", p.displayName)
        put("pinHash", p.pinHash)
        put("childModeEnabled", p.childModeEnabled)
        put("activeChildId", p.activeChildId)
        put("createdAtMs", p.createdAt?.toDate()?.time ?: System.currentTimeMillis())
        put("updatedAtMs", p.updatedAt?.toDate()?.time ?: System.currentTimeMillis())
    }.toString()

    private fun decodeParent(raw: String): ParentProfile? = runCatching {
        val o = JSONObject(raw)
        ParentProfile(
            id = o.optString("id"),
            userId = o.optString("userId"),
            displayName = o.optString("displayName", "Parent"),
            pinHash = o.optString("pinHash"),
            childModeEnabled = o.optBoolean("childModeEnabled", false),
            activeChildId = o.optString("activeChildId"),
            createdAt = Timestamp(Date(o.optLong("createdAtMs", System.currentTimeMillis()))),
            updatedAt = Timestamp(Date(o.optLong("updatedAtMs", System.currentTimeMillis())))
        )
    }.getOrNull()

    private fun encodeChildren(children: List<ChildProfile>): String = JSONArray().apply {
        children.forEach { c ->
            put(JSONObject().apply {
                put("id", c.id)
                put("userId", c.userId)
                put("name", c.name)
                put("age", c.age)
                put("deviceModel", c.deviceModel)
                put("avatarColor", c.avatarColor)
                put("dailyLimitMin", c.dailyLimitMin)
                put("bedtimeStart", c.bedtimeStart)
                put("bedtimeEnd", c.bedtimeEnd)
                put("downtimePaused", c.downtimePaused)
                put("safeSearchEnabled", c.safeSearchEnabled)
                put("aiProtectionEnabled", c.aiProtectionEnabled)
                put("vpnFilterEnabled", c.vpnFilterEnabled)
                put("blockAdult", c.blockAdult)
                put("blockGambling", c.blockGambling)
                put("blockMalware", c.blockMalware)
                put("blockSocial", c.blockSocial)
                put("customBlockedDomains", JSONArray(c.customBlockedDomains))
                put("blockedAppPackages", JSONArray(c.blockedAppPackages))
                put("todayScreenTimeMin", c.todayScreenTimeMin)
                put("blockedAttemptsToday", c.blockedAttemptsToday)
                put("batteryPercent", c.batteryPercent)
                put("createdAtMs", c.createdAt?.toDate()?.time ?: System.currentTimeMillis())
                put("updatedAtMs", c.updatedAt?.toDate()?.time ?: System.currentTimeMillis())
            })
        }
    }.toString()

    private fun decodeChildren(raw: String?): List<ChildProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ChildProfile(
                            id = o.optString("id"),
                            userId = o.optString("userId"),
                            name = o.optString("name"),
                            age = o.optLong("age", 10L),
                            deviceModel = o.optString("deviceModel", "Android Device"),
                            avatarColor = o.optString("avatarColor", AvatarTone.TEAL.name),
                            dailyLimitMin = o.optLong("dailyLimitMin", 120L),
                            bedtimeStart = o.optString("bedtimeStart", "21:30"),
                            bedtimeEnd = o.optString("bedtimeEnd", "06:30"),
                            downtimePaused = o.optBoolean("downtimePaused", false),
                            safeSearchEnabled = o.optBoolean("safeSearchEnabled", true),
                            aiProtectionEnabled = o.optBoolean("aiProtectionEnabled", true),
                            vpnFilterEnabled = o.optBoolean("vpnFilterEnabled", true),
                            blockAdult = o.optBoolean("blockAdult", true),
                            blockGambling = o.optBoolean("blockGambling", true),
                            blockMalware = o.optBoolean("blockMalware", true),
                            blockSocial = o.optBoolean("blockSocial", false),
                            customBlockedDomains = o.optJSONArray("customBlockedDomains").toStringList(),
                            blockedAppPackages = o.optJSONArray("blockedAppPackages").toStringList(),
                            todayScreenTimeMin = o.optLong("todayScreenTimeMin", 0L),
                            blockedAttemptsToday = o.optLong("blockedAttemptsToday", 0L),
                            batteryPercent = o.optLong("batteryPercent", 100L),
                            createdAt = Timestamp(Date(o.optLong("createdAtMs", System.currentTimeMillis()))),
                            updatedAt = Timestamp(Date(o.optLong("updatedAtMs", System.currentTimeMillis())))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeAlerts(alerts: List<SafetyAlert>): String = JSONArray().apply {
        alerts.forEach { a ->
            put(JSONObject().apply {
                put("id", a.id)
                put("userId", a.userId)
                put("childId", a.childId)
                put("childName", a.childName)
                put("alertType", a.alertType)
                put("severity", a.severity)
                put("appSource", a.appSource)
                put("redactedSnippet", a.redactedSnippet)
                put("confidence", a.confidence)
                put("acknowledged", a.acknowledged)
                put("createdAtMs", a.createdAt?.toDate()?.time ?: System.currentTimeMillis())
                put("updatedAtMs", a.updatedAt?.toDate()?.time ?: System.currentTimeMillis())
            })
        }
    }.toString()

    private fun decodeAlerts(raw: String?): List<SafetyAlert> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        SafetyAlert(
                            id = o.optString("id"),
                            userId = o.optString("userId"),
                            childId = o.optString("childId"),
                            childName = o.optString("childName"),
                            alertType = o.optString("alertType", AlertType.WEB_BLOCKED.name),
                            severity = o.optString("severity", AlertSeverity.MEDIUM.name),
                            appSource = o.optString("appSource"),
                            redactedSnippet = o.optString("redactedSnippet"),
                            confidence = o.optDouble("confidence", 0.0),
                            acknowledged = o.optBoolean("acknowledged", false),
                            createdAt = Timestamp(Date(o.optLong("createdAtMs", System.currentTimeMillis()))),
                            updatedAt = Timestamp(Date(o.optLong("updatedAtMs", System.currentTimeMillis())))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSummaries(summaries: List<DailySummary>): String = JSONArray().apply {
        summaries.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id)
                put("userId", s.userId)
                put("childId", s.childId)
                put("dateKey", s.dateKey)
                put("totalScreenTimeMin", s.totalScreenTimeMin)
                put("lateNightMin", s.lateNightMin)
                put("blockedAttempts", s.blockedAttempts)
                put("anomalyNote", s.anomalyNote)
                put("topApps", JSONArray(s.topApps))
                put("createdAtMs", s.createdAt?.toDate()?.time ?: System.currentTimeMillis())
                put("updatedAtMs", s.updatedAt?.toDate()?.time ?: System.currentTimeMillis())
            })
        }
    }.toString()

    private fun decodeSummaries(raw: String?): List<DailySummary> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DailySummary(
                            id = o.optString("id"),
                            userId = o.optString("userId"),
                            childId = o.optString("childId"),
                            dateKey = o.optString("dateKey"),
                            totalScreenTimeMin = o.optLong("totalScreenTimeMin", 0L),
                            lateNightMin = o.optLong("lateNightMin", 0L),
                            blockedAttempts = o.optLong("blockedAttempts", 0L),
                            anomalyNote = o.optString("anomalyNote"),
                            topApps = o.optJSONArray("topApps").toStringList(),
                            createdAt = Timestamp(Date(o.optLong("createdAtMs", System.currentTimeMillis()))),
                            updatedAt = Timestamp(Date(o.optLong("updatedAtMs", System.currentTimeMillis())))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val item = optString(i)
                if (item.isNotBlank()) add(item)
            }
        }
    }

    companion object {
        const val LOCAL_USER_ID = "local_device_parent"
        private val TIME_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        private val DATE_REGEX = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    }
}
