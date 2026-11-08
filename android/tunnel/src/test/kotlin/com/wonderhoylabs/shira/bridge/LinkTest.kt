package com.wonderhoylabs.shira.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LinkTest {
	private class Seen(val type: Int, val stream: Int, val payload: ByteArray)

	private val seen = mutableListOf<Seen>()
	private val reader = FrameReader { type, stream, buffer, offset, length ->
		seen += Seen(type, stream, buffer.copyOfRange(offset, offset + length))
	}

	private fun feed(bytes: ByteArray) {
		reader.fill { target, offset, _ ->
			bytes.copyInto(target, offset)
			bytes.size
		}
	}

	@Test
	fun connectIsLaidOutLikeWisp() {
		val frame = Link.connect(7, Link.STREAM_TCP, 443, "1.2.3.4")
		val expected = byteArrayOf(
			15, 0, 0, 0, // length of what follows
			0x01, // CONNECT
			7, 0, 0, 0, // stream
			0x01, // TCP
			0xbb.toByte(), 0x01, // port
		) + "1.2.3.4".toByteArray()
		assertArrayEquals(expected, frame)
	}

	@Test
	fun findsPacketsHoweverTheyAreSplit() {
		val first = Link.data(1, byteArrayOf(1, 2, 3), 0, 3)
		val second = Link.close(1, Link.CLOSE_VOLUNTARY)
		val joined = first + second
		for (byte in joined) feed(byteArrayOf(byte))

		assertEquals(2, seen.size)
		assertEquals(Link.DATA, seen[0].type)
		assertEquals(1, seen[0].stream)
		assertArrayEquals(byteArrayOf(1, 2, 3), seen[0].payload)
		assertEquals(Link.CLOSE, seen[1].type)
		assertArrayEquals(byteArrayOf(Link.CLOSE_VOLUNTARY.toByte()), seen[1].payload)
	}

	@Test
	fun findsSeveralPacketsInOneRead() {
		feed(Link.status(Link.STATUS_UP) + Link.hello("1.2.3") + Link.meta(9, 10123, Link.META_DNS, 28, "a.example"))
		assertEquals(listOf(Link.STATUS, Link.HELLO, Link.META), seen.map { it.type })
		assertEquals(Link.PROTOCOL_VERSION, seen[1].payload[0].toInt())
		assertEquals("1.2.3", String(seen[1].payload, 1, seen[1].payload.size - 1))
		assertEquals(10123, Link.getInt(seen[2].payload, 0))
	}

	@Test
	fun refusesAnImpossibleLength() {
		val bad = ByteArray(Link.LENGTH_BYTES)
		Link.putInt(bad, 0, Link.MAX_PACKET_BYTES + 1)
		assertThrows(IOException::class.java) { feed(bad) }
	}
}
