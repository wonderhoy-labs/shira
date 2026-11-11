package com.wonderhoylabs.shira.vpn

import com.wonderhoylabs.shira.BuildConfig
import com.wonderhoylabs.shira.R
import com.wonderhoylabs.shira.bridge.Link
import com.wonderhoylabs.shira.ui.MainActivity

/** Shira's VPN: the library's LinkVpnService with Shira's socket name, words and icon. */
internal class ShiraVpnService : LinkVpnService() {
	override val socketName = Link.SOCKET_NAME
	override val appVersion: String = BuildConfig.VERSION_NAME
	override val notificationIcon = R.drawable.ic_notification
	override val appActivity = MainActivity::class.java

	override fun texts() = LinkTexts(
		appName = getString(R.string.app_name),
		notificationChannel = getString(R.string.notification_channel),
		waiting = getString(R.string.notification_waiting),
		connected = { server -> getString(R.string.notification_connected, server) },
		deactivate = getString(R.string.deactivate),
		socketTaken = getString(R.string.error_socket_taken),
		revoked = getString(R.string.error_revoked),
		notPrepared = getString(R.string.error_not_prepared),
	)
}
