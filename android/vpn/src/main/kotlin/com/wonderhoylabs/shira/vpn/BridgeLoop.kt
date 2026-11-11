package com.wonderhoylabs.shira.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.wonderhoylabs.shira.bridge.FrameReader
import com.wonderhoylabs.shira.bridge.Link
import com.wonderhoylabs.shira.bridge.PacketHandler
import com.wonderhoylabs.shira.tunnel.AppPort
import com.wonderhoylabs.shira.tunnel.OwnerLookup
import com.wonderhoylabs.shira.tunnel.PagePort
import com.wonderhoylabs.shira.tunnel.Tunnel
import com.wonderhoylabs.shira.tunnel.TunnelStats
import java.io.FileDescriptor
import java.io.IOException

/** What the loop needs from the service. */
interface LoopHost : OwnerLookup {
	/** Brings the VPN up. Null means Android refused, and [lastRefusal] says why. */
	fun establish(): ParcelFileDescriptor?
	val lastRefusal: String
	fun onTunnelUp(server: String, stats: TunnelStats)
	fun onTunnelDown()
}

/**
 * The thread that does everything: listens for the web page, and moves bytes between its socket and the
 * VPN's device. The VPN only exists while a page is attached and connected to a Wisp server, so the
 * phone's own network keeps working the rest of the time.
 */
