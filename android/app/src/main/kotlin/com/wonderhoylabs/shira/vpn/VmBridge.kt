package com.wonderhoylabs.shira.vpn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.wonderhoylabs.shira.bridge.VmFrameReader
import com.wonderhoylabs.shira.bridge.VmLink
import com.wonderhoylabs.shira.tunnel.PagePort
import com.wonderhoylabs.shira.tunnel.StreamIds
import com.wonderhoylabs.shira.tunnel.Tunnel
import com.wonderhoylabs.shira.tunnel.TunnelStats
import com.wonderhoylabs.shira.tunnel.ipv4ToString
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/** The phone's address on the Linux VM's network, and how much of it is the VM's side. */
internal data class VmNetwork(val address: Int, val prefix: Int, val checkPeers: Boolean = true) {
	/** Only the VM's side of the network may connect. The phone's own apps would come from [address]. */
	fun allows(peer: Int): Boolean {
		if (!checkPeers) return true
		if (prefix !in 1 until ADDRESS_BITS || peer == address) return false
		val mask = -1 shl (ADDRESS_BITS - prefix)
		return (peer and mask) == (address and mask)
	}

	private companion object {
		const val ADDRESS_BITS = 32
	}
}

/** Finds the phone's address on the VM's network. Null when there's no VM running. */
internal fun interface VmNetworkFinder {
	fun find(): VmNetwork?
}

/**
 * Carries Android's Linux VM through the same page as the phone's VPN.
 *
 * Android forwards the VM's network straight out, past the VPN. So while the VPN is up, this listens on the phone's
 * address on the VM's interface, and nowhere else, for `shira-vm` in the VM to connect and hand over its packets.
 * They go through a [Tunnel] of their own that shares the page, its stream IDs and its stats with the phone's.
 * When the VPN goes down this closes, and `shira-vm` blocks the VM's traffic until it can connect again.
 *
 * Everything runs on the bridge's thread: the loop polls [pollFds], then calls [afterPoll].
 */
