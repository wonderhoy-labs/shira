package com.wonderhoylabs.shira.vpn

import com.wonderhoylabs.shira.tunnel.ipv4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmNetworkTest {
	private val network = VmNetwork(ipv4(192, 168, 7, 1), 24)

	@Test
	fun takesTheVmsSideOfItsNetwork() {
		assertTrue(network.allows(ipv4(192, 168, 7, 42)))
		assertTrue(network.allows(ipv4(192, 168, 7, 254)))
	}

	@Test
	fun refusesThePhoneItself() {
		// Other apps on the phone connecting to this address would come from it.
		assertFalse(network.allows(ipv4(192, 168, 7, 1)))
	}

	@Test
	fun refusesEverywhereElse() {
		assertFalse(network.allows(ipv4(192, 168, 8, 42)))
		assertFalse(network.allows(ipv4(10, 0, 0, 5)))
		assertFalse(network.allows(ipv4(127, 0, 0, 1)))
	}

	@Test
	fun refusesEveryoneWithoutAPrefix() {
		assertFalse(VmNetwork(ipv4(192, 168, 7, 1), 0).allows(ipv4(192, 168, 7, 42)))
		assertFalse(VmNetwork(ipv4(192, 168, 7, 1), 32).allows(ipv4(192, 168, 7, 42)))
	}

	@Test
	fun theDevelopmentAddressTakesAnyone() {
		assertTrue(VmNetwork(ipv4(127, 0, 0, 1), 0, checkPeers = false).allows(ipv4(127, 0, 0, 1)))
	}
}
