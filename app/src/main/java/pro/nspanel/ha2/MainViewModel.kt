package pro.nspanel.ha2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pro.nspanel.ha2.data.AppSettings
import pro.nspanel.ha2.data.PanelConfig
import pro.nspanel.ha2.data.SettingsRepository
import pro.nspanel.ha2.data.toManualPanelConfig
import pro.nspanel.ha2.panel.PanelYamlParser
import pro.nspanel.ha2.panel.YamlConfigFetcher

class MainViewModel(
    private val repository: SettingsRepository,
    private val yamlFetcher: YamlConfigFetcher = YamlConfigFetcher(),
) : ViewModel() {

    val settings = repository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(),
        )

    // Holds a transient live override while a slider is being dragged.
    // Cleared as soon as the value is persisted.
    private val liveConfig = MutableStateFlow<PanelConfig?>(null)

    val panelConfig = combine(
        repository.settings.map { s ->
            if (s.panelYamlUrl.isBlank()) s.toManualPanelConfig()
            else PanelYamlParser.parse(s.lastPanelYamlRaw)
        },
        liveConfig,
    ) { stored, live -> live ?: stored }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PanelConfig.DEFAULT,
        )

    /** Apply a draft immediately to the screen without persisting yet. */
    fun applyLive(draft: AppSettings) {
        if (draft.panelYamlUrl.isBlank()) liveConfig.value = draft.toManualPanelConfig()
    }

    /** Persist the draft and clear the live override. */
    fun saveDraft(draft: AppSettings) {
        liveConfig.value = null
        viewModelScope.launch {
            repository.update {
                if (draft.panelYamlUrl.isBlank()) draft.copy(lastPanelYamlRaw = "") else draft
            }
        }
    }

    fun fetchPanelYaml(
        url: String,
        onResult: (error: String?, downloadedCharCount: Int?) -> Unit,
    ) {
        viewModelScope.launch {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) { onResult(null, null); return@launch }
            val result = yamlFetcher.download(trimmed)
            result.fold(
                onSuccess = { body ->
                    repository.update { it.copy(lastPanelYamlRaw = body, panelYamlUrl = trimmed) }
                    onResult(null, body.length)
                },
                onFailure = { e -> onResult(e.message ?: "Download failed", null) },
            )
        }
    }
}

