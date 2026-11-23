package com.example.engine

/**
 * Parsed view of an IPv4/UDP packet read from Android's VpnService TUN interface.
 */
data class Ipv4UdpPacket(
    val rawHeader: ByteArray,
    val ipHeaderLength: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
)

/**
 * Pure-Kotlin IPv4 (RFC 791) and UDP (RFC 768) packet parser and reply builder
 * designed for Android local DNS VpnService proxies (UDP port 53).
 */
object Ipv4UdpPacketCodec {

    private const val IP_VERSION_4 = 4
    private const val PROTOCOL_UDP = 17
    private const val MIN_IPV4_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8
    const val DNS_PORT = 53

    /**
     * Parses a raw TUN buffer into an [Ipv4UdpPacket] if it is a valid IPv4 UDP packet
     * targeting [expectedDestPort] (defaults to DNS port 53). Returns null otherwise.
     */
    fun parseUdpPacket(
        buffer: ByteArray,
        length: Int,
        expectedDestPort: Int = DNS_PORT
    ): Ipv4UdpPacket? {
        if (length < MIN_IPV4_HEADER_LEN + UDP_HEADER_LEN) return null

        val version = (buffer[0].toInt() ushr 4) and 0x0F
        if (version != IP_VERSION_4) return null

        val ihl = (buffer[0].toInt() and 0x0F) * 4
        if (ihl < MIN_IPV4_HEADER_LEN || length < ihl + UDP_HEADER_LEN) return null

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
        if (dstPort != expectedDestPort) return null

        val udpDeclaredLength = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)
        val payloadOffset = ihl + UDP_HEADER_LEN
        val availablePayloadLen = length - payloadOffset
        val payloadLen = minOf(availablePayloadLen, (udpDeclaredLength - UDP_HEADER_LEN).coerceAtLeast(0))
        if (payloadLen <= 0) return null

        return Ipv4UdpPacket(
            rawHeader = buffer.copyOfRange(0, payloadOffset),
            ipHeaderLength = ihl,
            sourcePort = srcPort,
            destinationPort = dstPort,
            payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)
        )
    }

    /**
     * Constructs a valid IPv4/UDP response packet by swapping source/destination IPs and ports
     * from [request] and computing both RFC 791 IPv4 and RFC 768 UDP pseudo-header checksums.
     */
    fun buildUdpReply(request: Ipv4UdpPacket, responsePayload: ByteArray): ByteArray {
        val totalLen = MIN_IPV4_HEADER_LEN + UDP_HEADER_LEN + responsePayload.size
        val out = ByteArray(totalLen)

        // IPv4 Header (20 bytes)
        out[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        out[1] = 0x00 // DSCP / ECN
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = 0x00; out[5] = 0x00 // Identification
        out[6] = 0x40.toByte(); out[7] = 0x00 // Flags: Don't Fragment
        out[8] = 64.toByte() // TTL
        out[9] = PROTOCOL_UDP.toByte()
        // Swap source IP (12..15) and destination IP (16..19)
        System.arraycopy(request.rawHeader, 16, out, 12, 4)
        System.arraycopy(request.rawHeader, 12, out, 16, 4)

        val ipChecksum = computeIpHeaderChecksum(out, 0, MIN_IPV4_HEADER_LEN)
        out[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header (8 bytes): swap source and destination ports
        val reqUdpOffset = request.ipHeaderLength
        out[20] = request.rawHeader[reqUdpOffset + 2]
        out[21] = request.rawHeader[reqUdpOffset + 3]
        out[22] = request.rawHeader[reqUdpOffset]
        out[23] = request.rawHeader[reqUdpOffset + 1]

        val udpLen = UDP_HEADER_LEN + responsePayload.size
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0x00; out[27] = 0x00

        System.arraycopy(responsePayload, 0, out, MIN_IPV4_HEADER_LEN + UDP_HEADER_LEN, responsePayload.size)

        val udpChecksum = computeUdpChecksum(out, MIN_IPV4_HEADER_LEN, udpLen)
        out[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
        out[27] = (udpChecksum and 0xFF).toByte()

        return out
    }

    internal fun computeIpHeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            if (i == offset + 10) {
                i += 2
                continue
            }
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    internal fun computeUdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0
        // IPv4 pseudo-header: src IP (12..15) + dst IP (16..19)
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += PROTOCOL_UDP
        sum += udpLen

        // UDP header + payload
        var i = udpOffset
        val end = udpOffset + udpLen
        while (i + 1 < end) {
            if (i != udpOffset + 6) {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }
}
