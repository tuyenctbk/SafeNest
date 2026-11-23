package com.example.engine

import com.example.data.LocalFilterSnapshot
import java.io.ByteArrayOutputStream

enum class DnsVerdict {
    ALLOWED,
    BLOCKED_ADULT,
    BLOCKED_GAMBLING,
    BLOCKED_MALWARE,
    BLOCKED_SOCIAL,
    BLOCKED_CUSTOM,
    BLOCKED_CATEGORY_LIMIT,
    SAFESEARCH_ENFORCED
}

data class DnsEvaluation(
    val domain: String,
    val verdict: DnsVerdict,
    val reason: String,
    val latencyMs: Double,
    val matchedPattern: String? = null
) {
    val blocked: Boolean
        get() = verdict != DnsVerdict.ALLOWED && verdict != DnsVerdict.SAFESEARCH_ENFORCED
}

enum class DomainPatternType {
    DOMAIN_SUFFIX,
    SUBDOMAIN_WILDCARD,
    WILDCARD,
    KEYWORD
}

data class DomainPatternMatch(
    val blocked: Boolean,
    val matchedPattern: String? = null,
    val patternType: DomainPatternType? = null
)

enum class BlockedWebsiteSource {
    CUSTOM,
    CATEGORY_LIMIT,
    ADULT_SHIELD,
    GAMBLING_SHIELD,
    MALWARE_SHIELD,
    SOCIAL_SHIELD
}

data class BlockedWebsiteEntry(
    val domain: String,
    val source: BlockedWebsiteSource,
    val removable: Boolean,
    val patternType: DomainPatternType = InMemoryDomainBlocklist.classifyPattern(domain)
)

data class ParsedDnsQuestion(
    val domain: String,
    val qType: Int,
    val qClass: Int,
    val questionEndOffset: Int
)

/**
 * Thread-safe in-memory domain pattern blocklist.
 * - Exact / subdomain suffix rules ("example.com") and subdomain wildcards ("*.example.com")
 *   are evaluated in O(labels) time via HashMap lookups.
 * - Keyword patterns ("*casino*") and general wildcards ("ads.*.example.com") are precompiled
 *   into an immutable volatile snapshot so VPN packet-loop reads remain completely lock-free.
 */
class InMemoryDomainBlocklist(initialPatterns: Set<String> = emptySet()) {

    private data class CompiledWildcard(
        val pattern: String,
        val regex: Regex
    )

    private data class CompiledPatternIndex(
        val orderedPatterns: List<String> = emptyList(),
        val exactSuffixMap: Map<String, String> = emptyMap(),
        val subdomainWildcardMap: Map<String, String> = emptyMap(),
        val keywordRules: List<Pair<String, String>> = emptyList(),
        val wildcardRules: List<CompiledWildcard> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = orderedPatterns.isEmpty()
    }

    private val mutationLock = Any()

    @Volatile
    private var index: CompiledPatternIndex = compileIndex(initialPatterns)

    fun replaceAll(newPatterns: Set<String>) {
        synchronized(mutationLock) {
            index = compileIndex(newPatterns)
        }
    }

    fun addPattern(rawPattern: String): String? {
        val normalized = normalizeAndValidatePattern(rawPattern) ?: return null
        synchronized(mutationLock) {
            val updated = index.orderedPatterns.toMutableSet()
            updated.add(normalized)
            index = compileIndex(updated)
        }
        return normalized
    }

    fun removePattern(rawPattern: String): Boolean {
        val target = normalizeAndValidatePattern(rawPattern)
            ?: rawPattern.trim().lowercase().trimEnd('.')
        synchronized(mutationLock) {
            if (!index.orderedPatterns.contains(target)) return false
            val updated = index.orderedPatterns.filterNot { it == target }.toSet()
            index = compileIndex(updated)
            return true
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            index = CompiledPatternIndex()
        }
    }

    fun snapshotPatterns(): List<String> = index.orderedPatterns

    fun size(): Int = index.orderedPatterns.size

    fun isBlocked(rawDomain: String): Boolean = match(rawDomain).blocked

