package com.mediasaver

import androidx.lifecycle.ViewModel
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
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        _state.value = _state.value.copy(url = value)
    }

    fun clear() {
        _state.value = UiState()
    }

    fun toggleAllFormats() {
        _state.value = _state.value.copy(showAllFormats = !_state.value.showAllFormats)
    }

    /** Called when a link arrives from the share sheet. */
    fun submitSharedUrl(url: String) {
        _state.value = _state.value.copy(url = url)
        probe()
    }

    fun probe() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.value = _state.value.copy(error = "Paste a link first.")
            return
        }
        _state.value = _state.value.copy(looking = true, error = null, info = null)
        viewModelScope.launch {
            try {
                val info = Extractor.probe(url)
                _state.value = _state.value.copy(looking = false, info = info, error = null)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(looking = false, error = Extractor.humanError(t))
            }
        }
    }
}
