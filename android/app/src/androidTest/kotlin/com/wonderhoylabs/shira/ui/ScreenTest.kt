package com.wonderhoylabs.shira.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.wonderhoylabs.shira.tunnel.TunnelStats
import com.wonderhoylabs.shira.vpn.ShiraStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Draws every state of the screen, checks what it says and what its buttons do, and saves a picture. */
class ScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ScreenActions(
        onActivate = { calls += "activate" },
        onDeactivate = { calls += "deactivate" },
        onDismissError = { calls += "dismiss" },
    )

    private fun show(status: ShiraStatus, error: String? = null, dark: Boolean = false) {
        // The spinner never stops, so the test's clock only moves when it's told to.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ShiraTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ShiraScreen(ScreenState(status, error), actions)
                }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MILLIS)
    }

    private fun click(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(FRAME_MILLIS)
    }

    private fun picture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun connected() = ShiraStatus.Connected(
        "wisp.example.com",
        TunnelStats().apply {
            bytesDown = 52_428_800
            bytesUp = 1_572_864
            connections = 14
        },
    )

    @Test
    fun off() {
        show(ShiraStatus.Off)
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("How to connect").assertIsDisplayed()
        picture("off")
        click("Activate")
        assertEquals(listOf("activate"), calls)
    }

    @Test
    fun waiting() {
        show(ShiraStatus.Waiting)
        rule.onNodeWithText("Waiting for your computer").assertIsDisplayed()
        picture("waiting")
        click("Deactivate")
        assertEquals(listOf("deactivate"), calls)
    }

    @Test
    fun connectedShowsTheServerAndTraffic() {
        show(connected())
        rule.onNodeWithText("Connected").assertIsDisplayed()
        rule.onNodeWithText("Through wisp.example.com").assertIsDisplayed()
        rule.onNodeWithText("50.0 MB").assertIsDisplayed()
        rule.onNodeWithText("1.5 MB").assertIsDisplayed()
        rule.onNodeWithText("14").assertIsDisplayed()
        picture("connected")
        click("Deactivate")
        assertEquals(listOf("deactivate"), calls)
    }

    @Test
    fun errorCanBeDismissed() {
        show(ShiraStatus.Off, error = "Android needs your OK before Shira can act as a VPN.")
        rule.onNodeWithText("Android needs your OK before Shira can act as a VPN.").assertIsDisplayed()
        picture("error")
        click("Dismiss")
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun darkOff() {
        show(ShiraStatus.Off, dark = true)
        picture("off-dark")
    }

    @Test
    fun darkConnected() {
        show(connected(), dark = true)
        picture("connected-dark")
    }

    @Test
    fun darkError() {
        show(ShiraStatus.Waiting, error = "Another VPN has taken over. Open Shira on the phone and activate it again.", dark = true)
        picture("error-dark")
    }

    private companion object {
        const val FRAME_MILLIS = 400L
    }
}
