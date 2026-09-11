package com.mediasaver

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PLAY = "com.mediasaver.action.PLAY"
        const val EXTRA_PLAY_URI = "play_uri"
        const val EXTRA_PLAY_MIME = "play_mime"
        const val EXTRA_PLAY_NAME = "play_name"
        const val EXTRA_PLAY_LOCATION = "play_location"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // An Activity keeps its launch intent for life, and gets it back when it
        // is rebuilt - on rotation, and when Android restores it after freeing
        // memory. Reading the shared link on every onCreate therefore submitted
        // it again each time: rotating after a share downloaded the file a
        // second and a third time. Only a genuinely fresh launch counts, and a
        // relaunch from the recents list is not one.
        val fresh = savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh) handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MediaSaverTheme {
                SaverScreen(viewModel = vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handled in place. This used to recreate the Activity to get the new
        // link into composition, which tore down whatever was on screen and was
        // one more way for the link to be read twice.
        handleIntent(intent)
    }

    /**
     * Pass a shared link or a play request to the ViewModel, then replace the
     * intent with a plain one so nothing downstream can read it a second time.
     */
    private fun handleIntent(intent: Intent?) {
        val url = urlFromIntent(intent)
        val play = playFromIntent(intent)
        when {
            url != null -> vm.submitSharedUrl(url)
            play != null -> vm.requestPlay(play)
            else -> return
        }
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
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
