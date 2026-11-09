package com.wonderhoylabs.shira.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderhoylabs.shira.BuildConfig
import com.wonderhoylabs.shira.R
import com.wonderhoylabs.shira.vpn.ShiraState
import com.wonderhoylabs.shira.vpn.ShiraVpnService

class MainActivity : ComponentActivity() {
	private var activateWhenResumed = false
	private var devVmListen: String? = null

	private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			startService()
		} else {
			ShiraState.setError(getString(R.string.error_refused))
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		// Turning the phone re-creates this with the same intent, which shouldn't activate anything.
		if (savedInstanceState == null) handleIntent(intent)
		setContent {
			val status by ShiraState.status.collectAsStateWithLifecycle()
			val error by ShiraState.error.collectAsStateWithLifecycle()
			val actions = remember {
				ScreenActions(
					onActivate = ::activate,
					onDeactivate = { ShiraVpnService.stop(this) },
					onDismissError = { ShiraState.setError(null) },
				)
			}
			ShiraTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					ShiraScreen(ScreenState(status, error), actions)
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	override fun onResume() {
		super.onResume()
		// A locked phone creates this screen without showing it, and a hidden screen can't start the service.
		if (activateWhenResumed) {
			activateWhenResumed = false
			activate()
		}
	}

	/** The web page opens this screen through the name only adb may use, to say it should turn on. */
	private fun handleIntent(intent: Intent?) {
		if (BuildConfig.DEBUG) {
			intent?.getStringExtra(ShiraVpnService.EXTRA_DEV_VM_LISTEN)?.let { devVmListen = it }
		}
		if (intent?.component?.className == "$packageName$ACTIVATE_ALIAS") activateWhenResumed = true
	}

	private fun activate() {
		val prompt = VpnService.prepare(this)
		if (prompt == null) startService() else consent.launch(prompt)
	}

	private fun startService() {
		// Not any earlier, or this would land on top of the VPN's own question.
		if (
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
		) {
			requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
		}
		ShiraVpnService.start(this, devVmListen)
	}

	private companion object {
		const val ACTIVATE_ALIAS = ".Activate"
		const val REQUEST_NOTIFICATIONS = 1
	}
}
