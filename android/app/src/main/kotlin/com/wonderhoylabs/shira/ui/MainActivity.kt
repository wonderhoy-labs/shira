package com.wonderhoylabs.shira.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderhoylabs.shira.R
import com.wonderhoylabs.shira.vpn.ShiraVpnService
import com.wonderhoylabs.shira.vpn.VpnActivation
import com.wonderhoylabs.shira.vpn.VpnState

class MainActivity : ComponentActivity() {
	private val activation = VpnActivation(this, ShiraVpnService::class.java) { getString(R.string.error_refused) }

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		// Turning the phone re-creates this with the same intent, which shouldn't activate anything.
		if (savedInstanceState == null) activation.onIntent(intent)
		setContent {
			val status by VpnState.status.collectAsStateWithLifecycle()
			val error by VpnState.error.collectAsStateWithLifecycle()
			val actions = remember {
				ScreenActions(
					onActivate = activation::activate,
					onDeactivate = activation::deactivate,
					onDismissError = { VpnState.setError(null) },
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
		activation.onIntent(intent)
	}

	override fun onResume() {
		super.onResume()
		activation.onResume()
	}
}
