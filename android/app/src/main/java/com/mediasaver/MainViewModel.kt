package com.mediasaver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val url: String = "",
    val looking: Boolean = false,
    val error: String? = null,
    val info: MediaInfo? = null,
    val showAllFormats: Boolean = false,
    /** A short confirmation, e.g. that a link was handed to the background. */
    val notice: String? = null,
    /** A file to open in the player, from a "tap to play" notification. */
    val playRequest: SavedItem? = null,
)

/**
 * An AndroidViewModel so it can hand work straight to the download service,
 * without needing the UI to be on screen to do it.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        _state.value = _state.value.copy(url = value, notice = null)
    }

    fun clear() {
        _state.value = UiState()
    }

    fun toggleAllFormats() {
        _state.value = _state.value.copy(showAllFormats = !_state.value.showAllFormats)
    }

    /** A link arriving from the share sheet or the clipboard. */
    fun submitSharedUrl(url: String) {
        _state.value = _state.value.copy(url = url)
        probe()
    }

    fun requestPlay(item: SavedItem) {
        _state.value = _state.value.copy(playRequest = item)
    }

    fun playRequestHandled() {
        _state.value = _state.value.copy(playRequest = null)
    }

    fun probe() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.value = _state.value.copy(error = "Paste a link first.")
            return
        }

        if (Settings.state.value.autoDownloadBest) {
            // Handed to the foreground service, which looks the link up and
            // downloads it. Nothing further depends on this screen, so the user
            // can switch apps immediately and the download still happens.
            DownloadService.enqueueAuto(getApplication(), url)
            _state.value = UiState(
                notice = "Downloading the best quality in the background. You can switch apps.",
            )
            return
        }

        _state.value = _state.value.copy(looking = true, error = null, info = null, notice = null)
        viewModelScope.launch {
            try {
                val info = Extractor.probe(url)
                _state.value = _state.value.copy(looking = false, info = info, error = null)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(looking = false, error = Extractor.humanError(t, url))
            }
        }
    }
}
