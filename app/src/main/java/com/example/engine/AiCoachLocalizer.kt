package com.example.engine

import android.content.Context
import com.example.R
import com.example.analytics.AppUsageCategory
import com.example.data.AlertType

enum class AiCoachTopic {
    BULLY_OR_ALERT,
    GAMING_OR_SCREEN_TIME,
    BEDTIME_OR_SLEEP,
    FAMILY_AGREEMENT,
    DOMAIN_CHECK,
    GENERAL
}

enum class RiskCatalogCategory {
    STRANGER_VIDEO_CHAT,
    OPEN_MESSAGING,
    MULTIPLAYER_SANDBOX,
    SHORT_VIDEO_AUTOPLAY,
    GAMBLING_BETTING,
    BYPASS_VPN_TOOLS
}

/**
 * Pure-Kotlin localization abstraction for [ParentAiCoachEngine] so that:
 * - Android runtime uses [AndroidAiCoachLocalizer] backed by `R.string.*` across all 21 supported locales.
 * - Pure-JVM unit tests use [DefaultEnglishAiCoachLocalizer] without requiring an Android Context.
 */
interface AiCoachLocalizer {
    fun ageStageLabel(age: Int): String
    fun blockDomainActionLabel(domain: String): String

    fun matchesStarterTopic(question: String): AiCoachTopic?
    fun coachReply(
        topic: AiCoachTopic,
        childName: String,
        ageStage: String,
        dailyLimitMinutes: Int,
        bedtimeStart: String,
        bedtimeEnd: String,
        customBlockedCount: Int,
        unreviewedAlertsCount: Int,
        detectedDomain: String? = null,
        domainBlocked: Boolean = false
    ): String

    fun catalogSummary(category: RiskCatalogCategory): String
    fun catalogTip(category: RiskCatalogCategory): String

    fun riskInputAppCheck(): String
    fun riskInputScreenshotAttached(): String
    fun riskInputNone(): String

    fun alertTypeLabel(type: AlertType?): String
    fun dnsVerdictReasonLabel(verdict: DnsVerdict, fallbackReason: String): String
    fun categoryLabel(category: AppUsageCategory): String

    fun riskNlpVerdict(categoryLabel: String, triggersCsv: String, confidencePct: Int, childName: String): Triple<String, String, String>
    fun riskDnsVerdict(domain: String, localizedReason: String): Triple<String, String, String>
    fun riskCatalogHeadline(childAge: Int): String
    fun riskScreenshotNoCaption(): Triple<String, String, String>
    fun riskSafeVerdict(detectedDomain: String?, childName: String, childAge: Int): Triple<String, String, String>

    fun weeklyBriefEmpty(childName: String): AiWeeklyBriefResultText
    fun weeklyBriefHeadlineDown(childName: String, absDelta: Int): String
    fun weeklyBriefHeadlineUp(childName: String, absDelta: Int): String
    fun weeklyBriefHeadlineSteady(childName: String, dailyAvgMin: Int): String
    fun weeklyBriefHeadlineFirstWeek(childName: String, dailyAvgMin: Int): String
    fun weeklyBriefBulletTotal(totalMin: Int, dailyAvgMin: Int, dailyLimitMin: Int): String
    fun weeklyBriefBulletTopCategory(categoryLabel: String, minutes: Int): String
    fun weeklyBriefBulletDown(childName: String): String
    fun weeklyBriefBulletUp(categoryLabel: String, bedtimeStart: String, bedtimeEnd: String): String
    fun weeklyBriefBulletSteady(): String
    fun weeklyBriefStarter(topCategory: AppUsageCategory): String
}

data class AiWeeklyBriefResultText(
    val headline: String,
    val bullets: List<String>,
    val conversationStarter: String
)

object DefaultEnglishAiCoachLocalizer : AiCoachLocalizer {
    override fun ageStageLabel(age: Int): String = when {
        age <= 8 -> "early-reader (age $age)"
        age <= 12 -> "pre-teen (age $age)"
        else -> "teen (age $age)"
    }

    override fun blockDomainActionLabel(domain: String): String = "Block $domain"

    override fun matchesStarterTopic(question: String): AiCoachTopic? = null

