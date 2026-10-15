package com.moneybook.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.compositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Realbyte's fingerprint/face unlock: the system prompt titled "Passcode" with a "Cancel" button, nothing else.
 * Open so tests can fake it through [LocalBiometricGate]; the real one is the default.
 */
open class BiometricGate {
    /** The activity whose prompt is on screen, so a second request (a resume while it shows) never stacks one. */
    private var showingFor: FragmentActivity? = null

    open fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the prompt; [onSuccess] runs only for a recognized biometric. Cancel, errors and misses do nothing. */
    open fun prompt(context: Context, onSuccess: () -> Unit) {
        val activity = context.findFragmentActivity() ?: return
        if (showingFor === activity && !activity.isDestroyed) return
        showingFor = activity
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    showingFor = null
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showingFor = null
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Passcode")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build(),
        )
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

val LocalBiometricGate = compositionLocalOf { BiometricGate() }