    fun match(rawDomain: String): DomainPatternMatch {
        val snapshot = index
        if (snapshot.isEmpty) return DomainPatternMatch(blocked = false)

        val cleanDomain = normalizeQueryDomain(rawDomain)
        if (cleanDomain.isEmpty()) return DomainPatternMatch(blocked = false)

        // 1. O(labels) check for exact/suffix domains and *.domain subdomain wildcards
        var candidate = cleanDomain
        while (candidate.isNotEmpty()) {
            snapshot.exactSuffixMap[candidate]?.let { matched ->
                return DomainPatternMatch(
                    blocked = true,
                    matchedPattern = matched,
                    patternType = DomainPatternType.DOMAIN_SUFFIX
                )
            }
            snapshot.subdomainWildcardMap[candidate]?.let { matched ->
                return DomainPatternMatch(
                    blocked = true,
                    matchedPattern = matched,
                    patternType = DomainPatternType.SUBDOMAIN_WILDCARD
                )
            }
            val nextDot = candidate.indexOf('.')
            if (nextDot < 0) break
            candidate = candidate.substring(nextDot + 1)
        }

        // 2. Fast keyword substring rules (*keyword*)
        for ((keyword, originalPattern) in snapshot.keywordRules) {
            if (cleanDomain.contains(keyword)) {
                return DomainPatternMatch(
                    blocked = true,
                    matchedPattern = originalPattern,
                    patternType = DomainPatternType.KEYWORD
                )
            }
        }

        // 3. Precompiled general glob wildcard rules (e.g., ads.*.example.com)
        for (rule in snapshot.wildcardRules) {
            if (rule.regex.matches(cleanDomain)) {
                return DomainPatternMatch(
                    blocked = true,
                    matchedPattern = rule.pattern,
                    patternType = DomainPatternType.WILDCARD
                )
            }
        }

        return DomainPatternMatch(blocked = false)
    }

    private fun compileIndex(rawPatterns: Set<String>): CompiledPatternIndex {
        if (rawPatterns.isEmpty()) return CompiledPatternIndex()

        val normalizedList = rawPatterns.asSequence()
            .mapNotNull { normalizeAndValidatePattern(it) }
            .distinct()
            .sorted()
            .toList()

        val exactSuffix = HashMap<String, String>()
        val subdomainWildcard = HashMap<String, String>()
        val keywords = mutableListOf<Pair<String, String>>()
        val wildcards = mutableListOf<CompiledWildcard>()

        for (pattern in normalizedList) {
            when (classifyPattern(pattern)) {
                DomainPatternType.DOMAIN_SUFFIX -> {
                    exactSuffix[pattern] = pattern
                }
                DomainPatternType.SUBDOMAIN_WILDCARD -> {
                    val baseDomain = pattern.removePrefix("*.")
                    subdomainWildcard[baseDomain] = pattern
                }
                DomainPatternType.KEYWORD -> {
                    val keyword = pattern.trim('*')
                    keywords.add(keyword to pattern)
                }
                DomainPatternType.WILDCARD -> {
                    wildcards.add(CompiledWildcard(pattern, globToRegex(pattern)))
                }
            }
        }

        return CompiledPatternIndex(
            orderedPatterns = normalizedList,
            exactSuffixMap = exactSuffix,
            subdomainWildcardMap = subdomainWildcard,
            keywordRules = keywords,
            wildcardRules = wildcards
        )
    }

