package com.wonderhoylabs.shira.vpn

import com.wonderhoylabs.shira.tunnel.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ShiraStatus {
    /** The service isn't running. */
    data object Off : ShiraStatus

    /** The service is listening, and nothing's using the VPN yet, so the phone's network is untouched. */
    data object Waiting : ShiraStatus

    /** A web page is attached and the VPN is up. */
    data class Connected(val server: String, val stats: TunnelStats) : ShiraStatus
}

/** What the service is doing, for the screen to show. */
object ShiraState {
    private val mutableStatus = MutableStateFlow<ShiraStatus>(ShiraStatus.Off)
    private val mutableError = MutableStateFlow<String?>(null)

    val status: StateFlow<ShiraStatus> = mutableStatus.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun setStatus(status: ShiraStatus) {
        mutableStatus.value = status
    }

    fun setError(message: String?) {
        mutableError.value = message
    }
}
