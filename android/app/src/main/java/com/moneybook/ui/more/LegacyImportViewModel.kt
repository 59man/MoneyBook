package com.moneybook.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.CategoryChoice
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.LegacyCategoryKey
import com.moneybook.data.LegacyImportRepository
import com.moneybook.data.LegacyImportResult
import com.moneybook.data.LegacyPreview
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.shared.importer.ImportFormatException
import com.moneybook.shared.importer.LegacyBatch
import com.moneybook.shared.importer.LegacyExcelImporter
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.xlsx.XlsxException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LegacyPicker {
    data object Account : LegacyPicker
    data class Category(val key: LegacyCategoryKey) : LegacyPicker
}

sealed interface LegacyImportState {
    data object Loading : LegacyImportState

    data class Ready(
        val preview: LegacyPreview,
        val accounts: List<AccountEntity>,
        val accountId: String?,
        val keepBalance: Boolean,
        val choices: Map<LegacyCategoryKey, CategoryChoice>,
        /** Existing top-level categories by type, in display order. */
        val categories: Map<CategoryType, List<CategoryEntity>>,
        val picker: LegacyPicker? = null,
        val importing: Boolean = false,
    ) : LegacyImportState {
        fun accountName(): String? = accounts.firstOrNull { it.id == accountId }?.name

        fun label(key: LegacyCategoryKey): String = when (val c = choices[key]) {
            is CategoryChoice.Existing -> categories[key.type].orEmpty().firstOrNull { it.id == c.id }?.name ?: "?"
            CategoryChoice.CreateNew, null -> "New: ${key.name}"
        }
    }

    data class Done(val result: LegacyImportResult) : LegacyImportState
    data class Failed(val message: String) : LegacyImportState
}

class LegacyImportViewModel(
    private val open: () -> InputStream,
    private val repo: LegacyImportRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<LegacyImportState>(LegacyImportState.Loading)
    val state: StateFlow<LegacyImportState> = _state.asStateFlow()
    private var batch: LegacyBatch? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.value = try {
            val b = withContext(Dispatchers.IO) { open().use { LegacyExcelImporter.parse(it) } }
            batch = b
            val preview = repo.preview(b)
            val usable = accounts.accounts().filter { !it.hidden }
            val cash = DefaultData.accountId(AccountGroupType.CASH)
            LegacyImportState.Ready(
                preview = preview,
                accounts = usable,
                accountId = usable.firstOrNull { it.id == cash }?.id ?: usable.firstOrNull()?.id,
                keepBalance = true,
                choices = preview.categories.associate { g ->
                    g.key to (g.suggestionId?.let { CategoryChoice.Existing(it) } ?: CategoryChoice.CreateNew)
                },
                categories = categories.all().filter { it.parentId == null && !it.hidden }.sortedBy { it.sortOrder }.groupBy { it.type },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: XlsxException) {
            LegacyImportState.Failed(e.message ?: "Not an .xlsx file")
        } catch (e: ImportFormatException) {
            LegacyImportState.Failed(e.message ?: "Unsupported file")
        } catch (e: IOException) {
            LegacyImportState.Failed("Could not read the file: ${e.message}")
        } catch (e: Exception) {
            LegacyImportState.Failed("Import failed: ${e.message}")
        }
    }

    private fun update(change: (LegacyImportState.Ready) -> LegacyImportState.Ready) =
        _state.update { (it as? LegacyImportState.Ready)?.let(change) ?: it }

    fun openAccountPicker() = update { it.copy(picker = LegacyPicker.Account) }
    fun openCategoryPicker(key: LegacyCategoryKey) = update { it.copy(picker = LegacyPicker.Category(key)) }
    fun closePicker() = update { it.copy(picker = null) }
    fun pickAccount(id: String) = update { it.copy(accountId = id, picker = null) }
    fun choose(key: LegacyCategoryKey, choice: CategoryChoice) = update { it.copy(choices = it.choices + (key to choice), picker = null) }
    fun setKeepBalance(keep: Boolean) = update { it.copy(keepBalance = keep) }

    fun import() {
        val s = _state.value as? LegacyImportState.Ready ?: return
        val b = batch ?: return
        val accountId = s.accountId ?: return
        if (s.importing) return
        _state.value = s.copy(importing = true, picker = null)
        viewModelScope.launch {
            _state.value = try {
                LegacyImportState.Done(repo.apply(b, accountId, s.keepBalance, s.choices))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LegacyImportState.Failed("Import failed: ${e.message}")
            }
        }
    }
}