class BridgeLoop(
	private val host: LoopHost,
	/** The abstract socket name the page opens as `localabstract:<name>`. */
	private val socketName: String,
	/** The app's version, which the page hears in HELLO. */
	private val appVersion: String,
) {
	@Volatile private var stopping = false
	private val wakeRead: FileDescriptor
	private val wakeWrite: FileDescriptor

	init {
		val pipe = Os.pipe()
		wakeRead = pipe[0]
		wakeWrite = pipe[1]
	}

	/** Ends [run] from any thread. */
	fun stop() {
		stopping = true
		try {
			Os.write(wakeWrite, ByteArray(1), 0, 1)
		} catch (error: ErrnoException) {
			Log.w(TAG, "couldn't wake the loop", error)
		} catch (error: java.io.InterruptedIOException) {
			Log.w(TAG, "couldn't wake the loop", error)
		}
	}

	/** Returns when it's told to stop, or nobody's come for a long time. Throws if the name's taken. */
	@Throws(IOException::class)
	fun run() {
		val server = try {
			LocalServerSocket(socketName)
		} catch (error: IOException) {
			closeWake()
			throw error
		}
		try {
			var peer: LocalSocket? = null
			var idleSince = SystemClock.elapsedRealtime()
			while (!stopping) {
				val current = peer ?: waitForPeer(server, idleSince) ?: break
				val session = Session(current, server)
				peer = session.run()
				runCatching { current.close() }
				idleSince = SystemClock.elapsedRealtime()
			}
			peer?.let { runCatching { it.close() } }
		} finally {
			runCatching { server.close() }
			closeWake()
		}
	}

	private fun closeWake() {
		runCatching { Os.close(wakeRead) }
		runCatching { Os.close(wakeWrite) }
	}

	private fun waitForPeer(server: LocalServerSocket, idleSince: Long): LocalSocket? {
		val listening = poll(server.fileDescriptor, OsConstants.POLLIN)
		val wake = poll(wakeRead, OsConstants.POLLIN)
		while (!stopping) {
			val left = idleSince + IDLE_STOP_MILLIS - SystemClock.elapsedRealtime()
			if (left <= 0) return null
			Os.poll(arrayOf(listening, wake), minOf(left, Int.MAX_VALUE.toLong()).toInt())
			if (wake.revents.toInt() != 0) return null
			if (listening.revents.toInt() != 0) accept(server)?.let { return it }
		}
		return null
	}

	/** Takes a new connection, if it's really from adb. No other app may see or inject this traffic. */
	private fun accept(server: LocalServerSocket): LocalSocket? {
		val socket = server.accept()
		val uid = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)
		if (uid != UID_ROOT && uid != UID_SHELL) {
			Log.w(TAG, "refused a connection from uid $uid")
			runCatching { socket.close() }
			return null
		}
		return socket
	}

	private inner class Session(socket: LocalSocket, private val server: LocalServerSocket) :
		PacketHandler,
		PagePort,
		AppPort {
		private val fd = socket.fileDescriptor
		private val outgoing = ArrayDeque<ByteArray>()
		private var headOffset = 0
		private var tun: ParcelFileDescriptor? = null
		private var tunnel: Tunnel? = null
		private var stats: TunnelStats? = null
		private var now = SystemClock.elapsedRealtime()
		private var lastHeard = now
		private var pausedSince = 0L
		private var lastTick = now
		private val packet = ByteArray(Tunnel.DEFAULT_MTU)
		private val reader = FrameReader(this)

		override var queuedBytes = 0
			private set

		/** Runs until the page goes. Returns a new socket if that's why this one ended. */
		fun run(): LocalSocket? {
			val peerPoll = poll(fd, 0)
			val serverPoll = poll(server.fileDescriptor, OsConstants.POLLIN)
			val wakePoll = poll(wakeRead, OsConstants.POLLIN)
			val tunPoll = StructPollfd()
			try {
				send(Link.hello(appVersion))
				while (!stopping) {
					if (!flushOutgoing()) return null
					val active = tunnel
					val paused = updatePause(active)
					var events = if (paused) 0 else OsConstants.POLLIN
					if (outgoing.isNotEmpty()) events = events or OsConstants.POLLOUT
					peerPoll.events = events.toShort()
					tunPoll.fd = tun?.fileDescriptor
					tunPoll.events = OsConstants.POLLIN.toShort()

					val wait = if (active != null) TICK_MILLIS else IDLE_POLL_MILLIS
					val fds = if (active == null) {
						arrayOf(peerPoll, serverPoll, wakePoll)
					} else {
						arrayOf(peerPoll, serverPoll, wakePoll, tunPoll)
					}
					Os.poll(fds, wait)
					now = SystemClock.elapsedRealtime()

					if (wakePoll.revents.toInt() != 0) {
						sayGoodbye(Link.STATUS_OFF)
						return null
					}
					if (serverPoll.revents.toInt() != 0) {
						// Only one page can drive the phone. The newest one is the one somebody's looking at.
						accept(server)?.let {
							sayGoodbye(Link.STATUS_REPLACED)
							return it
						}
					}
					if (peerPoll.revents.toInt() and OsConstants.POLLIN != 0) {
						if (!readPage()) return null
					} else if (peerPoll.revents.toInt() and (OsConstants.POLLHUP or OsConstants.POLLERR) != 0) {
						return null
					}
					if (active != null && active === tunnel && tunPoll.revents.toInt() and OsConstants.POLLIN != 0) {
						readApps(active)
					}

					val after = tunnel
					if (after != null) {
						if (now - lastTick >= TICK_MILLIS) {
							lastTick = now
							after.tick(now)
						}
						after.afterBatch(now)
					}
					// A socket that's still open but silent is a closed tab. That has to end, or the
					// VPN would keep the phone's traffic with nowhere to go.
					if (!paused && now - lastHeard >= PAGE_SILENCE_MILLIS) {
						Log.i(TAG, "the page stopped answering")
						return null
					}
				}
				return null
			} catch (error: ErrnoException) {
				Log.i(TAG, "lost the page: ${error.message}")
				return null
			} catch (error: IOException) {
				Log.w(TAG, "the page sent something wrong", error)
				return null
			} finally {
				bringDown()
			}
		}

		/** Best effort: the socket's about to close anyway. */
		private fun sayGoodbye(status: Int) {
			send(Link.status(status))
			flushOutgoing()
		}

		private fun updatePause(active: Tunnel?): Boolean {
			val buffered = active?.downloadBuffered ?: 0
			when {
				pausedSince == 0L && buffered >= PAUSE_ABOVE_BYTES -> pausedSince = now

				pausedSince != 0L && buffered <= RESUME_BELOW_BYTES -> {
					pausedSince = 0
					// Nothing was being read, so the page's pings didn't count for anything.
					lastHeard = now
				}

				pausedSince != 0L && now - pausedSince >= Tunnel.STALLED_MILLIS -> {
					active?.abortStalledDownload(now)
					pausedSince = now
				}
			}
			return pausedSince != 0L
		}

		private fun readPage(): Boolean {
			val count = try {
				reader.fill { buffer, offset, length ->
					Os.recvfrom(fd, buffer, offset, length, MSG_DONTWAIT, null)
				}
			} catch (error: ErrnoException) {
				if (error.errno == OsConstants.EAGAIN) return true
				throw error
			}
			if (count == 0) return false
			lastHeard = now
			return !stopping
		}

		private fun readApps(active: Tunnel) {
			val device = tun?.fileDescriptor ?: return
			repeat(READ_BATCH) {
				val count = try {
					Os.read(device, packet, 0, packet.size)
				} catch (error: ErrnoException) {
					if (error.errno == OsConstants.EAGAIN) return
					throw error
				}
				if (count <= 0) return
				active.onAppPacket(packet, count, now)
			}
		}

		override fun onPacket(type: Int, stream: Int, buffer: ByteArray, offset: Int, length: Int) {
			when (type) {
				Link.PING -> Unit
				Link.START -> onStart(buffer, offset, length)
				Link.STOP -> onStop(length >= 1 && buffer[offset].toInt() != 0)
				else -> tunnel?.onPagePacket(type, stream, buffer, offset, length, now)
			}
		}

		private fun onStart(buffer: ByteArray, offset: Int, length: Int) {
			if (length < START_FIXED_BYTES) return
			val credits = Link.getInt(buffer, offset)
			val udp = buffer[offset + 4].toInt() != 0
			val server = String(buffer, offset + START_FIXED_BYTES, length - START_FIXED_BYTES)
			bringDown()
			val device = host.establish()
			if (device == null) {
				send(Link.status(Link.STATUS_FAILED, host.lastRefusal))
				return
			}
			val newStats = TunnelStats()
			tun = device
			stats = newStats
			tunnel = Tunnel(this, this, host, credits, udp, newStats)
			host.onTunnelUp(server, newStats)
			send(Link.status(Link.STATUS_UP))
		}

		private fun onStop(deactivate: Boolean) {
			bringDown()
			send(Link.status(Link.STATUS_DOWN))
			if (deactivate) stopping = true
		}

		private fun bringDown() {
			val device = tun ?: return
			tunnel = null
			tun = null
			stats = null
			runCatching { device.close() }
			host.onTunnelDown()
		}

		override fun write(packet: ByteArray, length: Int) {
			val device = tun ?: return
			try {
				Os.write(device.fileDescriptor, packet, 0, length)
			} catch (error: ErrnoException) {
				// The kernel's queue is full. TCP sends it again, and UDP's allowed to lose it.
				if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.ENOBUFS) throw error
			}
		}

		override fun send(frame: ByteArray) {
			outgoing.addLast(frame)
			queuedBytes += frame.size
		}

		/** Writes what the socket will take. False if the page has gone. */
		private fun flushOutgoing(): Boolean {
			while (outgoing.isNotEmpty()) {
				val frame = outgoing.first()
				val written = try {
					Os.sendto(
						fd,
						frame,
						headOffset,
						frame.size - headOffset,
						MSG_DONTWAIT,
						null,
						0,
					)
				} catch (error: ErrnoException) {
					if (error.errno == OsConstants.EAGAIN) return true
					Log.i(TAG, "lost the page: ${error.message}")
					return false
				}
				headOffset += written
				queuedBytes -= written
				if (headOffset == frame.size) {
					outgoing.removeFirst()
					headOffset = 0
				}
			}
			tunnel?.pumpUploads(now)
			return true
		}
	}

	private fun poll(fd: FileDescriptor, events: Int) = StructPollfd().apply {
		this.fd = fd
		this.events = events.toShort()
	}

	private companion object {
		const val TAG = "ShiraBridge"

		// Android doesn't publish this one, and it's the same on every architecture Android runs on.
		const val MSG_DONTWAIT = 0x40
		const val UID_ROOT = 0
		const val UID_SHELL = 2000
		const val READ_BATCH = 64
		const val START_FIXED_BYTES = 5
		const val TICK_MILLIS = 50
		const val IDLE_POLL_MILLIS = 1_000
		const val PAGE_SILENCE_MILLIS = 10_000L
		const val IDLE_STOP_MILLIS = 10 * 60 * 1_000L
		const val PAUSE_ABOVE_BYTES = 8L * 1024 * 1024
		const val RESUME_BELOW_BYTES = 4L * 1024 * 1024
	}
}
