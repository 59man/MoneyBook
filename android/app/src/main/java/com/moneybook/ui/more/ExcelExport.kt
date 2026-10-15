package com.moneybook.ui.more

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.ui.common.MessageDialog
import com.moneybook.ui.common.OutlinedButtonBox
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.rememberAppLauncher

/** Realbyte's "Export data to Excel" button on Trans → Total; it runs the same export as More → Backup. */
@Composable
fun ExcelExportButton(modifier: Modifier = Modifier) {
    val c = appContainer()
    val vm: BackupViewModel = viewModel(key = "excel-export") { BackupViewModel(c.backup, c.exporter, c.reset, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val picker = rememberAppLauncher(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) vm.exportExcel(resolver.writer(uri))
    }
    OutlinedButtonBox("Export data to Excel", modifier, icon = R.drawable.ic_table) {
        if (s != BackupState.Running) picker.launch(vm.excelFileName())
    }
    (s as? BackupState.Finished)?.let { MessageDialog(it.title, it.message, vm::dismiss) }
}
