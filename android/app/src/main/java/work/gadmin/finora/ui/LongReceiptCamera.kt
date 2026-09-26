package work.gadmin.finora.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Rational
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import work.gadmin.finora.capture.*

@Composable
fun LongReceiptCamera(
    busy: Boolean,
    error: String?,
    onClose: () -> Unit,
    onPhoto: (File) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            keepScreenOn = true
        }
    }
    var progress by remember { mutableStateOf(ScanProgress()) }
    var recording by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var discard by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var capturedFrames by remember { mutableIntStateOf(0) }
    val controller = remember {
        LongReceiptController(
            context.cacheDir,
            ContextCompat.getMainExecutor(context),
            {
                if (it.count > progress.count)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                progress = it
                if (it.limitReached) recording = false
                if (it.count == 0) working = false
            },
            {
                result = it
                working = false
                if (progress.warning)
                    captureError =
                        "Последние кадры не совместились. Проверьте, что весь чек, включая итог, попал в снимок."
            },
            {
                recording = false
                working = false
                captureError = it
            },
            { capturedFrames = it },
        )
    }
    val close = {
        if (progress.count > 0 || result != null) {
            controller.pause()
            recording = false
            discard = true
        } else onClose()
    }
    BackHandler { if (!busy && !working) close() }
    DisposableEffect(controller, owner) {
        val activity = context as? Activity
        val orientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.pause()
                recording = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            if (orientation != null) activity.requestedOrientation = orientation
            controller.close()
        }
    }
    DisposableEffect(owner, viewportSize, result != null) {
        val disposed = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
        val analysis =
            ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            androidx.camera.core.resolutionselector.AspectRatioStrategy
                                .RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        )
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(3840, 2160),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
        analysis.setAnalyzer(controller.executor, controller::analyze)
        if (viewportSize.width > 0 && viewportSize.height > 0 && result == null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (!disposed.get())
                        try {
                            provider = future.get()
                            val group =
                                UseCaseGroup.Builder()
                                    .addUseCase(preview)
                                    .addUseCase(analysis)
                                    .setViewPort(
                                        ViewPort.Builder(
                                                Rational(viewportSize.width, viewportSize.height),
                                                rotation,
                                            )
                                            .setScaleType(ViewPort.FILL_CENTER)
                                            .build()
                                    )
                                    .build()
                            camera =
                                provider?.bindToLifecycle(
                                    owner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    group,
                                )
                            if (torch) camera?.cameraControl?.enableTorch(true)
                        } catch (_: Exception) {
                            captureError =
                                "Не удалось открыть камеру. Закройте другое приложение с камерой и повторите."
                        }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            camera = null
        }
    }
    if (result != null) {
        LongReceiptReview(
            requireNotNull(result),
            busy || working,
            error ?: captureError,
            {
                captureError = null
                working = true
                result = null
                recording = false
                controller.reset()
            },
            {
                working = true
                controller.copyForDraft(requireNotNull(result)) {
                    working = false
                    onPhoto(it)
                }
            },
        )
    } else
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                { view },
                Modifier.fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(camera) {
                        detectTapGestures { point ->
                            camera
                                ?.cameraControl
                                ?.startFocusAndMetering(
                                    FocusMeteringAction.Builder(
                                            view.meteringPointFactory.createPoint(point.x, point.y)
                                        )
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build()
                                )
                        }
                    },
            )
            Canvas(Modifier.fillMaxSize()) {
                val width = size.width * SCAN_WINDOW_WIDTH
                val height = size.height * SCAN_WINDOW_HEIGHT
                val left = (size.width - width) / 2
                val top = (size.height - height) / 2
                drawRect(Color.Black.copy(alpha = .62f), size = Size(size.width, top))
                drawRect(
                    Color.Black.copy(alpha = .62f),
                    Offset(0f, top + height),
                    Size(size.width, top),
                )
                drawRect(Color.Black.copy(alpha = .62f), Offset(0f, top), Size(left, height))
                drawRect(
                    Color.Black.copy(alpha = .62f),
                    Offset(left + width, top),
                    Size(left, height),
                )
                drawRoundRect(
                    if (progress.warning) Amber else Mint,
                    Offset(left, top),
                    Size(width, height),
                    CornerRadius(18.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
                drawLine(
                    Mint.copy(alpha = .45f),
                    Offset(left + 12.dp.toPx(), top + height * .6f),
                    Offset(left + width - 12.dp.toPx(), top + height * .6f),
                    1.dp.toPx(),
                )
            }
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ close() }, enabled = !busy && !working) {
                        LineIcon(Glyph.CLOSE, "Закрыть съёмку чека", tint = Color.White)
                    }
                    Text(
                        "Съёмка чека",
                        Modifier.weight(1f),
                        color = Color.White,
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
                Text(
                    if (progress.count == 0)
                        "Наведите на белую бумагу. В рамке должна быть вся ширина чека."
                    else "Плавно ведите сверху вниз. Полоса чека собирается на ходу.",
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                progress.thumbnail?.let {
                    Image(
                        it.asImageBitmap(),
                        "Уже собранная часть чека",
                        Modifier.align(Alignment.End)
                            .size(45.dp, 110.dp)
                            .background(Color.White, RoundedCornerShape(5.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Surface(color = Forest, shape = RoundedCornerShape(18.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            if (working) "Завершаем снимок…" else progress.message,
                            color = if (progress.warning) Amber else Mint,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (capturedFrames > 0) {
                            Text(
                                "Кадров: $capturedFrames · участков: ${progress.count}",
                                color = Color.White.copy(alpha = .75f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            LinearProgressIndicator(
                                progress = {
                                    (progress.height.toFloat() / LONG_RECEIPT_MAX_HEIGHT).coerceIn(
                                        0f,
                                        1f,
                                    )
                                },
                                Modifier.fillMaxWidth(),
                                color = Mint,
                            )
                        }
                    }
                }
                (error ?: captureError)?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp),
                        color = Amber,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (recording)
                        OutlinedButton(
                            {
                                controller.pause()
                                recording = false
                            },
                            Modifier.weight(1f),
                            enabled = !working,
                        ) {
                            Text("Пауза", color = Color.White)
                        }
                    else
                        PrimaryButton(
                            if (progress.count == 0) "Начать" else "Продолжить",
                            {
                                captureError = null
                                controller.start()
                                recording = true
                            },
                            Modifier.weight(1f),
                            camera != null && !working && !progress.limitReached,
                        )
                    if (progress.count > 0)
                        PrimaryButton(
                            "Готово",
                            {
                                recording = false
                                working = true
                                controller.finish()
                            },
                            Modifier.weight(1f),
                            !working,
                            Glyph.CHECK,
                        )
                }
                if (progress.count == 0)
                    Text(
                        "Кадр каждые 0,3 с · фон убирается автоматически",
                        Modifier.padding(top = 10.dp),
                        color = Color.White.copy(alpha = .65f),
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
        }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Закрыть съёмку?") },
            text = {
                Text(
                    "Собранный снимок ещё не добавлен в черновик. Можно продолжить или переснять его позже."
                )
            },
            confirmButton = {
                TextButton({
                    discard = false
                    onClose()
                }) {
                    Text("Закрыть")
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text("Продолжить") } },
        )
}
