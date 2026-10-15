package com.moneybook.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * An outside activity (file picker, permission dialog) launched for a result. The lock is told before the launch that
 * MainActivity is about to stop, so coming back never locks, and again at the result, which drops the exemption if the
 * other screen never stopped MainActivity (see `AppLock.expectReturn`).
 */
class AppLauncher<I> internal constructor(private val before: () -> Unit, private val failed: () -> Unit, private val launcher: (I) -> Unit) {
    fun launch(input: I) {
        before()
        try {
            launcher(input)
        } catch (e: RuntimeException) {
            failed() // nothing opened, so the exemption must not excuse a later real trip
            throw e
        }
    }
}

@Composable
fun <I, O> rememberAppLauncher(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): AppLauncher<I> {
    val lock = appContainer().lock
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        lock.returned()
        onResult(result)
    }
    return remember(launcher, lock) { AppLauncher(lock::expectReturn, lock::returned, launcher::launch) }
}
