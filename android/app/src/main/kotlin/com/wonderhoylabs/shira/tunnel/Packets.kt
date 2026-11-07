package com.wonderhoylabs.shira.tunnel

internal const val IPV4_HEADER_BYTES = 20
internal const val TCP_HEADER_BYTES = 20
internal const val UDP_HEADER_BYTES = 8
internal const val PROTOCOL_ICMP = 1
internal const val PROTOCOL_TCP = 6
internal const val PROTOCOL_UDP = 17

internal const val FLAG_FIN = 0x01
internal const val FLAG_SYN = 0x02
internal const val FLAG_RST = 0x04
internal const val FLAG_PSH = 0x08
internal const val FLAG_ACK = 0x10

private const val OPTION_END = 0
private const val OPTION_NOP = 1
private const val OPTION_MSS = 2
private const val OPTION_WINDOW_SCALE = 3
private const val SYN_OPTION_BYTES = 8
private const val FLAGS_DONT_FRAGMENT = 0x40
private const val DEFAULT_TTL = 64
private const val ICMP_UNREACHABLE = 3
private const val ICMP_PORT_UNREACHABLE = 3
private const val ICMP_HEADER_BYTES = 8
private const val ICMP_QUOTED_PAYLOAD_BYTES = 8

internal fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xff

internal fun u16(bytes: ByteArray, at: Int): Int = (u8(bytes, at) shl 8) or u8(bytes, at + 1)

internal fun i32(bytes: ByteArray, at: Int): Int =
    (u8(bytes, at) shl 24) or (u8(bytes, at + 1) shl 16) or (u8(bytes, at + 2) shl 8) or u8(bytes, at + 3)

internal fun putU16(bytes: ByteArray, at: Int, value: Int) {
    bytes[at] = (value shr 8).toByte()
    bytes[at + 1] = value.toByte()
}

internal fun putI32(bytes: ByteArray, at: Int, value: Int) {
    bytes[at] = (value shr 24).toByte()
    bytes[at + 1] = (value shr 16).toByte()
    bytes[at + 2] = (value shr 8).toByte()
    bytes[at + 3] = value.toByte()
}

internal fun ipv4ToString(address: Int): String =
    "${address ushr 24}.${(address ushr 16) and 0xff}.${(address ushr 8) and 0xff}.${address and 0xff}"

internal fun ipv4(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d

/** Adds [length] bytes to a running Internet checksum, as 16-bit big-endian words. */
private fun sum(start: Long, bytes: ByteArray, offset: Int, length: Int): Long {
    var total = start
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
        total += ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)
        index += 2
    }
    if (index < end) total += (bytes[index].toInt() and 0xff) shl 8
    return total
}

private fun finish(start: Long): Int {
    var total = start
    while (total ushr 16 != 0L) total = (total and 0xffff) + (total ushr 16)
    return total.inv().toInt() and 0xffff
}

/** The fields of an IPv4 packet that the tunnel looks at. One instance is reused, to save allocating. */
internal class Ipv4Header {
    var source = 0
    var destination = 0
    var protocol = 0
    var headerBytes = 0
    var totalBytes = 0

    /** False for anything that isn't a whole, unfragmented IPv4 packet. */
    fun parse(packet: ByteArray, length: Int): Boolean {
        if (length < IPV4_HEADER_BYTES || u8(packet, 0) ushr 4 != 4) return false
        headerBytes = (u8(packet, 0) and 0x0f) * 4
        totalBytes = u16(packet, 2)
        if (headerBytes < IPV4_HEADER_BYTES || totalBytes < headerBytes || totalBytes > length) return false
        // The more-fragments flag and the fragment offset. Only a whole packet can be understood.
        if (u16(packet, 6) and 0x3fff != 0) return false
        protocol = u8(packet, 9)
        source = i32(packet, 12)
        destination = i32(packet, 16)
        return true
    }
}

