package com.wonderhoylabs.shira.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.wonderhoylabs.shira.BuildConfig
import com.wonderhoylabs.shira.R
import com.wonderhoylabs.shira.bridge.VmLink
import com.wonderhoylabs.shira.tunnel.Tunnel
import com.wonderhoylabs.shira.tunnel.TunnelStats
import com.wonderhoylabs.shira.tunnel.ipv4ToString
import com.wonderhoylabs.shira.ui.MainActivity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread

/** Waits for the web page over adb, and runs a VPN that sends the phone's traffic to it. */
internal class ShiraVpnService :
	VpnService(),
	LoopHost {
	private var loop: BridgeLoop? = null
	private var worker: Thread? = null
	private var wakeLock: PowerManager.WakeLock? = null

	override var lastRefusal = ""
		private set

	override var vmFinder = VmNetworkFinder(::findVmNetwork)
		private set

	override val vmOwner by lazy(::terminalUid)

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			loop?.stop() ?: stopSelf()
			return Service.START_NOT_STICKY
		}
		// Android requires this quickly after starting, whatever else happens.
		showNotification(getString(R.string.notification_waiting))
		if (worker != null) return Service.START_NOT_STICKY

		ShiraState.setError(null)
		devVmListen(intent)?.let { address ->
			vmFinder = VmNetworkFinder { VmNetwork(address, 0, checkPeers = false) }
		}
		val created = BridgeLoop(this)
		loop = created
		worker = thread(name = "shira-bridge", isDaemon = true) { runLoop(created) }
		ShiraState.setStatus(ShiraStatus.Waiting)
		return Service.START_NOT_STICKY
	}

	private fun runLoop(created: BridgeLoop) {
		try {
			created.run()
		} catch (error: IOException) {
			Log.w(TAG, "couldn't listen", error)
			ShiraState.setError(getString(R.string.error_socket_taken))
		}
		ShiraState.setStatus(ShiraStatus.Off)
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	override fun onRevoke() {
		// Another VPN took over, or the user revoked this one in Settings.
		ShiraState.setError(getString(R.string.error_revoked))
		loop?.stop()
		super.onRevoke()
	}

	override fun onDestroy() {
		loop?.stop()
		releaseWakeLock()
		ShiraState.setStatus(ShiraStatus.Off)
		super.onDestroy()
	}

	override fun establish(): ParcelFileDescriptor? {
		val device = try {
			Builder()
				.setSession(getString(R.string.app_name))
				.setMtu(Tunnel.DEFAULT_MTU)
				.addAddress(ipv4ToString(Tunnel.TUN_ADDRESS), HOST_PREFIX)
				.addRoute(DEFAULT_ROUTE, 0)
				.addDnsServer(ipv4ToString(Tunnel.DNS_ADDRESS))
				// Shira itself only listens for the Linux VM, on the VM's network. Inside its own VPN, Android would
				// drop what the VM sends it, and send the answers into the VPN.
				.addDisallowedApplication(packageName)
				.setBlocking(false)
				.setMetered(false)
				.setConfigureIntent(openAppIntent())
				.establish()
		} catch (error: IllegalStateException) {
			lastRefusal = error.message.orEmpty()
			return null
		} catch (error: SecurityException) {
			lastRefusal = error.message.orEmpty()
			return null
		} catch (error: PackageManager.NameNotFoundException) {
			lastRefusal = error.message.orEmpty()
			return null
		}
		if (device == null) {
			lastRefusal = getString(R.string.error_not_prepared)
			return null
		}
		markAsConnected()
		return device
	}

	/**
	 * With no other network, Android can treat a VPN as having nothing underneath it and tell apps
	 * they're offline. Naming the VPN as its own underlying network tells them otherwise.
	 */
	private fun markAsConnected() {
		val connectivity = getSystemService(ConnectivityManager::class.java)
		repeat(VPN_LOOKUP_TRIES) {
			@Suppress("DEPRECATION")
			val vpn = connectivity.allNetworks.firstOrNull { network ->
				connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
			}
			if (vpn != null) {
				setUnderlyingNetworks(arrayOf(vpn))
				return
			}
			Thread.sleep(VPN_LOOKUP_DELAY_MILLIS)
		}
	}

	override fun onTunnelUp(server: String, stats: TunnelStats) {
		// The phone is plugged into a computer, so it's charging, and sleeping would only slow this down.
		if (wakeLock == null) {
			wakeLock = getSystemService(PowerManager::class.java)
				.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shira:tunnel")
				.apply { acquire() }
		}
		ShiraState.setStatus(ShiraStatus.Connected(server, stats))
		showNotification(getString(R.string.notification_connected, server))
	}

	override fun onTunnelDown() {
		releaseWakeLock()
		ShiraState.setStatus(ShiraStatus.Waiting)
		showNotification(getString(R.string.notification_waiting))
	}

	override fun uidOf(
		protocol: Int,
		sourceAddress: Int,
		sourcePort: Int,
		destinationAddress: Int,
		destinationPort: Int,
	): Int = try {
		getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
			protocol,
			InetSocketAddress(address(sourceAddress), sourcePort),
			InetSocketAddress(address(destinationAddress), destinationPort),
		)
	} catch (error: SecurityException) {
		Log.d(TAG, "couldn't look up an owner", error)
		-1
	} catch (error: IllegalArgumentException) {
		Log.d(TAG, "couldn't look up an owner", error)
		-1
	}

	/** The phone's IPv4 address on the interface Android makes for the Linux VM, if it's there. */
	private fun findVmNetwork(): VmNetwork? {
		val found = try {
			NetworkInterface.getByName(VmLink.INTERFACE)
		} catch (error: SocketException) {
			Log.d(TAG, "couldn't look for the VM's network", error)
			null
		} ?: return null
		val ipv4 = found.interfaceAddresses.firstOrNull { it.address is Inet4Address } ?: return null
		val bytes = ipv4.address.address
		val value = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
			((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
		return VmNetwork(value, ipv4.networkPrefixLength.toInt())
	}

	/** The Terminal's user ID, so that the page's log names it for the VM's connections. */
	private fun terminalUid(): Int {
		for (name in TERMINAL_PACKAGES) {
			try {
				return packageManager.getPackageUid(name, 0)
			} catch (_: PackageManager.NameNotFoundException) {
				continue
			}
		}
		return -1
	}

	/** Debug builds can listen for the VM on another address, so it can be tested without a VM. */
	private fun devVmListen(intent: Intent?): Int? {
		if (!BuildConfig.DEBUG) return null
		val text = intent?.getStringExtra(EXTRA_DEV_VM_LISTEN) ?: return null
		val parts = text.split('.').mapNotNull(String::toIntOrNull)
		if (parts.size != IPV4_PARTS || parts.any { it !in 0..MAX_OCTET }) return null
		return parts.fold(0) { value, part -> (value shl OCTET_BITS) or part }
	}

	private fun address(value: Int): InetAddress = InetAddress.getByAddress(
		byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
	)

	private fun releaseWakeLock() {
		wakeLock?.let { if (it.isHeld) it.release() }
		wakeLock = null
	}

	private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
		this,
		0,
		Intent(this, MainActivity::class.java),
		PendingIntent.FLAG_IMMUTABLE,
	)

	private fun showNotification(text: String) {
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL_ID,
				getString(R.string.notification_channel),
				NotificationManager.IMPORTANCE_LOW,
			),
		)
		val stop = PendingIntent.getService(
			this,
			1,
			Intent(this, ShiraVpnService::class.java).setAction(ACTION_STOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val notification: Notification = Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(getString(R.string.app_name))
			.setContentText(text)
			.setContentIntent(openAppIntent())
			.setOngoing(true)
			.addAction(Notification.Action.Builder(null, getString(R.string.deactivate), stop).build())
			.build()
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
		)
	}

	companion object {
		private const val TAG = "ShiraVpn"
		private const val ACTION_STOP = "com.wonderhoylabs.shira.STOP"
		private const val CHANNEL_ID = "status"
		private const val NOTIFICATION_ID = 1
		private const val HOST_PREFIX = 32
		private const val DEFAULT_ROUTE = "0.0.0.0"
		private const val VPN_LOOKUP_TRIES = 20
		private const val VPN_LOOKUP_DELAY_MILLIS = 100L
		private const val IPV4_PARTS = 4
		private const val MAX_OCTET = 255
		private const val OCTET_BITS = 8

		/** AOSP's name for the Terminal app, and the one Google ships on Pixels. */
		private val TERMINAL_PACKAGES = listOf(
			"com.android.virtualization.terminal",
			"com.google.android.virtualization.terminal",
		)

		/** Debug builds only: an address to listen for the VM on instead, like 127.0.0.1 with adb forward. */
		const val EXTRA_DEV_VM_LISTEN = "dev_vm_listen"

		fun start(context: Context, devVmListen: String? = null) {
			val intent = Intent(context, ShiraVpnService::class.java)
			if (devVmListen != null) intent.putExtra(EXTRA_DEV_VM_LISTEN, devVmListen)
			context.startForegroundService(intent)
		}

		fun stop(context: Context) {
			context.startService(Intent(context, ShiraVpnService::class.java).setAction(ACTION_STOP))
		}
	}
}
