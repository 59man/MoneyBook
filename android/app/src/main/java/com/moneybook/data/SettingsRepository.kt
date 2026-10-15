package com.moneybook.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.moneybook.domain.CardExpenseMode
import com.moneybook.domain.WeekendShift
import java.time.DayOfWeek
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Configuration → Start Screen (Daily/Calendar): the Trans tab the app opens on. */
enum class StartScreen(val label: String) { DAILY("Daily"), CALENDAR("Calendar") }

/** Configuration → Swipe: what a horizontal swipe on the Trans tab does. */
enum class SwipeMode(val label: String) { TAB("To Change Tab"), DATE("To Change Date"), NONE("None") }

/** Configuration → Income-Expenses Color Setting: A is income blue, expenses red; B the other way round. */
enum class ColorSet(val label: String) { A("Set. A"), B("Set. B") }

/** Configuration → Time Input: whether the form asks for a time, whether lists show it, and a day's order. */
enum class TimeInput(val label: String, val inForm: Boolean, val onList: Boolean, val ascending: Boolean) {
    NONE_DESC("None, Desc.", false, false, false),
    NONE_ASC("None, Asc.", false, false, true),
    SHOW_DESC("Show on list, Desc.", true, true, false),
    SHOW_ASC("Show on list, Asc.", true, true, true),
    INPUT_DESC("Input Only, Desc.", true, false, false),
    INPUT_ASC("Input Only, Asc.", true, false, true),
}

/** Configuration → Input order: whether the entry form asks for the amount or the account first. */
enum class InputOrder(val label: String) { AMOUNT("From Amount"), ACCOUNT("From Account") }

/** Edit → Copy: the new entry takes today's date and the current time, or the entry's own. */
enum class CopyDateBy(val label: String) { TODAY("Copy with today's date"), ENTRY_DATE("Copy with date of entry") }

/** Configuration → Style. A device setting: it is not backed up. */
enum class StyleMode(val label: String) { SYSTEM("System Mode"), DARK("Dark Mode"), LIGHT("Light Mode") }

private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

/** Configuration. Everything but [style] is synced (M4) and backed up. */
data class AppSettings(
    val mainCurrency: String,
    val monthStartDay: Int = 1,
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    /** Configuration → Autocomplete; Realbyte's default is ON. */
    val autocomplete: Boolean = true,
    /** Autocomplete → Reset: entries saved before this time (epoch ms) no longer feed suggestions. */
    val autocompleteSince: Long = 0,
    val startScreen: StartScreen = StartScreen.DAILY,
    val monthStartWeekend: WeekendShift = WeekendShift.NONE,
    val swipe: SwipeMode = SwipeMode.DATE,
    val colorSet: ColorSet = ColorSet.A,
    val timeInput: TimeInput = TimeInput.INPUT_DESC,
    val showDescription: Boolean = false,
    val inputOrder: InputOrder = InputOrder.AMOUNT,
    val noteButton: Boolean = true,
    /** More → Accounts → Card expenses display config. */
    val cardExpenseMode: CardExpenseMode = CardExpenseMode.AT_THE_TIME,
    /** Configuration → Carry-over Setting. */
    val carryOver: Boolean = false,
    /** Configuration → Subcategory. */
    val subcategory: Boolean = false,
    /** Edit → Copy's date choice. */
    val copyDateBy: CopyDateBy = CopyDateBy.TODAY,
    /** Device-only; backups leave it out and a restore keeps it. */
    val style: StyleMode = StyleMode.SYSTEM,
)

/** The settings' own sync stamp (they are one synced row). [updatedAt] 0 = never changed on this device. */
data class SettingsSync(val updatedAt: Long, val dirty: Boolean)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings = settings.first()
    suspend fun setMainCurrency(code: String)

    /** Replaces every synced setting (restore, complete reset); stamps and marks dirty only when a synced field differs. */
    suspend fun replace(value: AppSettings)

    val syncState: Flow<SettingsSync>

    /** Sync side: writes every synced key (the stored [AppSettings.style] stays), clean, with the server's stamp. */
    suspend fun applyRemote(value: AppSettings, updatedAt: Long)

    /** Clears dirty only when the stamp is still [updatedAt] (an edit made during a push stays dirty). */
    suspend fun clearDirty(updatedAt: Long)

    /** Dirty again after an epoch change, but only when the settings were ever changed here. */
    suspend fun markDirty()

    /** Replace login: defaults, style kept, stamp 0, clean. */
    suspend fun resetForReplace(defaultCurrency: String)
}