    companion object {
        const val MAX_PATTERN_LENGTH = 120
        private val ALLOWED_PATTERN_CHARS = Regex("^[a-z0-9._*-]+$")
        private val MULTI_STAR_REGEX = Regex("\\*{2,}")

        fun normalizeQueryDomain(rawDomain: String): String =
            rawDomain.trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")
                .substringBefore(":")
                .trimEnd('.')

        /**
         * Normalizes and validates a user-supplied domain or wildcard pattern.
         * Supported formats:
         * - Domain + subdomains: "tiktok.com", "ads.example.org"
         * - Subdomain wildcard: "*.doubleclick.net"
         * - Keyword wildcard: "*casino*"
         * - General wildcard: "ads.*.example.com", "track-*.net"
         *
         * Rejects overly broad catch-alls like "*", "*.*", "*.com", or malformed strings.
         */
        fun normalizeAndValidatePattern(raw: String): String? {
            var cleaned = raw.trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")
                .substringBefore(":")
                .trimEnd('.')

            cleaned = MULTI_STAR_REGEX.replace(cleaned, "*")

            if (cleaned.length !in 3..MAX_PATTERN_LENGTH) return null
            if (!ALLOWED_PATTERN_CHARS.matches(cleaned)) return null
            if (cleaned.contains("..") || cleaned.startsWith('.')) return null

            val nonWildcardCore = cleaned.replace("*", "").replace(".", "")
            if (nonWildcardCore.length < 3) return null

            if (!cleaned.contains('*')) {
                // Exact/suffix domain must contain at least one dot and valid labels
                if (!cleaned.contains('.')) return null
                val labels = cleaned.split('.')
                if (labels.size < 2 || labels.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) {
                    return null
                }
                return cleaned
            }

            // Keyword wildcard: *keyword* (no dots, no internal stars)
            if (cleaned.startsWith('*') && cleaned.endsWith('*') && !cleaned.contains('.')) {
                val inner = cleaned.removePrefix("*").removeSuffix("*")
                if (inner.length < 3 || inner.contains('*')) return null
                return cleaned
            }

            // Subdomain wildcard: *.example.com (must block a registrable domain, not a bare TLD like *.com)
            if (cleaned.startsWith("*.")) {
                val remainder = cleaned.removePrefix("*.")
                if (!remainder.contains('.')) return null
                val labels = remainder.split('.')
                if (labels.size < 2 || labels.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) {
                    return null
                }
                return cleaned
            }

            // General wildcard pattern (e.g., ads.*.com, tracker-*.example.net)
            if (!cleaned.contains('.')) return null
            val labels = cleaned.split('.')
            if (labels.size < 2 || labels.any { it.isEmpty() }) return null
            return cleaned
        }

        fun classifyPattern(pattern: String): DomainPatternType {
            if (!pattern.contains('*')) return DomainPatternType.DOMAIN_SUFFIX
            if (pattern.startsWith('*') && pattern.endsWith('*') && !pattern.contains('.')) {
                val inner = pattern.removePrefix("*").removeSuffix("*")
                if (!inner.contains('*') && inner.isNotEmpty()) {
                    return DomainPatternType.KEYWORD
                }
            }
            if (pattern.startsWith("*.") && !pattern.removePrefix("*.").contains('*')) {
                return DomainPatternType.SUBDOMAIN_WILDCARD
            }
            return DomainPatternType.WILDCARD
        }

        private fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> sb.append(".*")
                    '.' -> sb.append("\\.")
                    '-' -> sb.append("\\-")
                    else -> sb.append(Regex.escape(ch.toString()))
                }
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}

/**
 * Pure-Kotlin On-Device DNS Filter Engine:
 * - O(labels) in-memory blocklists for Adult, Gambling, Malware/Scam, Social, and Custom pattern rules
 * - Fast lexical heuristic check for unlisted domains
 * - Production RFC 1035 DNS packet parser & 0.0.0.0 (A) / :: (AAAA) sinkhole response builder
 */
object LocalDnsFilterEngine {

    const val QTYPE_A = 1
    const val QTYPE_AAAA = 28
    private const val QCLASS_IN = 1
    private const val SINKHOLE_TTL_SECONDS = 60

