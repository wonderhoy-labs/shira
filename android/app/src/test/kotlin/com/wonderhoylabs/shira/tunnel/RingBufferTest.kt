package com.wonderhoylabs.shira.tunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferTest {
	@Test
	fun keepsBytesInOrderAcrossTheEnd() {
		val ring = RingBuffer()
		val chunk = ByteArray(3_000) { it.toByte() }
		ring.append(chunk, 0, chunk.size)
		ring.drop(2_500)
		// This one wraps around a 4 KB buffer without having to grow it.
		ring.append(chunk, 0, 3_000)
		assertEquals(3_500, ring.size)

		val out = ByteArray(3_500)
		ring.peek(0, out, 0, 3_500)
		assertArrayEquals(chunk.copyOfRange(2_500, 3_000) + chunk, out)
	}

	@Test
	fun growsWithoutLosingAnything() {
		val ring = RingBuffer()
		val chunk = ByteArray(10_000) { it.toByte() }
		ring.append(chunk, 0, 1_000)
		ring.drop(900)
		ring.append(chunk, 1_000, 9_000)
		val out = ByteArray(9_100)
		ring.peek(0, out, 0, 9_100)
		assertArrayEquals(chunk.copyOfRange(900, 10_000), out)
	}

	@Test
	fun readsFromTheMiddle() {
		val ring = RingBuffer()
		val chunk = ByteArray(100) { it.toByte() }
		ring.append(chunk, 0, 100)
		val out = ByteArray(10)
		ring.peek(50, out, 0, 10)
		assertArrayEquals(chunk.copyOfRange(50, 60), out)
	}
}
