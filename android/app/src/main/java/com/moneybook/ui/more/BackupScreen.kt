package com.moneybook.ui.more

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.map
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MessageDialog
import com.moneybook.ui.common.SettingsGroup
import com.moneybook.ui.common.SettingsRow
import com.moneybook.ui.common.SettingsSection
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.rememberAppLauncher
import com.moneybook.ui.theme.Mb

private const val ZIP_MIME = "application/zip"

// Some file managers label .xlsx and .zip files as octet-stream.
private val XLSX_TYPES = arrayOf(XLSX_MIME, "application/octet-stream")
private val ZIP_TYPES = arrayOf(ZIP_MIME, "application/octet-stream")

/** Appended to the reset and restore questions while this device syncs. */
const val SYNC_NOTE = " Your other synced devices will get the same change."

private enum class BackupDialog { DEVICE, RESET_ALL, RESET_CONTENTS }

@Composable
fun BackupScreen(onBack: () -> Unit, onImportLegacy: (String) -> Unit) {
    val c = appContainer()
    val importVm: ImportViewModel = viewModel { ImportViewModel(c.importer) }
    val vm: BackupViewModel = viewModel { BackupViewModel(c.backup, c.exporter, c.reset, c.time) }
    val importState by importVm.state.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val signedIn by remember { c.device.settings.map { it.signedIn } }.collectAsStateWithLifecycle(false)
    val note = if (signedIn) SYNC_NOTE else ""
    val resolver = LocalContext.current.contentResolver
    var dialog by rememberSaveable { mutableStateOf<BackupDialog?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }

    val backupPicker = rememberAppLauncher(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) vm.backup(resolver.writer(uri))
    }
    val restorePicker = rememberAppLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri?.toString()
    }
    val excelPicker = rememberAppLauncher(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) vm.exportExcel(resolver.writer(uri))
    }
    val importPicker = rememberAppLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVm.import(resolver.reader(uri))
    }
    val legacyPicker = rememberAppLauncher(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportLegacy(uri.toString())
    }
    val busy = s == BackupState.Running || importState == ImportState.Running

    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Backup") })
        Hairline(px = 2)
        SettingsGroup {
            SettingsRow("Backup/restore on device", enabled = !busy) { dialog = BackupDialog.DEVICE }
            SettingsRow("Export data to Excel", enabled = !busy) { excelPicker.launch(vm.excelFileName()) }
            SettingsRow("Import Excel File", enabled = !busy) { importPicker.launch(XLSX_TYPES) }
            SettingsRow("Import data from the old app", enabled = !busy) { legacyPicker.launch(XLSX_TYPES) }
        }
        SettingsSection("Reset")
        SettingsGroup {
            SettingsRow("A complete reset", enabled = !busy) { dialog = BackupDialog.RESET_ALL }
            SettingsRow("Reset contents only (Others remain)", enabled = !busy) { dialog = BackupDialog.RESET_CONTENTS }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mb.colors.accent)
    }

    when (dialog) {
        BackupDialog.DEVICE -> ChoiceDialog("Backup/restore on device", listOf("Backup", "Restore"), onDismiss = { dialog = null }) { choice ->
            dialog = null
            if (choice == 0) backupPicker.launch(vm.backupFileName()) else restorePicker.launch(ZIP_TYPES)
        }
        BackupDialog.RESET_ALL -> ConfirmDialog(
            "A complete reset", "Deletes all entries, accounts and categories, and restores the defaults.$note", "Reset",
            onConfirm = vm::resetAll, onDismiss = { dialog = null },
        )
        BackupDialog.RESET_CONTENTS -> ConfirmDialog(
            "Reset contents only", "Deletes all entries. Accounts, categories and settings remain.$note", "Reset",
            onConfirm = vm::resetContents, onDismiss = { dialog = null },
        )
        null -> Unit
    }
    restoreUri?.let { uri ->
        ConfirmDialog(
            "Restore", "Replaces all current data with the backup.$note", "Restore",
            onConfirm = { vm.restore(resolver.reader(Uri.parse(uri))) }, onDismiss = { restoreUri = null },
        )
    }
    (s as? BackupState.Finished)?.let { MessageDialog(it.title, it.message, vm::dismiss) }
    when (val st = importState) {
        is ImportState.Done -> MessageDialog("Import complete", importSummary(st.result), importVm::dismiss)
        is ImportState.Failed -> MessageDialog("Import failed", st.message, importVm::dismiss)
        else -> Unit
    }
}
