package com.example.engine

import androidx.annotation.StringRes
import com.example.R
import com.example.analytics.AppUsageCategory
import com.example.analytics.WeeklySummaryReport
import com.example.analytics.WeeklyTrendDirection
import com.example.data.AiCoachPreferences
import com.example.data.AiEngineMode
import com.example.data.AiModelConfig
import com.example.data.AlertSeverity
import com.example.data.AlertType
import com.example.data.ByokGeminiModel
import com.example.data.LocalFilterSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class AiResponseOrigin {
    ON_DEVICE_FREE,
    CLOUD_BYOK_STANDARD,
    CLOUD_BYOK_ECO_LITE,
    CACHED_ZERO_TOKEN,
    ON_DEVICE_FALLBACK
}

enum class AiFallbackReason {
    NONE,
    NO_KEY,
    DAILY_QUOTA_REACHED,
    OFFLINE_OR_NETWORK,
    AUTH_OR_RATE_LIMIT,
    TIMEOUT
}

enum class AiActionType {
    BLOCK_DOMAIN,
    APPLY_SCHOOL_NIGHT_PRESET,
    OPEN_CATEGORY_LIMITS,
    OPEN_BLOCKED_WEBSITES
}

data class AiRecommendedAction(
    val type: AiActionType,
    val domain: String = "",
    val label: String = ""
)

data class AiCoachMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val origin: AiResponseOrigin = AiResponseOrigin.ON_DEVICE_FREE,
    val fallbackReason: AiFallbackReason = AiFallbackReason.NONE,
    val cascadedFromStandard: Boolean = false,
    val recommendedActions: List<AiRecommendedAction> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
)

data class AiRiskCheckResult(
    val inputPreview: String,
    val hasScreenshot: Boolean,
    val severity: AlertSeverity,
    val headline: String,
    @StringRes val headlineRes: Int? = null,
    val explanation: String,
    val parentTip: String,
    val detectedDomain: String? = null,
    val origin: AiResponseOrigin = AiResponseOrigin.ON_DEVICE_FREE,
    val fallbackReason: AiFallbackReason = AiFallbackReason.NONE,
    val cascadedFromStandard: Boolean = false,
    val recommendedActions: List<AiRecommendedAction> = emptyList()
)

data class AiWeeklyBrief(
    val childName: String,
    val headline: String,
    val bullets: List<String>,
    val conversationStarter: String,
    val origin: AiResponseOrigin = AiResponseOrigin.ON_DEVICE_FREE,
    val fallbackReason: AiFallbackReason = AiFallbackReason.NONE,
    val cascadedFromStandard: Boolean = false,
    val recommendedActions: List<AiRecommendedAction> = emptyList()
)

data class AiChildContext(
    val childName: String = "Child",
    val childAge: Int = 10,
    val dailyLimitMinutes: Int = 120,
    val todayScreenMinutes: Int = 0,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    val unreviewedAlertsCount: Int = 0,
    val recentAlertCategories: List<AlertType> = emptyList(),
    val customBlockedCount: Int = 0,
    val vpnRunning: Boolean = false,
    val weeklyReport: WeeklySummaryReport? = null,
    val filterSnapshot: LocalFilterSnapshot = LocalFilterSnapshot(),
    val recentConversationTurns: List<Pair<Boolean, String>> = emptyList(),
    val languageTag: String = Locale.getDefault().toLanguageTag()
)

sealed interface CloudTransportResult {
    data class Success(val text: String) : CloudTransportResult
    data class Failure(
        val reason: AiFallbackReason,
        val detail: String,
        val isRateLimited429: Boolean = false
    ) : CloudTransportResult
}

enum class ByokTestStatus {
    SUCCESS,
    KEY_TOO_SHORT,
    AUTH_OR_RATE_LIMIT,
    TIMEOUT,
    NETWORK_ERROR,
    TIMEOUT_LOCAL_FALLBACK
}

data class ByokTestResult(
    val success: Boolean,
    val message: String,
    val fallbackReason: AiFallbackReason = AiFallbackReason.NONE,
    val status: ByokTestStatus = if (success) ByokTestStatus.SUCCESS else ByokTestStatus.NETWORK_ERROR,
    val detail: String = "",
    val testedModel: ByokGeminiModel? = null
)

fun interface GeminiCloudTransport {
    suspend fun generate(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        imageJpegBase64: String?
    ): CloudTransportResult
}

/**
 * Default HTTP transport using Android's built-in [HttpURLConnection] and the official Gemini
 * REST API with the API key sent securely in the `x-goog-api-key` HTTP header.
 */
object HttpGeminiCloudTransport : GeminiCloudTransport {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 40_000

    override suspend fun generate(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        imageJpegBase64: String?
    ): CloudTransportResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }

            val parts = JSONArray().apply {
                put(JSONObject().put("text", userPrompt))
                if (!imageJpegBase64.isNullOrBlank()) {
                    put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", imageJpegBase64)
                        )
                    )
                }
            }

            val payload = JSONObject().apply {
                put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", parts)
                    )
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.4)
                        .put("maxOutputTokens", 520)
                )
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()

            if (statusCode !in 200..299) {
                val reason = when (statusCode) {
                    400, 401, 403, 429 -> AiFallbackReason.AUTH_OR_RATE_LIMIT
                    408, 504 -> AiFallbackReason.TIMEOUT
                    else -> AiFallbackReason.OFFLINE_OR_NETWORK
                }
                return@withContext CloudTransportResult.Failure(
                    reason = reason,
                    detail = "HTTP $statusCode",
                    isRateLimited429 = statusCode == 429
                )
            }

            val parsedText = extractCandidateText(responseBody)
            if (parsedText.isBlank()) {
                CloudTransportResult.Failure(AiFallbackReason.OFFLINE_OR_NETWORK, "Empty AI response")
            } else {
                CloudTransportResult.Success(parsedText.trim())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            CloudTransportResult.Failure(AiFallbackReason.TIMEOUT, e.message ?: "Timeout")
        } catch (e: Exception) {
            CloudTransportResult.Failure(AiFallbackReason.OFFLINE_OR_NETWORK, e.message ?: "Network error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractCandidateText(responseJson: String): String {
        return runCatching {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            buildString {
                for (i in 0 until parts.length()) {
                    val partText = parts.getJSONObject(i).optString("text", "")
                    if (partText.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(partText)
                    }
                }
            }
        }.getOrDefault("")
    }
}

