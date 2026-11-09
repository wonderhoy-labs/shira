package com.wonderhoylabs.shira.tunnel

import com.wonderhoylabs.shira.bridge.Link
import com.wonderhoylabs.shira.bridge.VmLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's VPN and the Linux VM, each with its own tunnel, on one page. */
class SharedTunnelsTest {
	private val streams = StreamIds()
	private val stats = TunnelStats()
	private val phone = TunnelHarness(stats = stats, streams = streams)
	private val vm = TunnelHarness(appAddress = VmLink.VM_ADDRESS, stats = stats, streams = streams)
	private val remote = ipv4(93, 184, 216, 34)

	@Test
	fun theSameConnectionFromBothGetsTwoStreams() {
		phone.appSyn(40_000, remote, 443, 100)
		vm.appSyn(40_000, remote, 443, 200)
		val fromPhone = phone.nextFrame(Link.CONNECT).stream
		val fromVm = vm.nextFrame(Link.CONNECT).stream

		assertNotEquals(fromPhone, fromVm)
		assertTrue(phone.tunnel.owns(fromPhone))
		assertFalse(phone.tunnel.owns(fromVm))
		assertTrue(vm.tunnel.owns(fromVm))
		assertFalse(vm.tunnel.owns(fromPhone))
		assertEquals(2, stats.connections)
	}

	@Test
	fun eachTunnelAnswersItsOwnDevice() {
		vm.appSyn(40_000, remote, 443, 200)
		val stream = vm.nextFrame(Link.CONNECT).stream
		vm.nextFrame(Link.META)
		val synAck = vm.nextSegment()
		assertEquals(VmLink.VM_ADDRESS, synAck.ip.destination)
		assertEquals(remote, synAck.ip.source)
		assertTrue(phone.toApp.isEmpty())

		vm.appSegment(40_000, remote, 443, 201, synAck.tcp.sequence + 1, FLAG_ACK)
		vm.pageData(stream, "hello".toByteArray())
		val data = vm.nextSegment()
		assertEquals(VmLink.VM_ADDRESS, data.ip.destination)
		assertEquals("hello", String(data.payload))
		assertEquals(5L, stats.bytesDown)
	}

	@Test
	fun closeEndsEveryStreamOfThatTunnelOnly() {
		phone.appSyn(40_000, remote, 443, 100)
		val phoneStream = phone.nextFrame(Link.CONNECT).stream
		vm.appSyn(40_001, remote, 443, 200)
		val tcpStream = vm.nextFrame(Link.CONNECT).stream
		vm.nextFrame(Link.META)
		vm.appUdp(40_002, remote, 3478, byteArrayOf(1, 2, 3))
		val udpStream = vm.nextFrame(Link.CONNECT).stream
		vm.nextFrame(Link.META)
		vm.nextFrame(Link.DATA)
		assertEquals(3, stats.connections)

		vm.tunnel.close()

		val closed = vm.toPage.map {
			assertEquals(Link.CLOSE, it.type)
			it.stream
		}.toSet()
		assertEquals(setOf(tcpStream, udpStream), closed)
		assertFalse(vm.tunnel.owns(tcpStream))
		assertFalse(vm.tunnel.owns(udpStream))
		assertTrue(phone.tunnel.owns(phoneStream))
		assertEquals(1, stats.connections)
	}

	@Test
	fun streamIdsCountUpFromOne() {
		val ids = StreamIds()
		assertEquals(listOf(1, 2, 3), List(3) { ids.take() })
	}
}
