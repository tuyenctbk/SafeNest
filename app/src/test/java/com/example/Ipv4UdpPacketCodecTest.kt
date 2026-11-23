package com.example

import com.example.engine.Ipv4UdpPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4UdpPacketCodecTest {

    @Test
    fun parsesValidIpv4UdpPort53PacketAndIgnoresOtherTraffic() {
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val rawDnsPacket = buildRawIpv4UdpPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 53,
            protocol = 17,
            payload = dnsPayload
        )

        val parsed = Ipv4UdpPacketCodec.parseUdpPacket(rawDnsPacket, rawDnsPacket.size)
        assertNotNull(parsed)
        assertEquals(54321, parsed!!.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(dnsPayload, parsed.payload)

        // Non-53 UDP packet should be ignored
        val rawHttpUdp = buildRawIpv4UdpPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 443,
            protocol = 17,
            payload = dnsPayload
        )
        assertNull(Ipv4UdpPacketCodec.parseUdpPacket(rawHttpUdp, rawHttpUdp.size))

        // TCP packet on port 53 should be ignored by UDP filter
        val rawTcp = buildRawIpv4UdpPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 53,
            protocol = 6,
            payload = dnsPayload
        )
        assertNull(Ipv4UdpPacketCodec.parseUdpPacket(rawTcp, rawTcp.size))
    }

    @Test
    fun buildsValidSwappedReplyWithMatchingChecksums() {
        val reqPayload = byteArrayOf(0x11, 0x22, 0x01, 0x00)
        val respPayload = byteArrayOf(0x11, 0x22, 0x81.toByte(), 0x80.toByte(), 0x00, 0x00, 0x00, 0x00)
        val rawReq = buildRawIpv4UdpPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 49152,
            dstPort = 53,
            protocol = 17,
            payload = reqPayload
        )

        val parsed = Ipv4UdpPacketCodec.parseUdpPacket(rawReq, rawReq.size)!!
        val reply = Ipv4UdpPacketCodec.buildUdpReply(parsed, respPayload)

        // Source and destination IPs swapped (10.0.0.1 -> 10.0.0.2)
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), reply.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), reply.copyOfRange(16, 20))

        // Source and destination ports swapped (53 -> 49152)
        val replySrcPort = ((reply[20].toInt() and 0xFF) shl 8) or (reply[21].toInt() and 0xFF)
        val replyDstPort = ((reply[22].toInt() and 0xFF) shl 8) or (reply[23].toInt() and 0xFF)
        assertEquals(53, replySrcPort)
        assertEquals(49152, replyDstPort)

        // Payload matches exactly
        assertArrayEquals(respPayload, reply.copyOfRange(28, reply.size))

        // Stored IPv4 and UDP checksums match recomputed values
        val storedIpChecksum = ((reply[10].toInt() and 0xFF) shl 8) or (reply[11].toInt() and 0xFF)
        assertEquals(Ipv4UdpPacketCodec.computeIpHeaderChecksum(reply, 0, 20), storedIpChecksum)

        val storedUdpChecksum = ((reply[26].toInt() and 0xFF) shl 8) or (reply[27].toInt() and 0xFF)
        assertEquals(Ipv4UdpPacketCodec.computeUdpChecksum(reply, 20, 8 + respPayload.size), storedUdpChecksum)
    }

    private fun buildRawIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        protocol: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val out = ByteArray(totalLen)
        out[0] = 0x45.toByte()
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64.toByte()
        out[9] = protocol.toByte()
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }
}
