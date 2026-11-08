package com.wonderhoylabs.shira.tunnel

/** Sequence numbers wrap around, so they're compared by how far apart they are. */
private fun isAfter(a: Int, b: Int) = a - b > 0

/**
 * One TCP connection from an app, ended here on the phone.
 *
 * Apps talk TCP to this class through the VPN's device, and the bytes carry on through a Wisp stream.
 * It's always the side being connected to, and the link to the app is on the same machine, so there's no
 * congestion to manage. All it has to respect is the app's receive window. Anything out of order is
 * dropped for the kernel to send again, which almost never happens here.
 */
internal class TcpFlow(
	val streamId: Int,
	val key: Long,
	private val appAddress: Int,
	private val appPort: Int,
	private val remoteAddress: Int,
	private val remotePort: Int,
	private val host: Host,
	syn: TcpHeader,
	initialSequence: Int,
	credits: Int,
	now: Long,
) {
	interface Host {
		val builder: PacketBuilder
		fun writeToApp(length: Int)
		fun uploadReady(flow: TcpFlow)
		fun downloadDrained(bytes: Int)
	}

	/** How many more packets the Wisp server has said it can take on this stream. */
	var credits = credits

	/** Why this flow is over, once it is. Null means it's still going. */
	var ended: End? = null
		private set

	var streamOpen = true
		private set

	enum class End { CLEAN, RESET_BY_APP, ABORTED }

	private val peerScale = if (syn.windowScale >= 0) minOf(syn.windowScale, MAX_WINDOW_SCALE) else 0
	private val ourScale = if (syn.windowScale >= 0) WINDOW_SCALE else 0
	private val segmentBytes = minOf(
		if (syn.maximumSegment > 0) syn.maximumSegment else DEFAULT_SEGMENT_BYTES,
		host.builder.buffer.size - host.builder.tcpPayloadOffset,
	)

	private val upload = RingBuffer()
	private val download = RingBuffer()

	private var receiveNext = syn.sequence + 1
	private var sendUnacked = initialSequence
	private var sendNext = initialSequence + 1
	private var sendMax = initialSequence + 1
	private var downloadStart = initialSequence + 1
	private var peerWindow = syn.window

	private var synAcked = false
	private var appFinished = false
	private var remoteFinished = false
	private var finSent = false
	private var finSequence = 0
	private var finAcked = false

	private var ackNeeded = false
	private var lastAdvertised = 0
	private var duplicateAcks = 0
	private var retries = 0
	private var retransmitDelay = MIN_RETRANSMIT_MILLIS
	private var retransmitAt = 0L
	private var lastActivity = now

	/** When the app last took any data, or when it was last given some after having none waiting. */
	var lastDrained = now
		private set

	/** For the upload queue, so a flow is only in it once. */
	var waitingToUpload = false

	/** For the list of flows touched in this batch of packets. */
	var touched = false

	val hasUpload get() = upload.size > 0
	val downloadBytes get() = download.size

	init {
		sendSynAck()
		retransmitAt = now + retransmitDelay
	}

	fun onSegment(segment: TcpHeader, packet: ByteArray, now: Long) {
		lastActivity = now
		if (segment.isRst) {
			ended = End.RESET_BY_APP
			return
		}
		if (segment.isSyn) {
			// Its SYN-ACK must have got lost. Anything else carrying SYN has no business being here.
			if (!synAcked && segment.sequence + 1 == receiveNext) sendSynAck() else ackNeeded = true
			return
		}
		if (segment.isAck) onAck(segment, now)
		onPayload(segment, packet)
	}

	private fun onAck(segment: TcpHeader, now: Long) {
		val ack = segment.acknowledgment
		val window = segment.window shl peerScale
		val windowChanged = window != peerWindow
		peerWindow = window
		when {
			isAfter(ack, sendMax) -> ackNeeded = true

			isAfter(ack, sendUnacked) -> {
				sendUnacked = ack
				synAcked = true
				val end = downloadStart + download.size
				val dataAcked = (if (isAfter(ack, end)) end else ack) - downloadStart
				if (dataAcked > 0) {
					download.drop(dataAcked)
					downloadStart += dataAcked
					lastDrained = now
					host.downloadDrained(dataAcked)
				}
				if (finSent && isAfter(ack, finSequence)) finAcked = true
				if (isAfter(sendUnacked, sendNext)) sendNext = sendUnacked
				retries = 0
				duplicateAcks = 0
				retransmitDelay = MIN_RETRANSMIT_MILLIS
				retransmitAt = if (sendUnacked != sendMax) now + retransmitDelay else 0
			}

			ack == sendUnacked && sendUnacked != sendMax && segment.payloadBytes == 0 && !segment.isFin &&
				!windowChanged -> {
				if (++duplicateAcks == FAST_RETRANSMIT_ACKS) resendFirst()
			}
		}
	}

	private fun onPayload(segment: TcpHeader, packet: ByteArray) {
		var offset = segment.payloadOffset
		var length = segment.payloadBytes
		if (length == 0 && !segment.isFin) return

		val behind = receiveNext - segment.sequence
		if (behind < 0) {
			// Out of order. The repeated ACK tells the kernel to send what's missing.
			ackNeeded = true
			return
		}
		if (behind > length) {
			ackNeeded = true
			return
		}
		offset += behind
		length -= behind

		var taken = length
		if (length > 0 && !appFinished) {
			if (remoteFinished) {
				// Nobody's left to send it to, but the app can't know that yet.
				receiveNext += length
			} else {
				taken = minOf(length, UPLOAD_LIMIT_BYTES - upload.size)
				if (taken > 0) {
					upload.append(packet, offset, taken)
					receiveNext += taken
					host.uploadReady(this)
				}
			}
		}
		if (segment.isFin && taken == length && !appFinished) {
			receiveNext += 1
			appFinished = true
		}
		ackNeeded = true
	}

	/** Bytes from the Wisp stream, on their way to the app. */
	fun onRemoteData(source: ByteArray, offset: Int, length: Int, now: Long) {
		lastActivity = now
		if (download.size == 0) lastDrained = now
		download.append(source, offset, length)
		flush(now)
	}

	/** The Wisp stream ended, and everything the server had to send is already here. */
	fun onRemoteClosed(clean: Boolean, now: Long) {
		streamOpen = false
		lastActivity = now
		if (clean) {
			remoteFinished = true
			flush(now)
		} else {
			abort()
		}
	}

	/** Takes up to [limit] bytes for the Wisp stream into [target]. Returns how many. */
	fun takeUpload(target: ByteArray, targetOffset: Int, limit: Int): Int {
		val count = minOf(limit, upload.size)
		upload.peek(0, target, targetOffset, count)
		upload.drop(count)
		val window = advertisedWindow()
		if (window - lastAdvertised >= segmentBytes * WINDOW_UPDATE_SEGMENTS) ackNeeded = true
		return count
	}

	/** Sends what the window has room for, and an ACK if nothing else went out to carry one. */
	fun flush(now: Long) {
		if (ended != null) return
		if (synAcked) {
			val end = downloadStart + download.size
			while (true) {
				val unsent = end - sendNext
				val room = peerWindow - (sendNext - sendUnacked)
				val count = minOf(segmentBytes, minOf(unsent, room))
				if (count <= 0) break
				sendData(sendNext, count)
				sendNext += count
				if (isAfter(sendNext, sendMax)) sendMax = sendNext
			}
			if (remoteFinished && sendNext == end && !finAcked) {
				finSequence = end
				finSent = true
				send(FLAG_FIN or FLAG_ACK, end, 0)
				sendNext = end + 1
				if (isAfter(sendNext, sendMax)) sendMax = sendNext
			}
			if (retransmitAt == 0L && (sendUnacked != sendMax || (download.size > 0 && peerWindow == 0))) {
				retransmitAt = now + retransmitDelay
			}
		}
		if (ackNeeded) send(FLAG_ACK, sendNext, 0)
		if (appFinished && finAcked) ended = End.CLEAN
	}

	/** Does whatever has come due. */
	fun tick(now: Long) {
		if (ended != null) return
		if (appFinished && streamOpen && !hasUpload && now - lastActivity >= HALF_CLOSE_MILLIS) {
			// Wisp has no half-close, so a stream has to stay open after the app's FIN in case the app
			// only finished sending. If it closed completely, the first bytes back would have got a RST.
			abort()
			return
		}
		if (retransmitAt == 0L || now < retransmitAt) return
		when {
			!synAcked -> {
				if (++retries > MAX_RETRIES) {
					abort()
					return
				}
				sendSynAck()
			}

			download.size > 0 && peerWindow == 0 -> {
				// The app isn't reading. This asks about the window, for as long as the app's still there.
				sendData(sendUnacked, 1)
				if (sendNext == sendUnacked) sendNext += 1
				if (isAfter(sendNext, sendMax)) sendMax = sendNext
			}

			sendUnacked != sendMax -> {
				if (++retries > MAX_RETRIES) {
					abort()
					return
				}
				sendNext = sendUnacked
				flush(now)
			}

			else -> {
				retransmitAt = 0
				return
			}
		}
		retransmitDelay = minOf(retransmitDelay * 2, MAX_RETRANSMIT_MILLIS)
		retransmitAt = now + retransmitDelay
	}

	/** Ends the flow with a reset. */
	fun abort() {
		if (ended != null) return
		send(FLAG_RST or FLAG_ACK, sendNext, 0)
		ended = End.ABORTED
	}

	/** The stream's already gone, so there's no need to tell the server. */
	fun markStreamClosed() {
		streamOpen = false
	}

	private fun resendFirst() {
		val count = minOf(segmentBytes, downloadStart + download.size - sendUnacked)
		when {
			count > 0 -> sendData(sendUnacked, count)
			finSent && !finAcked -> send(FLAG_FIN or FLAG_ACK, finSequence, 0)
		}
	}

	private fun advertisedWindow(): Int = (UPLOAD_LIMIT_BYTES - upload.size) ushr ourScale shl ourScale

	private fun sendSynAck() {
		val builder = host.builder
		lastAdvertised = advertisedWindow()
		val length = builder.synAck(
			remoteAddress, remotePort, appAddress, appPort, sendUnacked, receiveNext,
			minOf(lastAdvertised, MAX_WINDOW_FIELD),
			builder.buffer.size - builder.tcpPayloadOffset,
			WINDOW_SCALE,
		)
		host.writeToApp(length)
	}

	private fun sendData(sequence: Int, count: Int) {
		download.peek(sequence - downloadStart, host.builder.buffer, host.builder.tcpPayloadOffset, count)
		send(FLAG_ACK or FLAG_PSH, sequence, count)
	}

	private fun send(flags: Int, sequence: Int, payloadBytes: Int) {
		lastAdvertised = advertisedWindow()
		val length = host.builder.tcp(
			remoteAddress, remotePort, appAddress, appPort, sequence, receiveNext, flags,
			minOf(lastAdvertised ushr ourScale, MAX_WINDOW_FIELD),
			payloadBytes,
		)
		host.writeToApp(length)
		ackNeeded = false
	}

	private companion object {
		const val DEFAULT_SEGMENT_BYTES = 536
		const val MAX_WINDOW_SCALE = 14
		const val MAX_WINDOW_FIELD = 0xffff
		const val WINDOW_SCALE = 6
		const val UPLOAD_LIMIT_BYTES = 2 * 1024 * 1024
		const val WINDOW_UPDATE_SEGMENTS = 2
		const val FAST_RETRANSMIT_ACKS = 3
		const val MIN_RETRANSMIT_MILLIS = 200L
		const val MAX_RETRANSMIT_MILLIS = 5_000L
		const val MAX_RETRIES = 12
		const val HALF_CLOSE_MILLIS = 10_000L
	}
}
