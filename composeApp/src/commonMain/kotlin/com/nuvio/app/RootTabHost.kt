package com.nuvio.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.rememberLifecycleOwner
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.LocalUiMotion

@Composable
internal fun RootTabHost(
    selectedTab: AppScreenTab,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    profileId: Int? = null,
    content: @Composable (AppScreenTab) -> Unit,
) {
    val parentLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val hostActive = active && parentLifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val focusManager = LocalFocusManager.current
    DisposableEffect(selectedTab, hostActive, profileId) {
        onDispose { focusManager.clearFocus(force = true) }
    }

    key(profileId) {
        val tabStateHolder = rememberSaveableStateHolder()
        val visitedTabs = remember { mutableSetOf<AppScreenTab>() }
        val displayedTabs = remember(selectedTab) { (visitedTabs + selectedTab).toList() }
        SideEffect { visitedTabs += selectedTab }

        Layout(
            modifier = modifier.fillMaxSize(),
            content = {
                displayedTabs.forEach { tab ->
                    key(tab) {
                        RootTabPane(
                            tab = tab,
                            active = hostActive && tab == selectedTab,
                            stateHolder = tabStateHolder,
                            content = content,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val placeable = measurables[displayedTabs.indexOf(selectedTab)].measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }
    }
}

@Composable
private fun RootTabPane(
    tab: AppScreenTab,
    active: Boolean,
    stateHolder: SaveableStateHolder,
    content: @Composable (AppScreenTab) -> Unit,
) {
    val motion = LocalUiMotion.current
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(active, motion) {
        if (!active || motion.durationMillis(240) == 0) {
            entrance.snapTo(1f)
        } else {
            entrance.snapTo(0f)
            entrance.animateTo(1f, tween(motion.durationMillis(240), easing = FastOutSlowInEasing))
        }
    }
    val lifecycleOwner = rememberLifecycleOwner(
        maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    )
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalScreenActive provides active,
    ) {
        stateHolder.SaveableStateProvider(tab.name) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = entrance.value
                        translationY = if (motion.allowsSpatialEffects) (1f - entrance.value) * 12.dp.toPx() else 0f
                    }
                    .focusProperties { canFocus = active }
                    .pointerInput(active) {
                        if (!active) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                content(tab)
            }
        }
    }
}