    override fun coachReply(
        topic: AiCoachTopic,
        childName: String,
        ageStage: String,
        dailyLimitMinutes: Int,
        bedtimeStart: String,
        bedtimeEnd: String,
        customBlockedCount: Int,
        unreviewedAlertsCount: Int,
        detectedDomain: String?,
        domainBlocked: Boolean
    ): String = when (topic) {
        AiCoachTopic.BULLY_OR_ALERT -> buildString {
            appendLine("For a $ageStage child facing a tough online interaction, stay calm first so $childName feels safe coming to you again:")
            appendLine("• Validate before reacting: thank them for telling you and avoid immediately confiscating the phone as punishment.")
            appendLine("• Capture evidence calmly: keep the redacted SafeNest alert and block the sender/platform together.")
            appendLine("• Conversation starter: \"I saw something that worried me — you're not in trouble. Can we look at it together and decide how to handle it?\"")
        }.trim()

        AiCoachTopic.GAMING_OR_SCREEN_TIME -> buildString {
            appendLine("Practical screen-time plan for $childName ($ageStage, current limit ${dailyLimitMinutes}m/day):")
            appendLine("• Replace sudden cutoffs with predictable category caps (e.g., School Night preset: 30m Video, 30m Games, 20m Social).")
            appendLine("• Give a 10-minute wrap-up cue before a match or episode ends to prevent meltdowns.")
            appendLine("• Conversation starter: \"You get to pick which 30 minutes of gaming feel best today — after homework or before dinner?\"")
        }.trim()

        AiCoachTopic.BEDTIME_OR_SLEEP -> buildString {
            appendLine("Protecting sleep for $childName (Bedtime $bedtimeStart–$bedtimeEnd):")
            appendLine("• Charge devices outside the bedroom 30 minutes before $bedtimeStart so bedtime feels like a family routine, not a punishment.")
            appendLine("• Keep SafeNest DNS Shield + Bedtime Downtime active so late-night autoplay never restarts.")
            appendLine("• Conversation starter: \"Let's both park our phones in the kitchen at $bedtimeStart so we wake up sharp tomorrow.\"")
        }.trim()

        AiCoachTopic.FAMILY_AGREEMENT -> buildString {
            val ageNum = ageStage.filter { it.isDigit() }.toIntOrNull() ?: 10
            appendLine("3-point Family Device Agreement tailored for $childName (age $ageNum):")
            appendLine("1. Safety first: no passwords, private photos, or meeting online strangers — ever.")
            appendLine("2. Balance: ${dailyLimitMinutes}m daily budget with $bedtimeStart–$bedtimeEnd screen-free sleep.")
            appendLine("3. No-shame rescue rule: if something weird pops up, tell a parent immediately with zero punishment.")
        }.trim()

        AiCoachTopic.DOMAIN_CHECK -> {
            val domain = detectedDomain.orEmpty()
            val status = if (domainBlocked) "currently BLOCKED" else "currently ALLOWED"
            buildString {
                appendLine("Checked $domain against SafeNest's local DNS rules ($status):")
                appendLine("• If this site isn't age-appropriate for a $ageStage child, tap 'Block $domain' below to enforce it live in Local DNS.")
                appendLine("• Explain the 'why' briefly so $childName understands it's about safety, not distrust.")
            }.trim()
        }

        AiCoachTopic.GENERAL -> buildString {
            appendLine("SafeNest On-Device Coach summary for $childName ($ageStage):")
            appendLine("• Active guardrails: ${dailyLimitMinutes}m daily limit, bedtime $bedtimeStart–$bedtimeEnd, $customBlockedCount custom blocked domains.")
            if (unreviewedAlertsCount > 0) {
                appendLine("• Priority next step: review $unreviewedAlertsCount open safety alert(s) calmly before tightening any rules.")
            } else {
                appendLine("• Everything looks steady right now — reinforce positive habits by praising healthy offline breaks.")
            }
            appendLine("• Ask me about bedtime routines, gaming limits, cyberbullying scripts, a family agreement, or paste any app/website to check risk.")
        }.trim()
    }

    override fun catalogSummary(category: RiskCatalogCategory): String = when (category) {
        RiskCatalogCategory.STRANGER_VIDEO_CHAT ->
            "Random stranger video-chat platforms carry high exposure to unmoderated explicit content and grooming."
        RiskCatalogCategory.OPEN_MESSAGING ->
            "Open group messaging and disappearing chats can expose kids to unmoderated servers or strangers."
        RiskCatalogCategory.MULTIPLAYER_SANDBOX ->
            "Multiplayer sandboxes are fun, but 'free currency' offers and public voice chats are top scam vectors for kids."
        RiskCatalogCategory.SHORT_VIDEO_AUTOPLAY ->
            "Short-form endless video feeds are engineered for late-night autoplay and rapid habit loops."
        RiskCatalogCategory.GAMBLING_BETTING ->
            "Gambling and loot-betting platforms pose financial and addictive risks to minors."
        RiskCatalogCategory.BYPASS_VPN_TOOLS ->
            "Bypass tools or third-party VPNs can disable local DNS family filters."
    }

