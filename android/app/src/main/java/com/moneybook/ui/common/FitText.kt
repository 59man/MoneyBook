package com.moneybook.ui.common

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val MIN_SP = 4f
private const val STEP_SP = 0.5f

/**
 * Text that shrinks (down to 4 sp) when it does not fit its box: big amounts, narrow phones and
 * large system fonts. When it fits it renders exactly like Text with [style].
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    val autoSize = remember(style.fontSize) { WholeWordAutoSize(style.fontSize) }
    Text(text, modifier, color = color, style = style, textAlign = textAlign, maxLines = maxLines, autoSize = autoSize)
}

/**
 * The largest size, in 0.5 sp steps from 4 sp up to [max], at which the text fits its box without
 * splitting a word across lines. The stock step-based auto size would rather break "entertain|ment"
 * than shrink.
 */
private data class WholeWordAutoSize(val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        fun size(step: Int) = minOf(MIN_SP + step * STEP_SP, max.value)
        fun fitsAt(step: Int) = performLayout(constraints, text, size(step).sp).fitsWholeWords(constraints)
        var hi = ((max.value - MIN_SP) / STEP_SP).roundToInt()
        if (fitsAt(hi)) return max
        // Binary search: size(lo) is the best candidate so far, size(hi) is known not to fit.
        var lo = 0
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (fitsAt(mid)) lo = mid else hi = mid
        }
        return size(lo).sp
    }
}

private fun TextLayoutResult.fitsWholeWords(constraints: Constraints): Boolean {
    if (multiParagraph.didExceedMaxLines || multiParagraph.height > constraints.maxHeight) return false
    val text = layoutInput.text.text
    return (0 until lineCount - 1).none { line ->
        val end = getLineEnd(line)
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }
}
