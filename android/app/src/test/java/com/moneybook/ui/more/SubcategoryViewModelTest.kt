package com.moneybook.ui.more

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.CategoryRepository
import com.moneybook.data.TimeSource
import com.moneybook.shared.model.CategoryType
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubcategoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC))

    @Test fun theSwitchWritesTheSharedSetting() = runTest {
        val db = inMemoryDb()
        val settings = FakeSettingsRepository()
        val vm = CategorySettingViewModel(CategoryType.EXPENSE, CategoryRepository(db, time), settings)
        vm.setSubcategory(true)
        assertEquals(true, settings.state.first { it.subcategory }.subcategory)
        db.close()
    }

    @Test fun theSubcategoryListAddsRenamesAndReorders() = runTest {
        val db = inMemoryDb()
        val repo = CategoryRepository(db, time)
        db.categoryDao().upsert(Fixtures.category("food", name = "Food"))
        val vm = SubcategoryListViewModel("food", repo)
        val a = repo.addSub("food", "Lunch")
        val b = repo.addSub("food", "Dinner")
        repo.rename(a.id, "Brunch")
        assertEquals(listOf("Brunch", "Dinner"), repo.observeChildren("food").first().map { it.name })
        vm.reorder(listOf(b.id, a.id))
        assertEquals(listOf("Dinner", "Brunch"), repo.observeChildren("food").first().map { it.name })
        vm.delete(a.id)
        assertEquals(listOf("Dinner"), repo.observeChildren("food").first().map { it.name })
        db.close()
    }
}
