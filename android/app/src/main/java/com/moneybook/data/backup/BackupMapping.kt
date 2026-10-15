package com.moneybook.data.backup

import com.moneybook.data.AppSettings
import com.moneybook.data.ColorSet
import com.moneybook.data.CopyDateBy
import com.moneybook.data.InputOrder
import com.moneybook.data.StartScreen
import com.moneybook.data.StyleMode
import com.moneybook.data.SwipeMode
import com.moneybook.data.TimeInput
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.data.db.BookmarkEntity
import com.moneybook.data.db.AttachmentEntity
import com.moneybook.data.db.BudgetEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.CurrencyEntity
import com.moneybook.data.db.MemoEntity
import com.moneybook.data.db.RepeatRuleEntity
import com.moneybook.data.db.TxnEntity
import com.moneybook.domain.CardExpenseMode
import com.moneybook.domain.WeekendShift
import com.moneybook.shared.dto.AccountDto
import com.moneybook.shared.dto.AccountGroupDto
import com.moneybook.shared.dto.BookmarkDto
import com.moneybook.shared.dto.AttachmentDto
import com.moneybook.shared.dto.BudgetDto
import com.moneybook.shared.dto.CategoryDto
import com.moneybook.shared.dto.CurrencyDto
import com.moneybook.shared.dto.MemoDto
import com.moneybook.shared.dto.RepeatRuleDto
import com.moneybook.shared.dto.SettingsDto
import com.moneybook.shared.dto.TxnDto
import com.moneybook.shared.money.SymbolPosition
import java.time.DayOfWeek

fun AccountGroupEntity.toDto() = AccountGroupDto(
    id = id, name = name, type = type, sortOrder = sortOrder, updatedAt = updatedAt, deleted = deleted, state = state,
)

fun AccountEntity.toDto() = AccountDto(
    id = id, groupId = groupId, name = name, currency = currency, initialBalance = initialBalance,
    description = description, includeInTotals = includeInTotals, hidden = hidden, sortOrder = sortOrder,
    statementDay = statementDay, paymentDay = paymentDay, paymentAccountId = paymentAccountId, autoPay = autoPay,
    updatedAt = updatedAt, deleted = deleted, state = state, transferExpense = transferExpense,
)

fun CategoryEntity.toDto() = CategoryDto(
    id = id, type = type, parentId = parentId, name = name, sortOrder = sortOrder, hidden = hidden,
    updatedAt = updatedAt, deleted = deleted,
)

fun TxnEntity.toDto() = TxnDto(
    id = id, type = type, dateTime = dateTime, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    amount = amount, toAmount = toAmount, currency = currency, amountMain = amountMain, accountAmount = accountAmount, fee = fee, note = note,
    description = description, repeatRuleId = repeatRuleId, installmentTotal = installmentTotal,
    installmentIndex = installmentIndex, updatedAt = updatedAt, deleted = deleted,
)

fun MemoEntity.toDto() = MemoDto(
    id = id, date = date, title = title, text = text, color = color, pinned = pinned, updatedAt = updatedAt, deleted = deleted,
)

fun BookmarkEntity.toDto() = BookmarkDto(
    id = id, name = name, type = type, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    amount = amount, currency = currency, note = note, description = description, createdAt = createdAt,
    updatedAt = updatedAt, deleted = deleted,
)

fun RepeatRuleEntity.toDto() = RepeatRuleDto(
    id = id, frequency = frequency, startDate = startDate, lastDate = lastDate, installments = installments, type = type,
    accountId = accountId, toAccountId = toAccountId, categoryId = categoryId, amount = amount, currency = currency,
    note = note, description = description, time = time, updatedAt = updatedAt, deleted = deleted,
)

// Restored rows are dirty with no server version, so the M4 sync pushes them as fresh changes.

fun AccountGroupDto.toEntity() = AccountGroupEntity(
    id = id, name = name, type = type, sortOrder = sortOrder, updatedAt = updatedAt, deleted = deleted,
    dirty = true, serverVersion = 0, state = state,
)

fun AccountDto.toEntity() = AccountEntity(
    id = id, groupId = groupId, name = name, currency = currency, initialBalance = initialBalance,
    description = description, includeInTotals = includeInTotals, hidden = hidden, sortOrder = sortOrder,
    statementDay = statementDay, paymentDay = paymentDay, paymentAccountId = paymentAccountId, autoPay = autoPay,
    updatedAt = updatedAt, deleted = deleted, dirty = true, serverVersion = 0, state = state, transferExpense = transferExpense,
)

fun CategoryDto.toEntity() = CategoryEntity(
    id = id, type = type, parentId = parentId, name = name, sortOrder = sortOrder, hidden = hidden,
    updatedAt = updatedAt, deleted = deleted, dirty = true, serverVersion = 0,
)

