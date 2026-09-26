package work.gadmin.finora.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LongReceiptReview(
    file: File,
    busy: Boolean,
    error: String?,
    onRetake: () -> Unit,
    onUse: () -> Unit,
) {
    val bitmap by
        produceState<Bitmap?>(null, file) {
            value =
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(
                        file.path,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        },
                    )
                }
        }
    Column(
        Modifier.fillMaxSize().background(Paper).safeDrawingPadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ваш чек", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Прокрутите до конца: все строки, итог и QR должны быть на месте. Снимок пока только на телефоне.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        val image = bitmap
        if (image == null)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BrandPulse(Modifier.size(64.dp))
            }
        else ReceiptImageViewer(image.asImageBitmap(), Modifier.weight(1f).fillMaxWidth())
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onRetake, Modifier.weight(1f), enabled = !busy) { Text("Переснять") }
            PrimaryButton(
                if (busy) "Сохраняем…" else "Использовать",
                onUse,
                Modifier.weight(1f),
                enabled = !busy && bitmap != null,
            )
        }
    }
}
