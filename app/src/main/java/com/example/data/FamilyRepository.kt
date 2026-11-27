package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FamilyRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) : SafeNestRepository {
    constructor(context: Context) : this(
        context.applicationContext.getString(R.string.firestore_database_id).trim().let { dbId ->
            if (dbId.isBlank() || dbId == "(default)") {
                FirebaseFirestore.getInstance()
            } else {
                FirebaseFirestore.getInstance(dbId)
            }
        }
    )

    private fun requireUserId(expectedUid: String? = null): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        if (expectedUid != null && expectedUid != uid) {
            throw IllegalStateException("Signed-in user changed; refusing cross-user operation.")
        }
        return uid
    }

    override fun observeParentProfile(userId: String): Flow<ParentProfile?> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            ParentProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    handleFlowError(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    override suspend fun ensureParentProfile(userId: String, displayName: String): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val safeName = displayName.trim().ifBlank { "Parent" }.take(80)
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "childModeEnabled" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun updateParentSettings(
        userId: String,
        pinHash: String?,
        childModeEnabled: Boolean?,
        activeChildId: String?,
        fallbackDisplayName: String
    ): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                ensureParentProfile(uid, fallbackDisplayName).getOrThrow()
            }
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (pinHash != null) updates["pinHash"] = pinHash.take(128)
            if (childModeEnabled != null) updates["childModeEnabled"] = childModeEnabled
            if (activeChildId != null) updates["activeChildId"] = activeChildId.take(128)
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override fun observeChildren(userId: String): Flow<List<ChildProfile>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/children"
        emitAll(
            db.collection("users").document(uid).collection("children")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChildProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
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
    ): Result<String> {
        var docPath = "users/$userId/children"
        return try {
            val uid = requireUserId(userId)
            val cleanName = name.trim().take(40)
            require(cleanName.isNotEmpty()) { "Child name is required" }
            val clampedAge = age.coerceIn(3L, 18L)
            val cleanDevice = deviceModel.trim().ifBlank { "Android Device" }.take(60)
            val clampedLimit = dailyLimitMin.coerceIn(15L, 720L)
            val validStart = if (TIME_REGEX.matches(bedtimeStart)) bedtimeStart else "21:30"
            val validEnd = if (TIME_REGEX.matches(bedtimeEnd)) bedtimeEnd else "06:30"

            val docRef = db.collection("users").document(uid).collection("children").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "name" to cleanName,
                "age" to clampedAge,
                "deviceModel" to cleanDevice,
                "avatarColor" to avatarTone.name,
                "dailyLimitMin" to clampedLimit,
                "bedtimeStart" to validStart,
                "bedtimeEnd" to validEnd,
                "downtimePaused" to false,
                "safeSearchEnabled" to (initialDnsSnapshot?.safeSearchEnabled ?: true),
                "aiProtectionEnabled" to (initialDnsSnapshot?.aiProtectionEnabled ?: true),
                "vpnFilterEnabled" to true,
                "blockAdult" to (initialDnsSnapshot?.blockAdult ?: true),
                "blockGambling" to (initialDnsSnapshot?.blockGambling ?: true),
                "blockMalware" to (initialDnsSnapshot?.blockMalware ?: true),
                "blockSocial" to (initialDnsSnapshot?.blockSocial ?: false),
                "customBlockedDomains" to (initialDnsSnapshot?.customBlockedDomains?.sorted()?.take(50) ?: emptyList<String>()),
                "blockedAppPackages" to emptyList<String>(),
                "todayScreenTimeMin" to 0L,
                "blockedAttemptsToday" to 0L,
                "batteryPercent" to 100L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun updateChildRules(
        userId: String,
        childId: String,
        updates: Map<String, Any>
    ): Result<Unit> {
        val path = "users/$userId/children/$childId"
        return try {
            val uid = requireUserId(userId)
            require(childId.isNotBlank()) { "Child ID must not be blank" }
            val sanitized = updates.toMutableMap().apply {
                this["updatedAt"] = FieldValue.serverTimestamp()
            }
            db.collection("users").document(uid)
                .collection("children").document(childId)
                .update(sanitized)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun updateChildTelemetry(
        userId: String,
        childId: String,
        todayScreenTimeMin: Long,
        blockedAttemptsToday: Long,
        batteryPercent: Long
    ): Result<Unit> {
        val path = "users/$userId/children/$childId"
        return try {
            val uid = requireUserId(userId)
            require(childId.isNotBlank()) { "Child ID must not be blank" }
            db.collection("users").document(uid)
                .collection("children").document(childId)
                .update(
                    mapOf(
                        "todayScreenTimeMin" to todayScreenTimeMin.coerceIn(0L, 1440L),
                        "blockedAttemptsToday" to blockedAttemptsToday.coerceIn(0L, 10000L),
                        "batteryPercent" to batteryPercent.coerceIn(0L, 100L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun deleteChild(userId: String, childId: String): Result<Unit> {
        val path = "users/$userId/children/$childId"
        return try {
            val uid = requireUserId(userId)
            require(childId.isNotBlank()) { "Child ID must not be blank" }
            db.collection("users").document(uid)
                .collection("children").document(childId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    override fun observeAlerts(userId: String): Flow<List<SafetyAlert>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/alerts"
        emitAll(
            db.collection("users").document(uid).collection("alerts")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SafetyAlert::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
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
    ): Result<String> {
        var docPath = "users/$userId/alerts"
        return try {
            val uid = requireUserId(userId)
            require(childId.isNotBlank()) { "Child ID is required for alerts" }
            val docRef = db.collection("users").document(uid).collection("alerts").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "childId" to childId.take(128),
                "childName" to childName.trim().ifBlank { "Child" }.take(40),
                "alertType" to alertType.name,
                "severity" to severity.name,
                "appSource" to appSource.trim().ifBlank { "On-Device Shield" }.take(60),
                "redactedSnippet" to redactedSnippet.trim().ifBlank { "Safety event flagged on device." }.take(140),
                "confidence" to confidence.coerceIn(0.0, 1.0),
                "acknowledged" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun setAlertAcknowledged(
        userId: String,
        alertId: String,
        acknowledged: Boolean
    ): Result<Unit> {
        val path = "users/$userId/alerts/$alertId"
        return try {
            val uid = requireUserId(userId)
            require(alertId.isNotBlank()) { "Alert ID must not be blank" }
            db.collection("users").document(uid)
                .collection("alerts").document(alertId)
                .update(
                    mapOf(
                        "acknowledged" to acknowledged,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun acknowledgeAllAlerts(
        userId: String,
        alertIds: List<String>
    ): Result<Unit> {
        val path = "users/$userId/alerts"
        return try {
            val uid = requireUserId(userId)
            val validIds = alertIds.filter { it.isNotBlank() }.distinct()
            if (validIds.isEmpty()) return Result.success(Unit)
            val batch = db.batch()
            val alertsCol = db.collection("users").document(uid).collection("alerts")
            validIds.take(400).forEach { id ->
                batch.update(
                    alertsCol.document(id),
                    mapOf(
                        "acknowledged" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun deleteAlert(userId: String, alertId: String): Result<Unit> {
        val path = "users/$userId/alerts/$alertId"
        return try {
            val uid = requireUserId(userId)
            require(alertId.isNotBlank()) { "Alert ID must not be blank" }
            db.collection("users").document(uid)
                .collection("alerts").document(alertId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    override fun observeDailySummaries(userId: String): Flow<List<DailySummary>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/dailySummaries"
        emitAll(
            db.collection("users").document(uid).collection("dailySummaries")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DailySummary::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.dateKey }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
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
    ): Result<Unit> {
        val summaryId = "${childId}_${dateKey}".replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val path = "users/$userId/dailySummaries/$summaryId"
        return try {
            val uid = requireUserId(userId)
            val safeDate = if (DATE_REGEX.matches(dateKey)) {
                dateKey
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            }
            val docRef = db.collection("users").document(uid)
                .collection("dailySummaries").document(summaryId)
            val existing = docRef.get().await()
            val cleanApps = topApps.take(10).map { it.take(120) }
            val cleanNote = anomalyNote.take(200)
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "totalScreenTimeMin" to totalScreenTimeMin.coerceIn(0L, 1440L),
                        "lateNightMin" to lateNightMin.coerceIn(0L, 600L),
                        "blockedAttempts" to blockedAttempts.coerceIn(0L, 10000L),
                        "anomalyNote" to cleanNote,
                        "topApps" to cleanApps,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "childId" to childId.take(128),
                        "dateKey" to safeDate,
                        "totalScreenTimeMin" to totalScreenTimeMin.coerceIn(0L, 1440L),
                        "lateNightMin" to lateNightMin.coerceIn(0L, 600L),
                        "blockedAttempts" to blockedAttempts.coerceIn(0L, 10000L),
                        "anomalyNote" to cleanNote,
                        "topApps" to cleanApps,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    private fun handleFlowError(
        error: Throwable,
        opType: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, opType, path)
        }
    }

    private companion object {
        val TIME_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        val DATE_REGEX = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    }
}
