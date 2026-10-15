package com.moneybook.ui.more

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoreScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tilesOpenTheirScreens() {
        val opened = mutableListOf<String>()
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                MoreScreen(
                    onOpenConfiguration = { opened += "configuration" },
                    onOpenAccountSettings = { opened += "accounts" },
                    onOpenSync = { opened += "sync" },
                    onOpenBackup = { opened += "backup" },
                )
            }
        }
        listOf("Configuration", "Accounts", "Sync", "Backup").forEach { rule.onNodeWithText(it).performClick() }
        assertEquals(listOf("configuration", "accounts", "sync", "backup"), opened)
    }
}
