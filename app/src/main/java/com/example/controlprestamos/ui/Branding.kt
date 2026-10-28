package com.example.controlprestamos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import com.example.controlprestamos.R
import com.example.controlprestamos.ui.theme.BrandAccents
import com.example.controlprestamos.ui.theme.readableForeground
import kotlin.math.roundToInt

@Composable
private fun CreatorSignature(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.85f)) {
    Text("created by WJenzo", modifier = modifier, color = color, fontSize = 13.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, fontFamily = FontFamily.SansSerif)
}

/** Original logo, layout, animation and signature from CalcShopping; no replacement artwork. */
@Composable
fun WJenzoSplash(accentColor: Color, progress: Float, error: String? = null, showRecovery: Boolean = error != null,
    actions: @Composable () -> Unit = {}) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(durationMillis = 350), label = "SplashProgress")
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B111E)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.wjenzo_background), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            if (error != null || showRecovery) Surface(color = Color(0xFF0B111E).copy(alpha = 0.94f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = Color(0xFFFFC2B5), style = MaterialTheme.typography.bodySmall) }
                    actions()
                }
            }
            Box(Modifier.width(250.dp).height(26.dp).progressSemantics(animated).clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF0B111E))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(animated).align(Alignment.CenterStart).background(accentColor))
                val percentage = "${(animated * 100).roundToInt()}%"
                Text(percentage, Modifier.fillMaxWidth().clearAndSetSemantics {}, color = Color.White,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(percentage, Modifier.fillMaxWidth().clearAndSetSemantics {}.drawWithContent {
                    val content = this
                    clipRect(right = size.width * animated) { content.drawContent() }
                }, color = readableForeground(accentColor), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            CreatorSignature()
            Spacer(Modifier.height(6.dp))
            Text("Versión $version", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            if (context.packageName == "com.example.controlprestamos.cliente") {
                ClientLegalLinks(contentColor = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
fun BrandAboutDialog(demonstration: Boolean = true, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} · ${PackageInfoCompat.getLongVersionCode(it)}" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(context.getString(R.string.app_name)) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.wj_2__2_), "Logo de WJenzo", Modifier.size(96.dp))
            Text("Administración de préstamos en soles.")
            Text("Versión $version", style = MaterialTheme.typography.labelMedium)
            Text(if (demonstration) "Versión de demostración · Datos ficticios locales" else "Datos de Google Sheets", style = MaterialTheme.typography.bodySmall)
            if (context.packageName == "com.example.controlprestamos.cliente") ClientLegalLinks()
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}

/** Public legal pages are available before Google consent and from About in the client build. */
@Composable
private fun ClientLegalLinks(contentColor: Color = MaterialTheme.colorScheme.primary) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    fun open(path: String) {
        runCatching { uri.openUri("https://tu-dominio.example/$path/") }
            .onFailure { android.widget.Toast.makeText(context, "No se pudo abrir el navegador.", android.widget.Toast.LENGTH_SHORT).show() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { open("privacidad") }, colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) {
            Text("Privacidad", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { open("condiciones") }, colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) {
            Text("Condiciones", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun AccentPicker(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cambiar color") }, text = {
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(BrandAccents) { index, (name, color) ->
                val chosen = selected == index
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxWidth().selectable(selected = chosen, role = Role.RadioButton,
                        onClick = { onSelect(index); onDismiss() }).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(32.dp).background(color, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            if (chosen) Text("✓", color = readableForeground(color), fontWeight = FontWeight.Bold)
                        }
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}
