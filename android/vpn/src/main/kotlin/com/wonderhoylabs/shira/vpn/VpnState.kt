package com.wonderhoylabs.shira.vpn

import com.wonderhoylabs.shira.tunnel.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a LinkVpnService is doing. */
sealed interface VpnStatus {
	/** The service isn't running. */
	data object Off : VpnStatus

	/** The service is listening, and nothing's using the VPN yet, so the phone's network is untouched. */
	data object Waiting : VpnStatus

	/** A page is attached and the VPN is up, carrying traffic out through [server]. */
	data class Connected(val server: String, val stats: TunnelStats) : VpnStatus
}

/** What the app's LinkVpnService is doing, for its screen to show. An app has one service, so one state. */
object VpnState {
	private val mutableStatus = MutableStateFlow<VpnStatus>(VpnStatus.Off)
	private val mutableError = MutableStateFlow<String?>(null)

	val status: StateFlow<VpnStatus> = mutableStatus.asStateFlow()

	/** Something to tell the person, such as Android refusing the VPN, until it's dismissed. */
	val error: StateFlow<String?> = mutableError.asStateFlow()

	fun setStatus(status: VpnStatus) {
		mutableStatus.value = status
	}

	fun setError(message: String?) {
		mutableError.value = message
	}
}
