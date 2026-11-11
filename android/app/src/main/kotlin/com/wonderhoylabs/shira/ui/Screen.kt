package com.wonderhoylabs.shira.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.shira.R
import com.wonderhoylabs.shira.tunnel.TunnelStats
import com.wonderhoylabs.shira.vpn.VpnStatus
import kotlinx.coroutines.delay

private const val STATS_REFRESH_MILLIS = 1_000L

data class ScreenState(val status: VpnStatus, val error: String?)

class ScreenActions(val onActivate: () -> Unit, val onDeactivate: () -> Unit, val onDismissError: () -> Unit)

@Composable
fun ShiraScreen(state: ScreenState, actions: ScreenActions) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.safeDrawingPadding(),
		contentAlignment = Alignment.TopCenter,
	) {
		Column(
			modifier = Modifier
				.widthIn(max = 560.dp)
				.fillMaxWidth()
				.padding(horizontal = 20.dp, vertical = 24.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			BrandHeader(subtitle = stringResource(R.string.tagline), logo = painterResource(R.drawable.logo))
			AnimatedVisibility(visible = state.error != null) {
				state.error?.let { ErrorCard(it, actions.onDismissError) }
			}
			StatusCard(state.status)
			when (state.status) {
				VpnStatus.Off -> Button(onClick = actions.onActivate, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.activate))
				}

				else -> FilledTonalButton(onClick = actions.onDeactivate, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.deactivate))
				}
			}
			if (state.status !is VpnStatus.Connected) Steps()
		}
	}
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
		Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onErrorContainer,
				modifier = Modifier.padding(end = 8.dp),
			)
			TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
				Text(stringResource(R.string.dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
			}
		}
	}
}

@Composable
private fun StatusCard(status: VpnStatus) {
	val connected = status is VpnStatus.Connected
	Card(
		colors = CardDefaults.cardColors(
			containerColor = if (connected) {
				MaterialTheme.colorScheme.primaryContainer
			} else {
				MaterialTheme.colorScheme.surfaceContainer
			},
		),
	) {
		Column(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				if (status == VpnStatus.Waiting) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
				}
				Text(
					text = stringResource(
						when (status) {
							VpnStatus.Off -> R.string.status_off
							VpnStatus.Waiting -> R.string.status_waiting
							is VpnStatus.Connected -> R.string.status_connected
						},
					),
					style = MaterialTheme.typography.titleLarge,
				)
			}
			when (status) {
				VpnStatus.Off -> Hint(stringResource(R.string.status_off_body))

				VpnStatus.Waiting -> Hint(stringResource(R.string.status_waiting_body))

				is VpnStatus.Connected -> {
					Text(
						text = stringResource(R.string.status_connected_body, status.server),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(10.dp))
					Stats(status.stats)
				}
			}
		}
	}
}

@Composable
private fun Hint(text: String) {
	Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Stats(stats: TunnelStats) {
	var down by remember { mutableLongStateOf(stats.bytesDown) }
	var up by remember { mutableLongStateOf(stats.bytesUp) }
	var connections by remember { mutableIntStateOf(stats.connections) }
	LaunchedEffect(stats) {
		while (true) {
			down = stats.bytesDown
			up = stats.bytesUp
			connections = stats.connections
			delay(STATS_REFRESH_MILLIS)
		}
	}
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Stat(stringResource(R.string.stat_down), formatBytes(down))
		Stat(stringResource(R.string.stat_up), formatBytes(up))
		Stat(stringResource(R.string.stat_connections), connections.toString())
	}
}

@Composable
private fun Stat(label: String, value: String) {
	Column {
		Text(
			text = value,
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
		)
	}
}

@Composable
private fun Steps() {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			Text(text = stringResource(R.string.steps_title), style = MaterialTheme.typography.titleMedium)
			Step(1, stringResource(R.string.step_cable))
			Step(2, stringResource(R.string.step_site, stringResource(R.string.site_address)))
			Step(3, stringResource(R.string.step_server))
		}
	}
}

@Composable
private fun Step(number: Int, text: String) {
	Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Surface(
			shape = CircleShape,
			color = MaterialTheme.colorScheme.secondaryContainer,
			contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.size(26.dp),
		) {
			Box(contentAlignment = Alignment.Center) {
				Text(text = number.toString(), style = MaterialTheme.typography.labelLarge)
			}
		}
		Text(
			text = text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
