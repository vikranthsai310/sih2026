package org.itantra.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * `FLAG_SECURE`, applied by the screen that needs it rather than by whoever hosts it.
 * Task **W8.10**, item 2.
 *
 * ## What the audit found
 *
 * `PairingScreen` documented that "the activity hosting this screen **must** set
 * `FLAG_SECURE`" and exposed `pairingWindowFlags` for it to use. Nothing set it. There is
 * no pairing activity yet, so the requirement was a note addressed to code that does not
 * exist — and the pre-submission checklist would have found it in week 8 at the earliest,
 * or a jury would have found it by pressing the recents key.
 *
 * The QR code on that screen **is** the shared key. Without the flag it lands in the
 * recents thumbnail, in any screenshot, and in a screen recording — three places a key has
 * no business being, all of which outlive the pairing.
 *
 * ## Why it is a composable and not a note
 *
 * A requirement that depends on a future author reading a comment is not a control. Moving
 * it into the screen makes the flag travel with the thing that needs protecting: any
 * activity that hosts [PairingScreen] gets it, including one written next month by someone
 * who never opened this file.
 *
 * The flag is cleared on dispose, because it is not free — `FLAG_SECURE` also blocks
 * casting and non-secure external displays, and leaving it set for the life of the process
 * would break screen sharing on every other screen for a reason nobody could find.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * The activity behind a composable's context.
 *
 * A Compose `LocalContext` is often a `ContextThemeWrapper` rather than the activity, so
 * the chain has to be walked. Returns null in a preview or a test, where there is no
 * window — which is why every use above is null-safe rather than asserting.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
