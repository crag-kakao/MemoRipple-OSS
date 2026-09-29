package io.github.cragcoffee.memoripple.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
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
 * The 読める形式で書き出す flow: confirm what leaves, pick where, watch it go, stop it if
 * wanted. Lives in a ViewModel so a rotation mid-export changes nothing — the job runs in
 * [viewModelScope], and the screen only ever renders the state it finds here.
 */
class PortableExportViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: PortableExportEngine =
        (application as MemoRippleApplication).portableExportEngine

    enum class Format { ZIP, PDF }

    sealed interface UiState {
        data object Idle : UiState
        data object Preparing : UiState
        data class Ready(
            val counts: PortableExportEngine.ExportCounts,
            val includeTrash: Boolean,
            val stripPhotoMetadata: Boolean,
            val format: Format,
        ) : UiState

        data class Exporting(val done: Int, val total: Int) : UiState
        data class Finished(val message: String) : UiState
    }

    sealed interface Effect {
        data class CreateDocument(val suggestedFileName: String, val format: Format) : Effect
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 1)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var exportJob: Job? = null
    private var includeTrash = false
    private var stripPhotoMetadata = false
    private var format = Format.ZIP

    fun open() {
        if (_state.value !is UiState.Idle && _state.value !is UiState.Finished) return
        includeTrash = false
        stripPhotoMetadata = false
        format = Format.ZIP
        _state.value = UiState.Preparing
        viewModelScope.launch {
            _state.value = UiState.Ready(engine.counts(), includeTrash, stripPhotoMetadata, format)
        }
    }

    fun setIncludeTrash(value: Boolean) {
        includeTrash = value
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(includeTrash = value)
    }

    fun setStripPhotoMetadata(value: Boolean) {
        stripPhotoMetadata = value
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(stripPhotoMetadata = value)
    }

    fun setFormat(value: Format) {
        format = value
        val current = _state.value
        if (current is UiState.Ready) _state.value = current.copy(format = value)
    }

    fun dismiss() {
        if (_state.value is UiState.Ready || _state.value is UiState.Preparing ||
            _state.value is UiState.Finished
        ) {
            _state.value = UiState.Idle
        }
    }

    fun chooseDestination() {
        if (_state.value !is UiState.Ready) return
        val now = System.currentTimeMillis()
        _effects.tryEmit(
            when (format) {
                Format.ZIP -> Effect.CreateDocument(engine.suggestedFileName(now), Format.ZIP)
                Format.PDF -> Effect.CreateDocument(engine.suggestedPdfFileName(now), Format.PDF)
            },
        )
    }

    fun onDestinationSelected(uri: Uri?) {
        if (uri == null) return // The picker was dismissed; the sheet stays as it was.
        if (exportJob?.isActive == true) return
        _state.value = UiState.Exporting(0, 0)
        exportJob = viewModelScope.launch {
            val result = try {
                when (format) {
                    Format.ZIP -> engine.export(
                        includeTrash = includeTrash,
                        stripPhotoMetadata = stripPhotoMetadata,
                        destination = uri,
                        nowMillis = System.currentTimeMillis(),
                        onProgress = { done, total ->
                            _state.value = UiState.Exporting(done, total)
                        },
                    )

                    Format.PDF -> engine.exportPdf(
                        includeTrash = includeTrash,
                        destination = uri,
                        nowMillis = System.currentTimeMillis(),
                        onProgress = { done, total ->
                            _state.value = UiState.Exporting(done, total)
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.value = UiState.Finished("書き出しを中止しました")
                throw cancelled
            } catch (failure: Exception) {
                PortableExportEngine.ExportResult.Failed
            }
            _state.value = when (result) {
                is PortableExportEngine.ExportResult.Done -> UiState.Finished(
                    if (result.warnings == 0) {
                        "読める形式で書き出しました"
                    } else {
                        "書き出しました（読み込めなかった写真があります。" +
                            "ZIP内のEXPORT_WARNINGS.mdをご覧ください）"
                    },
                )

                PortableExportEngine.ExportResult.Failed ->
                    UiState.Finished("書き出せませんでした")
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }
}