    override fun catalogTip(category: RiskCatalogCategory): String = when (category) {
        RiskCatalogCategory.STRANGER_VIDEO_CHAT ->
            "Explain calmly that stranger-matching apps cannot verify who is on the other side, and block the domain in Local DNS."
        RiskCatalogCategory.OPEN_MESSAGING ->
            "Review friend lists together, disable DMs from non-friends, and set a daily Social category limit."
        RiskCatalogCategory.MULTIPLAYER_SANDBOX ->
            "Enable account PIN & chat restrictions inside the game, and remind your child never to share passwords or OTP codes."
        RiskCatalogCategory.SHORT_VIDEO_AUTOPLAY ->
            "Agree on a predictable daily Video limit and enable Bedtime Downtime so autoplay never steals sleep."
        RiskCatalogCategory.GAMBLING_BETTING ->
            "Keep Gambling DNS Shield enabled and block any specific betting domain immediately."
        RiskCatalogCategory.BYPASS_VPN_TOOLS ->
            "Have an open trust conversation about why family guardrails exist rather than turning it into a cat-and-mouse game."
    }

    override fun riskInputAppCheck(): String = "App check"
    override fun riskInputScreenshotAttached(): String = "Screenshot attached"
    override fun riskInputNone(): String = "No text entered"

    override fun alertTypeLabel(type: AlertType?): String =
        type?.name?.replace('_', ' ') ?: "RISK"

    override fun dnsVerdictReasonLabel(verdict: DnsVerdict, fallbackReason: String): String = fallbackReason

    override fun categoryLabel(category: AppUsageCategory): String =
        category.name.lowercase().replaceFirstChar { it.uppercase() }

    override fun riskNlpVerdict(
        categoryLabel: String,
        triggersCsv: String,
        confidencePct: Int,
        childName: String
    ): Triple<String, String, String> = Triple(
        "High-priority safety signal detected ($categoryLabel)",
        "On-device NLP matched risk patterns ($triggersCsv) with $confidencePct% confidence.",
        "Stay calm, check in with $childName privately without blame, and block any related domain or account together."
    )

    override fun riskDnsVerdict(domain: String, localizedReason: String): Triple<String, String, String> = Triple(
        "$domain is flagged by SafeNest Local DNS",
        "This domain matches an active SafeNest blocklist rule ($localizedReason) and will be stopped locally on-device when DNS Shield is running.",
        "Make sure the Local DNS Shield VPN is toggled ON and discuss why unsafe sites are filtered."
    )

    override fun riskCatalogHeadline(childAge: Int): String =
        "Notable risk profile for age $childAge"

    override fun riskScreenshotNoCaption(): Triple<String, String, String> = Triple(
        "Screenshot ready — add a brief caption or enable BYOK Cloud Vision",
        "In 100% Free On-Device mode, SafeNest analyzes pasted text, URLs, slang, and app names locally without uploading images. Add optional BYOK Gemini in AI Settings for direct visual screenshot reading.",
        "Type any suspicious message, handle, or URL seen in the screenshot above for instant offline risk scoring."
    )

    override fun riskSafeVerdict(
        detectedDomain: String?,
        childName: String,
        childAge: Int
    ): Triple<String, String, String> {
        val explanation = if (detectedDomain != null) {
            "$detectedDomain did not trigger adult, gambling, malware, or scam signatures in SafeNest's local engine."
        } else {
            "No bullying, grooming, self-harm, or scam keywords were detected locally."
        }
        return Triple(
            "No immediate high-risk pattern detected on-device",
            explanation,
            "Trust your instincts: if an app or website still feels inappropriate for $childName (age $childAge), you can block it anytime in Local DNS."
        )
    }

    override fun weeklyBriefEmpty(childName: String): AiWeeklyBriefResultText = AiWeeklyBriefResultText(
        headline = "Sync device usage to unlock $childName's weekly coaching plan",
        bullets = listOf(
            "Open Dashboard and tap Sync to measure daily app categories privately on-device.",
            "Set a gentle School Night category cap so limits feel predictable rather than sudden.",
            "Keep Local DNS Shield active to filter adult, gambling, and malware sites at $0 cost."
        ),
        conversationStarter = "What was your favorite thing you watched or played this week?"
    )

