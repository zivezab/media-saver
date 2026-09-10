package com.mediasaver

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PLAY = "com.mediasaver.action.PLAY"
        const val EXTRA_PLAY_URI = "play_uri"
        const val EXTRA_PLAY_MIME = "play_mime"
        const val EXTRA_PLAY_NAME = "play_name"
        const val EXTRA_PLAY_LOCATION = "play_location"
    }

    private var pendingSharedUrl: String? = null
    private var pendingPlay: SavedItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingSharedUrl = urlFromIntent(intent)
        pendingPlay = playFromIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MediaSaverTheme {
                val vm: MainViewModel = viewModel()
                SaverScreen(
                    viewModel = vm,
                    consumeSharedUrl = { pendingSharedUrl.also { pendingSharedUrl = null } },
                    consumePlayRequest = { pendingPlay.also { pendingPlay = null } },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedUrl = urlFromIntent(intent)
        val play = playFromIntent(intent)

        // Only rebuild for an intent that actually carries something. Returning
        // to the app from the launcher delivers a plain MAIN intent here, and
        // recreating on that tore down whatever was on screen - closing the
        // player and stopping playback mid-video.
        if (sharedUrl == null && play == null) return

        pendingSharedUrl = sharedUrl
        pendingPlay = play
        // Re-enter composition so the new intent is picked up.
        recreate()
    }

    /** A "tap to play" notification carries the file it just saved. */
    private fun playFromIntent(intent: Intent?): SavedItem? {
        if (intent?.action != ACTION_PLAY) return null
        val uri = intent.getStringExtra(EXTRA_PLAY_URI) ?: return null
        val name = intent.getStringExtra(EXTRA_PLAY_NAME).orEmpty()
        return SavedItem(
            id = uri,
            title = name,
            displayName = name,
            uri = uri,
            mimeType = intent.getStringExtra(EXTRA_PLAY_MIME) ?: "video/*",
            location = intent.getStringExtra(EXTRA_PLAY_LOCATION).orEmpty(),
            sizeBytes = 0,
            savedAt = System.currentTimeMillis(),
        )
    }

    private fun urlFromIntent(intent: Intent?): String? {
        intent ?: return null
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        return Extractor.extractUrl(text)
    }
}

@Composable
fun MediaSaverTheme(content: @Composable () -> Unit) {
    val settings by Settings.state.collectAsState()
    val dark = when (settings.theme) {
        Settings.ThemeMode.LIGHT -> false
        Settings.ThemeMode.DARK -> true
        Settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current

    val colors = when (settings.accent) {
        Settings.Accent.DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
        else -> {
            val seed = when (settings.accent) {
                Settings.Accent.GREEN -> Color(0xFF2E7D53)
                Settings.Accent.PURPLE -> Color(0xFF6C4BB6)
                Settings.Accent.ORANGE -> Color(0xFFB4541E)
                else -> Color(0xFF2F6BFF)
            }
            if (dark) {
                darkColorScheme(primary = seed, secondary = seed, tertiary = seed)
            } else {
                lightColorScheme(primary = seed, secondary = seed, tertiary = seed)
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
