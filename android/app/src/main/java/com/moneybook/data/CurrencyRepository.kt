package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.CurrencyEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.money.Fx
import com.moneybook.shared.money.RealbyteCurrencies
import com.moneybook.shared.money.SymbolPosition
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The main currency and the currency table, as every screen needs them. */
data class CurrencyState(val main: String, val rows: List<CurrencyEntity>) {
    private val byCode = rows.associateBy { it.code }

    /** The table's display settings over Realbyte's defaults; a tombstone still counts (old entries keep their look). */
    fun spec(code: String): CurrencySpec {
        val d = RealbyteCurrencies.defaultSpec(code)
        val r = byCode[d.code] ?: return d
        return d.copy(symbol = r.symbol, position = r.position, display = r.decimals)
    }

    val mainSpec: CurrencySpec get() = spec(main)
    val subs: List<CurrencyEntity> get() = rows.filter { !it.deleted && it.code != main }.sortedBy { it.sortOrder }
    val subSpecs: List<CurrencySpec> get() = subs.map { spec(it.code) }

    /** The keypad strip: main first. */
    val stripSpecs: List<CurrencySpec> get() = listOf(mainSpec) + subSpecs
    val fx: Fx get() = Fx(main, rows.filter { it.code != main }.associate { it.code to (Fx.parseRate(it.rate) ?: BigDecimal.ONE) })
}

/** Main/Sub Currency Setting, and the conversions that changing a main or an account currency needs. */
class CurrencyRepository(
    private val db: MoneyBookDatabase,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    /** Backup's complete reset into a currency (Change → Data initialization). */
    private val reset: suspend (String) -> Unit,
) {
    private val dao get() = db.currencyDao()

    val state: Flow<CurrencyState> =
        combine(settings.settings.map { it.mainCurrency }.distinctUntilChanged(), dao.observeAll()) { main, rows -> CurrencyState(main, rows) }

    suspend fun current(): CurrencyState = CurrencyState(settings.current().mainCurrency, dao.allRows())

    private suspend fun write(row: CurrencyEntity) = dao.upsert(row.copy(updatedAt = time.millis(), dirty = true))

    private fun rowFor(code: String, rate: String, sortOrder: Int): CurrencyEntity {
        val d = RealbyteCurrencies.defaultSpec(code)
        return CurrencyEntity(d.code, d.symbol, d.position, d.display, rate, sortOrder, updatedAt = 0)
    }

    /** A product of rates can pass Fx.parseRate's 10 decimals; stored rates are rounded to them. */
    private fun rateText(rate: BigDecimal) = rate.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun nextOrder(s: CurrencyState) = (s.rows.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** Sub Currency Setting → + → a row. Offline the rate starts at 1 (Realbyte fetches it online). Main and duplicates are ignored. */
    suspend fun add(code: String): Boolean = db.withTransaction {
        val s = current()
        if (code == s.main || s.subs.any { it.code == code }) return@withTransaction false
        write(rowFor(code, "1", nextOrder(s)))
        true
    }

    suspend fun update(code: String, rate: BigDecimal, position: SymbolPosition, decimals: Int) {
        val row = dao.get(code) ?: return
        write(row.copy(rate = rateText(rate), position = position, decimals = decimals))
    }

    /** Main Currency Setting → Unit position / Decimal point. */
    suspend fun setMainDisplay(position: SymbolPosition, decimals: Int) {
        val main = settings.current().mainCurrency
        val row = dao.get(main) ?: rowFor(main, "1", 0)
        write(row.copy(position = position, decimals = decimals, rate = "1", deleted = false))
    }

    /** [codes] in display order; only rows whose position changed are written. */
    suspend fun reorder(codes: List<String>) = db.withTransaction {
        codes.forEachIndexed { i, c -> dao.get(c)?.takeIf { it.sortOrder != i }?.let { write(it.copy(sortOrder = i)) } }
    }

    /** False while a live account is in [code]: its balance would have no rate. Entries keep their amounts. */
    suspend fun delete(code: String): Boolean = db.withTransaction {
        if (db.accountDao().accounts().any { it.currency == code }) return@withTransaction false
        dao.get(code)?.let { write(it.copy(deleted = true)) }
        true
    }

    /**
     * Change → Data retention. [rate]: 1 old main = rate new main. Every main amount and sub-currency rate is
     * converted; the old main becomes a sub-currency, so accounts in it keep their balances.
     */
    suspend fun changeMainRetain(newCode: String, rate: BigDecimal) {
        val s = current()
        val old = s.main
        if (old == newCode) return
        db.withTransaction {
            val now = time.millis()
            val oldDec = CurrencySpec.isoDecimals(old)
            val newDec = CurrencySpec.isoDecimals(newCode)
            db.txnDao().allRows().map { t ->
                val main = BigDecimal.valueOf(t.amountMain, oldDec).multiply(rate).setScale(newDec, RoundingMode.HALF_UP)
                t.copy(amountMain = main.unscaledValue().toLong(), updatedAt = now, dirty = true)
            }.chunked(500).forEach { db.txnDao().upsertAll(it) }
            db.budgetDao().allRows().map { b ->
                val scaled = BigDecimal.valueOf(b.amount, oldDec).multiply(rate).setScale(newDec, RoundingMode.HALF_UP)
                b.copy(amount = scaled.unscaledValue().toLong(), updatedAt = now, dirty = true)
            }.let { db.budgetDao().upsertAll(it) }
            s.rows.filter { it.code != old && it.code != newCode }.forEach { r ->
                write(r.copy(rate = rateText((Fx.parseRate(r.rate) ?: BigDecimal.ONE).multiply(rate))))
            }
            val order = nextOrder(s)
            write((dao.get(old) ?: rowFor(old, "1", order)).copy(rate = rateText(rate), deleted = false))
            write((dao.get(newCode) ?: rowFor(newCode, "1", order + 1)).copy(rate = "1", deleted = false))
        }
        settings.setMainCurrency(newCode)
    }

    /** Change → Data initialization: Backup's complete reset, in [newCode]. */
    suspend fun changeMainInitialize(newCode: String) = reset(newCode)

    /** Account Info → Currency, after the [Notice]: its initial balance and its side of every entry, at the current rates. */
    suspend fun changeAccountCurrency(accountId: String, newCode: String) {
        val fx = current().fx
        db.withTransaction {
            val a = db.accountDao().get(accountId) ?: return@withTransaction
            if (a.currency == newCode) return@withTransaction
            val now = time.millis()
            fun side(t: TxnEntity): Long? = when (newCode) {
                t.currency -> null
                fx.main -> t.amountMain
                else -> fx.fromMain(t.amountMain, newCode)
            }
            db.txnDao().forAccount(accountId).map { t ->
                var u = t
                if (t.accountId == accountId) u = u.copy(accountAmount = side(t))
                if (t.toAccountId == accountId) u = u.copy(toAmount = side(t))
                u.copy(updatedAt = now, dirty = true)
            }.chunked(500).forEach { db.txnDao().upsertAll(it) }
            db.accountDao().upsert(
                a.copy(currency = newCode, initialBalance = fx.convert(a.initialBalance, a.currency, newCode), updatedAt = now, dirty = true),
            )
        }
    }
}
