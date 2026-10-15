package com.moneybook.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTest {
    private fun spans(text: String, typed: String) = highlightMatches(text, typed, Color.Red).spanStyles.map { it.start until it.end }

    @Test fun `every match is colored, ignoring case`() {
        assertEquals(listOf(0 until 1), spans("Market", "m"))
        assertEquals(listOf(4 until 5), spans("Pin money", "M"))
        assertEquals(listOf(5 until 9, 11 until 15), spans("Oběd jídlo jídlo", "JÍDL"))
        assertEquals(emptyList<IntRange>(), spans("Market", " "))
    }
}
