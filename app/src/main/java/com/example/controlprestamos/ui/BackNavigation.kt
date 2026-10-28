package com.example.controlprestamos.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal fun recordMenu(history: List<Int>, next: Int): List<Int> =
    if (history.lastOrNull() == next) history else history + next

internal class ExitBackGuard(private val windowMillis: Long = 2_500) {
    private var firstPress: Long? = null
    fun press(now: Long): Boolean {
        val previous = firstPress
        if (previous != null && now - previous in 0..windowMillis) { reset(); return true }
        firstPress = now
        return false
    }
    fun reset() { firstPress = null }
}
internal val LocalExitBackGuard = staticCompositionLocalOf { ExitBackGuard() }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Child menus and dialogs handle Back first; only the root asks for a second press to exit. */
@Composable internal fun DoubleBackToExit(resetKey: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val guard = remember { ExitBackGuard() }
    var hint by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(resetKey) { guard.reset(); hint?.cancel() }
    DisposableEffect(Unit) { onDispose { hint?.cancel() } }
    CompositionLocalProvider(LocalExitBackGuard provides guard) {
        BackHandler {
            if (guard.press(SystemClock.elapsedRealtime())) {
                hint?.cancel(); context.activity()?.finish()
            } else {
                hint?.cancel()
                hint = Toast.makeText(context, "Pulsa Atrás otra vez para salir", Toast.LENGTH_SHORT).also { it.show() }
            }
        }
        content()
    }
}
