package com.wonderhoylabs.shira.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Turns an app's LinkVpnService on from its activity: asks Android for the person's OK the first time, then the
 * notification permission, then starts the service. Make it as a field of the activity, so it registers before the
 * activity starts.
 *
 * The page turns the app on by opening an `<activity-alias android:name=".Activate">` of the activity, which only adb
 * may open (`android:permission="android.permission.DUMP"`). Pass the activity's intents to [onIntent] and call
 * [onResume] from the activity's, and that alias activates the VPN.
 */
class VpnActivation(
	private val activity: ComponentActivity,
	private val service: Class<out LinkVpnService>,
	/** Says what to tell the person when they don't give their OK. */
	private val refusedMessage: () -> String,
) {
	private var activateWhenResumed = false

	private val consent = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (it.resultCode == Activity.RESULT_OK) startService() else VpnState.setError(refusedMessage())
	}

	/** Asks for the OK if Android needs it, then starts the service. */
	fun activate() {
		val prompt = VpnService.prepare(activity)
		if (prompt == null) startService() else consent.launch(prompt)
	}

	/** Turns the service off. */
	fun deactivate() {
		LinkVpnService.stop(activity, service)
	}

	/** Notes whether [intent] came through the `.Activate` alias. Skip it when the activity is only being re-created. */
	fun onIntent(intent: Intent?) {
		if (intent?.component?.className == "${activity.packageName}$ACTIVATE_ALIAS") activateWhenResumed = true
	}

	/** A locked phone creates the activity without showing it, and a hidden activity can't start the service. */
	fun onResume() {
		if (activateWhenResumed) {
			activateWhenResumed = false
			activate()
		}
	}

	private fun startService() {
		// Not any earlier, or this would land on top of the VPN's own question.
		if (
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
		) {
			activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
		}
		LinkVpnService.start(activity, service)
	}

	private companion object {
		const val ACTIVATE_ALIAS = ".Activate"
		const val REQUEST_NOTIFICATIONS = 1
	}
}
