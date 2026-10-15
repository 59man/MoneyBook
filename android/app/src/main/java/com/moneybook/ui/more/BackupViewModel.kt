package com.moneybook.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.BackupRepository
import com.moneybook.data.ExportRepository
import com.moneybook.data.ResetRepository
import com.moneybook.data.TimeSource
import com.moneybook.shared.backup.BackupException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupState {
    data object Idle : BackupState
    data object Running : BackupState
    data class Finished(val title: String, val message: String) : BackupState
}

class BackupViewModel(
    private val backups: BackupRepository,
    private val exporter: ExportRepository,
    private val reset: ResetRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun backupFileName(): String = "MoneyBook-backup-${time.today()}.zip"
    fun excelFileName(): String = "MoneyBook-${time.today()}.xlsx"

    fun backup(open: () -> OutputStream) = run("Backup complete", "Backup failed") {
        "Saved ${entries(open().use { backups.backup(it) })}."
    }

    fun restore(open: () -> InputStream) = run("Restore complete", "Restore failed") {
        val r = open().use { backups.restore(it) }
        "Restored ${entries(r.entries)} from the backup of ${r.createdAt.take(10)}."
    }

    fun exportExcel(open: () -> OutputStream) = run("Export complete", "Export failed") {
        "Exported ${entries(open().use { exporter.exportExcel(it) })}."
    }

    fun resetContents() = run("Reset complete", "Reset failed") {
        reset.resetContents()
        "All entries were deleted."
    }

    fun resetAll() = run("Reset complete", "Reset failed") {
        reset.resetAll()
        "All data was deleted and the defaults were restored."
    }

    fun dismiss() {
        _state.value = BackupState.Idle
    }

    private fun run(doneTitle: String, failTitle: String, block: suspend () -> String) {
        if (_state.value == BackupState.Running) return
        _state.value = BackupState.Running
        viewModelScope.launch {
            _state.value = try {
                BackupState.Finished(doneTitle, withContext(Dispatchers.IO) { block() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                BackupState.Finished(failTitle, e.message ?: "The backup file is damaged.")
            } catch (e: IOException) {
                BackupState.Finished(failTitle, "Could not access the file: ${e.message}")
            } catch (e: Exception) {
                BackupState.Finished(failTitle, e.message ?: e.toString())
            }
        }
    }

    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
}