/** The fields of a TCP segment that the tunnel looks at. */
internal class TcpHeader {
    var sourcePort = 0
    var destinationPort = 0
    var sequence = 0
    var acknowledgment = 0
    var flags = 0
    var window = 0
    var maximumSegment = 0
    var windowScale = -1
    var payloadOffset = 0
    var payloadBytes = 0

    val isSyn get() = flags and FLAG_SYN != 0
    val isAck get() = flags and FLAG_ACK != 0
    val isFin get() = flags and FLAG_FIN != 0
    val isRst get() = flags and FLAG_RST != 0

    fun parse(packet: ByteArray, ip: Ipv4Header): Boolean {
        val start = ip.headerBytes
        if (ip.totalBytes - start < TCP_HEADER_BYTES) return false
        val headerBytes = (u8(packet, start + 12) ushr 4) * 4
        if (headerBytes < TCP_HEADER_BYTES || start + headerBytes > ip.totalBytes) return false
        sourcePort = u16(packet, start)
        destinationPort = u16(packet, start + 2)
        sequence = i32(packet, start + 4)
        acknowledgment = i32(packet, start + 8)
        flags = u8(packet, start + 13)
        window = u16(packet, start + 14)
        payloadOffset = start + headerBytes
        payloadBytes = ip.totalBytes - payloadOffset
        maximumSegment = 0
        windowScale = -1
        if (isSyn) parseOptions(packet, start + TCP_HEADER_BYTES, start + headerBytes)
        return true
    }

    private fun parseOptions(packet: ByteArray, from: Int, until: Int) {
        var at = from
        while (at < until) {
            when (val kind = u8(packet, at)) {
                OPTION_END -> return
                OPTION_NOP -> at++
                else -> {
                    if (at + 1 >= until) return
                    val size = u8(packet, at + 1)
                    if (size < 2 || at + size > until) return
                    if (kind == OPTION_MSS && size == 4) maximumSegment = u16(packet, at + 2)
                    if (kind == OPTION_WINDOW_SCALE && size == 3) windowScale = u8(packet, at + 2)
                    at += size
                }
            }
        }
    }
}

/** Builds packets in one reusable buffer, with their checksums filled in. */
internal class PacketBuilder(mtu: Int) {
    val buffer = ByteArray(mtu)

    private fun writeIpv4(source: Int, destination: Int, protocol: Int, totalBytes: Int) {
        buffer[0] = 0x45
        buffer[1] = 0
        putU16(buffer, 2, totalBytes)
        putU16(buffer, 4, 0)
        buffer[6] = FLAGS_DONT_FRAGMENT.toByte()
        buffer[7] = 0
        buffer[8] = DEFAULT_TTL.toByte()
        buffer[9] = protocol.toByte()
        putU16(buffer, 10, 0)
        putI32(buffer, 12, source)
        putI32(buffer, 16, destination)
        putU16(buffer, 10, finish(sum(0, buffer, 0, IPV4_HEADER_BYTES)))
    }

    private fun pseudoHeader(source: Int, destination: Int, protocol: Int, bytes: Int): Long =
        (source ushr 16).toLong() + (source and 0xffff) + (destination ushr 16) + (destination and 0xffff) +
            protocol + bytes

    /** Where a segment's payload goes, for a caller to fill in before calling [tcp]. */
    val tcpPayloadOffset get() = IPV4_HEADER_BYTES + TCP_HEADER_BYTES

    /** Finishes a TCP segment whose payload is already at [tcpPayloadOffset]. Returns the packet's size. */
    fun tcp(
        source: Int,
        sourcePort: Int,
        destination: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgment: Int,
        flags: Int,
        window: Int,
        payloadBytes: Int,
    ): Int = writeTcp(
        source, sourcePort, destination, destinationPort, sequence, acknowledgment, flags, window,
        TCP_HEADER_BYTES, payloadBytes,
    )

