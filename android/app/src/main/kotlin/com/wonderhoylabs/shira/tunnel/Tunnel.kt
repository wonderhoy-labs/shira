package com.wonderhoylabs.shira.tunnel

import com.wonderhoylabs.shira.bridge.Link
import java.io.ByteArrayOutputStream

/** Where packets for apps go. It's the VPN's device, and tests swap in something else. */
internal fun interface AppPort {
	fun write(packet: ByteArray, length: Int)
}

/** The socket to the web page. */
internal interface PagePort {
	/** How much is waiting to go, so that a fast sender can hold back. */
	val queuedBytes: Int
	fun send(frame: ByteArray)
}

/** Asks Android which app a connection belongs to. It says -1 if it can't tell. */
internal fun interface OwnerLookup {
	fun uidOf(protocol: Int, sourceAddress: Int, sourcePort: Int, destinationAddress: Int, destinationPort: Int): Int
}

/** Read from the UI's thread, so they're volatile. Only the tunnel's thread changes them. */
class TunnelStats {
	@Volatile var bytesUp = 0L

	@Volatile var bytesDown = 0L

	@Volatile var connections = 0
}

/**
 * Turns the packets apps send into Wisp streams, and Wisp streams back into packets.
 *
 * Everything happens on one thread. [BridgeLoop][com.wonderhoylabs.shira.vpn.BridgeLoop] feeds this from
 * the device and the socket, and calls [afterBatch] when it runs out of things to read. There's only an
 * IPv4 side. Wisp gives no sign that a stream connected, so a connection looks fine to an app the moment
 * it's made, and one to an IPv6 address the server can't reach would fail instead of falling back.
 */