internal class VmBridge(
	private val page: PagePort,
	private val streams: StreamIds,
	private val credits: Int,
	private val udpAllowed: Boolean,
	private val stats: TunnelStats,
	private val finder: VmNetworkFinder,
	private val owner: Int,
	private val appVersion: String,
) {
	private val listenPoll = StructPollfd()
	private val peerPoll = StructPollfd()
	private var polledListener = false
	private var polledPeer = false

	private var network: VmNetwork? = null
	private var listener: FileDescriptor? = null
	private var peer: FileDescriptor? = null
	private var tunnel: Tunnel? = null
	private var reader = newReader()
	private val packet = ByteArray(VmLink.MAX_FRAME_BYTES)
	private val outgoing = ArrayDeque<ByteArray>()
	private var headOffset = 0
	private var queuedBytes = 0
	private var lastLook = 0L
	private var looked = false
	private var now = 0L

	/** Whether the VM is connected right now. */
	val connected get() = tunnel != null

	val downloadBuffered get() = tunnel?.downloadBuffered ?: 0L

	fun owns(stream: Int) = tunnel?.owns(stream) == true

	fun onPagePacket(type: Int, stream: Int, payload: ByteArray, offset: Int, length: Int, now: Long) {
		tunnel?.onPagePacket(type, stream, payload, offset, length, now)
	}

	fun afterBatch(now: Long) {
		tunnel?.afterBatch(now)
	}

	fun pumpUploads(now: Long) {
		tunnel?.pumpUploads(now)
	}

	fun abortStalledDownload(now: Long) {
		tunnel?.abortStalledDownload(now)
	}

	/** Timers, and looking for the VM's network every couple of seconds, since a VM can start at any time. */
	fun tick(now: Long) {
		this.now = now
		tunnel?.tick(now)
		if (!looked || now - lastLook >= LOOK_MILLIS) look(now)
	}

	/** What to poll this time round. */
	fun pollFds(): List<StructPollfd> {
		val fds = ArrayList<StructPollfd>(2)
		polledListener = listener?.let {
			listenPoll.fd = it
			listenPoll.events = OsConstants.POLLIN.toShort()
			listenPoll.revents = 0
			fds += listenPoll
		} != null
		polledPeer = peer?.let {
			var events = OsConstants.POLLIN
			if (outgoing.isNotEmpty()) events = events or OsConstants.POLLOUT
			peerPoll.fd = it
			peerPoll.events = events.toShort()
			peerPoll.revents = 0
			fds += peerPoll
		} != null
		return fds
	}

	fun afterPoll(now: Long) {
		this.now = now
		if (polledListener && listenPoll.revents.toInt() != 0) accept()
		if (polledPeer && peer != null) {
			val revents = peerPoll.revents.toInt()
			if (revents and OsConstants.POLLIN != 0) {
				readPeer()
			} else if (revents and (OsConstants.POLLHUP or OsConstants.POLLERR) != 0) {
				dropPeer("the VM hung up")
			}
		}
		if (peer != null && outgoing.isNotEmpty()) flush()
	}

	/** Stops listening and lets the VM go. The page is going too, so its streams don't need closing. */
	fun close() {
		tunnel = null
		closePeer()
		closeListener()
		network = null
	}

	private fun look(now: Long) {
		lastLook = now
		looked = true
		val found = finder.find()
		if (found == network && (found == null || listener != null)) return
		if (network != null) Log.i(TAG, "the VM's network changed")
		dropPeer("its network changed")
		closeListener()
		network = found
		if (found != null) listen(found)
	}

	private fun listen(found: VmNetwork) {
		val fd = try {
			Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM or OsConstants.SOCK_NONBLOCK, 0)
		} catch (error: ErrnoException) {
			Log.w(TAG, "couldn't make a socket for the VM", error)
			return
		}
		try {
			Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
			Os.bind(fd, address(found.address), VmLink.PORT)
			Os.listen(fd, BACKLOG)
			listener = fd
			Log.i(TAG, "listening for the VM on ${ipv4ToString(found.address)}:${VmLink.PORT}")
		} catch (error: ErrnoException) {
			Log.w(TAG, "couldn't listen for the VM", error)
			runCatching { Os.close(fd) }
		} catch (error: SocketException) {
			Log.w(TAG, "couldn't listen for the VM", error)
			runCatching { Os.close(fd) }
		}
	}

	private fun accept() {
		val server = listener ?: return
		val from = InetSocketAddress(0)
		val fd = try {
			@Suppress("DEPRECATION")
			Os.accept(server, from)
		} catch (error: ErrnoException) {
			if (error.errno != OsConstants.EAGAIN) Log.w(TAG, "couldn't take the VM's connection", error)
			return
		} catch (error: SocketException) {
			Log.w(TAG, "couldn't take the VM's connection", error)
			return
		}
		val address = (from.address as? Inet4Address)?.address?.let(::toInt)
		if (address == null || network?.allows(address) != true) {
			// Linux takes packets for this address from any interface, so check it's really the VM.
			Log.w(TAG, "refused a connection from ${from.address}")
			runCatching { Os.close(fd) }
			return
		}
		// The newest connection wins, the way a new page does. It's shira-vm starting again.
		dropPeer("it connected again")
		runCatching { Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1) }
		peer = fd
		enqueue(VmLink.hello(VmLink.VM_ADDRESS, Tunnel.DNS_ADDRESS, Tunnel.DEFAULT_MTU, appVersion))
		tunnel = Tunnel(
			{ bytes, length -> enqueue(VmLink.packet(bytes, 0, length)) },
			page,
			{ _, _, _, _, _ -> owner },
			credits,
			udpAllowed,
			stats,
			Tunnel.DEFAULT_MTU,
			streams,
		)
		Log.i(TAG, "the VM connected from ${from.address}")
	}

	private fun readPeer() {
		val fd = peer ?: return
		repeat(READ_BATCH) {
			val count = try {
				reader.fill { buffer, offset, length -> Os.recvfrom(fd, buffer, offset, length, MSG_DONTWAIT, null) }
			} catch (error: ErrnoException) {
				if (error.errno == OsConstants.EAGAIN) return
				dropPeer(error.message.orEmpty())
				return
			} catch (error: SocketException) {
				dropPeer(error.message.orEmpty())
				return
			} catch (error: IOException) {
				dropPeer(error.message.orEmpty())
				return
			}
			if (count <= 0) {
				dropPeer("the VM hung up")
				return
			}
		}
	}

	private fun newReader() = VmFrameReader { type, buffer, offset, length ->
		if (type == VmLink.PACKET) {
			System.arraycopy(buffer, offset, packet, 0, length)
			tunnel?.onAppPacket(packet, length, now)
		}
	}

	private fun enqueue(frame: ByteArray) {
		// Like a full network card: TCP sends it again, and UDP's allowed to lose it.
		if (queuedBytes + frame.size > OUTGOING_LIMIT_BYTES) return
		outgoing.addLast(frame)
		queuedBytes += frame.size
	}

	private fun flush() {
		val fd = peer ?: return
		while (outgoing.isNotEmpty()) {
			val frame = outgoing.first()
			val written = try {
				Os.sendto(fd, frame, headOffset, frame.size - headOffset, MSG_DONTWAIT, null, 0)
			} catch (error: ErrnoException) {
				if (error.errno != OsConstants.EAGAIN) dropPeer(error.message.orEmpty())
				return
			} catch (error: SocketException) {
				dropPeer(error.message.orEmpty())
				return
			}
			headOffset += written
			queuedBytes -= written
			if (headOffset == frame.size) {
				outgoing.removeFirst()
				headOffset = 0
			}
		}
	}

	/** Lets the VM go, and closes its streams, since the page carries on without it. */
	private fun dropPeer(why: String) {
		if (peer == null) return
		Log.i(TAG, "the VM went: $why")
		tunnel?.close()
		tunnel = null
		closePeer()
	}

	private fun closePeer() {
		peer?.let { runCatching { Os.close(it) } }
		peer = null
		outgoing.clear()
		headOffset = 0
		queuedBytes = 0
		reader = newReader()
	}

	private fun closeListener() {
		listener?.let { runCatching { Os.close(it) } }
		listener = null
	}

	private fun address(value: Int): InetAddress = InetAddress.getByAddress(
		byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
	)

	private fun toInt(bytes: ByteArray): Int = ((bytes[0].toInt() and 0xff) shl 24) or
		((bytes[1].toInt() and 0xff) shl 16) or
		((bytes[2].toInt() and 0xff) shl 8) or
		(bytes[3].toInt() and 0xff)

	private companion object {
		const val TAG = "ShiraVm"

		// Android doesn't publish this one, and it's the same on every architecture Android runs on.
		const val MSG_DONTWAIT = 0x40
		const val BACKLOG = 4
		const val READ_BATCH = 64
		const val LOOK_MILLIS = 2_000L
		const val OUTGOING_LIMIT_BYTES = 4 * 1024 * 1024
	}
}
