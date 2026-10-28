package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable internal fun EmptyLoansMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(message, Modifier.widthIn(max = 320.dp), style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}
