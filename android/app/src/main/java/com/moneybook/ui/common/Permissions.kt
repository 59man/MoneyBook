package com.moneybook.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Asks for POST_NOTIFICATIONS when Android 13+ needs it; [request] reports whether MoneyBook may post. */
class NotificationPermission internal constructor(private val ask: ((Boolean) -> Unit) -> Unit) {
    fun request(onResult: (Boolean) -> Unit) = ask(onResult)
}

@Composable
fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberAppLauncher(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(launcher) {
        NotificationPermission { onResult ->
            val needed = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (!needed) {
                onResult(true)
            } else {
                pending[0] = onResult
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
