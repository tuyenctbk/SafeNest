package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.analytics.AppUsageCategory
import com.example.data.LocalFilterSnapshot
import com.example.data.LocalPinStore
import com.example.engine.CategoryTimeLimitEngine
import com.example.engine.DnsVerdict
import com.example.engine.DomainPatternMatch
import com.example.engine.InMemoryDomainBlocklist
import com.example.engine.Ipv4UdpPacket
import com.example.engine.Ipv4UdpPacketCodec
import com.example.engine.LocalDnsFilterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

data class DnsLogEntry(
    val domain: String,
    val verdict: DnsVerdict,
    val timestampMs: Long = System.currentTimeMillis(),
    val matchedPattern: String? = null,
    val id: Long = nextLogId.incrementAndGet()
) {
    private companion object {
        val nextLogId = AtomicLong(0L)
    }
}

data class VpnRuntimeState(
    val running: Boolean = false,
    val queriesInspected: Int = 0,
    val queriesBlocked: Int = 0,
    val recentLogs: List<DnsLogEntry> = emptyList(),
    val activeInMemoryPatterns: List<String> = emptyList(),
    val lastBlocklistSyncMs: Long = 0L,
    val enforcedCategories: Set<AppUsageCategory> = emptySet(),
    val categoryBlockedPatterns: List<String> = emptyList(),
    val lastCategoryCheckMs: Long = 0L
)

/**
 * Local DNS Proxy VpnService in pure Kotlin:
 * - Intercepts ONLY UDP port 53 traffic routed to virtual DNS 10.0.0.1/32 (zero bandwidth overhead).
 * - Checks queried domains against an O(labels) thread-safe in-memory blocklist.
 * - Replies locally with 0.0.0.0 (A) / :: (AAAA) sinkhole responses for blocked domains.
 * - Forwards allowed queries concurrently over protected UDP sockets with automatic upstream failover.
 */
class SafeNestDnsVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val upstreamConcurrency = Semaphore(permits = 32)
    private val tunWriteLock = Any()

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    private var packetLoopJob: Job? = null

    private var categoryMonitorJob: Job? = null

    @Volatile
    private var activeRules: LocalFilterSnapshot = LocalFilterSnapshot()

    override fun onCreate() {
        super.onCreate()
        val store = LocalPinStore(applicationContext)
        val usageChecker = DeviceProtectionChecker(applicationContext)
        serviceScope.launch {
            store.filterSnapshotFlow.collect { snapshot ->
                activeRules = snapshot
                updateInMemoryBlocklist(snapshot.customBlockedDomains)
                evaluateAndApplyCategoryLimits(usageChecker, snapshot)
            }
        }
        categoryMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(CATEGORY_LIMIT_POLL_MS)
                evaluateAndApplyCategoryLimits(usageChecker, activeRules)
            }
        }
    }

    private fun evaluateAndApplyCategoryLimits(
        usageChecker: DeviceProtectionChecker,
        rules: LocalFilterSnapshot
    ) {
        val hasEnabledLimits = rules.categoryLimitRules.values.any { it.enabled }
        if (!hasEnabledLimits) {
            updateCategoryLimitEnforcement(emptySet(), emptySet())
            return
        }
        val todayUsage = runCatching { usageChecker.readTodayCategoryUsage() }.getOrNull()
        val evaluation = CategoryTimeLimitEngine.evaluate(
            rules = rules.categoryLimitRules,
            todayUsage = todayUsage
        )
        updateCategoryLimitEnforcement(
            exceededCategories = evaluation.exceededCategories,
            patterns = evaluation.autoBlockedPatterns
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return
        try {
            startForegroundCompat()
            val builder = Builder()
                .setSession("SafeNest Local DNS Proxy")
                .addAddress(VPN_CLIENT_IP, 32)
                .addDnsServer(VIRTUAL_DNS_IP)
                // Route ONLY virtual DNS 10.0.0.1/32 so non-DNS traffic bypasses TUN at full speed
                .addRoute(VIRTUAL_DNS_IP, 32)
                .setMtu(1500)
                .apply {
                    runCatching { addDisallowedApplication(packageName) }
                }

            val established = builder.establish()
            if (established == null) {
                Log.w(TAG, "VpnService.Builder.establish() returned null — VPN consent not granted.")
                stopVpn()
                return
            }

            vpnInterface = established
            _runtimeState.update { it.copy(running = true) }
            packetLoopJob = serviceScope.launch {
                runLocalDnsProxyLoop(established)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Local DNS Proxy VPN", e)
            stopVpn()
        }
    }

    private fun runLocalDnsProxyLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packetBuffer = ByteArray(32767)
        val upstreamServers = UPSTREAM_DNS_SERVERS.map { InetAddress.getByName(it) }

        while (serviceScope.isActive && vpnInterface != null) {
            val length = try {
                input.read(packetBuffer)
            } catch (_: Exception) {
                break
            }
            if (length <= 0) continue

            // Filter strictly for IPv4 UDP packets destined for port 53
            val udpPacket = Ipv4UdpPacketCodec.parseUdpPacket(
                buffer = packetBuffer,
                length = length,
                expectedDestPort = Ipv4UdpPacketCodec.DNS_PORT
            ) ?: continue

            val question = LocalDnsFilterEngine.parseDnsQuestion(udpPacket.payload) ?: continue
            val evaluation = LocalDnsFilterEngine.evaluateDomain(
                rawDomain = question.domain,
                rules = activeRules,
                customBlocklist = runtimeBlocklist,
                categoryLimitBlocklist = categoryLimitBlocklist
            )

            val logEntry = DnsLogEntry(
                domain = question.domain,
                verdict = evaluation.verdict,
                matchedPattern = evaluation.matchedPattern
            )
            _runtimeState.update { state ->
                val updatedLogs = (listOf(logEntry) + state.recentLogs).take(25)
                state.copy(
                    queriesInspected = state.queriesInspected + 1,
                    queriesBlocked = state.queriesBlocked + if (evaluation.blocked) 1 else 0,
                    recentLogs = updatedLogs
                )
            }

            if (evaluation.blocked) {
                _blockedEvents.tryEmit(logEntry)
                val sinkholePayload = LocalDnsFilterEngine.buildSinkholeResponse(udpPacket.payload, question)
                writeReplyToTun(output, udpPacket, sinkholePayload)
            } else {
                // Non-blocking concurrent upstream forwarding with failover
                serviceScope.launch {
                    upstreamConcurrency.withPermit {
                        val upstreamResponse = forwardDnsWithFailover(udpPacket.payload, upstreamServers)
                        if (upstreamResponse != null && vpnInterface != null) {
                            writeReplyToTun(output, udpPacket, upstreamResponse)
                        }
                    }
                }
            }
        }
    }

    private fun writeReplyToTun(
        output: FileOutputStream,
        request: Ipv4UdpPacket,
        dnsPayload: ByteArray
    ) {
        val replyPacket = Ipv4UdpPacketCodec.buildUdpReply(request, dnsPayload)
        synchronized(tunWriteLock) {
            try {
                output.write(replyPacket)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing DNS reply to TUN: ${e.message}")
            }
        }
    }

    private fun forwardDnsWithFailover(
        query: ByteArray,
        upstreams: List<InetAddress>
    ): ByteArray? {
        for (upstream in upstreams) {
            val response = try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) {
                        Log.w(TAG, "Failed to protect upstream UDP socket")
                        return null
                    }
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, upstream, Ipv4UdpPacketCodec.DNS_PORT))
                    val respBuf = ByteArray(4096)
                    val respPacket = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(respPacket)
                    respBuf.copyOf(respPacket.length)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upstream DNS ${upstream.hostAddress} failed: ${e.message}")
                null
            }
            if (response != null) return response
        }
        return null
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_safenest_shield)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopVpn() {
        packetLoopJob?.cancel()
        packetLoopJob = null
        val pfd = vpnInterface
        vpnInterface = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        _runtimeState.update { it.copy(running = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "SafeNestDnsVpn"
        private const val CHANNEL_ID = "safenest_dns_vpn"
        private const val NOTIFICATION_ID = 4101
        private const val VPN_CLIENT_IP = "10.0.0.2"
        private const val VIRTUAL_DNS_IP = "10.0.0.1"
        private val UPSTREAM_DNS_SERVERS = listOf("1.1.1.3", "1.0.0.3")
        private const val UPSTREAM_TIMEOUT_MS = 2000
        private const val CATEGORY_LIMIT_POLL_MS = 30_000L

        const val ACTION_START = "com.example.action.START_DNS_VPN"
        const val ACTION_STOP = "com.example.action.STOP_DNS_VPN"

        private val runtimeBlocklist = InMemoryDomainBlocklist()
        private val categoryLimitBlocklist = InMemoryDomainBlocklist()

        private val _runtimeState = MutableStateFlow(VpnRuntimeState())
        val runtimeState: StateFlow<VpnRuntimeState> = _runtimeState.asStateFlow()

        private val _blockedEvents = MutableSharedFlow<DnsLogEntry>(extraBufferCapacity = 32)
        val blockedEvents: SharedFlow<DnsLogEntry> = _blockedEvents.asSharedFlow()

        /**
         * Replaces the in-memory custom domain pattern blocklist atomically at runtime
         * and publishes the updated snapshot to observers immediately.
         */
        fun updateInMemoryBlocklist(domains: Set<String>) {
            runtimeBlocklist.replaceAll(domains)
            publishBlocklistSnapshot()
        }

        /**
         * Atomically updates the automatic category time-limit DNS blocklist enforced by VpnService
         * when UsageStats reports that one or more app categories reached their daily limit.
         */
        fun updateCategoryLimitEnforcement(
            exceededCategories: Set<AppUsageCategory>,
            patterns: Set<String>
        ) {
            categoryLimitBlocklist.replaceAll(patterns)
            val snapshotPatterns = categoryLimitBlocklist.snapshotPatterns()
            _runtimeState.update { state ->
                state.copy(
                    enforcedCategories = exceededCategories,
                    categoryBlockedPatterns = snapshotPatterns,
                    lastCategoryCheckMs = System.currentTimeMillis()
                )
            }
        }

        /**
         * Adds a single domain pattern directly into the live in-memory blocklist.
         */
        fun addInMemoryPattern(rawPattern: String): String? {
            val added = runtimeBlocklist.addPattern(rawPattern) ?: return null
            publishBlocklistSnapshot()
            return added
        }

        /**
         * Removes a single domain pattern directly from the live in-memory blocklist.
         */
        fun removeInMemoryPattern(pattern: String): Boolean {
            val removed = runtimeBlocklist.removePattern(pattern)
            if (removed) {
                publishBlocklistSnapshot()
            }
            return removed
        }

        /**
         * Evaluates a test domain directly against the running VpnService's live in-memory blocklists
         * (custom patterns first, then active category-limit auto-blocks).
         */
        fun testDomainAgainstInMemoryBlocklist(rawDomain: String): DomainPatternMatch {
            val custom = runtimeBlocklist.match(rawDomain)
            if (custom.blocked) return custom
            return categoryLimitBlocklist.match(rawDomain)
        }

        fun getCustomBlocklist(): InMemoryDomainBlocklist = runtimeBlocklist

        fun getCategoryLimitBlocklist(): InMemoryDomainBlocklist = categoryLimitBlocklist

        private fun publishBlocklistSnapshot() {
            val snapshot = runtimeBlocklist.snapshotPatterns()
            _runtimeState.update { state ->
                state.copy(
                    activeInMemoryPatterns = snapshot,
                    lastBlocklistSyncMs = System.currentTimeMillis()
                )
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, SafeNestDnsVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            if (!_runtimeState.value.running) return
            val intent = Intent(context, SafeNestDnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
