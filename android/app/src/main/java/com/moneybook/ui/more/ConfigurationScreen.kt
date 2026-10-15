package com.moneybook.ui.more

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.AppSettings
import com.moneybook.data.ColorSet
import com.moneybook.data.CurrencyState
import com.moneybook.data.DeviceSettings
import com.moneybook.data.InputOrder
import com.moneybook.data.SettingsRepository
import com.moneybook.data.StartScreen
import com.moneybook.data.SwipeMode
import com.moneybook.data.TimeInput
import com.moneybook.data.TimeSource
import com.moneybook.shared.model.CategoryType
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.SettingsGroup
import com.moneybook.ui.common.SettingsRow
import com.moneybook.ui.common.SettingsSection
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.highlightMatches
import com.moneybook.ui.common.rememberNotificationPermission
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.DayOfWeek
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConfigurationViewModel(private val settings: SettingsRepository, private val time: TimeSource) : ViewModel() {
    val state: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** An option row's dialog or screen: the whole new settings. */
    fun change(new: AppSettings) = update { new }

    /** Writes [new] and returns once it is stored, for a screen that closes right after saving. */
    suspend fun save(new: AppSettings) = settings.replace(new)

    /** Autocomplete → Reset: entries saved before now stop feeding suggestions. */
    fun resetAutocomplete() = update { it.copy(autocompleteSince = time.millis()) }

    private fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.replace(change(settings.current())) }
    }
}

