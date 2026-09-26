package work.gadmin.finora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

/** Width-first reading, including receipts too tall to fit legibly on one screen. */
@Composable
fun ReceiptImageViewer(image: ImageBitmap, modifier: Modifier = Modifier) {
    var zoom by remember(image) { mutableFloatStateOf(1f) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(end = 60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${(zoom * 100).toInt()}%", Modifier.weight(1f), color = Mint)
            IconButton({ zoom = (zoom - .5f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                Text("−", style = MaterialTheme.typography.headlineSmall)
            }
            IconButton({ zoom = (zoom + .5f).coerceAtMost(3f) }, enabled = zoom < 3f) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(SurfaceColor)) {
            val displayWidth = maxWidth * zoom
            Box(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                Image(
                    image,
                    tr(Message.FULL_RECEIPT_PHOTO_SCROLL_DOWN),
                    Modifier.width(displayWidth).aspectRatio(image.width.toFloat() / image.height),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}
