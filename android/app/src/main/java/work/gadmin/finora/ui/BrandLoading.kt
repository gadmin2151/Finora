package work.gadmin.finora.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The stationary 27G signature stays readable while the surrounding trace moves. */
@Composable
fun BrandPulse(modifier: Modifier = Modifier, animated: Boolean = true, progress: Float = 1f) {
    val turn =
        if (animated) {
            val motion = rememberInfiniteTransition(label = "27G loading")
            val value by
                motion.animateFloat(
                    0f,
                    360f,
                    infiniteRepeatable(tween(1800, easing = LinearEasing)),
                    label = "27G trace",
                )
            value
        } else 270f * progress.coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val stroke = w * .022f
            drawCircle(OutlineSoft, radius = w * .46f, style = Stroke(stroke))
            drawArc(
                Amber,
                turn - 90f,
                if (animated) 105f else 300f * progress.coerceIn(0f, 1f),
                false,
                topLeft = Offset(w * .04f, w * .04f),
                size = Size(w * .92f, w * .92f),
                style = Stroke(stroke * 1.4f, cap = StrokeCap.Round),
            )
        }
        BrandMark(Modifier.fillMaxSize(.82f).offset(y = (-3).dp))
        Text(
            "27G",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = Mint,
        )
    }
}

@Composable
fun BrandLoading(title: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        modifier =
            modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        color = SurfaceColor,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, OutlineSoft),
    ) {
        Row(
            Modifier.padding(if (compact) 14.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandPulse(Modifier.size(if (compact) 48.dp else 64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Ink, style = MaterialTheme.typography.titleSmall)
                if (!compact)
                    Text(
                        "Всё важное — в одном месте",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
    }
}

/** Use Material's nested scroll gesture with our indicator; do not consume form gestures. */
@Composable
fun RefreshSurface(
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pull = rememberPullToRefreshState()
    val ready by remember { derivedStateOf { pull.distanceFraction >= 1f } }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(ready) {
        if (ready && !refreshing && enabled)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(
        modifier.pullToRefresh(
            isRefreshing = refreshing,
            state = pull,
            enabled = enabled,
            onRefresh = onRefresh,
        )
    ) {
        content()
        if (pull.distanceFraction > .01f || refreshing) {
            Surface(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .graphicsLayer {
                        alpha = pull.distanceFraction.coerceIn(0f, 1f)
                        translationY =
                            (pull.distanceFraction.coerceAtMost(1.3f) - 1f) * 36.dp.toPx()
                    }
                    .semantics(mergeDescendants = true) {
                        if (refreshing) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        liveRegion = LiveRegionMode.Polite
                    },
                color = SurfaceRaised,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Green.copy(alpha = .3f)),
                shadowElevation = 8.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BrandPulse(Modifier.size(44.dp), refreshing, pull.distanceFraction)
                    Text(
                        if (refreshing) "Обновляем данные"
                        else if (ready) "Отпустите для обновления" else "Потяните ещё немного",
                        Modifier.padding(end = 6.dp),
                        color = Ink,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
