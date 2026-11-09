package com.wonderhoylabs.shira.bridge

import com.wonderhoylabs.shira.tunnel.ipv4
import java.io.IOException

/**
 * The messages between the app and `shira-vm`, which runs in Android's Linux VM, over one TCP connection.
 *
 * Android gives the VM its network the way it serves a hotspot: through a tap interface, forwarded straight out to
 * the phone's own network, where no VPN can see it. So `shira-vm` sends the VM's IP packets to the app instead,
 * over TCP to the phone's address on that interface, and the app ends them the way it ends its VPN's.
 *
 * Each frame is a length in 4 little-endian bytes, counting what follows, then a type in 1 byte, then the payload.
 * The app sends [HELLO] first, and after that both sides send [PACKET]s. See docs/protocol.md.
 */
object VmLink {
	/** The TCP port the app listens on, on its address on the VM's network. */
	const val PORT = 47830

	/** The Linux interface Android makes for the VM. The app listens on its address, and nowhere else. */
	const val INTERFACE = "avf_tap_fixed"

	/** Has to match the one in `shira-vm`. They refuse each other if it doesn't. */
	const val PROTOCOL_VERSION = 1

	/** App to VM, first thing on a new connection. See [hello]. */
	const val HELLO = 0x01

	/** Both ways: one IPv4 packet. */
	const val PACKET = 0x02

	const val LENGTH_BYTES = 4
	const val TYPE_BYTES = 1
	const val FRAME_OVERHEAD = LENGTH_BYTES + TYPE_BYTES

	/** The most a frame can count after its length: a type and the biggest IPv4 packet. */
	const val MAX_FRAME_BYTES = TYPE_BYTES + 65_535

	/** The VM's address inside the tunnel. The phone's own VPN is 10.219.0.1, and its resolver 10.219.0.2. */
	val VM_ADDRESS = ipv4(10, 219, 0, 3)

	private const val ADDRESS_BYTES = 4
	private const val MTU_BYTES = 2

	/**
	 * Tells `shira-vm` how to set up its device: this protocol's version in 1 byte, the VM's address and its
	 * resolver's in 4 bytes each, most significant first the way addresses are written, the MTU in 2
	 * little-endian bytes, then the app's version as text.
	 */
	fun hello(vmAddress: Int, resolver: Int, mtu: Int, appVersion: String): ByteArray {
		val version = appVersion.toByteArray()
		val payload = 1 + ADDRESS_BYTES * 2 + MTU_BYTES + version.size
		val frame = header(HELLO, payload)
		var at = FRAME_OVERHEAD
		frame[at++] = PROTOCOL_VERSION.toByte()
		putAddress(frame, at, vmAddress)
		at += ADDRESS_BYTES
		putAddress(frame, at, resolver)
		at += ADDRESS_BYTES
		Link.putShort(frame, at, mtu)
		at += MTU_BYTES
		version.copyInto(frame, at)
		return frame
	}

	/** A [PACKET] frame holding a copy of [length] bytes of [source] from [offset]. */
	fun packet(source: ByteArray, offset: Int, length: Int): ByteArray {
		val frame = header(PACKET, length)
		System.arraycopy(source, offset, frame, FRAME_OVERHEAD, length)
		return frame
	}

	private fun header(type: Int, payload: Int): ByteArray {
		val frame = ByteArray(FRAME_OVERHEAD + payload)
		Link.putInt(frame, 0, TYPE_BYTES + payload)
		frame[LENGTH_BYTES] = type.toByte()
		return frame
	}

	private fun putAddress(bytes: ByteArray, at: Int, address: Int) {
		bytes[at] = (address ushr 24).toByte()
		bytes[at + 1] = (address ushr 16).toByte()
		bytes[at + 2] = (address ushr 8).toByte()
		bytes[at + 3] = address.toByte()
	}
}

/** What a [VmFrameReader] finds. The payload is only valid during the call. */
fun interface VmFrameHandler {
	fun onFrame(type: Int, buffer: ByteArray, offset: Int, length: Int)
}

/** Finds whole frames in what comes off a VM connection, which can be split up in any way. */
class VmFrameReader(private val handler: VmFrameHandler) {
	private val buffer = ByteArray(BUFFER_BYTES)
	private var filled = 0

	/** Lets [read] fill the free space, and returns what it returned. Calls the handler for each frame. */
	inline fun fill(read: (ByteArray, Int, Int) -> Int): Int {
		val count = read(storage(), position(), space())
		if (count > 0) received(count)
		return count
	}

	@PublishedApi internal fun storage() = buffer

	@PublishedApi internal fun position() = filled

	@PublishedApi internal fun space() = buffer.size - filled

	@PublishedApi
	internal fun received(count: Int) {
		filled += count
		var offset = 0
		while (filled - offset >= VmLink.LENGTH_BYTES) {
			val length = Link.getInt(buffer, offset)
			if (length < VmLink.TYPE_BYTES || length > VmLink.MAX_FRAME_BYTES) {
				throw IOException("The VM sent a frame of $length bytes.")
			}
			if (filled - offset - VmLink.LENGTH_BYTES < length) break
			val start = offset + VmLink.LENGTH_BYTES
			handler.onFrame(
				buffer[start].toInt() and 0xff,
				buffer,
				start + VmLink.TYPE_BYTES,
				length - VmLink.TYPE_BYTES,
			)
			offset = start + length
		}
		if (offset > 0) {
			System.arraycopy(buffer, offset, buffer, 0, filled - offset)
			filled -= offset
		}
	}

	private companion object {
		const val BUFFER_BYTES = 4 * (VmLink.LENGTH_BYTES + VmLink.MAX_FRAME_BYTES)
	}
}
