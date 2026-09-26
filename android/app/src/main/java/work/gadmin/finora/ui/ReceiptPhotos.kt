package work.gadmin.finora.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.Receipt
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
fun ReceiptPhotos(receipt: Receipt, organization: String, vm: FinoraViewModel) {
    var selected by remember(receipt.id, organization) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(tr(Message.RECEIPT_PHOTO_1_S, receipt.files.size))
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
                        tr(Message.PART_1_S, index + 1),
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
                    Modifier.fillMaxSize().padding(12.dp),
                    full = true,
                )
                IconButton(
                    { selected = null },
                    Modifier.align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Forest, RoundedCornerShape(50)),
                ) {
                    LineIcon(Glyph.CLOSE, tr(Message.CLOSE_PHOTO), tint = Color.White)
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
                                            if (full) 16_000 else 500
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
            result == null -> BrandPulse(Modifier.size(48.dp))
            result?.isSuccess == true -> {
                val image = requireNotNull(result?.getOrNull()).asImageBitmap()
                if (full) ReceiptImageViewer(image, Modifier.fillMaxSize())
                else
                    Image(
                        image,
                        tr(Message.RECEIPT_PART_1_S, index + 1),
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
            }
            else -> TextButton({ retry++ }) { Text(tr(Message.RETRY)) }
        }
    }
}
