package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.buildAccountsOverview
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountGroupTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    @After fun tearDown() = db.close()

    @Test fun renamedAndReorderedGroupsShowOnTheAccountsTab() = runTest {
        val repo = AccountRepository(db, time)
        val accountsGroup = DefaultData.groupId(AccountGroupType.ACCOUNTS)
        repo.renameGroup(accountsGroup, " Bank ")
        repo.reorderGroups(listOf(accountsGroup) + repo.observeGroups().first().map { it.id }.filter { it != accountsGroup })
        assertEquals("Bank", repo.observeGroups().first().first().name)
        val overview = buildAccountsOverview(repo.observeBalances().first())
        assertEquals(listOf("Bank", "Cash", "Card"), overview.groups.map { it.name })
    }
}
