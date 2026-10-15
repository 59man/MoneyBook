package com.moneybook.ui.memo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.MemoRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MemoEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private lateinit var memos: MemoRepository
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))

    @Before fun setUp() {
        db = inMemoryDb()
        memos = MemoRepository(db, time)
    }

    @After fun tearDown() = db.close()

    private suspend fun vm(id: String? = null, date: LocalDate? = null) =
        MemoEditViewModel(id, date, memos, time).also { it.state.first { s -> s.loaded } }

    private suspend fun MemoEditViewModel.closeAndWait() {
        close()
        events.first()
    }

    @Test fun aNewMemoIsSavedOnBack() = runTest {
        val vm = vm(date = LocalDate.of(2026, 9, 1))
        assertEquals(true, vm.state.value.isNew)
        vm.setTitle("Plan")
        vm.setText("Koupit chleba\nA mléko")
        vm.togglePin()
        vm.setColor(0xFF72D36C.toInt())
        vm.closeAndWait()
        val saved = db.memoDao().allRows().single()
        assertEquals("2026-09-01", saved.date)
        assertEquals("Plan", saved.title)
        assertEquals("Koupit chleba\nA mléko", saved.text)
        assertEquals(true, saved.pinned)
        assertEquals(0xFF72D36C.toInt(), saved.color)
    }

    @Test fun aNewMemoWithoutADateUsesToday() = runTest {
        assertEquals(LocalDate.of(2026, 9, 26), vm().state.value.date)
    }

    @Test fun aBlankNewMemoIsNotSaved() = runTest {
        val vm = vm()
        vm.setTitle("   ")
        vm.closeAndWait()
        assertEquals(emptyList<MemoEntity>(), db.memoDao().allRows())
    }

    @Test fun anUntouchedMemoIsNotRewrittenAndAnEditKeepsItsId() = runTest {
        db.memoDao().applyRemoteAll(listOf(MemoEntity(id = "m", date = "2026-09-20", title = "Old", updatedAt = 5, dirty = false)))
        vm("m").closeAndWait()
        assertEquals(MemoEntity(id = "m", date = "2026-09-20", title = "Old", updatedAt = 5, dirty = false), db.memoDao().get("m"))
        val vm = vm("m")
        assertEquals("Old", vm.state.value.title)
        vm.setDate(LocalDate.of(2026, 9, 21))
        vm.setTitle("New")
        vm.closeAndWait()
        val saved = db.memoDao().allRows().single()
        assertEquals("m", saved.id)
        assertEquals("2026-09-21", saved.date)
        assertEquals("New", saved.title)
    }

    @Test fun clearingAMemoKeepsWhatItSaid() = runTest {
        memos.save(MemoEntity(id = "m", date = "2026-09-20", title = "Old", updatedAt = 0))
        val vm = vm("m")
        vm.setTitle("")
        vm.closeAndWait()
        assertEquals("Old", db.memoDao().get("m")!!.title)
    }

    @Test fun deleteLeavesATombstone() = runTest {
        memos.save(MemoEntity(id = "m", date = "2026-09-20", title = "Old", updatedAt = 0))
        val vm = vm("m")
        vm.delete()
        vm.events.first()
        assertNull(memos.get("m"))
        assertEquals(true, db.memoDao().get("m")!!.deleted)
    }
}
