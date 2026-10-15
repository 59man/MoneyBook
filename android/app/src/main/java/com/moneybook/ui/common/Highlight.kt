package com.moneybook.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** [text] with every occurrence of [typed] (ignoring case) drawn in [color], as Realbyte's autocomplete list does. */
fun highlightMatches(text: String, typed: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val t = typed.trim()
    if (t.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val i = text.indexOf(t, from, ignoreCase = true)
        if (i < 0) break
        addStyle(SpanStyle(color = color), i, i + t.length)
        from = i + t.length
    }
}
