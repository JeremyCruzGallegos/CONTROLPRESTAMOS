package com.example.controlprestamos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Sits above the entire Scaffold, including the cash header and bottom navigation. */
@Composable internal fun SettingsDrawer(state: DrawerState, canOpen: Boolean,
    drawerContent: @Composable () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val exitGuard = LocalExitBackGuard.current
    LaunchedEffect(state.isOpen) { exitGuard.reset() }
    val open: () -> Unit = { scope.launch { state.open() }; Unit }
    val currentOpen by rememberUpdatedState(open)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = maxWidth * 0.85f
        val panelPixels = with(LocalDensity.current) { panelWidth.toPx() }
        val offset = state.currentOffset
        val progress = if (offset.isNaN()) { if (state.isOpen) 1f else 0f }
            else ((offset + panelPixels) / panelPixels).coerceIn(0f, 1f)
        ModalNavigationDrawer(drawerState = state,
            // The pager keeps leftward swipes. A rightward swipe on Home opens this drawer.
            gesturesEnabled = state.isOpen || state.isAnimationRunning,
            modifier = Modifier.openSettingsOnSwipe(canOpen && state.isClosed) { currentOpen() }
                .semantics {
                    if (canOpen && state.isClosed) customActions = listOf(
                        CustomAccessibilityAction("Abrir ajustes") { currentOpen(); true })
                },
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f),
            drawerContent = {
                ModalDrawerSheet(drawerState = state, modifier = Modifier.width(panelWidth)) {
                    drawerContent()
                }
            }) {
            Box(Modifier.fillMaxSize().blur((14f * progress).dp)) { content() }
        }
    }
}

/** Claim only rightward horizontal gestures before the child pager consumes them. */
private fun Modifier.openSettingsOnSwipe(enabled: Boolean, open: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var movement = Offset.Zero
            while (true) {
                var event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.size != 1) break
                movement += change.position - change.previousPosition
                if (abs(movement.y) > viewConfiguration.touchSlop && abs(movement.y) >= abs(movement.x)) break
                if (abs(movement.x) > viewConfiguration.touchSlop) {
                    if (movement.x > 0 && abs(movement.x) > abs(movement.y)) {
                        event.changes.forEach { it.consume() }
                        open()
                        do {
                            event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                    break
                }
            }
        }
    }
}
