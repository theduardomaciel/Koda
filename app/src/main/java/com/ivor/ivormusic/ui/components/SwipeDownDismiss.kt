package com.ivor.ivormusic.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How far down a panel has to travel before letting go closes it. A third is
 * the proportion the video player's other drags commit on, so its panels all
 * answer a swipe the same way.
 */
private const val SWIPE_DOWN_TRAVEL_FRACTION = 1f / 3f

/** A flick this fast closes a panel without the travel adding up. */
private val SWIPE_DOWN_FLING_DP_PER_SECOND = 400.dp

/**
 * A sheet-styled panel that closes on a downward swipe.
 *
 * It follows the finger wherever the drag starts: the leftover pull of a
 * scrollable inside it once that list reaches its top, or a finger on the
 * header, an empty state or the composer. A drag that starts on a list is
 * claimed by the list - a scrollable consumes its drag even when it cannot
 * scroll - and this wrapper takes over as soon as the list runs out.
 *
 * Letting go past a third of the panel's height, or on a 400 dp/s flick, calls
 * [onDismiss]; anything shorter springs back. The parent is
 * expected to drive its own exit animation from wherever the finger released
 * the panel, which is why the offset is not reset here - it belongs to this
 * composition, and a re-opened panel starts from rest.
 */
@Composable
fun SwipeDownDismissPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var panelHeight by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val flingPx = with(LocalDensity.current) { SWIPE_DOWN_FLING_DP_PER_SECOND.toPx() }

    fun shouldDismiss(velocityY: Float): Boolean =
        velocityY > flingPx || offset > panelHeight * SWIPE_DOWN_TRAVEL_FRACTION

    suspend fun settle() {
        animate(
            initialValue = offset,
            targetValue = 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        ) { value, _ -> offset = value }
    }

    val listDrag = remember(flingPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // An upward drag puts the panel back before the list scrolls.
                if (source != NestedScrollSource.UserInput ||
                    offset <= 0f || available.y >= 0f
                ) {
                    return Offset.Zero
                }
                val previous = offset
                offset = (offset + available.y).coerceAtLeast(0f)
                return Offset(0f, offset - previous)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Leftover downward drag means the list is already at its top.
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                offset = (offset + available.y).coerceAtMost(panelHeight.toFloat())
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                if (shouldDismiss(available.y)) currentOnDismiss() else settle()
                return available
            }
        }
    }

    val surfaceDrag = Modifier.pointerInput(flingPx) {
        val velocityTracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { velocityTracker.resetTracking() },
            onVerticalDrag = { change, dragAmount ->
                if (!change.isConsumed) {
                    change.consume()
                    offset = (offset + dragAmount).coerceIn(0f, panelHeight.toFloat())
                    velocityTracker.addPosition(change.uptimeMillis, Offset(0f, offset))
                }
            },
            onDragEnd = {
                if (shouldDismiss(velocityTracker.calculateVelocity().y)) {
                    currentOnDismiss()
                } else {
                    scope.launch { settle() }
                }
            },
            onDragCancel = { scope.launch { settle() } }
        )
    }

    Box(
        modifier = modifier
            .offset { IntOffset(0, offset.roundToInt()) }
            .nestedScroll(listDrag)
            .then(surfaceDrag)
            .onSizeChanged { panelHeight = it.height }
    ) {
        content()
    }
}
