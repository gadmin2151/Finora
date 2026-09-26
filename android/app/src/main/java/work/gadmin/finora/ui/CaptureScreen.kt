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
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
                if (state.draft.hasContent) tr(Message.ONE_RECEIPT_EVERYTHING_TOGETHER)
                else tr(Message.LESS_ROUTINE_MORE_LIVING),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                if (state.draft.hasContent)
                    tr(Message.CHECK_YOUR_PHOTOS_AND_SEND_THEM_FOR_RECOGNITION)
                else tr(Message.ONE_PHOTO_AND_YOUR_PURCHASES_ARE_READY_FOR_YOUR_BUDGET),
                color = Muted,
            )
        }
        if (state.workspaceLoading)
            item { BrandLoading(tr(Message.OPENING_YOUR_ORGANIZATION), compact = true) }
        if (state.draft.hasContent) {
            item {
                Surface(shape = RoundedCornerShape(26.dp), color = SurfaceColor) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr(Message.RECEIPT_DRAFT),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    tr(Message.SAVED_ON_THIS_DEVICE),
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton({ discard = true }, enabled = available) {
                                LineIcon(Glyph.TRASH, tr(Message.DELETE_DRAFT), tint = Muted)
                            }
                        }
                        if (state.draft.qr.isNotBlank())
                            InfoCard(
                                if (state.draft.pageCaptured)
                                    tr(
                                        Message
                                            .THE_RECEIPT_PAGE_IS_SAVED_ON_YOUR_PHONE_AND_READY_FOR_RECO
                                    )
                                else
                                    tr(
                                        Message
                                            .YOUR_PHONE_WILL_OPEN_THE_RECEIPT_LINK_AND_SEND_THE_PAGE_FO
                                    ),
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
                                    Text(
                                        tr(Message.PART_1_S, index + 1),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        tr(Message.TAP_TO_REVIEW),
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (index > 0)
                                    IconButton({ vm.movePhoto(index, -1) }, enabled = available) {
                                        LineIcon(
                                            Glyph.BACK,
                                            tr(Message.MOVE_PART_1_S_EARLIER, index + 1),
                                            size = 19.dp,
                                        )
                                    }
                                IconButton({ vm.removePhoto(name) }, enabled = available) {
                                    LineIcon(
                                        Glyph.CLOSE,
                                        tr(Message.DELETE_PART_1_S, index + 1),
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
                                Text(tr(Message.ADD_A_RECEIPT_PART_1_S_4, state.draft.photos.size))
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
                                        ?.name ?: tr(Message.SERVER_DEFAULT_ACCOUNT),
                                    Modifier.weight(1f),
                                )
                                LineIcon(Glyph.DOWN, size = 18.dp)
                            }
                            DropdownMenu(accountMenu, { accountMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(tr(Message.SERVER_DEFAULT)) },
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
                                if (progress >= 1f) tr(Message.THE_SERVER_IS_RECEIVING_YOUR_RECEIPT)
                                else tr(Message.UPLOADING_PHOTOS_1_S, (progress * 100).toInt()),
                                color = Muted,
                            )
                        }
                        PrimaryButton(
                            if (state.busy) tr(Message.PROCESSING_CC91E)
                            else tr(Message.READ_RECEIPT),
                            vm::sendDraft,
                            Modifier.fillMaxWidth(),
                            available,
                            Glyph.ARROW,
                        )
                        Text(
                            tr(Message.ORGANIZATION_1_S, state.organization?.name),
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
                                tr(Message.TEXT_01_QUICK_START),
                                color = Amber,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 7.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr(Message.SCAN_QR_CODE),
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HeroInk,
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr(Message.POINT_AT_THE_CODE_AT_THE_BOTTOM_OF_YOUR_RECEIPT),
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
                        tr(Message.CAPTURE_RECEIPT),
                        tr(Message.SHORT_OR_LONG_RECEIPTS),
                        Glyph.CAMERA,
                        Modifier.weight(1f),
                        available && state.draft.photos.size < MAX_PHOTOS,
                    ) {
                        vm.camera(CameraMode.LONG_RECEIPT)
                    }
                    CaptureTile(
                        tr(Message.FROM_GALLERY),
                        tr(Message.EXISTING_PHOTOS),
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
                if (state.organization?.isAdmin == true) {
                    OutlinedButton(
                        vm::createManualReceipt,
                        Modifier.fillMaxWidth(),
                        enabled = available,
                    ) {
                        LineIcon(Glyph.RECEIPT, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr(Message.MANUAL_RECEIPT))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton({ manualQr = true }, Modifier.fillMaxWidth(), enabled = available) {
                    LineIcon(Glyph.LINK, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(Message.PASTE_A_RECEIPT_LINK))
                }
                Spacer(Modifier.height(8.dp))
                InfoCard(
                    tr(Message.FOR_A_SHORT_RECEIPT_ONE_FRAME_IS_ENOUGH_FOR_A_LONG_ONE_MOV),
                    Glyph.SPARK,
                )
            }
        item {
            Text(
                tr(Message.YOUR_SERVER_YOUR_DATA),
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
            title = { Text(tr(Message.ELECTRONIC_RECEIPT_LINK)) },
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
                    Text(tr(Message.ADD))
                }
            },
            dismissButton = { TextButton({ manualQr = false }) { Text(tr(Message.CANCEL)) } },
        )
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(tr(Message.DELETE_THIS_DRAFT)) },
            text = {
                Text(tr(Message.THIS_DRAFT_S_PHOTOS_WILL_BE_REMOVED_FROM_THE_DEVICE_SUBMIT))
            },
            confirmButton = {
                TextButton({
                    vm.discardDraft()
                    discard = false
                }) {
                    Text(tr(Message.DELETE))
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text(tr(Message.KEEP)) } },
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
        else Image(requireNotNull(image), tr(Message.RECEIPT_PHOTO), modifier, contentScale = scale)
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
                LineIcon(Glyph.CLOSE, tr(Message.CLOSE_PHOTO), tint = Color.White)
            }
        }
    }
}
