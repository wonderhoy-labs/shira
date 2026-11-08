package com.wonderhoylabs.shira.tunnel

import com.wonderhoylabs.shira.bridge.Link
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsAndUdpTest {
	private val h = TunnelHarness()
	private val remote = ipv4(93, 184, 216, 34)

	private fun name(name: String): ByteArray {
		val out = ByteArrayOutputStream()
		for (label in name.split('.')) {
			out.write(label.length)
			out.write(label.toByteArray())
		}
		out.write(0)
		return out.toByteArray()
	}

	private fun query(host: String, id: Int = 0x1234, type: Int = 1, edns: Int = 0): ByteArray {
		val out = ByteArrayOutputStream()
		out.write(
			byteArrayOf(
				(id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0,
				if (edns >
					0
				) {
					1
				} else {
					0
				},
			),
		)
		out.write(name(host))
		out.write(byteArrayOf(0, type.toByte(), 0, 1))
		if (edns > 0) out.write(byteArrayOf(0, 0, 41, (edns shr 8).toByte(), edns.toByte(), 0, 0, 0, 0, 0, 0))
		return out.toByteArray()
	}

	private fun answer(query: ByteArray, address: Int, ttl: Int = 60, padding: Int = 0): ByteArray {
		val end = 12 + name(hostOf(query)).size + 4
		val out = ByteArrayOutputStream()
		out.write(query, 0, 12)
		out.write(query, 12, end - 12)
		out.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1))
		out.write(byteArrayOf((ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte()))
		out.write(byteArrayOf(0, 4))
		out.write(
			byteArrayOf((address ushr 24).toByte(), (address ushr 16).toByte(), (address ushr 8).toByte(), address.toByte()),
		)
		repeat(padding) { out.write(0) }
		val bytes = out.toByteArray()
		bytes[2] = 0x81.toByte()
		bytes[3] = 0x80.toByte()
		bytes[6] = 0
		bytes[7] = 1
		bytes[10] = 0
		bytes[11] = 0
		return bytes
	}

	private fun hostOf(query: ByteArray): String = DnsResolver.question(query)!!.name

	private fun framed(message: ByteArray) = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message

	/** Asks a question and returns the stream it went out on, after checking how it went out. */
	private fun ask(q: ByteArray, port: Int = 50_000): Int {
		h.appUdp(port, Tunnel.DNS_ADDRESS, 53, q)
		val connect = h.nextFrame(Link.CONNECT)
		assertEquals(Link.STREAM_TCP, connect.payload[0].toInt())
		assertEquals(53, connect.payload[1].toInt())
		assertEquals("1.1.1.1", String(connect.payload, 3, connect.payload.size - 3))
		h.nextFrame(Link.META)
		assertArrayEquals(framed(q), h.nextFrame(Link.DATA).payload)
		return connect.stream
	}

	@Test
	fun sendsQuestionsToCloudflareThroughWisp() {
		val q = query("example.com")
		h.appUdp(50_000, Tunnel.DNS_ADDRESS, 53, q)

		val connect = h.nextFrame(Link.CONNECT)
		assertEquals("1.1.1.1", String(connect.payload, 3, connect.payload.size - 3))
		val meta = h.nextFrame(Link.META)
		assertEquals(Link.META_DNS, meta.payload[4].toInt())
		assertEquals(1, meta.payload[5].toInt())
		assertEquals("example.com", String(meta.payload, 7, meta.payload.size - 7))
		assertArrayEquals(framed(q), h.nextFrame(Link.DATA).payload)
	}

	@Test
	fun answersFromTheAddressItWasAskedAt() {
		val q = query("example.com")
		val stream = ask(q)
		val a = answer(q, remote)
		h.pageData(stream, framed(a))

		val reply = h.nextDatagram()
		assertEquals(Tunnel.DNS_ADDRESS, reply.source)
		assertEquals(53, reply.sourcePort)
		assertEquals(50_000, reply.destinationPort)
		assertArrayEquals(a, reply.payload)
		assertEquals(Link.CLOSE_VOLUNTARY, h.nextFrame(Link.CLOSE).payload[0].toInt())
	}

	@Test
	fun copesWithAnAnswerInPieces() {
		val q = query("example.com")
		val stream = ask(q)
		val whole = framed(answer(q, remote))
		h.pageData(stream, whole.copyOfRange(0, 1))
		h.pageData(stream, whole.copyOfRange(1, 20))
		assertTrue(h.toApp.isEmpty())
		h.pageData(stream, whole.copyOfRange(20, whole.size))
		assertEquals(53, h.nextDatagram().sourcePort)
	}

	@Test
	fun answersRepeatsFromTheCache() {
		val q = query("example.com")
		val stream = ask(q)
		h.pageData(stream, framed(answer(q, remote)))
		h.nextDatagram()
		h.toPage.clear()

		val again = query("EXAMPLE.com", id = 0x7777)
		h.appUdp(50_001, Tunnel.DNS_ADDRESS, 53, again)
		assertTrue(h.toPage.isEmpty())
		val reply = h.nextDatagram()
		assertEquals(50_001, reply.destinationPort)
		// Its own ID and its own spelling, in case it's checking them.
		assertEquals(0x77.toByte(), reply.payload[0])
		assertEquals('E'.code.toByte(), reply.payload[13])
	}

	@Test
	fun theCacheExpires() {
		val q = query("example.com")
		h.pageData(ask(q), framed(answer(q, remote, ttl = 60)))
		h.nextDatagram()
		h.toPage.clear()

		h.now += 61_000
		ask(q)
	}

	@Test
	fun aFailedLookupGetsAFailureAnswer() {
		val q = query("example.com")
		val stream = ask(q)
		h.pageClose(stream, 0x42)
		val reply = h.nextDatagram()
		assertEquals(q[0], reply.payload[0])
		assertEquals(2, reply.payload[3].toInt() and 0x0f)
		assertTrue(reply.payload[2].toInt() and 0x80 != 0)
	}

	@Test
	fun aSlowLookupGetsAFailureAnswer() {
		val q = query("example.com")
		val stream = ask(q)
		h.advance(4_000)
		assertTrue(h.toApp.isEmpty())
		h.advance(1_100)
		assertEquals(2, h.nextDatagram().payload[3].toInt() and 0x0f)
		assertEquals(stream, h.nextFrame(Link.CLOSE).stream)
	}

	@Test
	fun aTooBigAnswerTellsTheClientToUseTcp() {
		val q = query("example.com")
		val stream = ask(q)
		h.pageData(stream, framed(answer(q, remote, padding = 600)))
		val reply = h.nextDatagram()
		assertEquals(0x02, reply.payload[2].toInt() and 0x02)
		assertEquals(0, reply.payload[7].toInt())
		assertTrue(reply.payload.size < 100)
	}

	@Test
	fun aClientThatSaidItTakesMoreGetsTheWholeAnswer() {
		val q = query("example.com", edns = 1232)
		val stream = ask(q)
		val a = answer(q, remote, padding = 600)
		h.pageData(stream, framed(a))
		assertEquals(a.size, h.nextDatagram().payload.size)
	}

	@Test
	fun tcpQuestionsGoStraightToCloudflare() {
		h.appSyn(50_000, Tunnel.DNS_ADDRESS, 53, 1_000)
		val connect = h.nextFrame(Link.CONNECT)
		assertEquals("1.1.1.1", String(connect.payload, 3, connect.payload.size - 3))
	}

	@Test
	fun secureDnsToTheResolverIsRefused() {
		h.appSyn(50_000, Tunnel.DNS_ADDRESS, 853, 1_000)
		assertTrue(h.nextSegment().tcp.isRst)
		assertTrue(h.toPage.isEmpty())
	}

	@Test
	fun remembersWhichNameAnAddressCameFrom() {
		val q = query("www.example.com")
		h.pageData(ask(q), framed(answer(q, remote)))
		h.nextDatagram()
		h.toPage.clear()

		h.appSyn(40_000, remote, 443, 1_000)
		val connect = h.nextFrame(Link.CONNECT)
		// The connection still goes to the address the app asked for.
		assertEquals("93.184.216.34", String(connect.payload, 3, connect.payload.size - 3))
		val meta = h.nextFrame(Link.META)
		assertEquals(Link.META_CONNECTION, meta.payload[4].toInt())
		assertEquals("www.example.com", String(meta.payload, 7, meta.payload.size - 7))
	}

	@Test
	fun carriesUdpThroughItsOwnStream() {
		h.appUdp(50_000, remote, 443, "hello".toByteArray())
		val connect = h.nextFrame(Link.CONNECT)
		assertEquals(Link.STREAM_UDP, connect.payload[0].toInt())
		h.nextFrame(Link.META)
		assertArrayEquals("hello".toByteArray(), h.nextFrame(Link.DATA).payload)

		h.appUdp(50_000, remote, 443, "again".toByteArray())
		assertEquals(connect.stream, h.nextFrame(Link.DATA).stream)

		h.pageData(connect.stream, "reply".toByteArray())
		val reply = h.nextDatagram()
		assertEquals(remote, reply.source)
		assertEquals(443, reply.sourcePort)
		assertEquals(50_000, reply.destinationPort)
		assertArrayEquals("reply".toByteArray(), reply.payload)
	}

	@Test
	fun aQuietUdpStreamGetsClosed() {
		h.appUdp(50_000, remote, 443, "hello".toByteArray())
		val stream = h.nextFrame(Link.CONNECT).stream
		h.toPage.clear()
		h.advance(119_000)
		assertTrue(h.toPage.isEmpty())
		h.advance(1_100)
		assertEquals(stream, h.nextFrame(Link.CLOSE).stream)
		assertEquals(0, h.stats.connections)
	}

	@Test
	fun udpOnAServerWithoutItFailsFast() {
		val noUdp = TunnelHarness(udp = false)
		noUdp.appUdp(50_000, remote, 443, "quic".toByteArray())
		assertTrue(noUdp.toPage.isEmpty())
		val icmp = noUdp.toApp.removeFirst()
		assertEquals(PROTOCOL_ICMP, u8(icmp, 9))
		assertEquals(remote, i32(icmp, 12))
		assertEquals(3, u8(icmp, 20))
		assertEquals(3, u8(icmp, 21))
	}

	@Test
	fun dropsMulticast() {
		h.appUdp(50_000, ipv4(239, 255, 255, 250), 1900, "ssdp".toByteArray())
		assertTrue(h.toPage.isEmpty())
		assertTrue(h.toApp.isEmpty())
	}
}
