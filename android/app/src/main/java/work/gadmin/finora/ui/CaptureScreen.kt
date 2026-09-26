package work.gadmin.finora.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.gadmin.finora.*
import work.gadmin.finora.data.*

@Composable
fun CaptureScreen(state: AppState, vm: FinoraViewModel) {
    var manualQr by rememberSaveable { mutableStateOf(false) }
    var qrText by rememberSaveable { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<File?>(null) }
    var accountMenu by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
        ) { uris ->
            if (uris.isNotEmpty()) vm.addPhotos(uris)
        }
    val available = !state.busy && !state.workspaceLoading
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                "27G FINORA / PERSONAL FINANCE",
                fontSize = 9.sp,
                letterSpacing = 1.7.sp,
                color = Green,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (state.draft.hasContent) "Один чек.\nВсё на месте."
                else "Меньше рутины.\nБольше жизни.",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                if (state.draft.hasContent) "Проверьте снимки и отправьте на распознавание."
                else "Один снимок — и покупки в вашем бюджете.",
                color = Muted,
            )
        }
        if (state.workspaceLoading)
            item { BrandLoading("Открываем вашу организацию", compact = true) }
        if (state.draft.hasContent) {
            item {
                Surface(shape = RoundedCornerShape(26.dp), color = SurfaceColor) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Черновик чека", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Сохранён на этом устройстве",
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton({ discard = true }, enabled = available) {
                                LineIcon(Glyph.TRASH, "Удалить черновик", tint = Muted)
                            }
                        }
                        if (state.draft.qr.isNotBlank())
                            InfoCard(
                                if (state.draft.pageCaptured)
                                    "Страница чека сохранена на телефоне и готова к распознаванию."
                                else
                                    "Телефон откроет ссылку на чек и передаст страницу на распознавание.",
                                Glyph.SCAN,
                            )
                        state.draft.photos.forEachIndexed { index, name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    onClick = { preview = vm.photoFile(name) },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    LocalPhoto(
                                        vm.photoFile(name),
                                        Modifier.size(70.dp, 86.dp),
                                        ContentScale.Crop,
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Часть ${index + 1}", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Нажмите, чтобы проверить",
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (index > 0)
                                    IconButton({ vm.movePhoto(index, -1) }, enabled = available) {
                                        LineIcon(
                                            Glyph.BACK,
                                            "Переместить часть ${index + 1} раньше",
                                            size = 19.dp,
                                        )
                                    }
                                IconButton({ vm.removePhoto(name) }, enabled = available) {
                                    LineIcon(
                                        Glyph.CLOSE,
                                        "Удалить часть ${index + 1}",
                                        tint = Muted,
                                        size = 20.dp,
                                    )
                                }
                            }
                        }
                        if (state.draft.qr.isBlank() && state.draft.photos.size < MAX_PHOTOS) {
                            OutlinedButton(
                                { vm.camera(CameraMode.PHOTO) },
                                Modifier.fillMaxWidth(),
                                enabled = available,
                                contentPadding = PaddingValues(14.dp),
                            ) {
                                LineIcon(Glyph.PLUS, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Добавить часть чека · ${state.draft.photos.size}/4")
                            }
                        }
                        Box {
                            OutlinedButton(
                                { accountMenu = true },
                                Modifier.fillMaxWidth(),
                                enabled = available,
                                contentPadding = PaddingValues(14.dp),
                            ) {
                                LineIcon(Glyph.WALLET, size = 19.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    state.accounts
                                        .firstOrNull { it.id == state.draft.accountId }
                                        ?.name ?: "Счёт по настройке сервера",
                                    Modifier.weight(1f),
                                )
                                LineIcon(Glyph.DOWN, size = 18.dp)
                            }
                            DropdownMenu(accountMenu, { accountMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("По настройке сервера") },
                                    onClick = {
                                        vm.setAccount(null)
                                        accountMenu = false
                                    },
                                )
                                state.accounts
                                    .filter { it.currency == "MDL" }
                                    .forEach { account ->
                                        DropdownMenuItem(
                                            text = { Text("${account.name} · MDL") },
                                            onClick = {
                                                vm.setAccount(account.id)
                                                accountMenu = false
                                            },
                                        )
                                    }
                            }
                        }
                        state.progress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (progress >= 1f) "Сервер принимает чек…"
                                else "Передаём фото: ${(progress * 100).toInt()}%",
                                color = Muted,
                            )
                        }
                        PrimaryButton(
                            if (state.busy) "Обрабатываем…" else "Распознать чек",
                            vm::sendDraft,
                            Modifier.fillMaxWidth(),
                            available,
                            Glyph.ARROW,
                        )
                        Text(
                            "Организация: ${state.organization?.name}",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            item {
                Surface(
                    onClick = { vm.camera(CameraMode.QR) },
                    enabled = available,
                    color = HeroStart,
                    shape = RoundedCornerShape(30.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.linearGradient(listOf(HeroStart, HeroEnd)))
                            .padding(26.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            BrandMark(Modifier.size(100.dp))
                            Text(
                                "01 / БЫСТРЫЙ СТАРТ",
                                color = Amber,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 7.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Сканировать QR",
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HeroInk,
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Наведите на код\nвнизу бумажного чека",
                                color = Mint.copy(alpha = .85f),
                                modifier = Modifier.weight(1f),
                            )
                            Surface(color = Amber, shape = RoundedCornerShape(50)) {
                                Box(Modifier.padding(14.dp)) {
                                    LineIcon(
                                        Glyph.ARROW,
                                        tint = MaterialTheme.colorScheme.onSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.draft.qr.isBlank())
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CaptureTile(
                        "Снять чек",
                        "Короткий или длинный",
                        Glyph.CAMERA,
                        Modifier.weight(1f),
                        available && state.draft.photos.size < MAX_PHOTOS,
                    ) {
                        vm.camera(CameraMode.LONG_RECEIPT)
                    }
                    CaptureTile(
                        "Из галереи",
                        "Готовые снимки",
                        Glyph.IMAGE,
                        Modifier.weight(1f),
                        available && state.draft.photos.size < MAX_PHOTOS,
                    ) {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                }
            }
        if (!state.draft.hasContent)
            item {
                TextButton({ manualQr = true }, Modifier.fillMaxWidth(), enabled = available) {
                    LineIcon(Glyph.LINK, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Вставить ссылку на чек")
                }
                Spacer(Modifier.height(8.dp))
                InfoCard(
                    "Короткий чек — один кадр. Длинный — ведите камеру сверху вниз. Получится одно фото для проверки.",
                    Glyph.SPARK,
                )
            }
        item {
            Text(
                "ВАШ СЕРВЕР · ВАШИ ДАННЫЕ",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (manualQr)
        AlertDialog(
            onDismissRequest = { manualQr = false },
            title = { Text("Ссылка на электронный чек") },
            text = {
                OutlinedTextField(
                    qrText,
                    { qrText = it.take(1000) },
                    label = { Text("https://…") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    {
                        vm.setQr(qrText)
                        manualQr = false
                    },
                    enabled = qrText.isNotBlank(),
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = { TextButton({ manualQr = false }) { Text("Отмена") } },
        )
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Удалить черновик?") },
            text = {
                Text(
                    "Фотографии этого черновика будут удалены с устройства. Отправленные чеки останутся на сервере."
                )
            },
            confirmButton = {
                TextButton({
                    vm.discardDraft()
                    discard = false
                }) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text("Оставить") } },
        )
    preview?.let { file -> PhotoDialog(file) { preview = null } }
}

@Composable
private fun CaptureTile(
    title: String,
    subtitle: String,
    glyph: Glyph,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = SurfaceColor,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LineIcon(glyph, size = 28.dp)
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LocalPhoto(
    file: File?,
    modifier: Modifier,
    scale: ContentScale = ContentScale.Fit,
    full: Boolean = false,
) {
    val image by
        produceState<ImageBitmap?>(null, file?.path) {
            value =
                withContext(Dispatchers.IO) {
                    file?.takeIf(File::isFile)?.let {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(it.path, bounds)
                        val options =
                            BitmapFactory.Options().apply {
                                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                inSampleSize = 1
                                while (
                                    maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize >
                                        if (full) 16_000 else 600
                                ) inSampleSize *= 2
                            }
                        BitmapFactory.decodeFile(it.path, options)?.asImageBitmap()
                    }
                }
        }
    if (image != null) {
        if (full) ReceiptImageViewer(requireNotNull(image), modifier)
        else Image(requireNotNull(image), "Фотография чека", modifier, contentScale = scale)
    } else
        Box(modifier.background(SoftGreen), contentAlignment = Alignment.Center) {
            LineIcon(Glyph.IMAGE)
        }
}

@Composable
private fun PhotoDialog(file: File, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            LocalPhoto(
                file,
                Modifier.fillMaxSize().padding(12.dp),
                full = true,
            )
            IconButton(
                onClose,
                Modifier.align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Forest, RoundedCornerShape(50)),
            ) {
                LineIcon(Glyph.CLOSE, "Закрыть фото", tint = Color.White)
            }
        }
    }
}
