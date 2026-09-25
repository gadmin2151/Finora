package work.gadmin.finora.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Decorative scan illustration. Motion follows the system animation duration scale. */
@Composable
fun ScanArtwork(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "Scan illustration")
    val progress by
        motion.animateFloat(
            initialValue = .22f,
            targetValue = .78f,
            animationSpec =
                infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Scan line",
        )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(Mint.copy(alpha = .08f), cornerRadius = CornerRadius(w * .22f))
        drawRoundRect(
            Mint.copy(alpha = .24f),
            cornerRadius = CornerRadius(w * .22f),
            style = Stroke(w * .008f),
        )
        val inset = w * .22f
        val edge = w * .14f
        listOf(
                Offset(inset, inset),
                Offset(w - inset, inset),
                Offset(inset, h - inset),
                Offset(w - inset, h - inset),
            )
            .forEach { corner ->
                val dx = if (corner.x < w / 2) edge else -edge
                val dy = if (corner.y < h / 2) edge else -edge
                drawLine(Mint, corner, corner + Offset(dx, 0f), w * .024f, StrokeCap.Round)
                drawLine(Mint, corner, corner + Offset(0f, dy), w * .024f, StrokeCap.Round)
            }
        for (row in 0..3) for (column in 0..3) {
            if ((row + column) % 3 != 1) {
                drawRoundRect(
                    Mint.copy(alpha = .4f),
                    Offset(w * (.33f + column * .09f), h * (.33f + row * .09f)),
                    Size(w * .05f, h * .05f),
                    CornerRadius(w * .008f),
                )
            }
        }
        drawLine(
            Mint.copy(alpha = .08f),
            Offset(w * .15f, h * progress),
            Offset(w * .85f, h * progress),
            h * .07f,
            StrokeCap.Round,
        )
        drawLine(
            Mint,
            Offset(w * .15f, h * progress),
            Offset(w * .85f, h * progress),
            h * .012f,
            StrokeCap.Round,
        )
    }
}