    override fun weeklyBriefHeadlineDown(childName: String, absDelta: Int): String =
        "$childName's screen time improved by $absDelta% this week"

    override fun weeklyBriefHeadlineUp(childName: String, absDelta: Int): String =
        "$childName's screen time rose $absDelta% vs last week"

    override fun weeklyBriefHeadlineSteady(childName: String, dailyAvgMin: Int): String =
        "$childName's screen time held steady around ${dailyAvgMin}m/day"

    override fun weeklyBriefHeadlineFirstWeek(childName: String, dailyAvgMin: Int): String =
        "$childName's first-week baseline: ${dailyAvgMin}m/day"

    override fun weeklyBriefBulletTotal(totalMin: Int, dailyAvgMin: Int, dailyLimitMin: Int): String =
        "Weekly total: ${totalMin}m (${dailyAvgMin}m/day vs ${dailyLimitMin}m daily goal)."

    override fun weeklyBriefBulletTopCategory(categoryLabel: String, minutes: Int): String =
        "Top category: $categoryLabel (${minutes}m this week) — focus any small adjustment here first."

    override fun weeklyBriefBulletDown(childName: String): String =
        "Positive reinforcement: celebrate the drop before setting new goals so $childName feels proud of self-regulation."

    override fun weeklyBriefBulletUp(categoryLabel: String, bedtimeStart: String, bedtimeEnd: String): String =
        "Gentle reset: apply the School Night category preset for $categoryLabel and review bedtime ($bedtimeStart–$bedtimeEnd)."

    override fun weeklyBriefBulletSteady(): String =
        "Consistent rhythm: keep current guardrails steady and check in on favorite apps over dinner."

    override fun weeklyBriefStarter(topCategory: AppUsageCategory): String = when (topCategory) {
        AppUsageCategory.GAMES -> "Which game level was the most fun this week, and what offline activity sounds good after?"
        AppUsageCategory.VIDEO -> "What's one cool video you learned something from this week?"
        AppUsageCategory.SOCIAL -> "How are things feeling with friends in your group chats this week?"
        else -> "What was the best thing you did online this week, and did anything feel stressful?"
    }
}

