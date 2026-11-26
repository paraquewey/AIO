package com.example.vpn.tun

import java.nio.ByteBuffer

object IpPacketParser {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class Ipv4Packet(
        val ihlBytes: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcIp: String,
        val dstIp: String
    )

    data class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val ackNum: Long,
        val headerLengthBytes: Int,
        val flags: Int,
        val windowSize: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class UdpDatagram(
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    fun parseIpv4(buffer: ByteArray, length: Int): Ipv4Packet? {
        if (length < 20) return null
        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return null
        val ihlBytes = (versionAndIhl and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes) return null

        val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val effectiveLen = minOf(length, totalLength)
        if (effectiveLen < ihlBytes) return null

        val protocol = buffer[9].toInt() and 0xFF
        val srcIpBytes = buffer.copyOfRange(12, 16)
        val dstIpBytes = buffer.copyOfRange(16, 20)

        return Ipv4Packet(
            ihlBytes = ihlBytes,
            totalLength = effectiveLen,
            protocol = protocol,
            srcIpBytes = srcIpBytes,
            dstIpBytes = dstIpBytes,
            srcIp = ipBytesToString(srcIpBytes),
            dstIp = ipBytesToString(dstIpBytes)
        )
    }

    fun parseTcp(buffer: ByteArray, ip: Ipv4Packet): TcpSegment? {
        val offset = ip.ihlBytes
        if (ip.totalLength < offset + 20) return null
        val srcPort = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val seqNum = readUInt32(buffer, offset + 4)
        val ackNum = readUInt32(buffer, offset + 8)
        val dataOffsetBytes = ((buffer[offset + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffsetBytes < 20 || ip.totalLength < offset + dataOffsetBytes) return null
        val flags = buffer[offset + 13].toInt() and 0x3F
        val window = ((buffer[offset + 14].toInt() and 0xFF) shl 8) or (buffer[offset + 15].toInt() and 0xFF)
        val payloadOffset = offset + dataOffsetBytes
        val payloadLen = (ip.totalLength - payloadOffset).coerceAtLeast(0)
        return TcpSegment(
            srcPort = srcPort,
            dstPort = dstPort,
            seqNum = seqNum,
            ackNum = ackNum,
            headerLengthBytes = dataOffsetBytes,
            flags = flags,
            windowSize = window,
            payloadOffset = payloadOffset,
            payloadLength = payloadLen
        )
    }

    fun parseUdp(buffer: ByteArray, ip: Ipv4Packet): UdpDatagram? {
        val offset = ip.ihlBytes
        if (ip.totalLength < offset + 8) return null
        val srcPort = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val udpLen = ((buffer[offset + 4].toInt() and 0xFF) shl 8) or (buffer[offset + 5].toInt() and 0xFF)
        val payloadOffset = offset + 8
        val payloadLen = (minOf(ip.totalLength, offset + udpLen) - payloadOffset).coerceAtLeast(0)
        return UdpDatagram(srcPort, dstPort, payloadOffset, payloadLen)
    }

    fun buildIpv4TcpPacket(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray = ByteArray(0),
        payloadLength: Int = payload.size
    ): ByteArray {
        val totalLen = 20 + 20 + payloadLength
        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // IPv4 Header (20 bytes)
        buf.put(0x45.toByte()) // Version 4, IHL 5
        buf.put(0x00)
        buf.putShort(totalLen.toShort())
        buf.putShort(0) // Identification
        buf.putShort(0x4000.toShort()) // Don't Fragment
        buf.put(64.toByte()) // TTL
        buf.put(PROTO_TCP.toByte())
        buf.putShort(0) // Checksum placeholder
        buf.put(srcIpBytes, 0, 4)
        buf.put(dstIpBytes, 0, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = (ipChecksum ushr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // TCP Header (20 bytes)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt((seqNum and 0xFFFFFFFFL).toInt())
        buf.putInt((ackNum and 0xFFFFFFFFL).toInt())
        buf.put((5 shl 4).toByte()) // Data offset 5 * 4 = 20 bytes
        buf.put((flags and 0x3F).toByte())
        buf.putShort(windowSize.coerceIn(0, 65535).toShort())
        buf.putShort(0) // Checksum placeholder
        buf.putShort(0) // Urgent pointer
        if (payloadLength > 0) {
            buf.put(payload, 0, payloadLength)
        }

        val tcpChecksum = computeTransportChecksum(
            srcIpBytes, dstIpBytes, PROTO_TCP, packet, 20, 20 + payloadLength
        )
        packet[36] = (tcpChecksum ushr 8).toByte()
        packet[37] = (tcpChecksum and 0xFF).toByte()

        return packet
    }

    fun buildIpv4UdpPacket(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLength: Int = payload.size
    ): ByteArray {
        val udpLen = 8 + payloadLength
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // IPv4 Header
        buf.put(0x45.toByte())
        buf.put(0x00)
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(PROTO_UDP.toByte())
        buf.putShort(0)
        buf.put(srcIpBytes, 0, 4)
        buf.put(dstIpBytes, 0, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = (ipChecksum ushr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        if (payloadLength > 0) {
            buf.put(payload, 0, payloadLength)
        }

        val udpChecksum = computeTransportChecksum(
            srcIpBytes, dstIpBytes, PROTO_UDP, packet, 20, udpLen
        )
        packet[26] = (udpChecksum ushr 8).toByte()
        packet[27] = (udpChecksum and 0xFF).toByte()
        return packet
    }

    fun ipBytesToString(bytes: ByteArray): String =
        "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"

    fun ipStringToBytes(ip: String): ByteArray {
        val parts = ip.split('.')
        return ByteArray(4) { i -> (parts.getOrNull(i)?.toIntOrNull() ?: 0).toByte() }
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)

    fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun computeTransportChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        packet: ByteArray,
        transportOffset: Int,
        transportLength: Int
    ): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol.toLong()
        sum += transportLength.toLong()

        var i = transportOffset
        val end = transportOffset + transportLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