/**
 * 3-Tier Hybrid Zero-Developer-Cost Parent Safety Coach & Risk Advisor Engine:
 *
 * - Tier 1 (Primary Standard Cloud BYOK): Used when BYOK is configured, usage < 80%, and Eco Lite is OFF.
 * - Tier 2 (Auto-Cascade Eco Lite Cloud BYOK): Automatically selected when Eco Lite is ON, usage >= 80%,
 *   or Tier 1 encounters HTTP 429 RESOURCE_EXHAUSTED — stretches quota 3x–5x further.
 * - Tier 3 (Zero-Cost Local Reflex Engine): Always-free, offline-capable fallback with 0 cloud tokens.
 *
 * Also implements:
 * - Local PII redaction before any cloud call
 * - 45-second fingerprint deduplication cache (0 extra credits on double-tap)
 * - 6-turn sliding conversation window
 * - Atomic deduct-on-success (failed calls never consume daily quota)
 */
class ParentAiCoachEngine(
    private val transport: GeminiCloudTransport = HttpGeminiCloudTransport,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val localizer: AiCoachLocalizer = DefaultEnglishAiCoachLocalizer
) {

    private data class CachedEntry(
        val text: String,
        val createdAtMs: Long
    )

    private data class CascadeAttemptResult(
        val text: String?,
        val origin: AiResponseOrigin,
        val fallbackReason: AiFallbackReason,
        val cascadedFromStandard: Boolean,
        val chargedCloudCredit: Boolean
    )

    private val dedupCache = ConcurrentHashMap<String, CachedEntry>()

    data class RiskAppSignature(
        val keywords: List<String>,
        val domainHint: String?,
        val severity: AlertSeverity,
        val category: RiskCatalogCategory
    )

    private val riskyAppOrPlatformCatalog: List<RiskAppSignature> = listOf(
        RiskAppSignature(
            keywords = listOf("omegle", "ometv", "monkey app", "chatroulette", "azar"),
            domainHint = "omegle.com",
            severity = AlertSeverity.HIGH,
            category = RiskCatalogCategory.STRANGER_VIDEO_CHAT
        ),
        RiskAppSignature(
            keywords = listOf("telegram", "kik", "wickr", "snapchat", "discord"),
            domainHint = "discord.com",
            severity = AlertSeverity.MEDIUM,
            category = RiskCatalogCategory.OPEN_MESSAGING
        ),
        RiskAppSignature(
            keywords = listOf("roblox", "free robux", "robux", "vbucks", "free diamonds", "minecraft server"),
            domainHint = null,
            severity = AlertSeverity.MEDIUM,
            category = RiskCatalogCategory.MULTIPLAYER_SANDBOX
        ),
        RiskAppSignature(
            keywords = listOf("tiktok", "shorts", "reels", "youtube", "twitch"),
            domainHint = "tiktok.com",
            severity = AlertSeverity.MEDIUM,
            category = RiskCatalogCategory.SHORT_VIDEO_AUTOPLAY
        ),
        RiskAppSignature(
            keywords = listOf("casino", "bet365", "1xbet", "tai xiu", "no hu", "poker", "gambling", "slot"),
            domainHint = "bet365.com",
            severity = AlertSeverity.HIGH,
            category = RiskCatalogCategory.GAMBLING_BETTING
        ),
        RiskAppSignature(
            keywords = listOf("vpn", "proxy", "tor browser", "dns changer", "bypass"),
            domainHint = null,
            severity = AlertSeverity.MEDIUM,
            category = RiskCatalogCategory.BYPASS_VPN_TOOLS
        )
    )

    private val domainExtractRegex = Regex(
        """\b(?:https?://)?(?:www\.)?([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+)\b"""
    )

    /**
     * Redacts personal identifiers (emails, phone numbers, OTP codes, @handles) locally
     * BEFORE any prompt leaves the device to a BYOK cloud endpoint.
     */
    fun redactForCloud(raw: String): String {
        var text = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        text = text.replace(Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"), "[EMAIL]")
        text = text.replace(Regex("\\+?\\d[\\d\\s\\-]{5,}\\d"), "[NUMBER]")
        text = text.replace(Regex("\\b\\d{4,8}\\b"), "[CODE]")
        text = text.replace(Regex("@[a-zA-Z0-9_.]{2,}"), "[USER]")
        return text.take(1_200)
    }

    /**
     * Evaluates whether a BYOK cloud call can run right now, or returns the exact fallback reason.
     */
    fun checkCloudEligibility(prefs: AiCoachPreferences): AiFallbackReason {
        if (prefs.mode != AiEngineMode.BYOK_CLOUD_WITH_FALLBACK) return AiFallbackReason.NONE
        if (!prefs.hasValidKeyFormat) return AiFallbackReason.NO_KEY
        if (prefs.remainingCloudToday <= 0) return AiFallbackReason.DAILY_QUOTA_REACHED
        return AiFallbackReason.NONE
    }

    suspend fun testByokApiKey(apiKey: String, model: ByokGeminiModel): ByokTestResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.length < 20) {
            return ByokTestResult(
                success = false,
                message = "API key looks too short. Paste a valid Gemini API key.",
                fallbackReason = AiFallbackReason.NO_KEY,
                status = ByokTestStatus.KEY_TOO_SHORT,
                testedModel = model
            )
        }
        return try {
            withTimeout(20_000L) {
                when (val result = transport.generate(
                    apiKey = cleanKey,
                    modelId = model.modelId,
                    systemInstruction = "Respond with exactly: OK",
                    userPrompt = "Ping",
                    imageJpegBase64 = null
                )) {
                    is CloudTransportResult.Success -> ByokTestResult(
                        success = true,
                        message = "Connected to ${model.displayLabel} successfully.",
                        status = ByokTestStatus.SUCCESS,
                        testedModel = model
                    )
                    is CloudTransportResult.Failure -> ByokTestResult(
                        success = false,
                        message = when (result.reason) {
                            AiFallbackReason.AUTH_OR_RATE_LIMIT -> "Key rejected or quota exceeded (${result.detail})."
                            AiFallbackReason.TIMEOUT -> "Connection timed out. Check internet and retry."
                            else -> "Could not reach Gemini (${result.detail})."
                        },
                        fallbackReason = result.reason,
                        status = when (result.reason) {
                            AiFallbackReason.AUTH_OR_RATE_LIMIT -> ByokTestStatus.AUTH_OR_RATE_LIMIT
                            AiFallbackReason.TIMEOUT -> ByokTestStatus.TIMEOUT
                            else -> ByokTestStatus.NETWORK_ERROR
                        },
                        detail = result.detail,
                        testedModel = model
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ByokTestResult(
                success = false,
                message = "Connection timed out. On-Device Free mode remains available.",
                fallbackReason = AiFallbackReason.TIMEOUT,
                status = ByokTestStatus.TIMEOUT_LOCAL_FALLBACK,
                testedModel = model
            )
        }
    }

    suspend fun askCoach(
        question: String,
        context: AiChildContext,
        prefs: AiCoachPreferences,
        onInteractionFinished: suspend (chargedCloudCredit: Boolean, cascadedTier: Boolean) -> Unit = { _, _ -> }
    ): AiCoachMessage {
        val localDraft = generateOnDeviceCoachReply(question, context)

        if (prefs.mode != AiEngineMode.BYOK_CLOUD_WITH_FALLBACK) {
            onInteractionFinished(false, false)
            return localDraft
        }

        val safeQuestion = redactForCloud(question)
        val systemInstruction = buildCoachSystemInstruction(context)
        val slidingTurns = context.recentConversationTurns.takeLast(AiModelConfig.SLIDING_CONTEXT_MAX_TURNS)
        val userPrompt = buildString {
            if (slidingTurns.isNotEmpty()) {
                appendLine("Recent conversation context (last ${slidingTurns.size} turns):")
                slidingTurns.forEach { (isUserTurn, turnText) ->
                    val role = if (isUserTurn) "Parent" else "Coach"
                    appendLine("$role: ${redactForCloud(turnText).take(220)}")
                }
                appendLine()
            }
            appendLine("Parent question: $safeQuestion")
            appendLine("Child age: ${context.childAge}, Daily limit: ${context.dailyLimitMinutes}m, Today: ${context.todayScreenMinutes}m.")
            appendLine("Bedtime: ${context.bedtimeStart}-${context.bedtimeEnd}, Unreviewed alerts: ${context.unreviewedAlertsCount}.")
            context.weeklyReport?.takeIf { it.hasAnyData }?.let { rep ->
                val deltaText = rep.deltaPercent?.let { "$it%" } ?: "N/A"
                appendLine("Weekly trend: ${rep.thisWeekTotalMin}m vs ${rep.previousWeekTotalMin}m ($deltaText).")
            }
            appendLine("Give warm, non-judgmental, actionable parenting guidance in 3-5 short bullet points plus 1 calm conversation starter.")
        }

        val fingerprint = computeFingerprint("coach", safeQuestion, "${context.childAge}:${context.dailyLimitMinutes}")
        val attempt = executeCloudCascade(
            fingerprint = fingerprint,
            prefs = prefs,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            imageJpegBase64 = null
        )
        onInteractionFinished(attempt.chargedCloudCredit, attempt.cascadedFromStandard)

        return if (attempt.text != null) {
            localDraft.copy(
                text = attempt.text,
                origin = attempt.origin,
                fallbackReason = AiFallbackReason.NONE,
                cascadedFromStandard = attempt.cascadedFromStandard
            )
        } else {
            localDraft.copy(
                origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                fallbackReason = attempt.fallbackReason,
                cascadedFromStandard = attempt.cascadedFromStandard
            )
        }
    }

    suspend fun analyzeRisk(
        inputText: String,
        screenshotJpegBase64: String?,
        context: AiChildContext,
        prefs: AiCoachPreferences,
        onInteractionFinished: suspend (chargedCloudCredit: Boolean, cascadedTier: Boolean) -> Unit = { _, _ -> }
    ): AiRiskCheckResult {
        val localResult = analyzeRiskOnDevice(
            inputText = inputText,
            hasScreenshot = !screenshotJpegBase64.isNullOrBlank(),
            context = context
        )

        if (prefs.mode != AiEngineMode.BYOK_CLOUD_WITH_FALLBACK) {
            onInteractionFinished(false, false)
            return localResult
        }

        val safeInput = redactForCloud(inputText)
        val prompt = buildString {
            appendLine("Analyze child digital safety risk for a ${context.childAge}-year-old.")
            if (safeInput.isNotBlank()) appendLine("Text / app / domain provided: $safeInput")
            if (!screenshotJpegBase64.isNullOrBlank()) appendLine("A screenshot is attached. Check for scams, cyberbullying, grooming, gambling, or unsafe app cues.")
            appendLine("Respond in 3 concise lines starting with:")
            appendLine("RISK: LOW or MEDIUM or HIGH")
            appendLine("WHY: 1-2 short sentences")
            appendLine("TIP: 1 calm parent action or conversation tip")
        }

        val mediaSig = screenshotJpegBase64?.let { "${it.length}:${it.take(32)}:${it.takeLast(16)}" }.orEmpty()
        val fingerprint = computeFingerprint("risk", safeInput, mediaSig)
        val attempt = executeCloudCascade(
            fingerprint = fingerprint,
            prefs = prefs,
            systemInstruction = buildCoachSystemInstruction(context),
            userPrompt = prompt,
            imageJpegBase64 = screenshotJpegBase64
        )
        onInteractionFinished(attempt.chargedCloudCredit, attempt.cascadedFromStandard)

        return if (attempt.text != null) {
            mergeCloudRiskResult(
                local = localResult,
                cloudText = attempt.text,
                origin = attempt.origin,
                cascaded = attempt.cascadedFromStandard
            )
        } else {
            localResult.copy(
                origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                fallbackReason = attempt.fallbackReason,
                cascadedFromStandard = attempt.cascadedFromStandard
            )
        }
    }

    suspend fun generateWeeklyBrief(
        context: AiChildContext,
        prefs: AiCoachPreferences,
        onInteractionFinished: suspend (chargedCloudCredit: Boolean, cascadedTier: Boolean) -> Unit = { _, _ -> }
    ): AiWeeklyBrief {
        val localBrief = buildOnDeviceWeeklyBrief(context)

        if (prefs.mode != AiEngineMode.BYOK_CLOUD_WITH_FALLBACK) {
            onInteractionFinished(false, false)
            return localBrief
        }

        val report = context.weeklyReport?.takeIf { it.hasAnyData } ?: run {
            onInteractionFinished(false, false)
            return localBrief
        }
        val topCatDelta = report.categoryDeltas.maxByOrNull { it.thisWeekMinutes }
        val topCatName = topCatDelta?.category?.name ?: AppUsageCategory.OTHER.name
        val topCatMin = topCatDelta?.thisWeekMinutes ?: 0
        val deltaText = report.deltaPercent?.let { "$it%" } ?: "N/A"
        val prompt = buildString {
            appendLine("Create a concise weekly digital-wellbeing coaching brief for a ${context.childAge}-year-old.")
            appendLine("This week total: ${report.thisWeekTotalMin}m (${report.thisWeekDailyAvgMin}m/day).")
            appendLine("Last week total: ${report.previousWeekTotalMin}m, delta: $deltaText.")
            appendLine("Top category: $topCatName (${topCatMin}m). Daily limit: ${context.dailyLimitMinutes}m.")
            appendLine("Respond with 3 short bullet lines starting with '- ' and a final line starting with 'STARTER: '.")
        }

        val fingerprint = computeFingerprint(
            "brief",
            "${context.childName}:${report.thisWeekTotalMin}:${report.previousWeekTotalMin}",
            topCatName
        )
        val attempt = executeCloudCascade(
            fingerprint = fingerprint,
            prefs = prefs,
            systemInstruction = buildCoachSystemInstruction(context),
            userPrompt = prompt,
            imageJpegBase64 = null
        )
        onInteractionFinished(attempt.chargedCloudCredit, attempt.cascadedFromStandard)

        return if (attempt.text != null) {
            parseCloudWeeklyBrief(
                local = localBrief,
                cloudText = attempt.text,
                origin = attempt.origin,
                cascaded = attempt.cascadedFromStandard
            )
        } else {
            localBrief.copy(
                origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                fallbackReason = attempt.fallbackReason,
                cascadedFromStandard = attempt.cascadedFromStandard
            )
        }
    }

    /**
     * Core 3-Tier Cascade Runner:
     * 1. Check 45s fingerprint deduplication cache -> 0 tokens if hit.
     * 2. Verify BYOK key & remaining daily quota -> Tier 3 fallback if exhausted/missing.
     * 3. Choose Tier 1 (Standard) or Tier 2 (Eco Lite when >= 80% used or Eco Lite ON).
     * 4. If Tier 1 hits HTTP 429 rate limit, automatically cascade to Tier 2 (Eco Lite).
     * 5. Only charge cloud quota on verified success (atomic deduct-on-success).
     */
    private suspend fun executeCloudCascade(
        fingerprint: String,
        prefs: AiCoachPreferences,
        systemInstruction: String,
        userPrompt: String,
        imageJpegBase64: String?
    ): CascadeAttemptResult {
        val now = clockMs()
        val cached = dedupCache[fingerprint]
        if (cached != null && (now - cached.createdAtMs) <= AiModelConfig.DEDUP_CACHE_TTL_MS) {
            return CascadeAttemptResult(
                text = cached.text,
                origin = AiResponseOrigin.CACHED_ZERO_TOKEN,
                fallbackReason = AiFallbackReason.NONE,
                cascadedFromStandard = false,
                chargedCloudCredit = false
            )
        }

        val eligibility = checkCloudEligibility(prefs)
        if (eligibility != AiFallbackReason.NONE) {
            return CascadeAttemptResult(
                text = null,
                origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                fallbackReason = eligibility,
                cascadedFromStandard = false,
                chargedCloudCredit = false
            )
        }

        val initialModel = prefs.resolvedCloudModel()
        val startedWithEco = initialModel.isEconomyTier
        val autoEcoByThreshold = !prefs.ecoLiteMode && !prefs.model.isEconomyTier && startedWithEco

        return try {
            withTimeout(45_000L) {
                when (val firstResult = transport.generate(
                    apiKey = prefs.byokApiKey,
                    modelId = initialModel.modelId,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    imageJpegBase64 = imageJpegBase64
                )) {
                    is CloudTransportResult.Success -> {
                        cacheResponse(fingerprint, firstResult.text, now)
                        CascadeAttemptResult(
                            text = firstResult.text,
                            origin = if (startedWithEco) AiResponseOrigin.CLOUD_BYOK_ECO_LITE else AiResponseOrigin.CLOUD_BYOK_STANDARD,
                            fallbackReason = AiFallbackReason.NONE,
                            cascadedFromStandard = autoEcoByThreshold,
                            chargedCloudCredit = true
                        )
                    }

                    is CloudTransportResult.Failure -> {
                        // Auto-cascade Tier 1 -> Tier 2 (Eco Lite) on HTTP 429 RESOURCE_EXHAUSTED
                        if (!startedWithEco && firstResult.isRateLimited429) {
                            when (val liteResult = transport.generate(
                                apiKey = prefs.byokApiKey,
                                modelId = ByokGeminiModel.ECONOMY_LITE.modelId,
                                systemInstruction = systemInstruction,
                                userPrompt = userPrompt,
                                imageJpegBase64 = imageJpegBase64
                            )) {
                                is CloudTransportResult.Success -> {
                                    cacheResponse(fingerprint, liteResult.text, now)
                                    CascadeAttemptResult(
                                        text = liteResult.text,
                                        origin = AiResponseOrigin.CLOUD_BYOK_ECO_LITE,
                                        fallbackReason = AiFallbackReason.NONE,
                                        cascadedFromStandard = true,
                                        chargedCloudCredit = true
                                    )
                                }
                                is CloudTransportResult.Failure -> {
                                    CascadeAttemptResult(
                                        text = null,
                                        origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                                        fallbackReason = liteResult.reason,
                                        cascadedFromStandard = true,
                                        chargedCloudCredit = false
                                    )
                                }
                            }
                        } else {
                            CascadeAttemptResult(
                                text = null,
                                origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                                fallbackReason = firstResult.reason,
                                cascadedFromStandard = autoEcoByThreshold,
                                chargedCloudCredit = false
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CascadeAttemptResult(
                text = null,
                origin = AiResponseOrigin.ON_DEVICE_FALLBACK,
                fallbackReason = AiFallbackReason.TIMEOUT,
                cascadedFromStandard = autoEcoByThreshold,
                chargedCloudCredit = false
            )
        }
    }

    private fun cacheResponse(fingerprint: String, text: String, nowMs: Long) {
        if (dedupCache.size > 32) {
            val cutoff = nowMs - AiModelConfig.DEDUP_CACHE_TTL_MS
            dedupCache.entries.removeIf { it.value.createdAtMs < cutoff }
        }
        dedupCache[fingerprint] = CachedEntry(text = text, createdAtMs = nowMs)
    }

    private fun computeFingerprint(channel: String, primary: String, meta: String): String {
        val raw = "$channel|${primary.trim().lowercase(Locale.US)}|$meta"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Tier 3 — Always-Free On-Device Parenting Safety Coach (0 cloud tokens).
     */
    fun generateOnDeviceCoachReply(question: String, context: AiChildContext): AiCoachMessage {
        val q = question.lowercase(Locale.US)
        val detectedDomain = extractFirstDomain(question)
        val actions = mutableListOf<AiRecommendedAction>()
        if (detectedDomain != null) {
            actions += AiRecommendedAction(
                type = AiActionType.BLOCK_DOMAIN,
                domain = detectedDomain,
                label = localizer.blockDomainActionLabel(detectedDomain)
            )
        }

        val ageStage = localizer.ageStageLabel(context.childAge)
        val starterMatch = localizer.matchesStarterTopic(question)

        val resolvedTopic = when {
            starterMatch != null -> starterMatch
            q.contains("bully") || q.contains("mean") || q.contains("hate") || q.contains("self") ||
                q.contains("groom") || q.contains("alert") || q.contains("bắt nạt") || q.contains("acoso") ||
                q.contains("harcèlement") || q.contains("mobbing") || q.contains("bullismo") ||
                q.contains("いじめ") || q.contains("괴롭힘") || q.contains("欺凌") || q.contains("霸凌") ||
                q.contains("تنمر") || q.contains("बुली") || q.contains("perundungan") || q.contains("กลั่นแกล้ง") ||
                q.contains("буллинг") || q.contains("zorbalık") || q.contains("pesten") || q.contains("przemoc") ||
                q.contains("цькуван") || q.contains("buli") ->
                AiCoachTopic.BULLY_OR_ALERT

            q.contains("game") || q.contains("gaming") || q.contains("roblox") || q.contains("youtube") ||
                q.contains("video") || q.contains("tiktok") || q.contains("screen time") || q.contains("limit") ||
                q.contains("trò chơi") || q.contains("juego") || q.contains("jeu") || q.contains("spiel") ||
                q.contains("gioco") || q.contains("ゲーム") || q.contains("게임") || q.contains("游戏") || q.contains("遊戲") ||
                q.contains("ألعاب") || q.contains("गेम") || q.contains("ขีดจำกัด") || q.contains("เกม") ||
                q.contains("игр") || q.contains("oyun") || q.contains("gamen") || q.contains("granie") ||
                q.contains("ігор") || q.contains("permainan") ->
                AiCoachTopic.GAMING_OR_SCREEN_TIME

            q.contains("bedtime") || q.contains("sleep") || q.contains("night") || q.contains("late") ||
                q.contains("ngủ") || q.contains("dormir") || q.contains("coucher") || q.contains("schlaf") ||
                q.contains("nanna") || q.contains("就寝") || q.contains("취침") || q.contains("睡觉") || q.contains("就寢") ||
                q.contains("نوم") || q.contains("सोने") || q.contains("tidur") || q.contains("นอน") ||
                q.contains("сон") || q.contains("uyku") || q.contains("bedtijd") || q.contains("snu") ||
                q.contains("сну") ->
                AiCoachTopic.BEDTIME_OR_SLEEP

            q.contains("agreement") || q.contains("contract") || q.contains("rule") || q.contains("trust") ||
                q.contains("thỏa thuận") || q.contains("acuerdo") || q.contains("accord") || q.contains("vereinbarung") ||
                q.contains("accordo") || q.contains("ルール") || q.contains("약속") || q.contains("协议") || q.contains("協議") ||
                q.contains("اتفاق") || q.contains("समझौता") || q.contains("kesepakatan") || q.contains("ข้อตกลง") ||
                q.contains("соглашен") || q.contains("anlaşma") || q.contains("afspraak") || q.contains("umowa") ||
                q.contains("угода") || q.contains("perjanjian") ->
                AiCoachTopic.FAMILY_AGREEMENT

            detectedDomain != null -> AiCoachTopic.DOMAIN_CHECK
            else -> AiCoachTopic.GENERAL
        }

        var domainBlocked = false
        when (resolvedTopic) {
            AiCoachTopic.BULLY_OR_ALERT ->
                actions += AiRecommendedAction(type = AiActionType.OPEN_BLOCKED_WEBSITES)
            AiCoachTopic.GAMING_OR_SCREEN_TIME -> {
                actions += AiRecommendedAction(type = AiActionType.APPLY_SCHOOL_NIGHT_PRESET)
                actions += AiRecommendedAction(type = AiActionType.OPEN_CATEGORY_LIMITS)
            }
            AiCoachTopic.BEDTIME_OR_SLEEP ->
                actions += AiRecommendedAction(type = AiActionType.APPLY_SCHOOL_NIGHT_PRESET)
            AiCoachTopic.FAMILY_AGREEMENT ->
                actions += AiRecommendedAction(type = AiActionType.OPEN_CATEGORY_LIMITS)
            AiCoachTopic.DOMAIN_CHECK -> {
                domainBlocked = detectedDomain?.let {
                    LocalDnsFilterEngine.evaluateDomain(it, context.filterSnapshot).blocked
                } ?: false
            }
            AiCoachTopic.GENERAL -> {
                actions += AiRecommendedAction(type = AiActionType.OPEN_CATEGORY_LIMITS)
                actions += AiRecommendedAction(type = AiActionType.OPEN_BLOCKED_WEBSITES)
            }
        }

        val replyText = localizer.coachReply(
            topic = resolvedTopic,
            childName = context.childName,
            ageStage = ageStage,
            dailyLimitMinutes = context.dailyLimitMinutes,
            bedtimeStart = context.bedtimeStart,
            bedtimeEnd = context.bedtimeEnd,
            customBlockedCount = context.customBlockedCount,
            unreviewedAlertsCount = context.unreviewedAlertsCount,
            detectedDomain = detectedDomain,
            domainBlocked = domainBlocked
        )

        return AiCoachMessage(
            id = "coach_${System.nanoTime()}",
            isUser = false,
            text = replyText,
            origin = AiResponseOrigin.ON_DEVICE_FREE,
            fallbackReason = AiFallbackReason.NONE,
            recommendedActions = actions.distinctBy { it.type to it.domain }
        )
    }

    /**
     * Tier 3 — On-Device App / Domain / Text / Screenshot Risk Analyzer.
     */
    fun analyzeRiskOnDevice(
        inputText: String,
        hasScreenshot: Boolean,
        context: AiChildContext
    ): AiRiskCheckResult {
        val trimmed = inputText.trim()
        val normalizedLower = trimmed.lowercase(Locale.US)
        val detectedDomain = extractFirstDomain(trimmed)
        val nlpResult = OnDeviceSafetyEngine.analyzeText(trimmed, deduplicate = false)
        val dnsEval = detectedDomain?.let { LocalDnsFilterEngine.evaluateDomain(it, context.filterSnapshot) }
        val catalogHit = riskyAppOrPlatformCatalog.firstOrNull { sig ->
            sig.keywords.any { kw -> normalizedLower.contains(kw) }
        }

        val actions = mutableListOf<AiRecommendedAction>()
        val targetDomain = detectedDomain ?: catalogHit?.domainHint
        if (!targetDomain.isNullOrBlank()) {
            actions += AiRecommendedAction(
                type = AiActionType.BLOCK_DOMAIN,
                domain = targetDomain,
                label = localizer.blockDomainActionLabel(targetDomain)
            )
        }

        return when {
            nlpResult.flagged -> {
                val (headline, explanation, tip) = localizer.riskNlpVerdict(
                    categoryLabel = localizer.alertTypeLabel(nlpResult.topCategory),
                    triggersCsv = nlpResult.matchedTriggers.take(3).joinToString(", "),
                    confidencePct = (nlpResult.confidence * 100).toInt(),
                    childName = context.childName
                )
                AiRiskCheckResult(
                    inputPreview = nlpResult.redactedSnippet.ifBlank { trimmed.take(90) },
                    hasScreenshot = hasScreenshot,
                    severity = nlpResult.severity,
                    headline = headline,
                    explanation = explanation,
                    parentTip = tip,
                    detectedDomain = targetDomain,
                    origin = AiResponseOrigin.ON_DEVICE_FREE,
                    recommendedActions = actions
                )
            }

            dnsEval != null && dnsEval.blocked -> {
                val localizedReason = localizer.dnsVerdictReasonLabel(dnsEval.verdict, dnsEval.reason)
                val (headline, explanation, tip) = localizer.riskDnsVerdict(
                    domain = detectedDomain,
                    localizedReason = localizedReason
                )
                AiRiskCheckResult(
                    inputPreview = detectedDomain,
                    hasScreenshot = hasScreenshot,
                    severity = AlertSeverity.HIGH,
                    headline = headline,
                    explanation = explanation,
                    parentTip = tip,
                    detectedDomain = detectedDomain,
                    origin = AiResponseOrigin.ON_DEVICE_FREE,
                    recommendedActions = actions
                )
            }

            catalogHit != null -> {
                actions += AiRecommendedAction(type = AiActionType.OPEN_CATEGORY_LIMITS)
                AiRiskCheckResult(
                    inputPreview = trimmed.ifBlank { catalogHit.domainHint ?: localizer.riskInputAppCheck() },
                    hasScreenshot = hasScreenshot,
                    severity = catalogHit.severity,
                    headline = localizer.riskCatalogHeadline(context.childAge),
                    explanation = localizer.catalogSummary(catalogHit.category),
                    parentTip = localizer.catalogTip(catalogHit.category),
                    detectedDomain = targetDomain,
                    origin = AiResponseOrigin.ON_DEVICE_FREE,
                    recommendedActions = actions.distinctBy { it.type to it.domain }
                )
            }

            hasScreenshot && trimmed.isBlank() -> {
                val (headline, explanation, tip) = localizer.riskScreenshotNoCaption()
                AiRiskCheckResult(
                    inputPreview = localizer.riskInputScreenshotAttached(),
                    hasScreenshot = true,
                    severity = AlertSeverity.LOW,
                    headline = headline,
                    headlineRes = R.string.ai_risk_local_headline_screenshot_ready,
                    explanation = explanation,
                    parentTip = tip,
                    detectedDomain = null,
                    origin = AiResponseOrigin.ON_DEVICE_FREE,
                    recommendedActions = emptyList()
                )
            }

            else -> {
                val (headline, explanation, tip) = localizer.riskSafeVerdict(
                    detectedDomain = detectedDomain,
                    childName = context.childName,
                    childAge = context.childAge
                )
                AiRiskCheckResult(
                    inputPreview = OnDeviceSafetyEngine.redactSnippet(trimmed).ifBlank { localizer.riskInputNone() },
                    hasScreenshot = hasScreenshot,
                    severity = AlertSeverity.LOW,
                    headline = headline,
                    headlineRes = R.string.ai_risk_local_headline_none,
                    explanation = explanation,
                    parentTip = tip,
                    detectedDomain = targetDomain,
                    origin = AiResponseOrigin.ON_DEVICE_FREE,
                    recommendedActions = actions
                )
            }
        }
    }

    /**
     * Tier 3 — On-Device Weekly Coaching Brief derived directly from [WeeklySummaryReport].
     */
    fun buildOnDeviceWeeklyBrief(context: AiChildContext): AiWeeklyBrief {
        val report = context.weeklyReport
        if (report == null || !report.hasAnyData || report.trendDirection == WeeklyTrendDirection.NO_DATA) {
            val empty = localizer.weeklyBriefEmpty(context.childName)
            return AiWeeklyBrief(
                childName = context.childName,
                headline = empty.headline,
                bullets = empty.bullets,
                conversationStarter = empty.conversationStarter,
                origin = AiResponseOrigin.ON_DEVICE_FREE,
                recommendedActions = listOf(
                    AiRecommendedAction(type = AiActionType.APPLY_SCHOOL_NIGHT_PRESET),
                    AiRecommendedAction(type = AiActionType.OPEN_CATEGORY_LIMITS)
                )
            )
        }

        val absDelta = kotlin.math.abs(report.deltaPercent ?: 0)
        val headline = when (report.trendDirection) {
            WeeklyTrendDirection.DOWN_IMPROVED -> localizer.weeklyBriefHeadlineDown(context.childName, absDelta)
            WeeklyTrendDirection.UP_INCREASED -> localizer.weeklyBriefHeadlineUp(context.childName, absDelta)
            WeeklyTrendDirection.STEADY -> localizer.weeklyBriefHeadlineSteady(context.childName, report.thisWeekDailyAvgMin)
            WeeklyTrendDirection.FIRST_WEEK -> localizer.weeklyBriefHeadlineFirstWeek(context.childName, report.thisWeekDailyAvgMin)
            WeeklyTrendDirection.NO_DATA -> localizer.weeklyBriefEmpty(context.childName).headline
        }

        val topCatDelta = report.categoryDeltas.maxByOrNull { it.thisWeekMinutes }
        val topCategory = topCatDelta?.category ?: AppUsageCategory.OTHER
        val topCategoryMin = topCatDelta?.thisWeekMinutes ?: 0
        val topCatLabel = localizer.categoryLabel(topCategory)
        val bullets = buildList {
            add(localizer.weeklyBriefBulletTotal(report.thisWeekTotalMin, report.thisWeekDailyAvgMin, context.dailyLimitMinutes))
            add(localizer.weeklyBriefBulletTopCategory(topCatLabel, topCategoryMin))
            when (report.trendDirection) {
                WeeklyTrendDirection.DOWN_IMPROVED -> add(localizer.weeklyBriefBulletDown(context.childName))
                WeeklyTrendDirection.UP_INCREASED -> add(localizer.weeklyBriefBulletUp(topCatLabel, context.bedtimeStart, context.bedtimeEnd))
                WeeklyTrendDirection.STEADY,
                WeeklyTrendDirection.FIRST_WEEK,
                WeeklyTrendDirection.NO_DATA -> add(localizer.weeklyBriefBulletSteady())
            }
        }

        val starter = localizer.weeklyBriefStarter(topCategory)

        val actions = buildList {
            if (report.trendDirection == WeeklyTrendDirection.UP_INCREASED || report.thisWeekDailyAvgMin > context.dailyLimitMinutes) {
                add(AiRecommendedAction(type = AiActionType.APPLY_SCHOOL_NIGHT_PRESET))
            }
            add(AiRecommendedAction(type = AiActionType.OPEN_CATEGORY_LIMITS))
        }

        return AiWeeklyBrief(
            childName = context.childName,
            headline = headline,
            bullets = bullets,
            conversationStarter = starter,
            origin = AiResponseOrigin.ON_DEVICE_FREE,
            recommendedActions = actions
        )
    }

    private fun buildCoachSystemInstruction(context: AiChildContext): String =
        "You are SafeNest Parent Safety Coach, a warm, evidence-based digital parenting advisor. " +
            "Respond in the parent's language (${context.languageTag}). " +
            "Never shame parents or kids. Keep advice practical, age-appropriate for a ${context.childAge}-year-old, " +
            "concise, and privacy-respecting."

    private fun mergeCloudRiskResult(
        local: AiRiskCheckResult,
        cloudText: String,
        origin: AiResponseOrigin,
        cascaded: Boolean
    ): AiRiskCheckResult {
        val lines = cloudText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val riskLine = lines.firstOrNull { it.uppercase(Locale.US).startsWith("RISK:") }.orEmpty()
        val whyLine = lines.firstOrNull { it.uppercase(Locale.US).startsWith("WHY:") }?.substringAfter(":")?.trim()
        val tipLine = lines.firstOrNull { it.uppercase(Locale.US).startsWith("TIP:") }?.substringAfter(":")?.trim()

        val parsedSeverity = when {
            riskLine.contains("HIGH", ignoreCase = true) -> AlertSeverity.HIGH
            riskLine.contains("MEDIUM", ignoreCase = true) -> AlertSeverity.MEDIUM
            riskLine.contains("LOW", ignoreCase = true) -> AlertSeverity.LOW
            else -> local.severity
        }

        return local.copy(
            severity = parsedSeverity,
            headline = when (parsedSeverity) {
                AlertSeverity.HIGH -> "High Risk Assessment (Cloud BYOK)"
                AlertSeverity.MEDIUM -> "Moderate Risk Assessment (Cloud BYOK)"
                AlertSeverity.LOW -> "Low Risk Assessment (Cloud BYOK)"
            },
            headlineRes = when (parsedSeverity) {
                AlertSeverity.HIGH -> R.string.ai_risk_cloud_headline_high
                AlertSeverity.MEDIUM -> R.string.ai_risk_cloud_headline_medium
                AlertSeverity.LOW -> R.string.ai_risk_cloud_headline_low
            },
            explanation = whyLine?.ifBlank { null } ?: cloudText.take(360),
            parentTip = tipLine?.ifBlank { null } ?: local.parentTip,
            origin = origin,
            fallbackReason = AiFallbackReason.NONE,
            cascadedFromStandard = cascaded
        )
    }

    private fun parseCloudWeeklyBrief(
        local: AiWeeklyBrief,
        cloudText: String,
        origin: AiResponseOrigin,
        cascaded: Boolean
    ): AiWeeklyBrief {
        val lines = cloudText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bullets = lines
            .filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }
            .map { it.trimStart('-', '•', '*', ' ') }
            .filter { it.isNotBlank() }
            .take(4)
        val starter = lines.firstOrNull { it.uppercase(Locale.US).startsWith("STARTER:") }
            ?.substringAfter(":")
            ?.trim()
            ?.ifBlank { null }
            ?: local.conversationStarter

        return local.copy(
            bullets = if (bullets.isNotEmpty()) bullets else listOf(cloudText.take(320)),
            conversationStarter = starter,
            origin = origin,
            fallbackReason = AiFallbackReason.NONE,
            cascadedFromStandard = cascaded
        )
    }

    private fun extractFirstDomain(text: String): String? {
        val match = domainExtractRegex.find(text) ?: return null
        val candidate = match.groupValues[1].lowercase(Locale.US)
        if (candidate.endsWith(".jpg") || candidate.endsWith(".png") || candidate.endsWith(".mp4")) return null
        return candidate
    }
}
