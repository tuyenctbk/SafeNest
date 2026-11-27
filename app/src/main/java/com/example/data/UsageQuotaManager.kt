package com.example.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.engine.AiFallbackReason
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class QuotaPeriod(val docId: String, val datePattern: String) {
    DAILY("daily", "yyyy-MM-dd"),
    MONTHLY("monthly", "yyyy-MM")
}

/**
 * Firestore Source of Truth schema for `/users/{userId}/usageQuota/{periodId}` (`daily` or `monthly`).
 * Strictly mirrors `firebase-blueprint.json` and `firestore.rules` (never stores local BYOK API keys).
 */
@Keep
@IgnoreExtraProperties
data class UsageQuotaLedger(
    @DocumentId val id: String = "",
    val userId: String = "",
    val dateIso: String = "",
    val dailyCloudLimit: Int = AiCoachPreferences.DEFAULT_DAILY_CLOUD_LIMIT,
    val cloudRequestsUsed: Int = 0,
    val chatUsed: Int = 0,
    val visionUsed: Int = 0,
    val briefsUsed: Int = 0,
    val studioUsed: Int = 0,
    val audioUsed: Int = 0,
    val bonusGraceCredits: Int = 0,
    val graceClaimed: Boolean = false,
    val ecoLiteMode: Boolean = false,
    val fallbackCascadeCount: Int = 0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    val effectiveLimit: Int get() = (dailyCloudLimit + bonusGraceCredits).coerceAtLeast(1)
    val remainingCloudCredits: Int get() = (effectiveLimit - cloudRequestsUsed).coerceAtLeast(0)

    /**
     * Rolls over usage counters & graceClaimed to 0/false when [currentPeriodIso] advances,
     * while preserving user preferences ([ecoLiteMode] and [dailyCloudLimit]).
     */
    fun rolledOverFor(currentPeriodIso: String): UsageQuotaLedger {
        if (dateIso == currentPeriodIso) return this
        return copy(
            dateIso = currentPeriodIso,
            cloudRequestsUsed = 0,
            chatUsed = 0,
            visionUsed = 0,
            briefsUsed = 0,
            studioUsed = 0,
            audioUsed = 0,
            bonusGraceCredits = 0,
            graceClaimed = false,
            fallbackCascadeCount = 0
        )
    }

    fun incrementChannel(channel: AiFeatureChannel): UsageQuotaLedger = when (channel) {
        AiFeatureChannel.COACH_CHAT -> copy(chatUsed = (chatUsed + 1).coerceAtMost(MAX_COUNTER))
        AiFeatureChannel.RISK_CHECK -> copy(visionUsed = (visionUsed + 1).coerceAtMost(MAX_COUNTER))
        AiFeatureChannel.WEEKLY_BRIEF -> copy(briefsUsed = (briefsUsed + 1).coerceAtMost(MAX_COUNTER))
        AiFeatureChannel.STUDIO -> copy(studioUsed = (studioUsed + 1).coerceAtMost(MAX_COUNTER))
        AiFeatureChannel.AUDIO -> copy(audioUsed = (audioUsed + 1).coerceAtMost(MAX_COUNTER))
    }

    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "dateIso" to dateIso,
        "dailyCloudLimit" to dailyCloudLimit.coerceIn(
            AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT,
            AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT
        ),
        "cloudRequestsUsed" to cloudRequestsUsed.coerceIn(0, MAX_COUNTER),
        "chatUsed" to chatUsed.coerceIn(0, MAX_COUNTER),
        "visionUsed" to visionUsed.coerceIn(0, MAX_COUNTER),
        "briefsUsed" to briefsUsed.coerceIn(0, MAX_COUNTER),
        "studioUsed" to studioUsed.coerceIn(0, MAX_COUNTER),
        "audioUsed" to audioUsed.coerceIn(0, MAX_COUNTER),
        "bonusGraceCredits" to bonusGraceCredits.coerceIn(0, MAX_GRACE_CREDITS),
        "graceClaimed" to graceClaimed,
        "ecoLiteMode" to ecoLiteMode,
        "fallbackCascadeCount" to fallbackCascadeCount.coerceIn(0, MAX_COUNTER),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    /**
     * Action 1 update map (counter mutation, atomic reserve/refund, grace grant, or period rollover).
     * Strictly matches `firestore.rules` affectedKeys allowlist.
     */
    fun toMutationUpdateMap(): Map<String, Any> = mapOf(
        "dateIso" to dateIso,
        "cloudRequestsUsed" to cloudRequestsUsed.coerceIn(0, MAX_COUNTER),
        "chatUsed" to chatUsed.coerceIn(0, MAX_COUNTER),
        "visionUsed" to visionUsed.coerceIn(0, MAX_COUNTER),
        "briefsUsed" to briefsUsed.coerceIn(0, MAX_COUNTER),
        "studioUsed" to studioUsed.coerceIn(0, MAX_COUNTER),
        "audioUsed" to audioUsed.coerceIn(0, MAX_COUNTER),
        "bonusGraceCredits" to bonusGraceCredits.coerceIn(0, MAX_GRACE_CREDITS),
        "graceClaimed" to graceClaimed,
        "fallbackCascadeCount" to fallbackCascadeCount.coerceIn(0, MAX_COUNTER),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    companion object {
        const val MAX_COUNTER = 1000
        const val MAX_GRACE_CREDITS = 15

        fun initial(
            userId: String,
            dateIso: String,
            dailyCloudLimit: Int = AiCoachPreferences.DEFAULT_DAILY_CLOUD_LIMIT,
            ecoLiteMode: Boolean = false
        ): UsageQuotaLedger = UsageQuotaLedger(
            userId = userId,
            dateIso = dateIso,
            dailyCloudLimit = dailyCloudLimit.coerceIn(
                AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT,
                AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT
            ),
            ecoLiteMode = ecoLiteMode
        )

        fun fromSnapshot(snapshot: DocumentSnapshot): UsageQuotaLedger? {
            if (!snapshot.exists()) return null
            val data = snapshot.data ?: return null
            return UsageQuotaLedger(
                id = snapshot.id,
                userId = data["userId"] as? String ?: "",
                dateIso = data["dateIso"] as? String ?: "",
                dailyCloudLimit = ((data["dailyCloudLimit"] as? Number)?.toInt()
                    ?: AiCoachPreferences.DEFAULT_DAILY_CLOUD_LIMIT).coerceIn(
                    AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT,
                    AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT
                ),
                cloudRequestsUsed = ((data["cloudRequestsUsed"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COUNTER),
                chatUsed = ((data["chatUsed"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COUNTER),
                visionUsed = ((data["visionUsed"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COUNTER),
                briefsUsed = ((data["briefsUsed"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COUNTER),
                studioUsed = ((data["studioUsed"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COUNTER),
                audioUsed = ((data["audioUsed"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COUNTER),
                bonusGraceCredits = ((data["bonusGraceCredits"] as? Number)?.toInt() ?: 0)
                    .coerceIn(0, MAX_GRACE_CREDITS),
                graceClaimed = data["graceClaimed"] as? Boolean ?: false,
                ecoLiteMode = data["ecoLiteMode"] as? Boolean ?: false,
                fallbackCascadeCount = ((data["fallbackCascadeCount"] as? Number)?.toInt() ?: 0)
                    .coerceIn(0, MAX_COUNTER),
                createdAt = snapshot.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                updatedAt = snapshot.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
        }
    }
}

/**
 * Ticket issued when a cloud AI interaction reserves a quota credit ahead of execution.
 * Used for idempotent commit or refund via [UsageQuotaManager.commitInteraction] / [UsageQuotaManager.refundInteraction].
 */
data class QuotaReservation(
    val ticketId: String,
    val userId: String,
    val channel: AiFeatureChannel,
    val periodIso: String,
    val reservedCloudCredit: Boolean,
    val resolvedModel: ByokGeminiModel,
    val autoCascadedAtStart: Boolean
)

sealed interface QuotaReservationResult {
    data class Reserved(
        val reservation: QuotaReservation,
        val snapshot: AiCoachPreferences
    ) : QuotaReservationResult

    data class Exhausted(
        val snapshot: AiCoachPreferences,
        val reason: AiFallbackReason
    ) : QuotaReservationResult
}

data class AtomicQuotaOutcome<T>(
    val value: T,
    val chargedCloudCredit: Boolean,
    val cascadedTier: Boolean = false
)

sealed interface RemoteReserveResult {
    data class Reserved(val ledger: UsageQuotaLedger) : RemoteReserveResult
    data class Exhausted(val ledger: UsageQuotaLedger) : RemoteReserveResult
}

interface QuotaLocalMirror {
    val preferencesFlow: Flow<AiCoachPreferences>
    suspend fun getPreferences(): AiCoachPreferences
    suspend fun saveConfiguration(
        mode: AiEngineMode,
        apiKey: String,
        model: ByokGeminiModel,
        dailyCloudLimit: Int,
        ecoLiteMode: Boolean
    )
    suspend fun setEcoLiteMode(enabled: Boolean)
    suspend fun switchToOnDeviceFreeMode()
    suspend fun clearByokKey()
    suspend fun applyPeriodRolloverIfNeeded(periodIso: String): AiCoachPreferences
    suspend fun applyRemoteQuotaSnapshot(ledger: UsageQuotaLedger, currentPeriodIso: String): AiCoachPreferences
    suspend fun reserveCloudCreditLocally(periodIso: String): Pair<Boolean, AiCoachPreferences>
    suspend fun refundCloudCreditLocally(periodIso: String): AiCoachPreferences
    suspend fun recordFeatureCompletionLocally(
        channel: AiFeatureChannel,
        cascadedTier: Boolean,
        periodIso: String
    ): AiCoachPreferences
    suspend fun claimDailyGraceCredits(periodIso: String): Boolean
}

interface QuotaRemoteLedger {
    fun observeQuotaLedger(userId: String): Flow<UsageQuotaLedger?>

    suspend fun reserveCloudCredit(
        userId: String,
        periodIso: String,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<RemoteReserveResult>

    suspend fun commitFeatureUsage(
        userId: String,
        periodIso: String,
        channel: AiFeatureChannel,
        cascadedTier: Boolean,
        refundReservedCloudCredit: Boolean,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<UsageQuotaLedger>

    suspend fun refundCloudCredit(
        userId: String,
        periodIso: String
    ): Result<UsageQuotaLedger?>

    suspend fun claimGraceCredits(
        userId: String,
        periodIso: String,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<Pair<Boolean, UsageQuotaLedger>>

    suspend fun updatePreferences(
        userId: String,
        periodIso: String,
        dailyCloudLimit: Int,
        ecoLiteMode: Boolean
    ): Result<Unit>
}

/**
 * Firestore-backed Source of Truth for `/users/{userId}/usageQuota/{periodId}`.
 * Uses atomic Firestore Transactions for rollover reset, deduct-with-refund, and one-time grace credits.
 * Targets `R.string.firestore_database_id` (falling back to the `(default)` Firestore database).
 */
class FirestoreQuotaRemoteLedger(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val period: QuotaPeriod = QuotaPeriod.DAILY
) : QuotaRemoteLedger {

    constructor(context: Context, period: QuotaPeriod = QuotaPeriod.DAILY) : this(
        db = context.applicationContext.getString(R.string.firestore_database_id).trim().let { dbId ->
            if (dbId.isBlank() || dbId == "(default)") {
                FirebaseFirestore.getInstance()
            } else {
                FirebaseFirestore.getInstance(dbId)
            }
        },
        period = period
    )

    private fun requireUserId(expectedUid: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing usageQuota.")
        if (expectedUid.isBlank() || expectedUid != uid) {
            throw IllegalStateException("Signed-in user mismatch; refusing cross-user quota access.")
        }
        return uid
    }

    private fun quotaDocRef(uid: String) =
        db.collection("users").document(uid).collection("usageQuota").document(period.docId)

    private fun quotaPath(uid: String) = "users/$uid/usageQuota/${period.docId}"

    override fun observeQuotaLedger(userId: String): Flow<UsageQuotaLedger?> = flow {
        val uid = requireUserId(userId)
        val path = quotaPath(uid)
        emitAll(
            quotaDocRef(uid)
                .snapshots()
                .map { snapshot -> UsageQuotaLedger.fromSnapshot(snapshot) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    handleFirestoreError(error, OperationType.GET, path)
                    throw error
                }
        )
    }

    override suspend fun reserveCloudCredit(
        userId: String,
        periodIso: String,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<RemoteReserveResult> {
        val path = quotaPath(userId)
        return try {
            val uid = requireUserId(userId)
            val docRef = quotaDocRef(uid)
            val outcome = db.runTransaction { tx ->
                val snap = tx.get(docRef)
                val existing = UsageQuotaLedger.fromSnapshot(snap)
                if (existing == null) {
                    val created = UsageQuotaLedger.initial(
                        userId = uid,
                        dateIso = periodIso,
                        dailyCloudLimit = fallbackDailyLimit,
                        ecoLiteMode = fallbackEcoLite
                    )
                    if (created.remainingCloudCredits <= 0) {
                        RemoteReserveResult.Exhausted(created)
                    } else {
                        val updated = created.copy(cloudRequestsUsed = 1)
                        tx.set(docRef, updated.toCreateMap())
                        RemoteReserveResult.Reserved(updated)
                    }
                } else {
                    val active = existing.rolledOverFor(periodIso)
                    if (active.remainingCloudCredits <= 0) {
                        if (existing.dateIso != periodIso) {
                            tx.update(docRef, active.toMutationUpdateMap())
                        }
                        RemoteReserveResult.Exhausted(active)
                    } else {
                        val updated = active.copy(
                            cloudRequestsUsed = (active.cloudRequestsUsed + 1)
                                .coerceAtMost(UsageQuotaLedger.MAX_COUNTER)
                        )
                        tx.update(docRef, updated.toMutationUpdateMap())
                        RemoteReserveResult.Reserved(updated)
                    }
                }
            }.await()
            Result.success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    override suspend fun commitFeatureUsage(
        userId: String,
        periodIso: String,
        channel: AiFeatureChannel,
        cascadedTier: Boolean,
        refundReservedCloudCredit: Boolean,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<UsageQuotaLedger> {
        val path = quotaPath(userId)
        return try {
            val uid = requireUserId(userId)
            val docRef = quotaDocRef(uid)
            val updated = db.runTransaction { tx ->
                val snap = tx.get(docRef)
                val existing = UsageQuotaLedger.fromSnapshot(snap)
                if (existing == null) {
                    val created = UsageQuotaLedger.initial(
                        userId = uid,
                        dateIso = periodIso,
                        dailyCloudLimit = fallbackDailyLimit,
                        ecoLiteMode = fallbackEcoLite
                    ).incrementChannel(channel).let { base ->
                        if (cascadedTier) base.copy(fallbackCascadeCount = 1) else base
                    }
                    tx.set(docRef, created.toCreateMap())
                    created
                } else {
                    val active = existing.rolledOverFor(periodIso)
                    val adjustedCloud = if (refundReservedCloudCredit && existing.dateIso == periodIso) {
                        (active.cloudRequestsUsed - 1).coerceAtLeast(0)
                    } else {
                        active.cloudRequestsUsed
                    }
                    val next = active.copy(
                        cloudRequestsUsed = adjustedCloud,
                        fallbackCascadeCount = if (cascadedTier) {
                            (active.fallbackCascadeCount + 1).coerceAtMost(UsageQuotaLedger.MAX_COUNTER)
                        } else {
                            active.fallbackCascadeCount
                        }
                    ).incrementChannel(channel)
                    tx.update(docRef, next.toMutationUpdateMap())
                    next
                }
            }.await()
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun refundCloudCredit(
        userId: String,
        periodIso: String
    ): Result<UsageQuotaLedger?> {
        val path = quotaPath(userId)
        return try {
            val uid = requireUserId(userId)
            val docRef = quotaDocRef(uid)
            val updated = db.runTransaction { tx ->
                val snap = tx.get(docRef)
                val existing = UsageQuotaLedger.fromSnapshot(snap) ?: return@runTransaction null
                if (existing.dateIso != periodIso) return@runTransaction existing
                val next = existing.copy(
                    cloudRequestsUsed = (existing.cloudRequestsUsed - 1).coerceAtLeast(0)
                )
                tx.update(docRef, next.toMutationUpdateMap())
                next
            }.await()
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun claimGraceCredits(
        userId: String,
        periodIso: String,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<Pair<Boolean, UsageQuotaLedger>> {
        val path = quotaPath(userId)
        return try {
            val uid = requireUserId(userId)
            val docRef = quotaDocRef(uid)
            val outcome = db.runTransaction { tx ->
                val snap = tx.get(docRef)
                val existing = UsageQuotaLedger.fromSnapshot(snap)
                if (existing == null) {
                    val created = UsageQuotaLedger.initial(
                        userId = uid,
                        dateIso = periodIso,
                        dailyCloudLimit = fallbackDailyLimit,
                        ecoLiteMode = fallbackEcoLite
                    ).copy(
                        bonusGraceCredits = AiModelConfig.GRACE_CREDITS_GRANT,
                        graceClaimed = true
                    )
                    tx.set(docRef, created.toCreateMap())
                    true to created
                } else {
                    val active = existing.rolledOverFor(periodIso)
                    if (active.graceClaimed) {
                        if (existing.dateIso != periodIso) {
                            tx.update(docRef, active.toMutationUpdateMap())
                        }
                        false to active
                    } else {
                        val next = active.copy(
                            bonusGraceCredits = (active.bonusGraceCredits + AiModelConfig.GRACE_CREDITS_GRANT)
                                .coerceAtMost(UsageQuotaLedger.MAX_GRACE_CREDITS),
                            graceClaimed = true
                        )
                        tx.update(docRef, next.toMutationUpdateMap())
                        true to next
                    }
                }
            }.await()
            Result.success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun updatePreferences(
        userId: String,
        periodIso: String,
        dailyCloudLimit: Int,
        ecoLiteMode: Boolean
    ): Result<Unit> {
        val path = quotaPath(userId)
        return try {
            val uid = requireUserId(userId)
            val safeLimit = dailyCloudLimit.coerceIn(
                AiCoachPreferences.MIN_DAILY_CLOUD_LIMIT,
                AiCoachPreferences.MAX_DAILY_CLOUD_LIMIT
            )
            val docRef = quotaDocRef(uid)
            db.runTransaction { tx ->
                val snap = tx.get(docRef)
                if (!snap.exists()) {
                    val created = UsageQuotaLedger.initial(
                        userId = uid,
                        dateIso = periodIso,
                        dailyCloudLimit = safeLimit,
                        ecoLiteMode = ecoLiteMode
                    )
                    tx.set(docRef, created.toCreateMap())
                } else {
                    // Action 2 update: strictly dailyCloudLimit, ecoLiteMode, updatedAt
                    tx.update(
                        docRef,
                        mapOf(
                            "dailyCloudLimit" to safeLimit,
                            "ecoLiteMode" to ecoLiteMode,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }
}

/**
 * Hybrid Firestore + Local DataStore Usage Quota Manager implementing:
 * 1. Instant Cold-Start Local Mirror (DataStore) synced with Firestore Source of Truth (`/users/{uid}/usageQuota/daily`).
 * 2. Automatic Period Rollover Reset (resets counters & graceClaimed while preserving `ecoLiteMode` & `dailyCloudLimit`).
 * 3. Atomic Deduct-with-Refund (`reserveInteraction` -> `commitInteraction` or `refundInteraction` / `executeWithAtomicQuota`)
 *    with idempotent ticket tracking so users never lose credits to timeouts, network drops, cancellations, or cached hits.
 */
class UsageQuotaManager(
    private val localMirror: QuotaLocalMirror,
    private val remoteLedger: QuotaRemoteLedger,
    private val period: QuotaPeriod = QuotaPeriod.DAILY,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private enum class TicketStatus { ACTIVE, COMMITTED, REFUNDED }

    private val activeTickets = ConcurrentHashMap<String, TicketStatus>()
    private val latestActiveTicketByFeature = ConcurrentHashMap<AiFeatureChannel, QuotaReservation>()

    val quotaState: Flow<AiCoachPreferences> = localMirror.preferencesFlow

    fun currentPeriodIso(nowMs: Long = clockMs()): String =
        SimpleDateFormat(period.datePattern, Locale.US).format(Date(nowMs))

    suspend fun getSnapshot(): AiCoachPreferences =
        localMirror.applyPeriodRolloverIfNeeded(currentPeriodIso())

    /**
     * Starts real-time Firestore snapshot mirroring into the local DataStore cache.
     * Automatically resets counters if the remote ledger is from an earlier period while preserving `ecoLiteMode`.
     */
    fun startCloudSync(scope: CoroutineScope, userId: String): Job = scope.launch {
        if (userId.isBlank()) return@launch
        remoteLedger.observeQuotaLedger(userId)
            .catch { error ->
                if (error is CancellationException) throw error
                runCatching {
                    Log.w(TAG, "Hybrid quota cloud sync unavailable; continuing with local DataStore mirror", error)
                }
            }
            .collect { remote ->
                val periodIso = currentPeriodIso()
                if (remote != null) {
                    localMirror.applyRemoteQuotaSnapshot(remote, periodIso)
                } else {
                    localMirror.applyPeriodRolloverIfNeeded(periodIso)
                }
            }
    }

    /**
     * Convenience helper for atomic interaction deduction before executing a cloud AI call.
     * Issues a [QuotaReservation] ticket that can be committed or refunded via [refundInteraction].
     */
    suspend fun deductInteraction(
        userId: String,
        feature: AiFeatureChannel,
        requiresCloudCredit: Boolean = true
    ): QuotaReservationResult = reserveInteraction(
        userId = userId,
        channel = feature,
        requiresCloudCredit = requiresCloudCredit
    )

    /**
     * Atomically reserves 1 cloud credit (when [requiresCloudCredit] is true and BYOK cloud is active)
     * across Firestore + Local DataStore before calling the AI model.
     *
     * Returns [QuotaReservationResult.Exhausted] without mutating counters if quota is 0.
     * If Firestore is temporarily offline, falls back cleanly to local atomic reservation so UX stays responsive.
     */
    suspend fun reserveInteraction(
        userId: String,
        channel: AiFeatureChannel,
        requiresCloudCredit: Boolean
    ): QuotaReservationResult {
        val periodIso = currentPeriodIso()
        val current = localMirror.applyPeriodRolloverIfNeeded(periodIso)

        if (!requiresCloudCredit || !current.isCloudActive) {
            val ticketId = newTicketId()
            activeTickets[ticketId] = TicketStatus.ACTIVE
            return QuotaReservationResult.Reserved(
                reservation = QuotaReservation(
                    ticketId = ticketId,
                    userId = userId,
                    channel = channel,
                    periodIso = periodIso,
                    reservedCloudCredit = false,
                    resolvedModel = current.resolvedCloudModel(),
                    autoCascadedAtStart = false
                ),
                snapshot = current
            )
        }

        if (current.remainingCloudToday <= 0) {
            return QuotaReservationResult.Exhausted(
                snapshot = current,
                reason = AiFallbackReason.DAILY_QUOTA_REACHED
            )
        }

        val resolvedModel = current.resolvedCloudModel()
        val autoCascaded = !current.ecoLiteMode && !current.model.isEconomyTier && resolvedModel.isEconomyTier

        // 1. Try authoritative Firestore Transaction first when signed in
        if (userId.isNotBlank()) {
            val remoteResult = remoteLedger.reserveCloudCredit(
                userId = userId,
                periodIso = periodIso,
                fallbackDailyLimit = current.dailyCloudLimit,
                fallbackEcoLite = current.ecoLiteMode
            )
            if (remoteResult.isSuccess) {
                return when (val outcome = remoteResult.getOrThrow()) {
                    is RemoteReserveResult.Reserved -> {
                        val synced = localMirror.applyRemoteQuotaSnapshot(outcome.ledger, periodIso)
                        val ticketId = newTicketId()
                        val reservation = QuotaReservation(
                            ticketId = ticketId,
                            userId = userId,
                            channel = channel,
                            periodIso = periodIso,
                            reservedCloudCredit = true,
                            resolvedModel = resolvedModel,
                            autoCascadedAtStart = autoCascaded
                        )
                        activeTickets[ticketId] = TicketStatus.ACTIVE
                        latestActiveTicketByFeature[channel] = reservation
                        QuotaReservationResult.Reserved(
                            reservation = reservation,
                            snapshot = synced
                        )
                    }

                    is RemoteReserveResult.Exhausted -> {
                        val synced = localMirror.applyRemoteQuotaSnapshot(outcome.ledger, periodIso)
                        QuotaReservationResult.Exhausted(
                            snapshot = synced,
                            reason = AiFallbackReason.DAILY_QUOTA_REACHED
                        )
                    }
                }
            }
        }

        // 2. Offline / unreachable Firestore fallback: reserve atomically in local DataStore mirror
        val (reservedLocally, updatedLocal) = localMirror.reserveCloudCreditLocally(periodIso)
        if (!reservedLocally) {
            return QuotaReservationResult.Exhausted(
                snapshot = updatedLocal,
                reason = AiFallbackReason.DAILY_QUOTA_REACHED
            )
        }
        val ticketId = newTicketId()
        val reservation = QuotaReservation(
            ticketId = ticketId,
            userId = userId,
            channel = channel,
            periodIso = periodIso,
            reservedCloudCredit = true,
            resolvedModel = resolvedModel,
            autoCascadedAtStart = autoCascaded
        )
        activeTickets[ticketId] = TicketStatus.ACTIVE
        latestActiveTicketByFeature[channel] = reservation
        return QuotaReservationResult.Reserved(
            reservation = reservation,
            snapshot = updatedLocal
        )
    }

    /**
     * Commits a completed AI interaction: records the per-feature value receipt counter and cascade count.
     * If a cloud credit was tentatively reserved but the call ended up costing 0 cloud tokens
     * (e.g., 45s dedup cache hit or local fallback), automatically refunds the reserved credit.
     */
    suspend fun commitInteraction(
        reservation: QuotaReservation,
        actualChargedCloudCredit: Boolean,
        cascadedTier: Boolean
    ) {
        val transitioned = activeTickets.replace(
            reservation.ticketId,
            TicketStatus.ACTIVE,
            TicketStatus.COMMITTED
        )
        if (!transitioned) return
        latestActiveTicketByFeature.remove(reservation.channel, reservation)

        val shouldRefundReserved = reservation.reservedCloudCredit && !actualChargedCloudCredit
        val current = localMirror.getPreferences()

        if (shouldRefundReserved) {
            localMirror.refundCloudCreditLocally(reservation.periodIso)
        }
        localMirror.recordFeatureCompletionLocally(
            channel = reservation.channel,
            cascadedTier = cascadedTier,
            periodIso = reservation.periodIso
        )

        if (reservation.userId.isNotBlank()) {
            remoteLedger.commitFeatureUsage(
                userId = reservation.userId,
                periodIso = reservation.periodIso,
                channel = reservation.channel,
                cascadedTier = cascadedTier,
                refundReservedCloudCredit = shouldRefundReserved,
                fallbackDailyLimit = current.dailyCloudLimit,
                fallbackEcoLite = current.ecoLiteMode
            ).onSuccess { remoteUpdated ->
                localMirror.applyRemoteQuotaSnapshot(remoteUpdated, currentPeriodIso())
            }
        }
        pruneTicketsIfNeeded()
    }

    /**
     * Atomically refunds a tentatively reserved cloud credit by [feature] channel if an AI call
     * fails or times out. Uses the active ticket for idempotency when available, or performs a
     * safe bounded decrement when invoked directly.
     */
    suspend fun refundInteraction(
        feature: AiFeatureChannel,
        userId: String,
        reason: String = "network_failure"
    ): Boolean {
        val activeTicket = latestActiveTicketByFeature[feature]
        if (activeTicket != null && activeTicket.userId == userId) {
            return refundInteraction(activeTicket, reason)
        }
        val periodIso = currentPeriodIso()
        val current = localMirror.applyPeriodRolloverIfNeeded(periodIso)
        if (current.cloudRequestsToday <= 0) return false

        runCatching {
            Log.i(TAG, "Refunding cloud credit for feature=$feature (reason=$reason)")
        }
        localMirror.refundCloudCreditLocally(periodIso)
        if (userId.isNotBlank()) {
            remoteLedger.refundCloudCredit(
                userId = userId,
                periodIso = periodIso
            ).onSuccess { remoteUpdated ->
                if (remoteUpdated != null) {
                    localMirror.applyRemoteQuotaSnapshot(remoteUpdated, currentPeriodIso())
                }
            }
        }
        return true
    }

    /**
     * Atomically refunds a tentatively reserved cloud credit if an AI call fails, times out,
     * or is cancelled mid-flight. Idempotent per [QuotaReservation.ticketId] (cannot double-refund).
     */
    suspend fun refundInteraction(
        reservation: QuotaReservation,
        reason: String = "ai_call_failed"
    ): Boolean {
        val transitioned = activeTickets.replace(
            reservation.ticketId,
            TicketStatus.ACTIVE,
            TicketStatus.REFUNDED
        )
        if (!transitioned) return false
        latestActiveTicketByFeature.remove(reservation.channel, reservation)
        if (!reservation.reservedCloudCredit) {
            pruneTicketsIfNeeded()
            return false
        }

        runCatching {
            Log.i(TAG, "Refunding reserved cloud credit (ticket=${reservation.ticketId}, reason=$reason)")
        }
        localMirror.refundCloudCreditLocally(reservation.periodIso)

        if (reservation.userId.isNotBlank()) {
            remoteLedger.refundCloudCredit(
                userId = reservation.userId,
                periodIso = reservation.periodIso
            ).onSuccess { remoteUpdated ->
                if (remoteUpdated != null) {
                    localMirror.applyRemoteQuotaSnapshot(remoteUpdated, currentPeriodIso())
                }
            }
        }
        pruneTicketsIfNeeded()
        return true
    }

    /**
     * End-to-end Atomic Deduct-with-Refund wrapper:
     * 1. Reserves 1 cloud credit (if applicable) before invoking [block].
     * 2. Passes the pre-reservation snapshot + [QuotaReservation] (or null if exhausted) into [block].
     * 3. On normal completion: commits feature receipt and automatically refunds if `chargedCloudCredit == false`.
     * 4. On any exception or cancellation: executes [refundInteraction] inside `NonCancellable` so credits are never lost.
     */
    suspend fun <T> executeWithAtomicQuota(
        userId: String,
        channel: AiFeatureChannel,
        requiresCloudCredit: Boolean,
        block: suspend (prefsForExecution: AiCoachPreferences, reservation: QuotaReservation?) -> AtomicQuotaOutcome<T>
    ): T {
        val reservationResult = reserveInteraction(
            userId = userId,
            channel = channel,
            requiresCloudCredit = requiresCloudCredit
        )

        return when (reservationResult) {
            is QuotaReservationResult.Exhausted -> {
                val outcome = block(reservationResult.snapshot, null)
                // Record zero-credit local/fallback value receipt without charging cloud quota
                localMirror.recordFeatureCompletionLocally(
                    channel = channel,
                    cascadedTier = outcome.cascadedTier,
                    periodIso = currentPeriodIso()
                )
                if (userId.isNotBlank()) {
                    remoteLedger.commitFeatureUsage(
                        userId = userId,
                        periodIso = currentPeriodIso(),
                        channel = channel,
                        cascadedTier = outcome.cascadedTier,
                        refundReservedCloudCredit = false,
                        fallbackDailyLimit = reservationResult.snapshot.dailyCloudLimit,
                        fallbackEcoLite = reservationResult.snapshot.ecoLiteMode
                    ).onSuccess { remoteUpdated ->
                        localMirror.applyRemoteQuotaSnapshot(remoteUpdated, currentPeriodIso())
                    }
                }
                outcome.value
            }

            is QuotaReservationResult.Reserved -> {
                val reservation = reservationResult.reservation
                // Provide authoritative pre-deduction count to the AI engine so eligibility and 80% Eco Lite threshold reflect Firestore truth
                val executionPrefs = if (reservation.reservedCloudCredit) {
                    reservationResult.snapshot.copy(
                        cloudRequestsToday = (reservationResult.snapshot.cloudRequestsToday - 1).coerceAtLeast(0)
                    )
                } else {
                    reservationResult.snapshot
                }
                try {
                    val outcome = block(executionPrefs, reservation)
                    commitInteraction(
                        reservation = reservation,
                        actualChargedCloudCredit = outcome.chargedCloudCredit,
                        cascadedTier = outcome.cascadedTier
                    )
                    outcome.value
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        refundInteraction(reservation, reason = "cancelled")
                    }
                    throw e
                } catch (e: Exception) {
                    withContext(NonCancellable) {
                        refundInteraction(reservation, reason = e.javaClass.simpleName)
                    }
                    throw e
                }
            }
        }
    }

    suspend fun claimDailyGraceCredits(userId: String): Boolean {
        val periodIso = currentPeriodIso()
        val current = localMirror.applyPeriodRolloverIfNeeded(periodIso)
        if (userId.isNotBlank()) {
            val remote = remoteLedger.claimGraceCredits(
                userId = userId,
                periodIso = periodIso,
                fallbackDailyLimit = current.dailyCloudLimit,
                fallbackEcoLite = current.ecoLiteMode
            )
            if (remote.isSuccess) {
                val (granted, ledger) = remote.getOrThrow()
                localMirror.applyRemoteQuotaSnapshot(ledger, periodIso)
                return granted
            }
        }
        return localMirror.claimDailyGraceCredits(periodIso)
    }

    suspend fun setEcoLiteMode(userId: String, enabled: Boolean) {
        val periodIso = currentPeriodIso()
        val current = localMirror.getPreferences()
        localMirror.setEcoLiteMode(enabled)
        if (userId.isNotBlank()) {
            remoteLedger.updatePreferences(
                userId = userId,
                periodIso = periodIso,
                dailyCloudLimit = current.dailyCloudLimit,
                ecoLiteMode = enabled
            )
        }
    }

    suspend fun saveConfiguration(
        userId: String,
        mode: AiEngineMode,
        apiKey: String,
        model: ByokGeminiModel,
        dailyCloudLimit: Int,
        ecoLiteMode: Boolean
    ) {
        val periodIso = currentPeriodIso()
        // BYOK API key, mode, and model stay strictly on-device; only quota budget & ecoLiteMode sync to Firestore
        localMirror.saveConfiguration(
            mode = mode,
            apiKey = apiKey,
            model = model,
            dailyCloudLimit = dailyCloudLimit,
            ecoLiteMode = ecoLiteMode
        )
        if (userId.isNotBlank()) {
            remoteLedger.updatePreferences(
                userId = userId,
                periodIso = periodIso,
                dailyCloudLimit = dailyCloudLimit,
                ecoLiteMode = ecoLiteMode
            )
        }
    }

    suspend fun switchToOnDeviceFreeMode() {
        localMirror.switchToOnDeviceFreeMode()
    }

    suspend fun clearByokKey() {
        localMirror.clearByokKey()
    }

    private fun newTicketId(): String = "q_${UUID.randomUUID().toString().take(12)}"

    private fun pruneTicketsIfNeeded() {
        if (activeTickets.size > 128) {
            activeTickets.entries.removeIf { it.value != TicketStatus.ACTIVE }
        }
    }

    companion object {
        private const val TAG = "UsageQuotaManager"

        fun create(context: Context, db: FirebaseFirestore?): UsageQuotaManager =
            UsageQuotaManager(
                localMirror = AiCoachPreferencesStore(context.applicationContext),
                remoteLedger = if (db != null) {
                    FirestoreQuotaRemoteLedger(db = db)
                } else {
                    NoOpQuotaRemoteLedger
                }
            )
    }
}

private object NoOpQuotaRemoteLedger : QuotaRemoteLedger {
    override fun observeQuotaLedger(userId: String): Flow<UsageQuotaLedger?> = flow { emit(null) }

    override suspend fun reserveCloudCredit(
        userId: String,
        periodIso: String,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<RemoteReserveResult> = Result.failure(IllegalStateException("Local-only quota mode"))

    override suspend fun commitFeatureUsage(
        userId: String,
        periodIso: String,
        channel: AiFeatureChannel,
        cascadedTier: Boolean,
        refundReservedCloudCredit: Boolean,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<UsageQuotaLedger> = Result.failure(IllegalStateException("Local-only quota mode"))

    override suspend fun refundCloudCredit(
        userId: String,
        periodIso: String
    ): Result<UsageQuotaLedger?> = Result.success(null)

    override suspend fun claimGraceCredits(
        userId: String,
        periodIso: String,
        fallbackDailyLimit: Int,
        fallbackEcoLite: Boolean
    ): Result<Pair<Boolean, UsageQuotaLedger>> = Result.failure(IllegalStateException("Local-only quota mode"))

    override suspend fun updatePreferences(
        userId: String,
        periodIso: String,
        dailyCloudLimit: Int,
        ecoLiteMode: Boolean
    ): Result<Unit> = Result.success(Unit)
}