fun TxnDto.toEntity() = TxnEntity(
    id = id, type = type, dateTime = dateTime, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    amount = amount, toAmount = toAmount, currency = currency, amountMain = amountMain, accountAmount = accountAmount, fee = fee, note = note,
    description = description, repeatRuleId = repeatRuleId, installmentTotal = installmentTotal,
    installmentIndex = installmentIndex, updatedAt = updatedAt, deleted = deleted, dirty = true, serverVersion = 0,
)

fun MemoDto.toEntity() = MemoEntity(
    id = id, date = date, title = title, text = text, color = color, pinned = pinned, updatedAt = updatedAt,
    deleted = deleted, dirty = true, serverVersion = 0,
)

fun BookmarkDto.toEntity() = BookmarkEntity(
    id = id, name = name, type = type, accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
    amount = amount, currency = currency, note = note, description = description, createdAt = createdAt,
    updatedAt = updatedAt, deleted = deleted, dirty = true, serverVersion = 0,
)

fun RepeatRuleDto.toEntity() = RepeatRuleEntity(
    id = id, frequency = frequency, startDate = startDate, lastDate = lastDate, installments = installments, type = type,
    accountId = accountId, toAccountId = toAccountId, categoryId = categoryId, amount = amount, currency = currency,
    note = note, description = description, time = time, updatedAt = updatedAt, deleted = deleted, dirty = true, serverVersion = 0,
)

fun AppSettings.toDto() = SettingsDto(
    mainCurrency = mainCurrency, monthStartDay = monthStartDay, weekStart = weekStart.name, autocomplete = autocomplete,
    autocompleteSince = autocompleteSince, startScreen = startScreen.name, monthStartWeekend = monthStartWeekend.name,
    swipe = swipe.name, colorSet = colorSet.name, timeInput = timeInput.name, showDescription = showDescription,
    inputOrder = inputOrder.name, noteButton = noteButton, cardExpenseMode = cardExpenseMode.name, carryOver = carryOver, subcategory = subcategory,
    copyDateBy = copyDateBy.name,
)

private inline fun <reified T : Enum<T>> named(name: String, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default

/** The backed-up settings; [style] is the device's own, which a restore keeps. */
fun SettingsDto.toAppSettings(style: StyleMode) = AppSettings(
    mainCurrency = mainCurrency,
    monthStartDay = monthStartDay.coerceIn(1, 31),
    weekStart = named(weekStart, DayOfWeek.SUNDAY),
    autocomplete = autocomplete,
    autocompleteSince = autocompleteSince,
    startScreen = named(startScreen, StartScreen.DAILY),
    monthStartWeekend = named(monthStartWeekend, WeekendShift.NONE),
    swipe = named(swipe, SwipeMode.DATE),
    colorSet = named(colorSet, ColorSet.A),
    timeInput = named(timeInput, TimeInput.INPUT_DESC),
    showDescription = showDescription,
    inputOrder = named(inputOrder, InputOrder.AMOUNT),
    noteButton = noteButton,
    cardExpenseMode = named(cardExpenseMode, CardExpenseMode.AT_THE_TIME),
    carryOver = carryOver,
    subcategory = subcategory,
    copyDateBy = named(copyDateBy, CopyDateBy.TODAY),
    style = style,
)

fun CurrencyEntity.toDto() = CurrencyDto(
    code = code, symbol = symbol, position = position.name, decimals = decimals, rate = rate, sortOrder = sortOrder,
    updatedAt = updatedAt, deleted = deleted,
)

/** Restored rows are dirty, like every other restored row. The codec has checked [CurrencyDto.position]. */
fun CurrencyDto.toEntity() = CurrencyEntity(
    code = code, symbol = symbol, position = SymbolPosition.valueOf(position), decimals = decimals, rate = rate,
    sortOrder = sortOrder, updatedAt = updatedAt, deleted = deleted,
)

fun BudgetEntity.toDto() = BudgetDto(id = id, categoryId = categoryId, fromMonth = fromMonth, amount = amount, updatedAt = updatedAt, deleted = deleted)

fun BudgetDto.toEntity() = BudgetEntity(id = id, categoryId = categoryId, fromMonth = fromMonth, amount = amount, updatedAt = updatedAt, deleted = deleted)

fun AttachmentEntity.toDto() = AttachmentDto(id = id, txnId = txnId, fileName = fileName, sortOrder = sortOrder, updatedAt = updatedAt, deleted = deleted)

fun AttachmentDto.toEntity() = AttachmentEntity(id = id, txnId = txnId, fileName = fileName, sortOrder = sortOrder, updatedAt = updatedAt, deleted = deleted)
