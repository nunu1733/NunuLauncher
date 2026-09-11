package app.lawnchair.backup.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.lawnchair.backup.BackupPageSummaryReader
import app.lawnchair.backup.BackupPageSummaryResult
import app.lawnchair.backup.LawnchairBackup
import app.lawnchair.util.hasFlag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RestoreBackupUiState {
    val isLoading: Boolean

    data class Success(val backup: LawnchairBackup) : RestoreBackupUiState {
        override val isLoading: Boolean = false
    }

    data object Loading : RestoreBackupUiState {
        override val isLoading: Boolean = true
    }

    data object Error : RestoreBackupUiState {
        override val isLoading: Boolean = true
    }
}

/**
 * Issue #233: page summary analysis runs independently of the main Success
 * state so the confirmation screen never waits for it. Pending renders as
 * nothing; analysis failure is typed and never blocks restore.
 */
sealed interface BackupPageSummaryUiState {
    data object Pending : BackupPageSummaryUiState

    data class Result(val result: BackupPageSummaryResult) : BackupPageSummaryUiState
}

private data class RestoreBackupViewModelState(
    val backup: LawnchairBackup? = null,
    val hasError: Boolean = false,
) {
    fun toUiState(): RestoreBackupUiState = when {
        hasError -> RestoreBackupUiState.Error
        backup != null -> RestoreBackupUiState.Success(backup)
        else -> RestoreBackupUiState.Loading
    }
}

class RestoreBackupViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private var initialized = false
    private lateinit var backupUri: Uri

    private val viewModelState = MutableStateFlow(RestoreBackupViewModelState())
    val uiState = viewModelState
        .map { it.toUiState() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            viewModelState.value.toUiState(),
        )

    val backupContents = savedStateHandle.getStateFlow("contents", 0)

    private val _pageSummary = MutableStateFlow<BackupPageSummaryUiState>(BackupPageSummaryUiState.Pending)
    val pageSummary: StateFlow<BackupPageSummaryUiState> = _pageSummary

    fun init(backupUri: Uri) {
        if (initialized) return
        initialized = true
        this.backupUri = backupUri
        viewModelScope.launch {
            try {
                val backup = LawnchairBackup(getApplication(), backupUri)
                backup.readInfoAndPreview()
                setBackupContents(backup.info.contents)
                viewModelState.update { it.copy(backup = backup) }
                launchPageSummaryAnalysis(backup)
            } catch (t: Throwable) {
                Log.e("RestoreBackupViewModel", "failed to parse backup", t)
                viewModelState.update { it.copy(hasError = true) }
            }
        }
    }

    private fun launchPageSummaryAnalysis(backup: LawnchairBackup) {
        if (!backup.info.contents.hasFlag(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)) return
        viewModelScope.launch {
            val result = BackupPageSummaryReader(getApplication(), backupUri).read()
            _pageSummary.value = BackupPageSummaryUiState.Result(result)
        }
    }

    fun setBackupContents(contents: Int) {
        savedStateHandle["contents"] = contents
    }
}
