package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.domain.WeekendShift
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.RadioMark
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.launch

@Composable
fun MonthlyStartDateScreen(onBack: () -> Unit) {
    val c = appContainer()
    val vm: ConfigurationViewModel = viewModel { ConfigurationViewModel(c.settings, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val settings = s ?: return
    MonthlyStartDateContent(
        settings.monthStartDay, settings.monthStartWeekend,
        // Saved before closing: the view model's own scope ends with this screen.
        onSave = { day, weekend ->
            scope.launch {
                vm.save(settings.copy(monthStartDay = day, monthStartWeekend = weekend))
                onBack()
            }
        },
        onBack = onBack,
    )
}

/** Configuration → Monthly Start Date (capture 67): the day, the weekend rule and Save. */
@Composable
fun MonthlyStartDateContent(day: Int, weekend: WeekendShift, onSave: (Int, WeekendShift) -> Unit, onBack: () -> Unit) {
    var d by rememberSaveable { mutableIntStateOf(day) }
    var w by rememberSaveable { mutableStateOf(weekend) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Monthly Start Date") })
        Hairline(px = 2)
        Spacer(Modifier.height(12.dp))
        FormRow("Start Date", onClick = { picking = true }) { ValueText("Every $d") }
        Spacer(Modifier.height(24.dp))
        Text(
            "If monthly start date is weekend,", style = MbType.formLabel, color = Mb.colors.textTertiary,
            modifier = Modifier.padding(horizontal = 21.dp),
        )
        Spacer(Modifier.height(8.dp))
        WeekendShift.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).clickable { w = option }.padding(horizontal = 21.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioMark(option == w)
                Spacer(Modifier.width(16.dp))
                Text(option.label, style = MbType.dialogOption, color = Mb.colors.textBody)
            }
        }
        Spacer(Modifier.height(30.dp))
        FilledButton("Save", Mb.colors.accent, Modifier.padding(horizontal = 15.dp).fillMaxWidth()) { onSave(d, w) }
    }
    if (picking) {
        ChoiceDialog("Start Date", (1..31).map { "Every $it" }, d - 1, onDismiss = { picking = false }) {
            d = it + 1
            picking = false
        }
    }
}
