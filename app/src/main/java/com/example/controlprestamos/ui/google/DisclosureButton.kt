package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.R

@Composable internal fun DisclosureChevron(expanded: Boolean, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp).rotate(if (expanded) 180f else 0f), tint = color)
}

@Composable internal fun DisclosureButton(expanded: Boolean, label: String, toggle: () -> Unit) {
    IconButton(onClick = toggle, modifier = Modifier.size(48.dp).semantics {
        stateDescription = if (expanded) "Desplegado" else "Resumido"
    }) {
        Icon(painterResource(R.drawable.ic_expand_more), label, Modifier.size(26.dp).rotate(if (expanded) 180f else 0f))
    }
}
