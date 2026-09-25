package work.gadmin.finora.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.Receipt

@Composable
fun ReceiptPhotos(receipt: Receipt, organization: String, vm: FinoraViewModel) {
    var selected by remember(receipt.id, organization) { mutableStateOf<Int?>(null) }
    var zoom by remember(selected) { mutableFloatStateOf(1f) }
    var offset by remember(selected) { mutableStateOf(Offset.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Фото чека · ${receipt.files.size}")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(receipt.files.indices.toList()) { index ->
                OutlinedCard(
                    onClick = { selected = index },
                    modifier = Modifier.size(105.dp, 130.dp),
                ) {
                    RemotePhoto(
                        receipt.id,
                        organization,
                        index,
                        vm,
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                    Text(
                        "Часть ${index + 1}",
                        Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
    selected?.let { index ->
        Dialog({ selected = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                RemotePhoto(
                    receipt.id,
                    organization,
                    index,
                    vm,
                    Modifier.fillMaxSize()
                        .transformable(
                            rememberTransformableState { change, pan, _ ->
                                zoom = (zoom * change).coerceIn(1f, 5f)
                                offset = if (zoom == 1f) Offset.Zero else offset + pan
                            }
                        )
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offset.x
                            translationY = offset.y
                        },
                    full = true,
                )
                IconButton(
                    { selected = null },
                    Modifier.align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Forest, RoundedCornerShape(50)),
                ) {
                    LineIcon(Glyph.CLOSE, "Закрыть фото", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RemotePhoto(
    id: String,
    organization: String,
    index: Int,
    vm: FinoraViewModel,
    modifier: Modifier,
    full: Boolean = false,
) {
    var retry by remember { mutableIntStateOf(0) }
    val result by
        produceState<Result<android.graphics.Bitmap>?>(null, id, organization, index, full, retry) {
            value =
                try {
                    val bytes = vm.receiptPhoto(id, index)
                    Result.success(
                        withContext(Dispatchers.Default) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            val options =
                                BitmapFactory.Options().apply {
                                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                    inSampleSize = 1
                                    while (
                                        maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize >
                                            if (full) 5000 else 500
                                    ) inSampleSize *= 2
                                }
                            requireNotNull(
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            )
                        }
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Result.failure(error)
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            result == null -> CircularProgressIndicator(Modifier.size(24.dp), color = Green)
            result?.isSuccess == true ->
                Image(
                    requireNotNull(result?.getOrNull()).asImageBitmap(),
                    "Часть ${index + 1} чека",
                    Modifier.fillMaxSize(),
                    contentScale = if (full) ContentScale.Fit else ContentScale.Crop,
                )
            else -> TextButton({ retry++ }) { Text("Повторить") }
        }
    }
}
