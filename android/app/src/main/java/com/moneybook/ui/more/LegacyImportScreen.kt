package com.moneybook.ui.more

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.data.CategoryChoice
import com.moneybook.data.LegacyCategoryGroup
import com.moneybook.data.LegacyImportResult
import com.moneybook.data.LegacyPreview
import com.moneybook.domain.Formats
import com.moneybook.shared.model.CategoryType
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MessageDialog
import com.moneybook.ui.common.SettingsSection
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.edit.GridPickerPanel
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.io.IOException

private const val NEW_CATEGORY = "__new__"

@Composable
fun LegacyImportScreen(uri: String, onBack: () -> Unit) {
    val c = appContainer()
    val resolver = LocalContext.current.contentResolver
    val vm: LegacyImportViewModel = viewModel {
        LegacyImportViewModel(
            { resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot open the selected file") },
            c.legacy, c.accounts, c.categories,
        )
    }
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Import old data") })
        Hairline(px = 2)
        when (val st = s) {
            LegacyImportState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mb.colors.accent)
            is LegacyImportState.Ready -> ReadyContent(st, vm, Modifier.weight(1f))
            is LegacyImportState.Done -> MessageDialog("Import complete", legacySummary(st.result), onBack)
            is LegacyImportState.Failed -> MessageDialog("Import failed", st.message, onBack)
        }
    }
}

@Composable
private fun ReadyContent(s: LegacyImportState.Ready, vm: LegacyImportViewModel, modifier: Modifier) {
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                previewSummary(s.preview), style = MbType.listItem, color = Mb.colors.textBody,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            FormRow("Account", active = s.picker == LegacyPicker.Account, onClick = vm::openAccountPicker) { ValueText(s.accountName()) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(horizontal = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FitText("Keep the account's current balance", MbType.formLabel, Mb.colors.textBody, Modifier.weight(1f), maxLines = 2)
                Switch(
                    checked = s.keepBalance, onCheckedChange = vm::setKeepBalance,
                    colors = SwitchDefaults.colors(checkedTrackColor = Mb.colors.accent),
                )
            }
            for (type in listOf(CategoryType.EXPENSE, CategoryType.INCOME)) {
                val groups = s.preview.categories.filter { it.key.type == type }
                if (groups.isEmpty()) continue
                SettingsSection(if (type == CategoryType.EXPENSE) "Expenses" else "Income")
                groups.forEach { g ->
                    MappingRow(g, s.label(g.key), isNew = s.choices[g.key] == CategoryChoice.CreateNew) { vm.openCategoryPicker(g.key) }
                }
            }
        }
        when (val p = s.picker) {
            LegacyPicker.Account -> GridPickerPanel("Accounts", s.accounts.map { it.id to it.name }, vm::pickAccount, vm::closePicker)
            is LegacyPicker.Category -> GridPickerPanel(
                "Category",
                listOf(NEW_CATEGORY to "+ New \"${p.key.name}\"") + s.categories[p.key.type].orEmpty().map { it.id to it.name },
                onPick = { id -> vm.choose(p.key, if (id == NEW_CATEGORY) CategoryChoice.CreateNew else CategoryChoice.Existing(id)) },
                onClose = vm::closePicker,
            )
            null -> if (s.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mb.colors.accent)
            } else {
                FilledButton("Import", Mb.colors.accent, Modifier.fillMaxWidth().padding(15.dp)) { vm.import() }
            }
        }
    }
}

@Composable
private fun MappingRow(group: LegacyCategoryGroup, label: String, isNew: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FitText(group.key.name, MbType.listItem, Mb.colors.textBody, Modifier.weight(1f))
            Text("${group.count}", style = MbType.listItem, color = Mb.colors.textSecondary, modifier = Modifier.padding(horizontal = 10.dp))
            FitText(label, MbType.listItem, if (isNew) Mb.colors.accent else Mb.colors.textPrimary, Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Hairline()
    }
}

internal fun previewSummary(p: LegacyPreview): String = buildString {
    append("${p.importable} entries will be imported.")
    if (p.overlapping > 0 && p.cutoff != null) {
        append(" ${p.overlapping} entries from ${Formats.editDate(p.cutoff)} on are skipped because your data already covers those days.")
    }
    if (p.unreadable.isNotEmpty()) {
        append(" Could not read ${p.unreadable.size} rows: ")
        append(p.unreadable.take(5).joinToString { "row ${it.rowNumber} (${it.reason})" })
        if (p.unreadable.size > 5) append("…")
    }
}

internal fun legacySummary(r: LegacyImportResult): String = buildString {
    append("Imported ${r.imported} entries.")
    if (r.duplicates > 0) append("\nSkipped ${r.duplicates} already imported.")
    if (r.overlapping > 0) append("\nSkipped ${r.overlapping} on days your data already covers.")
    if (r.categoriesCreated > 0) append("\nCreated ${r.categoriesCreated} categories.")
    if (r.balanceAdjustment != 0L) append("\nThe account's starting balance was adjusted so its current balance stays the same.")
}
