package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.analytics.AppUsageCategory
import com.example.engine.CategoryLimitRule
import com.example.engine.CategoryTimeLimitEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.safeNestDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safenest_local_prefs"
)

data class LocalFilterSnapshot(
    val blockAdult: Boolean = true,
    val blockGambling: Boolean = true,
    val blockMalware: Boolean = true,
    val blockSocial: Boolean = false,
    val safeSearchEnabled: Boolean = true,
    val aiProtectionEnabled: Boolean = true,
    val customBlockedDomains: Set<String> = emptySet(),
    val categoryLimitRules: Map<AppUsageCategory, CategoryLimitRule> = CategoryTimeLimitEngine.defaultRuleMap(),
    val activeChildId: String = "",
    val activeChildName: String = "Child",
    val weeklySummaryNotifyEnabled: Boolean = true,
    val lastWeeklySummaryWeekKey: String = ""
)

class LocalPinStore(private val context: Context) {

    private val pinHashKey = stringPreferencesKey("parent_pin_hash")
    private val blockAdultKey = booleanPreferencesKey("dns_block_adult")
    private val blockGamblingKey = booleanPreferencesKey("dns_block_gambling")
    private val blockMalwareKey = booleanPreferencesKey("dns_block_malware")
    private val blockSocialKey = booleanPreferencesKey("dns_block_social")
    private val safeSearchKey = booleanPreferencesKey("dns_safe_search")
    private val aiProtectionKey = booleanPreferencesKey("edge_ai_enabled")
    private val customDomainsKey = stringSetPreferencesKey("dns_custom_domains")
    private val categoryLimitsKey = stringPreferencesKey("category_hard_limits_v1")
    private val activeChildIdKey = stringPreferencesKey("active_child_id")
    private val activeChildNameKey = stringPreferencesKey("active_child_name")
    private val weeklySummaryNotifyEnabledKey = booleanPreferencesKey("weekly_summary_notify_enabled")
    private val weeklySummaryLastSentWeekKey = stringPreferencesKey("weekly_summary_last_sent_week")

    val pinHashFlow: Flow<String> = context.safeNestDataStore.data.map { prefs ->
        prefs[pinHashKey] ?: ""
    }

    val filterSnapshotFlow: Flow<LocalFilterSnapshot> = context.safeNestDataStore.data.map { prefs ->
        LocalFilterSnapshot(
            blockAdult = prefs[blockAdultKey] ?: true,
            blockGambling = prefs[blockGamblingKey] ?: true,
            blockMalware = prefs[blockMalwareKey] ?: true,
            blockSocial = prefs[blockSocialKey] ?: false,
            safeSearchEnabled = prefs[safeSearchKey] ?: true,
            aiProtectionEnabled = prefs[aiProtectionKey] ?: true,
            customBlockedDomains = prefs[customDomainsKey] ?: emptySet(),
            categoryLimitRules = CategoryTimeLimitEngine.decodeRules(prefs[categoryLimitsKey]),
            activeChildId = prefs[activeChildIdKey] ?: "",
            activeChildName = prefs[activeChildNameKey] ?: "Child",
            weeklySummaryNotifyEnabled = prefs[weeklySummaryNotifyEnabledKey] ?: true,
            lastWeeklySummaryWeekKey = prefs[weeklySummaryLastSentWeekKey] ?: ""
        )
    }

    suspend fun getFilterSnapshot(): LocalFilterSnapshot = filterSnapshotFlow.first()

    suspend fun savePinHash(hash: String) {
        context.safeNestDataStore.edit { prefs ->
            prefs[pinHashKey] = hash
        }
    }

    suspend fun saveCustomBlockedDomains(domains: Set<String>) {
        context.safeNestDataStore.edit { prefs ->
            prefs[customDomainsKey] = domains
                .map { it.trim().lowercase().trimEnd('.') }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }

    suspend fun saveCategoryLimitRules(rules: Map<AppUsageCategory, CategoryLimitRule>) {
        context.safeNestDataStore.edit { prefs ->
            prefs[categoryLimitsKey] = CategoryTimeLimitEngine.encodeRules(rules)
        }
    }

    suspend fun setWeeklySummaryNotifyEnabled(enabled: Boolean) {
        context.safeNestDataStore.edit { prefs ->
            prefs[weeklySummaryNotifyEnabledKey] = enabled
        }
    }

    suspend fun recordWeeklySummaryNotified(isoWeekKey: String) {
        if (isoWeekKey.isBlank()) return
        context.safeNestDataStore.edit { prefs ->
            prefs[weeklySummaryLastSentWeekKey] = isoWeekKey
        }
    }

    suspend fun setDnsShieldToggle(field: String, enabled: Boolean) {
        val key = when (field) {
            "blockAdult" -> blockAdultKey
            "blockGambling" -> blockGamblingKey
            "blockMalware" -> blockMalwareKey
            "blockSocial" -> blockSocialKey
            "safeSearchEnabled" -> safeSearchKey
            else -> return
        }
        context.safeNestDataStore.edit { prefs ->
            prefs[key] = enabled
        }
    }

    suspend fun syncLocalRulesFromChild(child: ChildProfile) {
        context.safeNestDataStore.edit { prefs ->
            prefs[blockAdultKey] = child.blockAdult
            prefs[blockGamblingKey] = child.blockGambling
            prefs[blockMalwareKey] = child.blockMalware
            prefs[blockSocialKey] = child.blockSocial
            prefs[safeSearchKey] = child.safeSearchEnabled
            prefs[aiProtectionKey] = child.aiProtectionEnabled
            prefs[customDomainsKey] = child.customBlockedDomains
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            prefs[activeChildIdKey] = child.id
            prefs[activeChildNameKey] = child.name
        }
    }

    suspend fun clearSessionState() {
        context.safeNestDataStore.edit { prefs ->
            prefs.remove(pinHashKey)
            prefs.remove(customDomainsKey)
            prefs.remove(categoryLimitsKey)
            prefs.remove(activeChildIdKey)
            prefs.remove(activeChildNameKey)
            prefs.remove(weeklySummaryLastSentWeekKey)
        }
    }

    companion object {
        private const val PIN_SALT = "SafeNest_EdgeGuard_v1_"

        fun hashPin(pin: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest((PIN_SALT + pin).toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
