package com.example.controlprestamos.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.R

private val LocalSettingsEntered = staticCompositionLocalOf { true }

@Composable internal fun SettingsPanel(account: String, dark: Boolean, toggleTheme: () -> Unit,
    changeAccount: () -> Unit, canChangeAccount: Boolean, accountRestriction: String? = null,
    chooseColor: () -> Unit, about: () -> Unit, signOut: (() -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {}, active: Boolean = true, dismiss: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(active) { entered = active }
    CompositionLocalProvider(LocalSettingsEntered provides entered) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = dismiss) { Icon(painterResource(R.drawable.ic_back), "Volver a Inicio") }
                Text("Ajustes", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = toggleTheme) {
                    Icon(painterResource(if (dark) R.drawable.ic_sun else R.drawable.ic_moon),
                        if (dark) "Cambiar a tema claro" else "Cambiar a tema oscuro")
                }
            }
            Text(account, Modifier.padding(start = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsAction(R.drawable.ic_account, "Cambiar cuenta", changeAccount, canChangeAccount, order = 0)
            if (!canChangeAccount) accountRestriction?.let {
                Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsAction(R.drawable.ic_palette, "Color de la app", chooseColor, order = 1)
            extra()
            SettingsAction(R.drawable.ic_info, "Acerca de la app", about, order = 4)
            signOut?.let { SettingsAction(R.drawable.ic_logout, "Cerrar sesión", it, canChangeAccount,
                destructive = true, order = 5) }
        }
    }
}

/** Plain icon-and-name actions enter in sequence when the settings menu opens. */
@Composable internal fun SettingsAction(icon: Int, label: String, action: () -> Unit, enabled: Boolean = true,
    destructive: Boolean = false, order: Int = 0) {
    val entered = LocalSettingsEntered.current
    val progress by animateFloatAsState(if (entered) 1f else 0f,
        tween(340, delayMillis = order * 55, easing = FastOutSlowInEasing), label = "Entrada de $label")
    val foreground = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(onClick = action, enabled = enabled && entered,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer {
            alpha = progress
            translationY = 12.dp.toPx() * (1f - progress)
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = foreground)) {
        Icon(painterResource(icon), null, Modifier.size(23.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
