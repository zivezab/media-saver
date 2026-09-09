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

    private var pendingSharedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingSharedUrl = urlFromIntent(intent)

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
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedUrl = urlFromIntent(intent)
        // Re-enter composition so the new link is picked up.
        recreate()
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
