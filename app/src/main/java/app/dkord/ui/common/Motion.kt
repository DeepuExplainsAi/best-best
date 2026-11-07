package app.dkord.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Short standard Compose animations automatically respect the system animator-duration scale. */
object DkordMotion {
    const val QUICK = 120
    const val CONTENT = 180
    const val SCREEN = 240
    const val SHEET = 280
    val easing = FastOutSlowInEasing
}

/** Keep Material ripple, semantics and long-press behavior; add only a 1.5% press response. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.softClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(DkordMotion.QUICK), label = "press")
    val visual = graphicsLayer { scaleX = scale; scaleY = scale }
    return if (onLongClick == null) visual.clickable(
        interactionSource = interactions, indication = LocalIndication.current, onClick = onClick,
    ) else visual.combinedClickable(
        interactionSource = interactions, indication = LocalIndication.current,
        onClick = onClick, onLongClick = onLongClick,
    )
}