/** Realbyte's Configuration list. Only rows whose options work are shown; later plans add the rest. */
@Composable
fun ConfigurationScreen(
    onBack: () -> Unit,
    onOpenCategories: (CategoryType) -> Unit,
    onOpenRepeatSetting: () -> Unit,
    onOpenBudgetSetting: () -> Unit = {},
    onOpenMonthlyStartDate: () -> Unit = {},
    onOpenStyle: () -> Unit = {},
    onOpenPasscode: (Boolean) -> Unit = {},
    onOpenAlarm: () -> Unit = {},
    onOpenWidgetSettings: () -> Unit = {},
    onOpenMainCurrency: () -> Unit = {},
    onOpenSubCurrency: () -> Unit = {},
) {
    val c = appContainer()
    val vm: ConfigurationViewModel = viewModel { ConfigurationViewModel(c.settings, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    val device by c.device.settings.collectAsStateWithLifecycle(initialValue = DeviceSettings())
    val currencies by c.currencies.state.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val permission = rememberNotificationPermission()
    val scope = rememberCoroutineScope()
    val setQuickAdd: (Boolean) -> Unit = { on ->
        if (!on) {
            scope.launch { c.device.update { it.copy(quickAdd = false) }; c.quickAdd.hide() }
        } else {
            permission.request { granted ->
                if (granted) {
                    scope.launch { c.device.update { it.copy(quickAdd = true) }; c.quickAdd.show() }
                } else {
                    Toast.makeText(context, "Allow notifications for MoneyBook to show quick add.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val settings = s ?: return
    ConfigurationContent(
        settings,
        ConfigActions(
            onBack = onBack, onOpenCategories = onOpenCategories, onOpenRepeatSetting = onOpenRepeatSetting,
            onOpenBudgetSetting = onOpenBudgetSetting,
            onOpenMonthlyStartDate = onOpenMonthlyStartDate, onOpenStyle = onOpenStyle, onOpenPasscode = onOpenPasscode, onOpenAlarm = onOpenAlarm, onOpenWidgetSettings = onOpenWidgetSettings, onSetQuickAdd = setQuickAdd,
            onOpenMainCurrency = onOpenMainCurrency, onOpenSubCurrency = onOpenSubCurrency, onChange = vm::change,
            onResetAutocomplete = {
                vm.resetAutocomplete()
                Toast.makeText(context, "Autocomplete was reset.", Toast.LENGTH_SHORT).show()
            },
        ),
        device = device,
        currencies = currencies,
    )
}

/** What the Configuration rows do; the defaults do nothing (tests). */
data class ConfigActions(
    val onBack: () -> Unit = {},
    val onOpenCategories: (CategoryType) -> Unit = {},
    val onOpenRepeatSetting: () -> Unit = {},
    val onOpenBudgetSetting: () -> Unit = {},
    val onOpenMonthlyStartDate: () -> Unit = {},
    val onOpenStyle: () -> Unit = {},
    /** True when a passcode is set (the flow then turns it off). */
    val onOpenPasscode: (Boolean) -> Unit = {},
    val onOpenAlarm: () -> Unit = {},
    val onOpenWidgetSettings: () -> Unit = {},
    val onOpenMainCurrency: () -> Unit = {},
    val onOpenSubCurrency: () -> Unit = {},
    val onSetQuickAdd: (Boolean) -> Unit = {},
    /** The whole new settings after an option dialog's choice. */
    val onChange: (AppSettings) -> Unit = {},
    val onResetAutocomplete: () -> Unit = {},
)

private enum class ConfigDialog { START_SCREEN, WEEK_START, CARRY_OVER, SUBCATEGORY, SWIPE, COLORS, TIME_INPUT, SHOW_DESCRIPTION, AUTOCOMPLETE, INPUT_ORDER, NOTE_BUTTON, QUICK_ADD }

private val WEEK_DAYS = listOf(DayOfWeek.SUNDAY) + (1L..6L).map { DayOfWeek.SUNDAY.plus(it) }

private fun dayName(day: DayOfWeek): String = day.getDisplayName(DayStyle.FULL, Locale.ENGLISH)

private fun onOff(on: Boolean) = if (on) "ON" else "OFF"

/** Realbyte's help text in the Color Setting dialog (capture 57). */
private const val COLOR_HELP = "By default, income is shown in 'Blue' color and expenses in 'Red' color. You can customize it to the other way around.\n\n" +
    "A. Income: Blue, Expenses: Red\nB. Income: Red, Expenses: Blue"

/** Realbyte's help text in the Carry-over Setting dialog (capture 58). */
internal const val CARRY_OVER_HELP = "Carry-over allows your previous month\u2019s outstanding balance to be reflected on Total for this month. " +
    "If the outstanding balance is negative, it is considered as an expense while surplus is considered as an asset. " +
    "Accounts\u2019 carry-over(except debit card) to the next month is set by default. " +
    "If you wish to include your debit card spendings, we recommend you to change your debit card\u2019s group from Debit Card to Accounts."

/** Realbyte's help text in the Subcategory Setting dialog (capture 64). */
internal const val SUBCATEGORY_HELP = "Subcategory function is disabled in Money Manager by default. Tap \u2019ON\u2019 to enable subcategory. " +
    "You can switch the subcategory to Main Category and vice versa."

@Composable
fun ConfigurationContent(
    s: AppSettings,
    actions: ConfigActions = ConfigActions(),
    device: DeviceSettings = DeviceSettings(),
    currencies: CurrencyState? = null,
) {
    var dialog by remember { mutableStateOf<ConfigDialog?>(null) }
    val close = { dialog = null }
    val change = { new: AppSettings -> actions.onChange(new); dialog = null }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = actions.onBack, title = { TitleText("Configuration") })
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsSection("Category/Repeat")
            SettingsGroup {
                SettingsRow("Income Category Setting") { actions.onOpenCategories(CategoryType.INCOME) }
                SettingsRow("Expenses Category Setting") { actions.onOpenCategories(CategoryType.EXPENSE) }
                SettingsRow("Subcategory", value = onOff(s.subcategory)) { dialog = ConfigDialog.SUBCATEGORY }
                SettingsRow("Budget Setting") { actions.onOpenBudgetSetting() }
                SettingsRow("Repeat Setting") { actions.onOpenRepeatSetting() }
            }
            SettingsSection("Configuration")
            SettingsGroup {
                SettingsRow("Main Currency Setting", value = currencies?.let { "${it.main} (${it.mainSpec.symbol})" }) { actions.onOpenMainCurrency() }
                SettingsRow("Sub Currency Setting", value = currencies?.subSpecs?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.symbol }) {
                    actions.onOpenSubCurrency()
                }
                SettingsRow("Start Screen (Daily/Calendar)", value = s.startScreen.label) { dialog = ConfigDialog.START_SCREEN }
                SettingsRow("Monthly Start Date", value = "Every ${s.monthStartDay}") { actions.onOpenMonthlyStartDate() }
                SettingsRow("Weekly Start Day", value = dayName(s.weekStart)) { dialog = ConfigDialog.WEEK_START }
                SettingsRow("Carry-over Setting", value = onOff(s.carryOver)) { dialog = ConfigDialog.CARRY_OVER }
                SettingsRow("Swipe", value = s.swipe.label) { dialog = ConfigDialog.SWIPE }
                SettingsRow("Income-Expenses Color Setting", value = s.colorSet.label) { dialog = ConfigDialog.COLORS }
                SettingsRow("Time Input", value = s.timeInput.label) { dialog = ConfigDialog.TIME_INPUT }
                SettingsRow("Show description", value = onOff(s.showDescription)) { dialog = ConfigDialog.SHOW_DESCRIPTION }
                SettingsRow("Autocomplete", value = onOff(s.autocomplete)) { dialog = ConfigDialog.AUTOCOMPLETE }
                SettingsRow("Input order", value = s.inputOrder.label) { dialog = ConfigDialog.INPUT_ORDER }
                SettingsRow("Note button setting", value = onOff(s.noteButton)) { dialog = ConfigDialog.NOTE_BUTTON }
            }
            SettingsSection("Other")
            SettingsGroup {
                SettingsRow("Passcode", value = onOff(device.passcodeSet)) { actions.onOpenPasscode(device.passcodeSet) }
                SettingsRow("Alarm Setting", value = onOff(device.alarmOn)) { actions.onOpenAlarm() }
                SettingsRow("Quick add", value = onOff(device.quickAdd)) { dialog = ConfigDialog.QUICK_ADD }
                SettingsRow("Style") { actions.onOpenStyle() }
                SettingsRow("Widget Settings") { actions.onOpenWidgetSettings() }
            }
        }
    }
    when (dialog) {
        ConfigDialog.START_SCREEN -> ChoiceDialog("Start Screen", StartScreen.entries.map { it.label }, s.startScreen.ordinal, onDismiss = close) {
            change(s.copy(startScreen = StartScreen.entries[it]))
        }
        ConfigDialog.WEEK_START -> ChoiceDialog("Weekly Start Day", WEEK_DAYS.map(::dayName), WEEK_DAYS.indexOf(s.weekStart), onDismiss = close) {
            change(s.copy(weekStart = WEEK_DAYS[it]))
        }
        ConfigDialog.CARRY_OVER -> ChoiceDialog(
            "Carry-over Setting", listOf("OFF", "ON"), if (s.carryOver) 1 else 0, onDismiss = close, description = CARRY_OVER_HELP,
        ) { change(s.copy(carryOver = it == 1)) }
        ConfigDialog.SUBCATEGORY -> ChoiceDialog(
            "Subcategory Setting", listOf("OFF", "ON"), if (s.subcategory) 1 else 0, onDismiss = close, description = SUBCATEGORY_HELP,
        ) { change(s.copy(subcategory = it == 1)) }
        ConfigDialog.SWIPE -> ChoiceDialog("Swipe", SwipeMode.entries.map { it.label }, s.swipe.ordinal, onDismiss = close) {
            change(s.copy(swipe = SwipeMode.entries[it]))
        }
        ConfigDialog.COLORS -> ChoiceDialog(
            "Color Setting", ColorSet.entries.map { it.label }, s.colorSet.ordinal, onDismiss = close, description = COLOR_HELP,
        ) { change(s.copy(colorSet = ColorSet.entries[it])) }
        ConfigDialog.TIME_INPUT -> ChoiceDialog("Time Input", TimeInput.entries.map { it.label }, s.timeInput.ordinal, onDismiss = close) {
            change(s.copy(timeInput = TimeInput.entries[it]))
        }
        ConfigDialog.SHOW_DESCRIPTION -> ChoiceDialog("Show description", listOf("OFF", "ON"), if (s.showDescription) 1 else 0, onDismiss = close) {
            change(s.copy(showDescription = it == 1))
        }
        ConfigDialog.AUTOCOMPLETE -> AutocompleteDialog(
            s.autocomplete, onChoose = { change(s.copy(autocomplete = it)) }, onReset = actions.onResetAutocomplete, onDismiss = close,
        )
        ConfigDialog.INPUT_ORDER -> ChoiceDialog("Input order", InputOrder.entries.map { it.label }, s.inputOrder.ordinal, onDismiss = close) {
            change(s.copy(inputOrder = InputOrder.entries[it]))
        }
        ConfigDialog.NOTE_BUTTON -> ChoiceDialog("Note button setting", listOf("OFF", "ON"), if (s.noteButton) 1 else 0, onDismiss = close) {
            change(s.copy(noteButton = it == 1))
        }
        ConfigDialog.QUICK_ADD -> ChoiceDialog(
            "Quick add", listOf("OFF", "ON"), if (device.quickAdd) 1 else 0, onDismiss = close,
            description = "Displays quick add feature in the notification bar.", illustration = { QuickAddSample() },
        ) {
            actions.onSetQuickAdd(it == 1)
            dialog = null
        }
        null -> Unit
    }
}

/** The Quick add dialog's picture of the bar (capture 54), drawn instead of Realbyte's image. */
@Composable
fun QuickAddSample() {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Mb.colors.background).border(2.dp, Mb.colors.accent, shape).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(R.drawable.ic_wallet, R.drawable.ic_search, R.drawable.ic_star, R.drawable.ic_add).forEach { MbIcon(it, null, tint = Mb.colors.textBody) }
    }
}

/** Realbyte's Autocomplete option dialog (capture 72): title, Reset, ✕, a picture of the feature, then OFF / ON. */
@Composable
private fun AutocompleteDialog(on: Boolean, onChoose: (Boolean) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Autocomplete", style = MbType.screenTitle, color = Mb.colors.textPrimary)
                Spacer(Modifier.width(12.dp))
                val shape = RoundedCornerShape(4.dp)
                Text(
                    "Reset", style = MbType.formLabel, color = Mb.colors.textBody,
                    modifier = Modifier.clip(shape).border(1.dp, Mb.colors.segmentBorder, shape).clickable(onClick = onReset)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).clickable(onClick = onDismiss).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    MbIcon(R.drawable.ic_close, null)
                }
            }
        },
        text = {
            Column {
                AutocompletePreview()
                Spacer(Modifier.height(12.dp))
                listOf("OFF" to false, "ON" to true).forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).clickable { onChoose(value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MbType.listItem, color = if (value == on) Mb.colors.accent else Mb.colors.textBody, modifier = Modifier.weight(1f))
                        if (value == on) MbIcon(R.drawable.ic_check, null, tint = Mb.colors.accent)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** The dialog's picture: a Note field with "m" typed and three suggestions. */
@Composable
private fun AutocompletePreview() {
    Column(Modifier.fillMaxWidth().border(1.dp, Mb.colors.divider).padding(12.dp)) {
        listOf("Meat", "Market", "Pin money").forEach {
            Text(highlightMatches(it, "m", Mb.colors.accent), style = MbType.summaryLabel, color = Mb.colors.textBody)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Note", style = MbType.summaryLabel, color = Mb.colors.textSecondary)
            Spacer(Modifier.width(12.dp))
            Text("m", style = MbType.summaryLabel, color = Mb.colors.textPrimary)
        }
    }
}
