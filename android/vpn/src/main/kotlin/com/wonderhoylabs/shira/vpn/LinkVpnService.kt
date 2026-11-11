package com.wonderhoylabs.shira.vpn

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.wonderhoylabs.shira.tunnel.Tunnel
import com.wonderhoylabs.shira.tunnel.TunnelStats
import com.wonderhoylabs.shira.tunnel.ipv4ToString
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/** The words a LinkVpnService shows, in the app's own language and name. */
data class LinkTexts(
	/** The app's name, for the notification and the VPN's session in Settings. */
	val appName: String,
	val notificationChannel: String,
	val waiting: String,
	/** Says the phone is online through [server], the name the page gave. */
	val connected: (server: String) -> String,
	val deactivate: String,
	/** Another app holds the socket name. */
	val socketTaken: String,
	/** Another VPN took over, or someone turned this one off in Settings. */
	val revoked: String,
	/** Android won't set up the VPN, usually because another one is preferred. */
	val notPrepared: String,
)

/**
 * Waits for a page over adb, and runs a VPN that sends the phone's traffic to it. Subclass it with the app's socket
 * name, words and icon, declare the subclass in the manifest with `android.permission.BIND_VPN_SERVICE`, the
 * `android.net.VpnService` action and `foregroundServiceType="connectedDevice"`, and start it with [start] once
 * [VpnService.prepare] has the person's OK. Its state is in [VpnState].
 */
abstract class LinkVpnService :
	VpnService(),
	LoopHost {
	/** The abstract socket name the page opens as `localabstract:<name>`. It has to be this app's alone. */
	protected abstract val socketName: String

	/** The app's version, which the page hears when it connects. */
	protected abstract val appVersion: String

	/** A monochrome drawable for the status bar. */
	protected abstract val notificationIcon: Int

	/** The screen the notification opens. */
	protected abstract val appActivity: Class<out Activity>

	protected abstract fun texts(): LinkTexts

	private var loop: BridgeLoop? = null
	private var worker: Thread? = null
	private var wakeLock: PowerManager.WakeLock? = null

	final override var lastRefusal = ""
		private set

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			loop?.stop() ?: stopSelf()
			return Service.START_NOT_STICKY
		}
		// Android requires this quickly after starting, whatever else happens.
		showNotification(texts().waiting)
		if (worker != null) return Service.START_NOT_STICKY

		VpnState.setError(null)
		val created = BridgeLoop(this, socketName, appVersion)
		loop = created
		worker = thread(name = "link-bridge", isDaemon = true) { runLoop(created) }
		VpnState.setStatus(VpnStatus.Waiting)
		return Service.START_NOT_STICKY
	}

	private fun runLoop(created: BridgeLoop) {
		try {
			created.run()
		} catch (error: IOException) {
			Log.w(TAG, "couldn't listen", error)
			VpnState.setError(texts().socketTaken)
		}
		VpnState.setStatus(VpnStatus.Off)
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	override fun onRevoke() {
		// Another VPN took over, or the user revoked this one in Settings.
		VpnState.setError(texts().revoked)
		loop?.stop()
		super.onRevoke()
	}

	override fun onDestroy() {
		loop?.stop()
		releaseWakeLock()
		VpnState.setStatus(VpnStatus.Off)
		super.onDestroy()
	}

	final override fun establish(): ParcelFileDescriptor? {
		val device = try {
			Builder()
				.setSession(texts().appName)
				.setMtu(Tunnel.DEFAULT_MTU)
				.addAddress(ipv4ToString(Tunnel.TUN_ADDRESS), HOST_PREFIX)
				.addRoute(DEFAULT_ROUTE, 0)
				.addDnsServer(ipv4ToString(Tunnel.DNS_ADDRESS))
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
		}
		if (device == null) {
			lastRefusal = texts().notPrepared
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

	final override fun onTunnelUp(server: String, stats: TunnelStats) {
		// The phone is plugged into a computer, so it's charging, and sleeping would only slow this down.
		if (wakeLock == null) {
			wakeLock = getSystemService(PowerManager::class.java)
				.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:tunnel")
				.apply { acquire() }
		}
		VpnState.setStatus(VpnStatus.Connected(server, stats))
		showNotification(texts().connected(server))
	}

	final override fun onTunnelDown() {
		releaseWakeLock()
		VpnState.setStatus(VpnStatus.Waiting)
		showNotification(texts().waiting)
	}

	final override fun uidOf(
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
		Intent(this, appActivity),
		PendingIntent.FLAG_IMMUTABLE,
	)

	private fun showNotification(text: String) {
		val texts = texts()
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_ID, texts.notificationChannel, NotificationManager.IMPORTANCE_LOW),
		)
		val stop = PendingIntent.getService(
			this,
			1,
			Intent(this, javaClass).setAction(ACTION_STOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val notification: Notification = Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(notificationIcon)
			.setContentTitle(texts.appName)
			.setContentText(text)
			.setContentIntent(openAppIntent())
			.setOngoing(true)
			.addAction(Notification.Action.Builder(null, texts.deactivate, stop).build())
			.build()
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
		)
	}

	companion object {
		private const val TAG = "LinkVpn"
		private const val ACTION_STOP = "com.wonderhoylabs.shira.vpn.STOP"
		private const val CHANNEL_ID = "status"
		private const val NOTIFICATION_ID = 1
		private const val HOST_PREFIX = 32
		private const val DEFAULT_ROUTE = "0.0.0.0"
		private const val VPN_LOOKUP_TRIES = 20
		private const val VPN_LOOKUP_DELAY_MILLIS = 100L

		/** Starts [service], once [VpnService.prepare] has returned null or the person said OK. */
		fun start(context: Context, service: Class<out LinkVpnService>) {
			context.startForegroundService(Intent(context, service))
		}

		/** Asks [service] to turn off. It lets the page know first. */
		fun stop(context: Context, service: Class<out LinkVpnService>) {
			context.startService(Intent(context, service).setAction(ACTION_STOP))
		}
	}
}
