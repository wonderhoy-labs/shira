package com.wonderhoylabs.shira.tunnel

import com.wonderhoylabs.shira.bridge.FrameReader
import com.wonderhoylabs.shira.bridge.Link
import org.junit.Assert.assertEquals

internal class PageFrame(val type: Int, val stream: Int, val payload: ByteArray)

/** Plays both ends: an app sending packets, and the web page answering for the Wisp server. */
internal class TunnelHarness(
	credits: Int = 8,
	udp: Boolean = true,
	private val mtu: Int = 1500,
	val appAddress: Int = Tunnel.TUN_ADDRESS,
	val stats: TunnelStats = TunnelStats(),
	streams: StreamIds = StreamIds(),
) {
	val toApp = ArrayDeque<ByteArray>()
	val toPage = ArrayDeque<PageFrame>()
	var now = 1_000L
	var pageQueued = 0

	private val builder = PacketBuilder(65_535)
	private val reader = FrameReader { type, stream, buffer, offset, length ->
		toPage.addLast(PageFrame(type, stream, buffer.copyOfRange(offset, offset + length)))
	}

	val tunnel = Tunnel(
		{ packet, length -> toApp.addLast(packet.copyOf(length)) },
		object : PagePort {
			override val queuedBytes get() = pageQueued
			override fun send(frame: ByteArray) {
				reader.fill { target, offset, _ ->
					frame.copyInto(target, offset)
					frame.size
				}
			}
		},
		{ _, _, _, _, _ -> APP_UID },
		credits,
		udp,
		stats,
		mtu,
		streams,
	)

	fun appSegment(
		port: Int,
		remote: Int,
		remotePort: Int,
		sequence: Int,
		ack: Int,
		flags: Int,
		window: Int = 0xffff,
		payload: ByteArray = ByteArray(0),
	) {
		payload.copyInto(builder.buffer, builder.tcpPayloadOffset)
		val size = builder.tcp(appAddress, port, remote, remotePort, sequence, ack, flags, window, payload.size)
		tunnel.onAppPacket(builder.buffer, size, now)
		tunnel.afterBatch(now)
	}

	fun appSyn(port: Int, remote: Int, remotePort: Int, sequence: Int) {
		// MSS of 1000 and a window scale of 2, so that the numbers in tests stay simple.
		val packet = ByteArray(IPV4_HEADER_BYTES + TCP_HEADER_BYTES + 8)
		val size = builder.synAck(remote, remotePort, appAddress, port, sequence, 0, 0xffff, 1000, 2)
		builder.buffer.copyInto(packet, 0, 0, size)
		// That built a SYN-ACK in the wrong direction. Flip it into the SYN an app would send.
		putI32(packet, 12, appAddress)
		putI32(packet, 16, remote)
		putU16(packet, IPV4_HEADER_BYTES, port)
		putU16(packet, IPV4_HEADER_BYTES + 2, remotePort)
		packet[IPV4_HEADER_BYTES + 13] = FLAG_SYN.toByte()
		tunnel.onAppPacket(packet, size, now)
		tunnel.afterBatch(now)
	}

	fun appUdp(port: Int, remote: Int, remotePort: Int, payload: ByteArray) {
		val size = builder.udp(appAddress, port, remote, remotePort, payload, 0, payload.size)
		tunnel.onAppPacket(builder.buffer, size, now)
		tunnel.afterBatch(now)
	}

	fun pageData(stream: Int, payload: ByteArray) {
		tunnel.onPagePacket(Link.DATA, stream, payload, 0, payload.size, now)
		tunnel.afterBatch(now)
	}

	fun pageClose(stream: Int, reason: Int) {
		tunnel.onPagePacket(Link.CLOSE, stream, byteArrayOf(reason.toByte()), 0, 1, now)
		tunnel.afterBatch(now)
	}

	fun pageContinue(stream: Int, credits: Int) {
		val payload = ByteArray(4)
		Link.putInt(payload, 0, credits)
		tunnel.onPagePacket(Link.CONTINUE, stream, payload, 0, 4, now)
		tunnel.afterBatch(now)
	}

	fun advance(millis: Long) {
		now += millis
		tunnel.tick(now)
		tunnel.afterBatch(now)
	}

	/** Takes a TCP segment on its way to the app, after checking its checksums are right. */
	fun nextSegment(): Segment {
		val bytes = toApp.removeFirst()
		assertChecksumsValid(bytes)
		val ip = Ipv4Header()
		check(ip.parse(bytes, bytes.size))
		assertEquals(PROTOCOL_TCP, ip.protocol)
		val tcp = TcpHeader()
		check(tcp.parse(bytes, ip))
		return Segment(ip, tcp, bytes.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadBytes))
	}

	/** Takes a UDP datagram on its way to the app. */
	fun nextDatagram(): Datagram {
		val bytes = toApp.removeFirst()
		assertChecksumsValid(bytes)
		val ip = Ipv4Header()
		check(ip.parse(bytes, bytes.size))
		assertEquals(PROTOCOL_UDP, ip.protocol)
		return Datagram(
			ip.source,
			u16(bytes, ip.headerBytes),
			u16(bytes, ip.headerBytes + 2),
			bytes.copyOfRange(ip.headerBytes + 8, ip.totalBytes),
		)
	}

	fun nextFrame(type: Int): PageFrame {
		val frame = toPage.removeFirst()
		assertEquals(type, frame.type)
		return frame
	}

	private fun assertChecksumsValid(bytes: ByteArray) {
		val headerBytes = (u8(bytes, 0) and 0x0f) * 4
		assertEquals("IPv4 checksum", 0xffff, folded(bytes, 0, headerBytes, 0))
		val protocol = u8(bytes, 9)
		if (protocol == PROTOCOL_ICMP) return
		val length = bytes.size - headerBytes
		val pseudo = u16(bytes, 12).toLong() + u16(bytes, 14) + u16(bytes, 16) + u16(bytes, 18) + protocol + length
		assertEquals("segment checksum", 0xffff, folded(bytes, headerBytes, length, pseudo))
	}

	private fun folded(bytes: ByteArray, offset: Int, length: Int, start: Long): Int {
		var total = start
		var at = offset
		while (at + 1 < offset + length) {
			total += u16(bytes, at)
			at += 2
		}
		if (at < offset + length) total += u8(bytes, at) shl 8
		while (total ushr 16 != 0L) total = (total and 0xffff) + (total ushr 16)
		return total.toInt()
	}

	class Segment(val ip: Ipv4Header, val tcp: TcpHeader, val payload: ByteArray)

	class Datagram(val source: Int, val sourcePort: Int, val destinationPort: Int, val payload: ByteArray)

	companion object {
		const val APP_UID = 10123
	}
}
