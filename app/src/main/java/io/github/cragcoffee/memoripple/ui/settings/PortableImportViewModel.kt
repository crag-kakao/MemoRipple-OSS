package io.github.cragcoffee.memoripple.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.portableexport.PortableImportEngine
import io.github.cragcoffee.memoripple.ui.components.ImportWording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 書き出したZIPを取り込む: pick the file, see what it holds, agree, watch it arrive. Nothing
 * imports before the person has read the counts and said so, and existing data is only ever
 * added to.
 */
class PortableImportViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: PortableImportEngine =
        (application as MemoRippleApplication).portableImportEngine

    sealed interface UiState {
        data object Idle : UiState
        data object Previewing : UiState
        data class Confirm(
            val preview: PortableImportEngine.ImportPreview,
            val source: Uri,
        ) : UiState

        data class Importing(val done: Int, val total: Int) : UiState
        data class Finished(val message: String) : UiState
    }

    sealed interface Effect {
        data object OpenDocument : Effect
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 1)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var importJob: Job? = null

    fun open() {
        if (_state.value !is UiState.Idle && _state.value !is UiState.Finished) return
        _effects.tryEmit(Effect.OpenDocument)
    }

    fun onSourceSelected(uri: Uri?) {
        if (uri == null) return
        _state.value = UiState.Previewing
        viewModelScope.launch {
            val preview = engine.preview(uri)
            _state.value = if (preview == null) {
                UiState.Finished(ImportWording.PORTABLE_NOT_AN_EXPORT)
            } else {
                UiState.Confirm(preview, uri)
            }
        }
    }

    fun confirmImport() {
        val current = _state.value as? UiState.Confirm ?: return
        if (importJob?.isActive == true) return
        _state.value = UiState.Importing(0, current.preview.memos)
        importJob = viewModelScope.launch {
            val result = try {
                engine.import(current.source) { done, total ->
                    _state.value = UiState.Importing(done, total)
                }
            } catch (cancelled: CancellationException) {
                _state.value = UiState.Finished(ImportWording.PORTABLE_CANCELLED)
                throw cancelled
            }
            _state.value = UiState.Finished(
                when (result) {
                    is PortableImportEngine.ImportResult.Done ->
                        ImportWording.portableDone(result.memos, result.photos, result.skippedPhotos)

                    PortableImportEngine.ImportResult.NotAPortableExport ->
                        ImportWording.PORTABLE_NOT_AN_EXPORT

                    PortableImportEngine.ImportResult.TooLarge ->
                        "ファイルが大きすぎるため取り込めませんでした"

                    PortableImportEngine.ImportResult.Failed ->
                        "取り込めませんでした"
                },
            )
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun dismiss() {
        if (_state.value is UiState.Confirm || _state.value is UiState.Previewing ||
            _state.value is UiState.Finished
        ) {
            _state.value = UiState.Idle
        }
    }
}
