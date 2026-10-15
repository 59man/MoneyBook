package com.moneybook.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** Realbyte's budget help block: the budget icon in an accent circle, then [text] (clBudgetHelpBlock). */
@Composable
fun BudgetHelpCard(text: AnnotatedString) {
    Row(
        Modifier.fillMaxWidth().background(Mb.colors.background).heightIn(min = MbDimens.budgetHelpMin)
            .padding(horizontal = MbDimens.budgetHelpSide, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(MbDimens.budgetHelpRing).border(1.dp, Mb.colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            MbIcon(R.drawable.ic_budget, null, tint = Mb.colors.accent, size = 20.dp)
        }
        Spacer(Modifier.width(15.dp))
        Text(text, style = MbType.budgetHelp, color = Mb.colors.textSecondary)
    }
    Hairline()
}

/** "You can check out the budget status in **Trans. tab > Total page.**" */
fun budgetListHelp(bold: Color) = buildAnnotatedString {
    append("You can check out the budget status in ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = bold)) { append("Trans. tab > Total page.") }
}
