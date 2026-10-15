package com.moneybook.ui.more

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneybook.BuildConfig
import com.moneybook.R
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** Realbyte's "Settings" grid, three columns: Configuration, Accounts, Sync, then Backup. The passcode lives in Configuration. */
@Composable
fun MoreScreen(onOpenConfiguration: () -> Unit, onOpenAccountSettings: () -> Unit, onOpenSync: () -> Unit, onOpenBackup: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().height(MbDimens.topBar).background(Mb.colors.surface).padding(horizontal = MbDimens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MbType.screenTitle, color = Mb.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(BuildConfig.VERSION_NAME, style = MbType.gridLabel, color = Mb.colors.textSecondary)
        }
        Hairline()
        Row(Modifier.fillMaxWidth().background(Mb.colors.surface).padding(horizontal = MbDimens.sidePadding)) {
            GridTile(R.drawable.ic_settings, "Configuration", Modifier.weight(1f), onOpenConfiguration)
            GridTile(R.drawable.ic_nav_accounts, "Accounts", Modifier.weight(1f), onOpenAccountSettings)
            GridTile(R.drawable.ic_system_mode, "Sync", Modifier.weight(1f), onOpenSync)
        }
        Row(Modifier.fillMaxWidth().background(Mb.colors.surface).padding(horizontal = MbDimens.sidePadding)) {
            GridTile(R.drawable.ic_backup, "Backup", Modifier.weight(1f), onOpenBackup)
            Spacer(Modifier.weight(2f)) // keeps Backup one column wide
        }
    }
}

@Composable
private fun GridTile(@DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = MbDimens.gridTileHeight).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MbIcon(icon, null, tint = Mb.colors.textBody, size = 35.dp)
        Spacer(Modifier.height(9.dp))
        FitText(label, MbType.gridLabel, Mb.colors.textBody, maxLines = 2, textAlign = TextAlign.Center)
    }
}
