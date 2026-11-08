package com.wonderhoylabs.shira.tunnel

import com.wonderhoylabs.shira.bridge.Link
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TcpFlowTest {
	private lateinit var h: TunnelHarness
	private val remote = ipv4(93, 184, 216, 34)
	private val port = 40_000
	private val appIsn = 1_000
	private var appSeq = 0
	private var serverSeq = 0
	private var stream = 0

	@Before
	fun setUp() {
		h = TunnelHarness()
	}

	/** Gets a connection to the point where both sides can send. */
	private fun connect(credits: Int = 8) {
		h = TunnelHarness(credits)
		h.appSyn(port, remote, 443, appIsn)
		val connect = h.nextFrame(Link.CONNECT)
		stream = connect.stream
		h.nextFrame(Link.META)
		val synAck = h.nextSegment()
		serverSeq = synAck.tcp.sequence + 1
		appSeq = appIsn + 1
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK)
	}

	private fun appSend(bytes: ByteArray) {
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK or FLAG_PSH, payload = bytes)
		appSeq += bytes.size
	}

	@Test
	fun answersSynAtOnceAndOpensAStream() {
		h.appSyn(port, remote, 443, appIsn)

		val connect = h.nextFrame(Link.CONNECT)
		assertEquals(Link.STREAM_TCP, connect.payload[0].toInt())
		assertEquals(443, (connect.payload[1].toInt() and 0xff) or ((connect.payload[2].toInt() and 0xff) shl 8))
		assertEquals("93.184.216.34", String(connect.payload, 3, connect.payload.size - 3))
		val meta = h.nextFrame(Link.META)
		assertEquals(TunnelHarness.APP_UID, Link.getInt(meta.payload, 0))

		val synAck = h.nextSegment()
		assertEquals(FLAG_SYN or FLAG_ACK, synAck.tcp.flags)
		assertEquals(appIsn + 1, synAck.tcp.acknowledgment)
		assertEquals(remote, synAck.ip.source)
		assertEquals(1460, synAck.tcp.maximumSegment)
		assertEquals(6, synAck.tcp.windowScale)
		assertEquals(1, h.stats.connections)
	}

	@Test
	fun repeatsSynAckIfTheAppAsksAgain() {
		h.appSyn(port, remote, 443, appIsn)
		h.nextSegment()
		h.appSyn(port, remote, 443, appIsn)
		assertEquals(FLAG_SYN or FLAG_ACK, h.nextSegment().tcp.flags)
		// It's the same connection, so there's no second stream.
		h.nextFrame(Link.CONNECT)
		h.nextFrame(Link.META)
		assertTrue(h.toPage.isEmpty())
	}

	@Test
	fun sendsSynAckAgainAfterATimeout() {
		h.appSyn(port, remote, 443, appIsn)
		h.nextSegment()
		h.advance(250)
		assertEquals(FLAG_SYN or FLAG_ACK, h.nextSegment().tcp.flags)
	}

	@Test
	fun uploadsWhatTheAppSendsAndAcksIt() {
		connect()
		appSend("hello".toByteArray())

		val data = h.nextFrame(Link.DATA)
		assertEquals(stream, data.stream)
		assertArrayEquals("hello".toByteArray(), data.payload)
		val ack = h.nextSegment()
		assertEquals(appSeq, ack.tcp.acknowledgment)
		assertEquals(5, h.stats.bytesUp)
	}

	@Test
	fun waitsForCreditsBeforeUploadingMore() {
		connect(credits = 1)
		appSend("first".toByteArray())
		assertArrayEquals("first".toByteArray(), h.nextFrame(Link.DATA).payload)

		appSend("second".toByteArray())
		assertTrue(h.toPage.isEmpty())

		h.pageContinue(stream, 4)
		assertArrayEquals("second".toByteArray(), h.nextFrame(Link.DATA).payload)
	}

	@Test
	fun holdsBackWhenTheCableIsFull() {
		connect()
		h.pageQueued = 2 * 1024 * 1024
		appSend("wait".toByteArray())
		assertTrue(h.toPage.isEmpty())

		h.pageQueued = 0
		h.tunnel.pumpUploads(h.now)
		assertArrayEquals("wait".toByteArray(), h.nextFrame(Link.DATA).payload)
	}

	@Test
	fun ignoresRepeatsAndAcksAgain() {
		connect()
		appSend("abc".toByteArray())
		h.nextFrame(Link.DATA)
		h.nextSegment()

		h.appSegment(port, remote, 443, appSeq - 3, serverSeq, FLAG_ACK or FLAG_PSH, payload = "abc".toByteArray())
		assertTrue(h.toPage.isEmpty())
		assertEquals(appSeq, h.nextSegment().tcp.acknowledgment)
	}

	@Test
	fun takesOnlyTheNewPartOfAnOverlap() {
		connect()
		appSend("abc".toByteArray())
		h.nextFrame(Link.DATA)
		h.nextSegment()

		h.appSegment(port, remote, 443, appSeq - 1, serverSeq, FLAG_ACK or FLAG_PSH, payload = "cde".toByteArray())
		assertArrayEquals("de".toByteArray(), h.nextFrame(Link.DATA).payload)
		assertEquals(appSeq + 2, h.nextSegment().tcp.acknowledgment)
	}

	@Test
	fun dropsAnythingOutOfOrderAndAsksForTheGap() {
		connect()
		h.appSegment(port, remote, 443, appSeq + 100, serverSeq, FLAG_ACK or FLAG_PSH, payload = "later".toByteArray())
		assertTrue(h.toPage.isEmpty())
		assertEquals(appSeq, h.nextSegment().tcp.acknowledgment)
	}

	@Test
	fun cutsDownloadsIntoSegmentsTheAppCanTake() {
		connect()
		h.pageData(stream, ByteArray(2_500) { it.toByte() })

		val first = h.nextSegment()
		val second = h.nextSegment()
		val third = h.nextSegment()
		assertEquals(1000, first.payload.size)
		assertEquals(1000, second.payload.size)
		assertEquals(500, third.payload.size)
		assertEquals(serverSeq, first.tcp.sequence)
		assertEquals(serverSeq + 1000, second.tcp.sequence)
		assertEquals(serverSeq + 2000, third.tcp.sequence)
		assertEquals(1000.toByte(), second.payload[0])
		assertEquals(2500, h.stats.bytesDown)
	}

	@Test
	fun staysInsideTheAppsWindow() {
		connect()
		// A window of 500 at the app's scale of 2 is 2000 bytes.
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK, window = 500)
		h.pageData(stream, ByteArray(5_000))
		assertEquals(1000, h.nextSegment().payload.size)
		assertEquals(1000, h.nextSegment().payload.size)
		assertTrue(h.toApp.isEmpty())

		h.appSegment(port, remote, 443, appSeq, serverSeq + 2000, FLAG_ACK, window = 500)
		assertEquals(1000, h.nextSegment().payload.size)
		assertEquals(1000, h.nextSegment().payload.size)
		assertTrue(h.toApp.isEmpty())
	}

	@Test
	fun asksAboutAClosedWindowUntilItOpens() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK, window = 0)
		h.pageData(stream, ByteArray(100))
		assertTrue(h.toApp.isEmpty())

		h.advance(250)
		val probe = h.nextSegment()
		assertEquals(1, probe.payload.size)
		assertEquals(serverSeq, probe.tcp.sequence)

		// Many rounds later it's still asking, not giving up on the app.
		repeat(30) {
			h.advance(6_000)
			assertEquals(1, h.nextSegment().payload.size)
		}

		h.appSegment(port, remote, 443, appSeq, serverSeq + 1, FLAG_ACK, window = 1000)
		val rest = h.nextSegment()
		assertEquals(99, rest.payload.size)
		assertEquals(serverSeq + 1, rest.tcp.sequence)
	}

	@Test
	fun sendsAgainWhenAcksDontCome() {
		connect()
		h.pageData(stream, "lost".toByteArray())
		h.nextSegment()

		h.advance(100)
		assertTrue(h.toApp.isEmpty())
		h.advance(150)
		val again = h.nextSegment()
		assertEquals(serverSeq, again.tcp.sequence)
		assertArrayEquals("lost".toByteArray(), again.payload)
	}

	@Test
	fun sendsAgainAfterThreeRepeatedAcks() {
		connect()
		h.pageData(stream, ByteArray(3_000))
		repeat(3) { h.nextSegment() }

		repeat(3) { h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK) }
		val again = h.nextSegment()
		assertEquals(serverSeq, again.tcp.sequence)
		assertEquals(1000, again.payload.size)
	}

	@Test
	fun givesUpOnAnAppThatNeverAnswers() {
		connect()
		h.pageData(stream, "x".toByteArray())
		h.nextSegment()
		repeat(13) { h.advance(6_000) }
		h.toApp.lastOrNull()?.let {
			val ip = Ipv4Header()
			ip.parse(it, it.size)
			val tcp = TcpHeader()
			tcp.parse(it, ip)
			assertTrue(tcp.isRst)
		}
		assertEquals(0, h.stats.connections)
		assertEquals(Link.CLOSE_CLIENT_ERROR, h.nextFrame(Link.CLOSE).payload[0].toInt() and 0xff)
	}

	@Test
	fun finishesCleanlyWhenTheServerClosesFirst() {
		connect()
		h.pageData(stream, "bye".toByteArray())
		val data = h.nextSegment()
		h.pageClose(stream, Link.CLOSE_VOLUNTARY)
		val fin = h.nextSegment()
		assertTrue(fin.tcp.isFin)
		assertEquals(data.tcp.sequence + 3, fin.tcp.sequence)

		h.appSegment(port, remote, 443, appSeq, fin.tcp.sequence + 1, FLAG_ACK or FLAG_FIN)
		val lastAck = h.nextSegment()
		assertEquals(appSeq + 1, lastAck.tcp.acknowledgment)
		assertEquals(0, h.stats.connections)
		// The server already closed it, so nothing goes back to the page.
		assertTrue(h.toPage.isEmpty())
	}

	@Test
	fun deliversEverythingBeforeTheFin() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK, window = 250)
		h.pageData(stream, ByteArray(1_500))
		assertEquals(1000, h.nextSegment().payload.size)
		h.pageClose(stream, Link.CLOSE_VOLUNTARY)
		assertTrue(h.toApp.isEmpty())

		h.appSegment(port, remote, 443, appSeq, serverSeq + 1000, FLAG_ACK, window = 250)
		assertEquals(500, h.nextSegment().payload.size)
		assertTrue(h.nextSegment().tcp.isFin)
	}

	@Test
	fun keepsTheStreamOpenAfterTheAppFinishesSending() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK or FLAG_FIN)
		assertEquals(appSeq + 1, h.nextSegment().tcp.acknowledgment)
		appSeq += 1
		assertTrue(h.toPage.isEmpty())

		// The answer still gets through, because the app may only have shut down its sending side.
		h.pageData(stream, "answer".toByteArray())
		assertArrayEquals("answer".toByteArray(), h.nextSegment().payload)
		h.appSegment(port, remote, 443, appSeq, serverSeq + 6, FLAG_ACK)

		h.pageClose(stream, Link.CLOSE_VOLUNTARY)
		val fin = h.nextSegment()
		h.appSegment(port, remote, 443, appSeq, fin.tcp.sequence + 1, FLAG_ACK)
		assertEquals(0, h.stats.connections)
	}

	@Test
	fun closesAFinishedAppsStreamAfterItGoesQuiet() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK or FLAG_FIN)
		h.nextSegment()
		h.advance(9_000)
		assertEquals(1, h.stats.connections)
		h.advance(1_000)
		assertEquals(0, h.stats.connections)
		assertEquals(Link.CLOSE, h.nextFrame(Link.CLOSE).type)
	}

	@Test
	fun aResetFromTheAppClosesTheStream() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_RST)
		val close = h.nextFrame(Link.CLOSE)
		assertEquals(stream, close.stream)
		assertEquals(Link.CLOSE_VOLUNTARY, close.payload[0].toInt())
		assertEquals(0, h.stats.connections)
	}

	@Test
	fun aFailedStreamResetsTheConnection() {
		connect()
		h.pageClose(stream, 0x42)
		assertTrue(h.nextSegment().tcp.isRst)
		assertEquals(0, h.stats.connections)
		assertTrue(h.toPage.isEmpty())
	}

	@Test
	fun refusesSegmentsForNothing() {
		h.appSegment(port, remote, 443, appIsn, 5_000, FLAG_ACK)
		val reset = h.nextSegment()
		assertTrue(reset.tcp.isRst)
		assertEquals(5_000, reset.tcp.sequence)
		assertTrue(h.toPage.isEmpty())
	}

	@Test
	fun answersAResetWithSilence() {
		h.appSegment(port, remote, 443, appIsn, 5_000, FLAG_RST)
		assertTrue(h.toApp.isEmpty())
	}

	@Test
	fun keepsAnAppThatsJustSlowerThanTheCable() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK, window = 1000)
		val chunk = ByteArray(20 * 1024 * 1024)
		h.pageData(stream, chunk)
		// Holding more than anything would call reasonable, and still taking data. It isn't touched.
		h.now += 1_900
		h.appSegment(port, remote, 443, appSeq, serverSeq + 1000, FLAG_ACK, window = 1000)
		h.now += 1_900
		h.tunnel.abortStalledDownload(h.now)
		assertEquals(1, h.stats.connections)
	}

	@Test
	fun dropsAStreamWhoseAppStoppedReading() {
		connect()
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_ACK, window = 0)
		h.pageData(stream, ByteArray(1024 * 1024))
		h.now += Tunnel.STALLED_MILLIS - 1
		h.tunnel.abortStalledDownload(h.now)
		assertEquals(1, h.stats.connections)

		h.now += 1
		h.tunnel.abortStalledDownload(h.now)
		assertEquals(0, h.stats.connections)
		assertEquals(0, h.tunnel.downloadBuffered)
		assertEquals(Link.CLOSE_CLIENT_ERROR, h.nextFrame(Link.CLOSE).payload[0].toInt() and 0xff)
	}

	@Test
	fun leavesAloneAConnectionWithNothingWaiting() {
		connect()
		h.now += 60_000
		h.tunnel.abortStalledDownload(h.now)
		assertEquals(1, h.stats.connections)

		// It's been quiet for a long time, but data that's only just arrived isn't stalled.
		h.pageData(stream, ByteArray(100))
		h.tunnel.abortStalledDownload(h.now)
		assertEquals(1, h.stats.connections)
	}

	@Test
	fun countsWhatsBeingHeldForApps() {
		connect()
		h.pageData(stream, ByteArray(1_000))
		assertEquals(1_000, h.tunnel.downloadBuffered)
		h.appSegment(port, remote, 443, appSeq, serverSeq + 400, FLAG_ACK)
		assertEquals(600, h.tunnel.downloadBuffered)
		h.appSegment(port, remote, 443, appSeq, serverSeq, FLAG_RST)
		assertEquals(0, h.tunnel.downloadBuffered)
	}

	@Test
	fun sequenceNumbersCanWrapAround() {
		h = TunnelHarness()
		val start = Int.MAX_VALUE - 1
		h.appSyn(port, remote, 443, start)
		val connect = h.nextFrame(Link.CONNECT)
		h.nextFrame(Link.META)
		val server = h.nextSegment().tcp.sequence + 1
		h.appSegment(port, remote, 443, start + 1, server, FLAG_ACK)
		h.appSegment(port, remote, 443, start + 1, server, FLAG_ACK or FLAG_PSH, payload = ByteArray(10))
		assertEquals(10, h.nextFrame(Link.DATA).payload.size)
		assertEquals(connect.stream, connect.stream)
		assertEquals(start + 11, h.nextSegment().tcp.acknowledgment)
	}

	@Test
	fun anIpv6PacketIsIgnored() {
		val packet = ByteArray(60)
		packet[0] = 0x60
		h.tunnel.onAppPacket(packet, packet.size, h.now)
		assertTrue(h.toApp.isEmpty())
		assertFalse(h.toPage.isNotEmpty())
	}
}