class AndroidAiCoachLocalizer(
    private val context: Context
) : AiCoachLocalizer {

    override fun ageStageLabel(age: Int): String = when {
        age <= 8 -> context.getString(R.string.ai_coach_age_stage_early, age)
        age <= 12 -> context.getString(R.string.ai_coach_age_stage_preteen, age)
        else -> context.getString(R.string.ai_coach_age_stage_teen, age)
    }

    override fun blockDomainActionLabel(domain: String): String =
        context.getString(R.string.ai_coach_action_block_label, domain)

    override fun matchesStarterTopic(question: String): AiCoachTopic? {
        val clean = question.trim()
        return when {
            clean.equals(context.getString(R.string.ai_coach_starter_bully), ignoreCase = true) ->
                AiCoachTopic.BULLY_OR_ALERT
            clean.equals(context.getString(R.string.ai_coach_starter_gaming), ignoreCase = true) ->
                AiCoachTopic.GAMING_OR_SCREEN_TIME
            clean.equals(context.getString(R.string.ai_coach_starter_bedtime), ignoreCase = true) ->
                AiCoachTopic.BEDTIME_OR_SLEEP
            clean.equals(context.getString(R.string.ai_coach_starter_agreement), ignoreCase = true) ->
                AiCoachTopic.FAMILY_AGREEMENT
            else -> null
        }
    }

    override fun coachReply(
        topic: AiCoachTopic,
        childName: String,
        ageStage: String,
        dailyLimitMinutes: Int,
        bedtimeStart: String,
        bedtimeEnd: String,
        customBlockedCount: Int,
        unreviewedAlertsCount: Int,
        detectedDomain: String?,
        domainBlocked: Boolean
    ): String = when (topic) {
        AiCoachTopic.BULLY_OR_ALERT -> context.getString(
            R.string.ai_coach_reply_bully,
            ageStage,
            childName
        )

        AiCoachTopic.GAMING_OR_SCREEN_TIME -> context.getString(
            R.string.ai_coach_reply_gaming,
            childName,
            ageStage,
            dailyLimitMinutes
        )

        AiCoachTopic.BEDTIME_OR_SLEEP -> context.getString(
            R.string.ai_coach_reply_bedtime,
            childName,
            bedtimeStart,
            bedtimeEnd
        )

        AiCoachTopic.FAMILY_AGREEMENT -> {
            val ageNum = ageStage.filter { it.isDigit() }.toIntOrNull() ?: 10
            context.getString(
                R.string.ai_coach_reply_agreement,
                childName,
                ageNum,
                dailyLimitMinutes,
                bedtimeStart,
                bedtimeEnd
            )
        }

        AiCoachTopic.DOMAIN_CHECK -> {
            val statusText = context.getString(
                if (domainBlocked) R.string.ai_coach_dns_state_blocked
                else R.string.ai_coach_dns_state_allowed
            )
            context.getString(
                R.string.ai_coach_reply_domain,
                detectedDomain.orEmpty(),
                statusText,
                ageStage,
                childName
            )
        }

        AiCoachTopic.GENERAL -> {
            val priorityLine = if (unreviewedAlertsCount > 0) {
                context.getString(R.string.ai_coach_reply_general_priority_alerts, unreviewedAlertsCount)
            } else {
                context.getString(R.string.ai_coach_reply_general_steady)
            }
            context.getString(
                R.string.ai_coach_reply_general,
                childName,
                ageStage,
                dailyLimitMinutes,
                bedtimeStart,
                bedtimeEnd,
                customBlockedCount,
                priorityLine
            )
        }
    }

    override fun catalogSummary(category: RiskCatalogCategory): String = when (category) {
        RiskCatalogCategory.STRANGER_VIDEO_CHAT -> context.getString(R.string.ai_risk_cat_stranger_summary)
        RiskCatalogCategory.OPEN_MESSAGING -> context.getString(R.string.ai_risk_cat_messaging_summary)
        RiskCatalogCategory.MULTIPLAYER_SANDBOX -> context.getString(R.string.ai_risk_cat_sandbox_summary)
        RiskCatalogCategory.SHORT_VIDEO_AUTOPLAY -> context.getString(R.string.ai_risk_cat_video_summary)
        RiskCatalogCategory.GAMBLING_BETTING -> context.getString(R.string.ai_risk_cat_gambling_summary)
        RiskCatalogCategory.BYPASS_VPN_TOOLS -> context.getString(R.string.ai_risk_cat_bypass_summary)
    }

    override fun catalogTip(category: RiskCatalogCategory): String = when (category) {
        RiskCatalogCategory.STRANGER_VIDEO_CHAT -> context.getString(R.string.ai_risk_cat_stranger_tip)
        RiskCatalogCategory.OPEN_MESSAGING -> context.getString(R.string.ai_risk_cat_messaging_tip)
        RiskCatalogCategory.MULTIPLAYER_SANDBOX -> context.getString(R.string.ai_risk_cat_sandbox_tip)
        RiskCatalogCategory.SHORT_VIDEO_AUTOPLAY -> context.getString(R.string.ai_risk_cat_video_tip)
        RiskCatalogCategory.GAMBLING_BETTING -> context.getString(R.string.ai_risk_cat_gambling_tip)
        RiskCatalogCategory.BYPASS_VPN_TOOLS -> context.getString(R.string.ai_risk_cat_bypass_tip)
    }

    override fun riskInputAppCheck(): String = context.getString(R.string.ai_risk_input_app_check)
    override fun riskInputScreenshotAttached(): String = context.getString(R.string.ai_risk_input_screenshot_attached)
    override fun riskInputNone(): String = context.getString(R.string.ai_risk_input_none)

    override fun alertTypeLabel(type: AlertType?): String =
        if (type != null) context.getString(type.labelRes) else "RISK"

    override fun dnsVerdictReasonLabel(verdict: DnsVerdict, fallbackReason: String): String = when (verdict) {
        DnsVerdict.BLOCKED_CUSTOM -> context.getString(R.string.dns_reason_custom)
        DnsVerdict.BLOCKED_CATEGORY_LIMIT -> context.getString(R.string.dns_reason_category_limit)
        DnsVerdict.BLOCKED_ADULT -> context.getString(R.string.dns_reason_adult)
        DnsVerdict.BLOCKED_GAMBLING -> context.getString(R.string.dns_reason_gambling)
        DnsVerdict.BLOCKED_MALWARE -> context.getString(R.string.dns_reason_malware)
        DnsVerdict.BLOCKED_SOCIAL -> context.getString(R.string.dns_reason_social)
        DnsVerdict.SAFESEARCH_ENFORCED -> context.getString(R.string.dns_reason_safesearch)
        DnsVerdict.ALLOWED -> context.getString(R.string.dns_reason_allowed)
    }

    override fun categoryLabel(category: AppUsageCategory): String = context.getString(category.labelRes)

    override fun riskNlpVerdict(
        categoryLabel: String,
        triggersCsv: String,
        confidencePct: Int,
        childName: String
    ): Triple<String, String, String> = Triple(
        context.getString(R.string.ai_risk_nlp_headline, categoryLabel),
        context.getString(R.string.ai_risk_nlp_explanation, triggersCsv, confidencePct),
        context.getString(R.string.ai_risk_nlp_tip, childName)
    )

    override fun riskDnsVerdict(domain: String, localizedReason: String): Triple<String, String, String> = Triple(
        context.getString(R.string.ai_risk_dns_headline, domain),
        context.getString(R.string.ai_risk_dns_explanation, localizedReason),
        context.getString(R.string.ai_risk_dns_tip)
    )

    override fun riskCatalogHeadline(childAge: Int): String =
        context.getString(R.string.ai_risk_catalog_headline, childAge)

    override fun riskScreenshotNoCaption(): Triple<String, String, String> = Triple(
        context.getString(R.string.ai_risk_local_headline_screenshot_ready),
        context.getString(R.string.ai_risk_screenshot_explanation),
        context.getString(R.string.ai_risk_screenshot_tip)
    )

    override fun riskSafeVerdict(
        detectedDomain: String?,
        childName: String,
        childAge: Int
    ): Triple<String, String, String> = Triple(
        context.getString(R.string.ai_risk_local_headline_none),
        if (detectedDomain != null) {
            context.getString(R.string.ai_risk_safe_domain_explanation, detectedDomain)
        } else {
            context.getString(R.string.ai_risk_safe_text_explanation)
        },
        context.getString(R.string.ai_risk_safe_tip, childName, childAge)
    )

    override fun weeklyBriefEmpty(childName: String): AiWeeklyBriefResultText = AiWeeklyBriefResultText(
        headline = context.getString(R.string.ai_brief_empty_headline, childName),
        bullets = listOf(
            context.getString(R.string.ai_brief_empty_bullet_1),
            context.getString(R.string.ai_brief_empty_bullet_2),
            context.getString(R.string.ai_brief_empty_bullet_3)
        ),
        conversationStarter = context.getString(R.string.ai_brief_empty_starter)
    )

    override fun weeklyBriefHeadlineDown(childName: String, absDelta: Int): String =
        context.getString(R.string.ai_brief_headline_down, childName, absDelta)

    override fun weeklyBriefHeadlineUp(childName: String, absDelta: Int): String =
        context.getString(R.string.ai_brief_headline_up, childName, absDelta)

    override fun weeklyBriefHeadlineSteady(childName: String, dailyAvgMin: Int): String =
        context.getString(R.string.ai_brief_headline_steady, childName, dailyAvgMin)

    override fun weeklyBriefHeadlineFirstWeek(childName: String, dailyAvgMin: Int): String =
        context.getString(R.string.ai_brief_headline_first_week, childName, dailyAvgMin)

    override fun weeklyBriefBulletTotal(totalMin: Int, dailyAvgMin: Int, dailyLimitMin: Int): String =
        context.getString(R.string.ai_brief_bullet_total, totalMin, dailyAvgMin, dailyLimitMin)

    override fun weeklyBriefBulletTopCategory(categoryLabel: String, minutes: Int): String =
        context.getString(R.string.ai_brief_bullet_top_cat, categoryLabel, minutes)

    override fun weeklyBriefBulletDown(childName: String): String =
        context.getString(R.string.ai_brief_bullet_down, childName)

    override fun weeklyBriefBulletUp(categoryLabel: String, bedtimeStart: String, bedtimeEnd: String): String =
        context.getString(R.string.ai_brief_bullet_up, categoryLabel, bedtimeStart, bedtimeEnd)

    override fun weeklyBriefBulletSteady(): String =
        context.getString(R.string.ai_brief_bullet_steady)

    override fun weeklyBriefStarter(topCategory: AppUsageCategory): String = when (topCategory) {
        AppUsageCategory.GAMES -> context.getString(R.string.ai_brief_starter_games)
        AppUsageCategory.VIDEO -> context.getString(R.string.ai_brief_starter_video)
        AppUsageCategory.SOCIAL -> context.getString(R.string.ai_brief_starter_social)
        else -> context.getString(R.string.ai_brief_starter_other)
    }
}