class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    private val defaultCurrency: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : SettingsRepository {
    private val syncUpdatedAt = longPreferencesKey("sync_updated_at")
    private val syncDirty = booleanPreferencesKey("sync_dirty")
    private val mainCurrency = stringPreferencesKey("main_currency")
    private val monthStartDay = intPreferencesKey("month_start_day")
    private val weekStart = stringPreferencesKey("week_start")
    private val autocomplete = booleanPreferencesKey("autocomplete")
    private val autocompleteSince = longPreferencesKey("autocomplete_since")
    private val startScreen = stringPreferencesKey("start_screen")
    private val monthStartWeekend = stringPreferencesKey("month_start_weekend")
    private val swipe = stringPreferencesKey("swipe")
    private val colorSet = stringPreferencesKey("color_set")
    private val timeInput = stringPreferencesKey("time_input")
    private val showDescription = booleanPreferencesKey("show_description")
    private val inputOrder = stringPreferencesKey("input_order")
    private val noteButton = booleanPreferencesKey("note_button")
    private val cardExpenseMode = stringPreferencesKey("card_expense_mode")
    private val carryOver = booleanPreferencesKey("carry_over")
    private val subcategory = booleanPreferencesKey("subcategory")
    private val copyDateBy = stringPreferencesKey("copy_date_by")
    private val style = stringPreferencesKey("style")

    private fun read(p: Preferences) = AppSettings(
        mainCurrency = p[mainCurrency] ?: defaultCurrency,
        monthStartDay = p[monthStartDay] ?: 1,
        weekStart = p[weekStart]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: DayOfWeek.SUNDAY,
        autocomplete = p[autocomplete] ?: true,
        autocompleteSince = p[autocompleteSince] ?: 0,
        startScreen = enumOr(p[startScreen], StartScreen.DAILY),
        monthStartWeekend = enumOr(p[monthStartWeekend], WeekendShift.NONE),
        swipe = enumOr(p[swipe], SwipeMode.DATE),
        colorSet = enumOr(p[colorSet], ColorSet.A),
        timeInput = enumOr(p[timeInput], TimeInput.INPUT_DESC),
        showDescription = p[showDescription] ?: false,
        inputOrder = enumOr(p[inputOrder], InputOrder.AMOUNT),
        noteButton = p[noteButton] ?: true,
        cardExpenseMode = enumOr(p[cardExpenseMode], CardExpenseMode.AT_THE_TIME),
        carryOver = p[carryOver] ?: false,
        subcategory = p[subcategory] ?: false,
        copyDateBy = enumOr(p[copyDateBy], CopyDateBy.TODAY),
        style = enumOr(p[style], StyleMode.SYSTEM),
    )

    private fun MutablePreferences.write(value: AppSettings) {
        this[mainCurrency] = value.mainCurrency.uppercase()
        this[monthStartDay] = value.monthStartDay
        this[weekStart] = value.weekStart.name
        this[autocomplete] = value.autocomplete
        this[autocompleteSince] = value.autocompleteSince
        this[startScreen] = value.startScreen.name
        this[monthStartWeekend] = value.monthStartWeekend.name
        this[swipe] = value.swipe.name
        this[colorSet] = value.colorSet.name
        this[timeInput] = value.timeInput.name
        this[showDescription] = value.showDescription
        this[inputOrder] = value.inputOrder.name
        this[noteButton] = value.noteButton
        this[cardExpenseMode] = value.cardExpenseMode.name
        this[carryOver] = value.carryOver
        this[subcategory] = value.subcategory
        this[copyDateBy] = value.copyDateBy.name
        this[style] = value.style.name
    }

    /** A synced change: the stamp never goes backwards, even when the clock does. */
    private fun MutablePreferences.stamp() {
        this[syncUpdatedAt] = maxOf(clock(), (this[syncUpdatedAt] ?: 0) + 1)
        this[syncDirty] = true
    }

    override val settings: Flow<AppSettings> = store.data.map { read(it) }

    override val syncState: Flow<SettingsSync> = store.data.map { SettingsSync(it[syncUpdatedAt] ?: 0, it[syncDirty] ?: false) }

    override suspend fun setMainCurrency(code: String) {
        store.edit {
            it[mainCurrency] = code.uppercase()
            it.stamp()
        }
    }

    override suspend fun replace(value: AppSettings) {
        store.edit {
            val old = read(it)
            val new = value.copy(mainCurrency = value.mainCurrency.uppercase())
            it.write(new)
            // style is device-only: a change of it alone is not a synced change.
            if (new.copy(style = old.style) != old.copy(mainCurrency = old.mainCurrency.uppercase())) it.stamp()
        }
    }

    override suspend fun applyRemote(value: AppSettings, updatedAt: Long) {
        store.edit {
            it.write(value.copy(style = read(it).style))
            it[syncUpdatedAt] = updatedAt
            it[syncDirty] = false
        }
    }

    override suspend fun clearDirty(updatedAt: Long) {
        store.edit { if ((it[syncUpdatedAt] ?: 0) == updatedAt) it[syncDirty] = false }
    }

    override suspend fun markDirty() {
        store.edit { it[syncDirty] = (it[syncUpdatedAt] ?: 0) > 0 }
    }

    override suspend fun resetForReplace(defaultCurrency: String) {
        store.edit {
            it.write(AppSettings(mainCurrency = defaultCurrency, style = read(it).style))
            it[syncUpdatedAt] = 0
            it[syncDirty] = false
        }
    }

    companion object {
        fun defaultCurrency(): String =
            runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"
    }
}