internal class Tunnel(
	private val app: AppPort,
	private val page: PagePort,
	private val owners: OwnerLookup,
	private val initialCredits: Int,
	private val udpAllowed: Boolean,
	private val stats: TunnelStats,
	mtu: Int = DEFAULT_MTU,
) : TcpFlow.Host {
	override val builder = PacketBuilder(mtu)

	private val ip = Ipv4Header()
	private val tcp = TcpHeader()
	private val dns = DnsResolver()
	private val tcpFlows = HashMap<Long, TcpFlow>()
	private val tcpByStream = HashMap<Int, TcpFlow>()
	private val udpFlows = HashMap<Long, UdpFlow>()
	private val udpByStream = HashMap<Int, UdpFlow>()
	private val dnsByStream = HashMap<Int, DnsQuery>()
	private val touched = ArrayList<TcpFlow>()
	private val uploaders = ArrayDeque<TcpFlow>()
	private val uploadBuffer = ByteArray(Link.FRAME_OVERHEAD + UPLOAD_CHUNK_BYTES)

	private var nextStream = 1
	private var nextSequence = 0x5a17_0000
	private var lastSweep = 0L

	/** Bytes held for apps that haven't read them yet. The loop stops reading the socket when it's high. */
	var downloadBuffered = 0L
		private set

	private class UdpFlow(
		val streamId: Int,
		val key: Long,
		val appAddress: Int,
		val appPort: Int,
		val remoteAddress: Int,
		val remotePort: Int,
		var lastActivity: Long,
	)

	private class DnsQuery(
		val query: ByteArray,
		val appAddress: Int,
		val appPort: Int,
		val resolverAddress: Int,
		val startedAt: Long,
	) {
		val reply = ByteArrayOutputStream()
	}

	/** A packet from an app. */
	fun onAppPacket(packet: ByteArray, length: Int, now: Long) {
		if (!ip.parse(packet, length)) return
		when (ip.protocol) {
			PROTOCOL_TCP -> if (tcp.parse(packet, ip)) onTcp(packet, now)
			PROTOCOL_UDP -> onUdp(packet, now)
		}
	}

	/** A packet from the web page. */
	fun onPagePacket(type: Int, stream: Int, payload: ByteArray, offset: Int, length: Int, now: Long) {
		when (type) {
			Link.DATA -> onStreamData(stream, payload, offset, length, now)
			Link.CONTINUE -> if (length >= 4) onStreamContinue(stream, Link.getInt(payload, offset))
			Link.CLOSE -> onStreamClosed(stream, if (length >= 1) u8(payload, offset) else Link.CLOSE_UNKNOWN, now)
		}
	}

	/** Called after each round of reading, so a burst of packets shares ACKs and Wisp packets. */
	fun afterBatch(now: Long) {
		for (index in touched.indices) {
			val flow = touched[index]
			flow.touched = false
			flow.flush(now)
			if (flow.ended != null) remove(flow)
		}
		touched.clear()
		pumpUploads(now)
	}

	/** Called every so often, for timers. */
	fun tick(now: Long) {
		for (flow in tcpFlows.values.toList()) {
			flow.tick(now)
			if (flow.ended != null) remove(flow)
		}
		if (now - lastSweep >= SWEEP_MILLIS) sweep(now)
	}

	/**
	 * Drops whichever connection is holding the most data, if its app has stopped reading.
	 *
	 * Wisp can't ask a server to slow down one stream, only all of them together, by not reading from the
	 * connection. That's fine for an app that's just slower than the cable. One that's stopped altogether,
	 * like a paused video, would hold everything else up, so it loses its connection and can make another.
	 */
	fun abortStalledDownload(now: Long) {
		val stalled = tcpFlows.values
			.filter { it.downloadBytes > 0 && now - it.lastDrained >= STALLED_MILLIS }
			.maxByOrNull { it.downloadBytes } ?: return
		stalled.abort()
		remove(stalled)
	}

	/** Called when the socket can take more, to move data that's been waiting. Flows take turns. */
	fun pumpUploads(now: Long) {
		while (uploaders.isNotEmpty() && page.queuedBytes < PAGE_QUEUE_LIMIT_BYTES) {
			val flow = uploaders.removeFirst()
			flow.waitingToUpload = false
			// One that's out of credits comes back when the server sends more.
			if (flow.ended != null || !flow.streamOpen || flow.credits <= 0) continue

			val count = flow.takeUpload(uploadBuffer, 0, UPLOAD_CHUNK_BYTES)
			page.send(Link.data(flow.streamId, uploadBuffer, 0, count))
			flow.credits--
			stats.bytesUp += count
			flow.flush(now)
			if (flow.hasUpload) uploadReady(flow)
		}
	}

	private fun onTcp(packet: ByteArray, now: Long) {
		val key = flowKey(tcp.sourcePort, ip.destination, tcp.destinationPort)
		val flow = tcpFlows[key]
		if (flow != null) {
			flow.onSegment(tcp, packet, now)
			touch(flow)
			return
		}
		if (tcp.isRst) return
		if (!tcp.isSyn || tcp.isAck) {
			refuse()
			return
		}
		var remoteAddress = ip.destination
		var remotePort = tcp.destinationPort
		when (remoteAddress) {
			DNS_ADDRESS -> {
				// Clients ask over TCP after a truncated answer. Secure DNS on 853 is refused here, which
				// makes Android stay with the plain resolver that this class can see.
				if (remotePort != DNS_PORT) {
					refuse()
					return
				}
				remoteAddress = UPSTREAM_DNS
			}

			TUN_ADDRESS -> {
				refuse()
				return
			}
		}

		val stream = takeStream()
		page.send(Link.connect(stream, Link.STREAM_TCP, remotePort, ipv4ToString(remoteAddress)))
		val uid = owners.uidOf(PROTOCOL_TCP, ip.source, tcp.sourcePort, ip.destination, tcp.destinationPort)
		page.send(Link.meta(stream, uid, Link.META_CONNECTION, 0, dns.nameOf(remoteAddress).orEmpty()))
		val created = TcpFlow(
			stream, key, ip.source, tcp.sourcePort, ip.destination, tcp.destinationPort, this, tcp,
			nextSequence, initialCredits, now,
		)
		nextSequence += SEQUENCE_STEP
		tcpFlows[key] = created
		tcpByStream[stream] = created
		updateConnections()
	}

	/** Answers a segment that belongs to nothing with a RST, the way a closed port does. */
	private fun refuse() {
		val sequence = if (tcp.isAck) tcp.acknowledgment else 0
		var length = tcp.payloadBytes
		if (tcp.isSyn) length++
		if (tcp.isFin) length++
		val size = builder.tcp(
			ip.destination, tcp.destinationPort, ip.source, tcp.sourcePort, sequence, tcp.sequence + length,
			FLAG_RST or FLAG_ACK, 0, 0,
		)
		app.write(builder.buffer, size)
	}

	private fun onUdp(packet: ByteArray, now: Long) {
		val start = ip.headerBytes
		if (ip.totalBytes - start < UDP_HEADER_BYTES) return
		val sourcePort = u16(packet, start)
		val destinationPort = u16(packet, start + 2)
		val datagramBytes = u16(packet, start + 4)
		if (datagramBytes < UDP_HEADER_BYTES || start + datagramBytes > ip.totalBytes) return
		val payloadOffset = start + UDP_HEADER_BYTES
		val payloadBytes = datagramBytes - UDP_HEADER_BYTES

		if (destinationPort == DNS_PORT) {
			onDnsQuery(packet.copyOfRange(payloadOffset, payloadOffset + payloadBytes), sourcePort, now)
			return
		}
		// Multicast and broadcast have nowhere to go on the other side of a proxy.
		if (ip.destination ushr 28 >= FIRST_MULTICAST_NIBBLE) return
		if (!udpAllowed) {
			// Better than silence: QUIC falls back to TCP straight away when it hears this.
			app.write(builder.buffer, builder.portUnreachable(packet, ip))
			return
		}

		val key = flowKey(sourcePort, ip.destination, destinationPort)
		val flow = udpFlows[key] ?: run {
			val stream = takeStream()
			page.send(Link.connect(stream, Link.STREAM_UDP, destinationPort, ipv4ToString(ip.destination)))
			val uid = owners.uidOf(PROTOCOL_UDP, ip.source, sourcePort, ip.destination, destinationPort)
			page.send(Link.meta(stream, uid, Link.META_CONNECTION, 0, dns.nameOf(ip.destination).orEmpty()))
			UdpFlow(stream, key, ip.source, sourcePort, ip.destination, destinationPort, now).also {
				udpFlows[key] = it
				udpByStream[stream] = it
				updateConnections()
			}
		}
		flow.lastActivity = now
		// UDP is allowed to lose packets, which beats queueing them behind a full cable.
		if (page.queuedBytes >= PAGE_QUEUE_LIMIT_BYTES) return
		page.send(Link.data(flow.streamId, packet, payloadOffset, payloadBytes))
		stats.bytesUp += payloadBytes
	}

	private fun onDnsQuery(query: ByteArray, appPort: Int, now: Long) {
		val cached = dns.cached(query, now)
		if (cached != null) {
			answerDns(ip.source, appPort, ip.destination, query, cached)
			return
		}
		val question = DnsResolver.question(query)
		val stream = takeStream()
		// One stream per query keeps failures simple, and gives the log one row for each.
		dnsByStream[stream] = DnsQuery(query, ip.source, appPort, ip.destination, now)
		page.send(Link.connect(stream, Link.STREAM_TCP, DNS_PORT, ipv4ToString(UPSTREAM_DNS)))
		page.send(Link.meta(stream, -1, Link.META_DNS, question?.type ?: 0, question?.name.orEmpty()))
		val framed = ByteArray(DNS_LENGTH_BYTES + query.size)
		putU16(framed, 0, query.size)
		query.copyInto(framed, DNS_LENGTH_BYTES)
		page.send(Link.data(stream, framed, 0, framed.size))
	}

	private fun answerDns(appAddress: Int, appPort: Int, resolver: Int, query: ByteArray, response: ByteArray) {
		val fitted = DnsResolver.fitForUdp(query, response)
		app.write(builder.buffer, builder.udp(resolver, DNS_PORT, appAddress, appPort, fitted, 0, fitted.size))
	}

	private fun onStreamData(stream: Int, payload: ByteArray, offset: Int, length: Int, now: Long) {
		stats.bytesDown += length
		val flow = tcpByStream[stream]
		if (flow != null) {
			flow.onRemoteData(payload, offset, length, now)
			downloadBuffered += length
			if (flow.ended != null) remove(flow)
			return
		}
		val udp = udpByStream[stream]
		if (udp != null) {
			udp.lastActivity = now
			val size = builder.udp(
				udp.remoteAddress,
				udp.remotePort,
				udp.appAddress,
				udp.appPort,
				payload,
				offset,
				length,
			)
			app.write(builder.buffer, size)
			return
		}
		dnsByStream[stream]?.let { query ->
			query.reply.write(payload, offset, length)
			val received = query.reply.toByteArray()
			if (received.size < DNS_LENGTH_BYTES) return
			val size = u16(received, 0)
			if (received.size < DNS_LENGTH_BYTES + size) return
			val response = received.copyOfRange(DNS_LENGTH_BYTES, DNS_LENGTH_BYTES + size)
			dnsByStream.remove(stream)
			dns.remember(query.query, response, now)
			answerDns(query.appAddress, query.appPort, query.resolverAddress, query.query, response)
			page.send(Link.close(stream, Link.CLOSE_VOLUNTARY))
		}
	}

	private fun onStreamContinue(stream: Int, credits: Int) {
		val flow = tcpByStream[stream] ?: return
		flow.credits = credits
		if (flow.hasUpload) uploadReady(flow)
	}

	private fun onStreamClosed(stream: Int, reason: Int, now: Long) {
		val flow = tcpByStream.remove(stream)
		if (flow != null) {
			flow.onRemoteClosed(reason == Link.CLOSE_VOLUNTARY, now)
			if (flow.ended != null) remove(flow) else touch(flow)
			return
		}
		val udp = udpByStream.remove(stream)
		if (udp != null) {
			udpFlows.remove(udp.key)
			updateConnections()
			return
		}
		dnsByStream.remove(stream)?.let { query ->
			answerDns(
				query.appAddress,
				query.appPort,
				query.resolverAddress,
				query.query,
				DnsResolver.servfail(query.query),
			)
		}
	}

	private fun remove(flow: TcpFlow) {
		tcpByStream.remove(flow.streamId)
		tcpFlows.remove(flow.key)
		if (flow.streamOpen) {
			val reason = if (flow.ended == TcpFlow.End.ABORTED) Link.CLOSE_CLIENT_ERROR else Link.CLOSE_VOLUNTARY
			page.send(Link.close(flow.streamId, reason))
			flow.markStreamClosed()
		}
		downloadBuffered -= flow.downloadBytes
		updateConnections()
	}

	/** Clears out what's stopped, about once a second. */
	private fun sweep(now: Long) {
		lastSweep = now
		val idleUdp = udpFlows.values.filter { now - it.lastActivity >= UDP_IDLE_MILLIS }
		for (flow in idleUdp) {
			udpFlows.remove(flow.key)
			udpByStream.remove(flow.streamId)
			page.send(Link.close(flow.streamId, Link.CLOSE_VOLUNTARY))
		}
		if (idleUdp.isNotEmpty()) updateConnections()

		val lateDns = dnsByStream.entries.filter { now - it.value.startedAt >= DNS_TIMEOUT_MILLIS }
		for ((stream, query) in lateDns) {
			dnsByStream.remove(stream)
			page.send(Link.close(stream, Link.CLOSE_VOLUNTARY))
			answerDns(
				query.appAddress,
				query.appPort,
				query.resolverAddress,
				query.query,
				DnsResolver.servfail(query.query),
			)
		}
	}

	private fun touch(flow: TcpFlow) {
		if (!flow.touched) {
			flow.touched = true
			touched += flow
		}
	}

	private fun updateConnections() {
		stats.connections = tcpFlows.size + udpFlows.size
	}

	private fun takeStream(): Int {
		val stream = nextStream
		nextStream = if (stream == Int.MAX_VALUE) 1 else stream + 1
		return stream
	}

	private fun flowKey(sourcePort: Int, destination: Int, destinationPort: Int): Long =
		((destination.toLong() and 0xffffffffL) shl 32) or (sourcePort.toLong() shl 16) or destinationPort.toLong()

	override fun writeToApp(length: Int) {
		app.write(builder.buffer, length)
	}

	override fun uploadReady(flow: TcpFlow) {
		if (!flow.waitingToUpload) {
			flow.waitingToUpload = true
			uploaders.addLast(flow)
		}
	}

	override fun downloadDrained(bytes: Int) {
		downloadBuffered -= bytes
	}

	companion object {
		const val DEFAULT_MTU = 9000

		/** How long an app can go without reading before it counts as having stopped. */
		const val STALLED_MILLIS = 2_000L

		/** The phone's address on the VPN. */
		val TUN_ADDRESS = ipv4(10, 219, 0, 1)

		/** The resolver's address. Nothing is there: queries to it get picked out of the packets. */
		val DNS_ADDRESS = ipv4(10, 219, 0, 2)

		/** Where the resolver sends the questions it can't answer itself. */
		val UPSTREAM_DNS = ipv4(1, 1, 1, 1)

		private const val DNS_PORT = 53
		private const val DNS_LENGTH_BYTES = 2
		private const val DNS_TIMEOUT_MILLIS = 5_000L
		private const val UDP_IDLE_MILLIS = 120_000L
		private const val SWEEP_MILLIS = 1_000L
		private const val FIRST_MULTICAST_NIBBLE = 0xe
		private const val SEQUENCE_STEP = 0x0100_0193
		private const val UPLOAD_CHUNK_BYTES = 60 * 1024
		private const val PAGE_QUEUE_LIMIT_BYTES = 1024 * 1024
	}
}
