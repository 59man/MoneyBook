package com.moneybook.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReorderableColumnTest {
    @get:Rule val rule = createComposeRule()

    @Test fun draggingAHandlePastTwoRowsMovesTheItemAndSavesOnce() {
        var order by mutableStateOf(listOf("A", "B", "C"))
        val saved = mutableListOf<List<String>>()
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                ReorderableColumn(
                    items = order,
                    keyOf = { it },
                    onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
                    onDragEnd = { saved += order },
                ) { item, handle ->
                    Row(Modifier.fillMaxWidth().height(46.dp)) {
                        Text(item, Modifier.weight(1f))
                        Box(handle.size(46.dp).testTag("handle-$item"))
                    }
                }
            }
        }
        rule.onNodeWithTag("handle-A").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + height * 2.2f, durationMillis = 600)
        }
        rule.waitForIdle()
        assertEquals(listOf("B", "C", "A"), order)
        assertEquals(listOf(listOf("B", "C", "A")), saved)
    }
}
