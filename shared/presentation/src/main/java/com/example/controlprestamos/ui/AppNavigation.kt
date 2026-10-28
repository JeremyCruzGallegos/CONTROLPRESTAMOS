package com.example.controlprestamos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun AppNavigation(page: Int, labels: List<String>, icons: List<Int>,
    navigate: (Int) -> Unit, paymentsBadge: Int = 0) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        labels.indices.forEach { index ->
            val selected = page == index
            val tint by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(220), label = "Selección del menú")
            NavigationBarItem(selected = selected, onClick = { navigate(index) },
                icon = {
                    BadgedBox(badge = { if (index == 2 && paymentsBadge > 0) Badge { Text(paymentsBadge.toString()) } }) {
                        Icon(painterResource(icons[index]), null, Modifier.size(24.dp), tint = tint)
                    }
                }, label = { Text(labels[index], fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}