    /** A SYN-ACK, which is the only segment that carries options. */
    fun synAck(
        source: Int,
        sourcePort: Int,
        destination: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgment: Int,
        window: Int,
        maximumSegment: Int,
        windowScale: Int,
    ): Int {
        var at = IPV4_HEADER_BYTES + TCP_HEADER_BYTES
        buffer[at++] = OPTION_MSS.toByte()
        buffer[at++] = 4
        putU16(buffer, at, maximumSegment)
        at += 2
        buffer[at++] = OPTION_NOP.toByte()
        buffer[at++] = OPTION_WINDOW_SCALE.toByte()
        buffer[at++] = 3
        buffer[at] = windowScale.toByte()
        return writeTcp(
            source, sourcePort, destination, destinationPort, sequence, acknowledgment, FLAG_SYN or FLAG_ACK, window,
            TCP_HEADER_BYTES + SYN_OPTION_BYTES, 0,
        )
    }

    private fun writeTcp(
        source: Int,
        sourcePort: Int,
        destination: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgment: Int,
        flags: Int,
        window: Int,
        headerBytes: Int,
        payloadBytes: Int,
    ): Int {
        val segmentBytes = headerBytes + payloadBytes
        val totalBytes = IPV4_HEADER_BYTES + segmentBytes
        writeIpv4(source, destination, PROTOCOL_TCP, totalBytes)
        val at = IPV4_HEADER_BYTES
        putU16(buffer, at, sourcePort)
        putU16(buffer, at + 2, destinationPort)
        putI32(buffer, at + 4, sequence)
        putI32(buffer, at + 8, acknowledgment)
        buffer[at + 12] = ((headerBytes / 4) shl 4).toByte()
        buffer[at + 13] = flags.toByte()
        putU16(buffer, at + 14, window)
        putU16(buffer, at + 16, 0)
        putU16(buffer, at + 18, 0)
        val pseudo = pseudoHeader(source, destination, PROTOCOL_TCP, segmentBytes)
        putU16(buffer, at + 16, finish(sum(pseudo, buffer, at, segmentBytes)))
        return totalBytes
    }

    fun udp(
        source: Int,
        sourcePort: Int,
        destination: Int,
        destinationPort: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val datagramBytes = UDP_HEADER_BYTES + length
        val totalBytes = IPV4_HEADER_BYTES + datagramBytes
        writeIpv4(source, destination, PROTOCOL_UDP, totalBytes)
        val at = IPV4_HEADER_BYTES
        putU16(buffer, at, sourcePort)
        putU16(buffer, at + 2, destinationPort)
        putU16(buffer, at + 4, datagramBytes)
        putU16(buffer, at + 6, 0)
        System.arraycopy(payload, offset, buffer, at + UDP_HEADER_BYTES, length)
        val checksum = finish(sum(pseudoHeader(source, destination, PROTOCOL_UDP, datagramBytes), buffer, at, datagramBytes))
        // Zero would mean the datagram has no checksum at all.
        putU16(buffer, at + 6, if (checksum == 0) 0xffff else checksum)
        return totalBytes
    }

    /** Says a UDP port can't be reached, quoting the start of the [original] packet as ICMP has to. */
    fun portUnreachable(original: ByteArray, ip: Ipv4Header): Int {
        val quoted = minOf(ip.headerBytes + ICMP_QUOTED_PAYLOAD_BYTES, ip.totalBytes)
        val messageBytes = ICMP_HEADER_BYTES + quoted
        val totalBytes = IPV4_HEADER_BYTES + messageBytes
        writeIpv4(ip.destination, ip.source, PROTOCOL_ICMP, totalBytes)
        val at = IPV4_HEADER_BYTES
        buffer[at] = ICMP_UNREACHABLE.toByte()
        buffer[at + 1] = ICMP_PORT_UNREACHABLE.toByte()
        putU16(buffer, at + 2, 0)
        putI32(buffer, at + 4, 0)
        System.arraycopy(original, 0, buffer, at + ICMP_HEADER_BYTES, quoted)
        putU16(buffer, at + 2, finish(sum(0, buffer, at, messageBytes)))
        return totalBytes
    }
}
