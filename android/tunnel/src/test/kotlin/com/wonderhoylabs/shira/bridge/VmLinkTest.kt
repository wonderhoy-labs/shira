package com.wonderhoylabs.shira.bridge

import com.wonderhoylabs.shira.tunnel.ipv4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** The same bytes are checked by shira-vm's tests, in crates/shira-vm/src/link.rs. */
class VmLinkTest {
	private class Seen(val type: Int, val payload: ByteArray)

	private val seen = mutableListOf<Seen>()
	private val reader = VmFrameReader { type, buffer, offset, length ->
		seen += Seen(type, buffer.copyOfRange(offset, offset + length))
	}

	private fun feed(bytes: ByteArray) {
		reader.fill { target, offset, _ ->
			bytes.copyInto(target, offset)
			bytes.size
		}
	}

	@Test
	fun helloIsLaidOutAsDocumented() {
		val frame = VmLink.hello(VmLink.VM_ADDRESS, ipv4(10, 219, 0, 2), 9000, "0.3.0")
		val expected = byteArrayOf(
			17, 0, 0, 0, // length of what follows
			0x01, // HELLO
			1, // protocol version
			10, 219.toByte(), 0, 3, // the VM's address
			10, 219.toByte(), 0, 2, // its resolver
			0x28, 0x23, // MTU 9000
		) + "0.3.0".toByteArray()
		assertArrayEquals(expected, frame)
	}

	@Test
	fun packetCarriesItsBytes() {
		val packet = byteArrayOf(0x45, 0, 0, 20, 9)
		assertArrayEquals(byteArrayOf(6, 0, 0, 0, 0x02, 0x45, 0, 0, 20, 9), VmLink.packet(packet, 0, 5))
		assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0x02, 0, 20), VmLink.packet(packet, 2, 2))
	}

	@Test
	fun findsFramesHoweverTheyAreSplit() {
		val first = VmLink.packet(byteArrayOf(1, 2, 3), 0, 3)
		// The biggest packet there can be.
		val second = VmLink.packet(ByteArray(65_535), 0, 65_535)
		val all = first + second
		for (cut in listOf(1, 4, 5, 7, 8, 9, 100)) {
			seen.clear()
			feed(all.copyOfRange(0, cut))
			feed(all.copyOfRange(cut, all.size))
			assertEquals(2, seen.size)
			assertEquals(VmLink.PACKET, seen[0].type)
			assertArrayEquals(byteArrayOf(1, 2, 3), seen[0].payload)
			assertEquals(65_535, seen[1].payload.size)
		}
	}

	@Test
	fun refusesFramesThatCantBeRight() {
		assertThrows(IOException::class.java) { feed(byteArrayOf(0, 0, 0, 0)) }
		val huge = ByteArray(VmLink.LENGTH_BYTES)
		Link.putInt(huge, 0, VmLink.MAX_FRAME_BYTES + 1)
		assertThrows(IOException::class.java) {
			VmFrameReader { _, _, _, _ -> }.fill { target, offset, _ ->
				huge.copyInto(target, offset)
				huge.size
			}
		}
	}
}
