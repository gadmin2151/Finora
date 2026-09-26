package work.gadmin.finora.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import work.gadmin.finora.CameraMode
import work.gadmin.finora.data.receiptLink

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen(
    mode: CameraMode,
    busy: Boolean,
    photoCount: Int,
    onClose: () -> Unit,
    onPhoto: (File) -> Unit,
    onQr: (String) -> Unit,
    error: String?,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
        }
    LaunchedEffect(Unit) { if (!permission) requestPermission.launch(Manifest.permission.CAMERA) }
    BackHandler(!busy && (mode != CameraMode.LONG_RECEIPT || !permission)) { onClose() }
    if (!permission) {
        Column(
            Modifier.fillMaxSize().background(Paper).safeDrawingPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                "Нужен доступ к камере",
                "Камера используется только для QR-кодов и фотографий чеков.",
                Glyph.CAMERA,
            )
            PrimaryButton(
                "Разрешить камеру",
                { requestPermission.launch(Manifest.permission.CAMERA) },
                Modifier.fillMaxWidth(),
            )
            TextButton({
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri(),
                    )
                )
            }) {
                Text("Открыть настройки разрешений")
            }
            TextButton(onClose) { Text("Назад · можно выбрать фото из галереи") }
        }
        return
    }
    if (mode == CameraMode.LONG_RECEIPT) {
        LongReceiptCamera(busy, error, onClose, onPhoto)
        return
    }
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(0f) }
    val qrCallback by rememberUpdatedState(onQr)
    val errorCallback by rememberUpdatedState(onError)
    DisposableEffect(owner, mode) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        // Photo capture must not depend on the QR library being available.
        val scanner =
            if (mode == CameraMode.QR)
                try {
                    BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .enableAllPotentialBarcodes()
                            .setZoomSuggestionOptions(
                                ZoomSuggestionOptions.Builder { suggested ->
                                        val active = camera
                                        val limits = active?.cameraInfo?.zoomState?.value
                                        if (active == null || limits == null) false
                                        else {
                                            active.cameraControl.setZoomRatio(
                                                suggested.coerceIn(
                                                    limits.minZoomRatio,
                                                    minOf(4f, limits.maxZoomRatio),
                                                )
                                            )
                                            true
                                        }
                                    }
                                    .setMaxSupportedZoomRatio(4f)
                                    .build()
                            )
                            .build()
                    )
                } catch (_: Exception) {
                    errorCallback(
                        "Не удалось запустить QR-сканер. Сфотографируйте чек или вставьте ссылку на чек."
                    )
                    null
                }
            else null
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val disposed = AtomicBoolean(false)
        val found = AtomicBoolean(false)
        var lastInvalid = 0L
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        if (scanner != null)
            analysis.setAnalyzer(executor) { frame ->
                val media = frame.image
                if (disposed.get() || found.get() || media == null) frame.close()
                else
                    scanner
                        .process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener(mainExecutor) { codes ->
                            if (!disposed.get() && !found.get()) {
                                codes
                                    .firstOrNull { !it.rawValue.isNullOrBlank() }
                                    ?.rawValue
                                    ?.let { raw ->
                                        try {
                                            val link = receiptLink(raw)
                                            if (found.compareAndSet(false, true)) {
                                                view.performHapticFeedback(
                                                    if (android.os.Build.VERSION.SDK_INT >= 30)
                                                        HapticFeedbackConstants.CONFIRM
                                                    else HapticFeedbackConstants.LONG_PRESS
                                                )
                                                qrCallback(link)
                                            }
                                        } catch (invalid: IllegalArgumentException) {
                                            if (System.currentTimeMillis() - lastInvalid > 3000) {
                                                lastInvalid = System.currentTimeMillis()
                                                errorCallback(
                                                    invalid.message
                                                        ?: "Нужна ссылка на электронный чек"
                                                )
                                            }
                                        }
                                    }
                            }
                        }
                        .addOnFailureListener(mainExecutor) {
                            if (!disposed.get())
                                errorCallback(
                                    "Не удалось прочитать QR. Попробуйте снять чек или вставить ссылку."
                                )
                        }
                        .addOnCompleteListener { frame.close() }
            }
        future.addListener(
            {
                if (!disposed.get())
                    try {
                        provider = future.get()
                        camera =
                            if (mode == CameraMode.QR)
                                provider?.bindToLifecycle(
                                    owner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            else
                                provider?.bindToLifecycle(
                                    owner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture,
                                )
                    } catch (_: Exception) {
                        errorCallback(
                            "Камера недоступна. Закройте другие приложения с камерой или выберите фото из галереи."
                        )
                    }
            },
            mainExecutor,
        )
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis, capture)
            scanner?.close()
            executor.shutdown()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            { view },
            Modifier.fillMaxSize().pointerInput(camera) {
                detectTapGestures { point ->
                    val metering = view.meteringPointFactory.createPoint(point.x, point.y)
                    camera
                        ?.cameraControl
                        ?.startFocusAndMetering(
                            FocusMeteringAction.Builder(metering)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                        )
                }
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width * .79f
            val height = if (mode == CameraMode.QR) width else size.height * .52f
            val left = (size.width - width) / 2f
            val top = (size.height - height) / 2f
            drawRect(Color.Black.copy(alpha = .5f), size = Size(size.width, top))
            drawRect(
                Color.Black.copy(alpha = .5f),
                topLeft = Offset(0f, top + height),
                size = Size(size.width, top),
            )
            drawRect(
                Color.Black.copy(alpha = .5f),
                topLeft = Offset(0f, top),
                size = Size(left, height),
            )
            drawRect(
                Color.Black.copy(alpha = .5f),
                topLeft = Offset(left + width, top),
                size = Size(left, height),
            )
            drawRoundRect(
                Mint,
                Offset(left, top),
                Size(width, height),
                CornerRadius(20.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
            if (mode == CameraMode.QR)
                drawLine(
                    Mint.copy(alpha = .8f),
                    Offset(left + 12.dp.toPx(), size.height / 2),
                    Offset(left + width - 12.dp.toPx(), size.height / 2),
                    1.dp.toPx(),
                )
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose, enabled = !busy && !taking) {
                    LineIcon(Glyph.CLOSE, "Закрыть камеру", tint = Color.White)
                }
                Text(
                    if (mode == CameraMode.QR) "QR-код чека" else "Фото чека · ${photoCount + 1}/4",
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                ) {
                    LineIcon(
                        Glyph.FLASH,
                        if (torch) "Выключить фонарик" else "Включить фонарик",
                        tint = if (torch) Mint else Color.White,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                if (mode == CameraMode.QR)
                    "Наведите на QR внизу чека.\nКоснитесь кода для фокусировки."
                else
                    "Держите телефон параллельно чеку.\nЗахватите недостающую часть с небольшим перекрытием.",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            error?.let {
                Surface(color = Forest, shape = RoundedCornerShape(14.dp)) {
                    Text(
                        it,
                        Modifier.padding(14.dp),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Slider(
                value = zoom,
                onValueChange = {
                    zoom = it
                    camera?.cameraControl?.setLinearZoom(it)
                },
                modifier = Modifier.width(180.dp),
                enabled = camera != null,
            )
            if (mode == CameraMode.PHOTO) {
                Surface(
                    onClick = {
                        taking = true
                        val file = File.createTempFile("finora-capture-", ".jpg", context.cacheDir)
                        capture.targetRotation =
                            view.display?.rotation ?: android.view.Surface.ROTATION_0
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    taking = false
                                    onPhoto(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    taking = false
                                    file.delete()
                                    onError("Не удалось сделать фото. Попробуйте ещё раз.")
                                }
                            },
                        )
                    },
                    enabled = camera != null && !taking && !busy,
                    color = Color.White,
                    shape = CircleShape,
                    modifier =
                        Modifier.size(82.dp)
                            .border(4.dp, Color.White.copy(alpha = .6f), CircleShape)
                            .padding(6.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (taking || busy)
                            CircularProgressIndicator(Modifier.size(28.dp), color = Forest)
                        else LineIcon(Glyph.CAMERA, "Снять чек", tint = Forest, size = 30.dp)
                    }
                }
            } else Text("QR электронного чека", color = Mint, modifier = Modifier.padding(20.dp))
            Spacer(Modifier.height(14.dp))
        }
    }
}
