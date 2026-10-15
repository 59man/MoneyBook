package com.moneybook.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.ImportRepository
import com.moneybook.data.ImportResult
import com.moneybook.shared.importer.ImportFormatException
import com.moneybook.shared.xlsx.XlsxException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Idle : ImportState
    data object Running : ImportState
    data class Done(val result: ImportResult) : ImportState
    data class Failed(val message: String) : ImportState
}

class ImportViewModel(private val importer: ImportRepository) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun import(open: () -> InputStream) {
        if (_state.value == ImportState.Running) return
        _state.value = ImportState.Running
        viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { open().use { ImportState.Done(importer.importRealbyte(it)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XlsxException) {
                ImportState.Failed(e.message ?: "Not an .xlsx file")
            } catch (e: ImportFormatException) {
                ImportState.Failed(e.message ?: "Unsupported file")
            } catch (e: IOException) {
                ImportState.Failed("Could not read the file: ${e.message}")
            } catch (e: Exception) {
                ImportState.Failed("Import failed: ${e.message}")
            }
        }
    }

    fun dismiss() {
        _state.value = ImportState.Idle
    }
}

fun importSummary(result: ImportResult): String = buildString {
    append("Imported ${result.imported} transactions.")
    if (result.duplicates > 0) append("\nSkipped ${result.duplicates} already imported.")
    if (result.accountsCreated > 0) append("\nCreated ${result.accountsCreated} accounts.")
    if (result.categoriesCreated > 0) append("\nCreated ${result.categoriesCreated} categories.")
    if (result.skipped.isNotEmpty()) {
        append("\nCould not read ${result.skipped.size} rows: ")
        append(result.skipped.take(5).joinToString { "row ${it.rowNumber} (${it.reason})" })
        if (result.skipped.size > 5) append("…")
    }
}