    private val adultBlocklist = InMemoryDomainBlocklist(
        setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "onlyfans.com", "chaturbate.com", "redtube.com", "youporn.com"
        )
    )

    private val gamblingBlocklist = InMemoryDomainBlocklist(
        setOf(
            "bet365.com", "1xbet.com", "stake.com", "draftkings.com",
            "fanduel.com", "pokerstars.com", "kubet.com", "fun88.com",
            "w88.com"
        )
    )

    private val malwareScamBlocklist = InMemoryDomainBlocklist(
        setOf(
            "coinhive.com", "coin-hive.com", "authedmine.com",
            "crypto-loot.com", "jsecoin.com", "minero.cc"
        )
    )

    private val socialBlocklist = InMemoryDomainBlocklist(
        setOf(
            "tiktok.com", "tiktokv.com", "musical.ly", "instagram.com",
            "facebook.com", "fbcdn.net", "snapchat.com", "x.com", "twitter.com",
            "reddit.com", "discord.com"
        )
    )

    private val safeSearchDomains = setOf(
        "google.com", "www.google.com", "bing.com", "www.bing.com",
        "youtube.com", "www.youtube.com", "m.youtube.com"
    )

    private val adultTokens = listOf("porn", "xxx", "nsfw", "hentai", "camgirl", "escort", "sexvideo")
    private val gamblingTokens = listOf("casino", "betting", "taixiu", "cado", "nohu", "nhacai", "slotgame", "jackpot")
    private val scamTokens = listOf("freerobux", "phish", "hacknick", "claimprize", "verifyotp")

    /**
     * Parses single or multi-domain user input (comma, semicolon, whitespace, or newline separated)
     * into distinct normalized, valid local DNS blocklist patterns.
     */
    fun parseBatchDomainInputs(rawInput: String): List<String> {
        if (rawInput.isBlank()) return emptyList()
        return rawInput.split(Regex("[,;\\s]+"))
            .asSequence()
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) null else InMemoryDomainBlocklist.normalizeAndValidatePattern(trimmed)
            }
            .distinct()
            .toList()
    }

    /**
     * Returns the complete, unified list of websites currently blocked by the local DNS filtering
     * engine based on active custom rules, category time-limit auto-blocks, and built-in shields.
     */
    fun getActiveBlockedWebsites(
        rules: LocalFilterSnapshot,
        customBlocklist: InMemoryDomainBlocklist? = null,
        categoryLimitBlocklist: InMemoryDomainBlocklist? = null
    ): List<BlockedWebsiteEntry> {
        val result = mutableListOf<BlockedWebsiteEntry>()
        val seenKeys = hashSetOf<String>()

        fun addEntries(domains: Collection<String>, source: BlockedWebsiteSource, removable: Boolean) {
            domains.sorted().forEach { raw ->
                val normalized = InMemoryDomainBlocklist.normalizeAndValidatePattern(raw) ?: return@forEach
                val dedupeKey = "$source|$normalized"
                if (seenKeys.add(dedupeKey)) {
                    result += BlockedWebsiteEntry(
                        domain = normalized,
                        source = source,
                        removable = removable,
                        patternType = InMemoryDomainBlocklist.classifyPattern(normalized)
                    )
                }
            }
        }

        val customDomains = customBlocklist?.snapshotPatterns()?.ifEmpty { null }
            ?: rules.customBlockedDomains
        addEntries(customDomains, BlockedWebsiteSource.CUSTOM, removable = true)

        val limitDomains = categoryLimitBlocklist?.snapshotPatterns().orEmpty()
        if (limitDomains.isNotEmpty()) {
            addEntries(limitDomains, BlockedWebsiteSource.CATEGORY_LIMIT, removable = false)
        }

        if (rules.blockAdult) {
            addEntries(adultBlocklist.snapshotPatterns(), BlockedWebsiteSource.ADULT_SHIELD, removable = false)
        }
        if (rules.blockGambling) {
            addEntries(gamblingBlocklist.snapshotPatterns(), BlockedWebsiteSource.GAMBLING_SHIELD, removable = false)
        }
        if (rules.blockMalware) {
            addEntries(malwareScamBlocklist.snapshotPatterns(), BlockedWebsiteSource.MALWARE_SHIELD, removable = false)
        }
        if (rules.blockSocial) {
            addEntries(socialBlocklist.snapshotPatterns(), BlockedWebsiteSource.SOCIAL_SHIELD, removable = false)
        }

        return result
    }

    fun builtInShieldCounts(): Map<BlockedWebsiteSource, Int> = mapOf(
        BlockedWebsiteSource.ADULT_SHIELD to adultBlocklist.size(),
        BlockedWebsiteSource.GAMBLING_SHIELD to gamblingBlocklist.size(),
        BlockedWebsiteSource.MALWARE_SHIELD to malwareScamBlocklist.size(),
        BlockedWebsiteSource.SOCIAL_SHIELD to socialBlocklist.size()
    )

    fun evaluateDomain(
        rawDomain: String,
        rules: LocalFilterSnapshot,
        customBlocklist: InMemoryDomainBlocklist? = null,
        categoryLimitBlocklist: InMemoryDomainBlocklist? = null
    ): DnsEvaluation {
        val startNs = System.nanoTime()
        val domain = InMemoryDomainBlocklist.normalizeQueryDomain(rawDomain)

        if (domain.isEmpty()) {
            return DnsEvaluation("", DnsVerdict.ALLOWED, "Empty query", 0.05)
        }

        // 1. Custom parent-blocked domain patterns (exact/suffix, *.subdomains, wildcards, *keywords*)
        val customMatch = customBlocklist?.match(domain)
            ?: InMemoryDomainBlocklist(rules.customBlockedDomains).match(domain)
        if (customMatch.blocked) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.BLOCKED_CUSTOM,
                reason = "Sinkholed to 0.0.0.0 by pattern ${customMatch.matchedPattern ?: domain}",
                latencyMs = elapsedMs(startNs),
                matchedPattern = customMatch.matchedPattern
            )
        }

        // 1b. Hard Daily Category Time Limit Auto-Enforcement (triggered from UsageStats)
        val categoryMatch = categoryLimitBlocklist?.match(domain)
        if (categoryMatch != null && categoryMatch.blocked) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.BLOCKED_CATEGORY_LIMIT,
                reason = "Sinkholed to 0.0.0.0 — daily category time limit reached (${categoryMatch.matchedPattern ?: domain})",
                latencyMs = elapsedMs(startNs),
                matchedPattern = categoryMatch.matchedPattern
            )
        }

        // 2. Category O(labels) In-Memory Blocklists + Fast Lexical Check
        if (rules.blockAdult && (adultBlocklist.isBlocked(domain) || adultTokens.any { domain.contains(it) })) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.BLOCKED_ADULT,
                reason = "Sinkholed to 0.0.0.0 by Adult Content DNS filter",
                latencyMs = elapsedMs(startNs)
            )
        }

        if (rules.blockGambling && (gamblingBlocklist.isBlocked(domain) || gamblingTokens.any { domain.contains(it) })) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.BLOCKED_GAMBLING,
                reason = "Sinkholed to 0.0.0.0 by Gambling & Betting DNS filter",
                latencyMs = elapsedMs(startNs)
            )
        }

        if (rules.blockMalware && (malwareScamBlocklist.isBlocked(domain) || scamTokens.any { domain.contains(it) })) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.BLOCKED_MALWARE,
                reason = "Sinkholed to 0.0.0.0 by Scam & Malware DNS filter",
                latencyMs = elapsedMs(startNs)
            )
        }

        if (rules.blockSocial && socialBlocklist.isBlocked(domain)) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.BLOCKED_SOCIAL,
                reason = "Sinkholed to 0.0.0.0 by Social & Short-Video DNS filter",
                latencyMs = elapsedMs(startNs)
            )
        }

        // 3. SafeSearch Enforcement Check
        if (rules.safeSearchEnabled && safeSearchDomains.contains(domain)) {
            return DnsEvaluation(
                domain = domain,
                verdict = DnsVerdict.SAFESEARCH_ENFORCED,
                reason = "Allowed with SafeSearch / Restricted Mode policy",
                latencyMs = elapsedMs(startNs)
            )
        }

        return DnsEvaluation(
            domain = domain,
            verdict = DnsVerdict.ALLOWED,
            reason = "Clean domain — forwarded to Cloudflare Family DNS (1.1.1.3)",
            latencyMs = elapsedMs(startNs)
        )
    }

    /**
     * Parses the first DNS Question (QNAME, QTYPE, QCLASS) from a raw RFC 1035 DNS packet.
     */
    fun parseDnsQuestion(dnsPayload: ByteArray): ParsedDnsQuestion? {
        if (dnsPayload.size < 17) return null
        val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < dnsPayload.size) {
            val len = dnsPayload[pos].toInt() and 0xFF
            if (len == 0) {
                pos++ // consume root zero terminator
                break
            }
            if ((len and 0xC0) != 0) return null // Invalid compression pointer in initial query QNAME
            pos++
            if (pos + len > dnsPayload.size) return null
            labels.add(String(dnsPayload, pos, len, Charsets.US_ASCII))
            pos += len
        }

        if (labels.isEmpty() || pos + 4 > dnsPayload.size) return null
        val qType = ((dnsPayload[pos].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 1].toInt() and 0xFF)
        val qClass = ((dnsPayload[pos + 2].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 3].toInt() and 0xFF)
        return ParsedDnsQuestion(
            domain = labels.joinToString(".").lowercase(),
            qType = qType,
            qClass = qClass,
            questionEndOffset = pos + 4
        )
    }

    /**
     * Builds a standards-compliant RFC 1035 sinkhole DNS response:
     * - QTYPE A (1)    -> NOERROR with 1 Answer pointing to 0.0.0.0 (TTL 60s)
     * - QTYPE AAAA (28)-> NOERROR with 1 Answer pointing to :: (16 zero bytes, TTL 60s)
     * - Other QTYPEs   -> NOERROR with 0 Answers (NODATA) so apps fail fast without DoH fallback
     */
    fun buildSinkholeResponse(queryPayload: ByteArray, question: ParsedDnsQuestion): ByteArray {
        val out = ByteArrayOutputStream(question.questionEndOffset + 32)
        val hasSinkholeAnswer = question.qClass == QCLASS_IN &&
            (question.qType == QTYPE_A || question.qType == QTYPE_AAAA)

        // 1. Copy 12-byte DNS header with response flags
        val header = ByteArray(12)
        // Transaction ID (bytes 0..1)
        header[0] = queryPayload[0]
        header[1] = queryPayload[1]
        // Flags: QR=1, preserve Opcode & RD (byte 2); RA=1, RCODE=0 NOERROR (byte 3)
        header[2] = ((queryPayload[2].toInt() and 0x79) or 0x80).toByte()
        header[3] = 0x80.toByte()
        // QDCOUNT = 1
        header[4] = 0x00; header[5] = 0x01
        // ANCOUNT = 1 for A/AAAA, else 0 (NODATA)
        header[6] = 0x00; header[7] = if (hasSinkholeAnswer) 0x01 else 0x00
        // NSCOUNT = 0, ARCOUNT = 0 (strips EDNS OPT cleanly so packet remains valid)
        header[8] = 0x00; header[9] = 0x00
        header[10] = 0x00; header[11] = 0x00
        out.write(header)

        // 2. Copy exact original Question section (bytes 12 until questionEndOffset)
        out.write(queryPayload, 12, question.questionEndOffset - 12)

        // 3. Append Answer RR for A (0.0.0.0) or AAAA (::)
        if (hasSinkholeAnswer) {
            // NAME: compression pointer 0xC00C -> offset 12 (start of QNAME)
            out.write(0xC0)
            out.write(0x0C)
            // TYPE (2 bytes)
            out.write((question.qType ushr 8) and 0xFF)
            out.write(question.qType and 0xFF)
            // CLASS IN (2 bytes)
            out.write(0x00)
            out.write(0x01)
            // TTL (4 bytes, 60 seconds)
            out.write((SINKHOLE_TTL_SECONDS ushr 24) and 0xFF)
            out.write((SINKHOLE_TTL_SECONDS ushr 16) and 0xFF)
            out.write((SINKHOLE_TTL_SECONDS ushr 8) and 0xFF)
            out.write(SINKHOLE_TTL_SECONDS and 0xFF)

            if (question.qType == QTYPE_A) {
                // RDLENGTH = 4, RDATA = 0.0.0.0
                out.write(0x00)
                out.write(0x04)
                out.write(byteArrayOf(0, 0, 0, 0))
            } else {
                // RDLENGTH = 16, RDATA = :: (IPv6 unspecified)
                out.write(0x00)
                out.write(0x10)
                out.write(ByteArray(16))
            }
        }

        return out.toByteArray()
    }

    private fun elapsedMs(startNs: Long): Double =
        ((System.nanoTime() - startNs) / 1_000_000.0).coerceAtLeast(0.05)
}
