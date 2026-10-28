package com.example.controlprestamos.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/** Pager owns the drag and settling animation; menu actions run only after a gesture settles. */
@Composable internal fun MenuPager(page: Int, order: List<Int>, navigate: (Int) -> Unit,
    modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    val target = order.indexOf(page).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = target) { order.size }
    val currentPage by rememberUpdatedState(page)
    val onNavigate by rememberUpdatedState(navigate)
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(target) {
        if (pager.settledPage != target || pager.currentPage != target) {
            requestedPage = target
            try { pager.animateScrollToPage(target, animationSpec = tween(380, easing = FastOutSlowInEasing)) }
            finally { requestedPage = null }
        }
    }
    LaunchedEffect(pager, order) {
        snapshotFlow { if (!pager.isScrollInProgress && requestedPage == null) pager.settledPage else null }
            .filterNotNull().distinctUntilChanged().collect { index ->
                val selected = order[index]
                if (selected != currentPage) onNavigate(selected)
            }
    }
    val background = MaterialTheme.colorScheme.background
    HorizontalPager(state = pager, key = { order[it] }, overscrollEffect = null, modifier = modifier.clipToBounds()) { index ->
        Box(Modifier.fillMaxSize().background(background)) { content(order[index]) }
    }
}
