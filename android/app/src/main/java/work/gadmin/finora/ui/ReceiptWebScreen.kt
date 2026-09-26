package work.gadmin.finora.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import work.gadmin.finora.data.publicReceiptAddress
import work.gadmin.finora.data.receiptLink

/** The document is downloaded by Android. API credentials never enter this WebView. */
private class ReceiptDocumentView(context: Context) : WebView(context) {
    // WebView documents onDraw + enableSlowWholeDocumentDraw for off-screen content.
    // View.draw would clip the image to the visible viewport.
    @SuppressLint("WrongCall") fun drawDocument(canvas: Canvas) = super.onDraw(canvas)
}

private class ReceiptPagePolicy {
    private val hosts = ConcurrentHashMap<String, Boolean>()

    fun permits(url: String): Boolean =
        try {
            val host = requireNotNull(URI(receiptLink(url)).host)
            if (hosts.size > 80) false
            else
                hosts.getOrPut(host) {
                    InetAddress.getAllByName(host).let {
                        it.isNotEmpty() && it.all(::publicReceiptAddress)
                    }
                }
        } catch (_: Exception) {
            false
        }
}

private suspend fun WebView.documentText(): String =
    withTimeout(5000) {
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript("(document.body ? document.body.innerText : '').slice(0,50000)") {
                result ->
                if (continuation.isActive)
                    continuation.resume(
                        runCatching { Json.decodeFromString<String>(result) }.getOrDefault("")
                    )
            }
        }
    }

private suspend fun WebView.dismissOptionalCookies() {
    val dismissed =
        withTimeout(5000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                evaluateJavascript(
                    """(() => {
                const labels = /^(Refuza toate|Respinge toate|Reject all|Decline all|Отклонить все)$/i;
                const roots = [document];
                let button = null, inspected = 0;
                while (roots.length && !button && inspected < 20000) {
                    const root = roots.pop();
                    for (const element of root.querySelectorAll('*')) {
                        if (++inspected > 20000) break;
                        if (element.shadowRoot) roots.push(element.shadowRoot);
                        if (!element.matches('button,[role="button"]') || !element.getBoundingClientRect().height) continue;
                        const label = (element.innerText || '').trim().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/\s+/g, ' ');
                        if (!labels.test(label)) continue;
                        let context = element.parentElement;
                        for (let depth = 0; context && depth < 6; depth++, context = context.parentElement) {
                            if (/cookie|куки/i.test(context.innerText || '')) { button = element; break; }
                        }
                        if (button) break;
                    }
                }
                if (!button) return false;
                button.click(); return true;
            })()"""
                ) { result ->
                    if (continuation.isActive) continuation.resume(result == "true")
                }
            }
        }
    if (dismissed) delay(500)
}

private suspend fun WebView.awaitDocumentDraw() {
    val ready =
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                postVisualStateCallback(
                    0,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                    },
                )
                invalidate()
            }
        }
    require(ready == true) { "Страница ещё обновляется. Повторите распознавание." }
}

@Suppress("DEPRECATION")
private suspend fun ReceiptDocumentView.captureDocument(): Pair<String, List<File>> {
    dismissOptionalCookies()
    evaluateJavascript(mevReceiptCleanup, null)
    awaitDocumentDraw()
    val text = documentText()
    val rawHeight = ceil(contentHeight * scale.toDouble()).toInt().coerceAtLeast(height)
    require(width > 0 && rawHeight > 0 && rawHeight <= 60000) {
        "Слишком длинная страница. Сфотографируйте чек частями."
    }
    val ratio = minOf(1f, 1440f / width)
    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = ceil(rawHeight * ratio.toDouble()).toInt()
    require(targetHeight <= 19800 && targetWidth.toLong() * targetHeight <= 25_000_000) {
        "Страница слишком большая. Сфотографируйте сам чек."
    }
    val oldX = scrollX
    val oldY = scrollY
    val files = mutableListOf<File>()
    scrollTo(0, 0)
    try {
        // Full-document drawing was enabled before the first WebView was constructed.
        delay(150)
        var top = 0
        while (top < targetHeight) {
            currentCoroutineContext().ensureActive()
            val tileHeight = minOf(5000, targetHeight - top)
            val bitmap = Bitmap.createBitmap(targetWidth, tileHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.translate(0f, -top.toFloat())
                canvas.scale(ratio, ratio)
                drawDocument(canvas)
                val file = File.createTempFile("receipt-page-", ".jpg", context.cacheDir)
                files += file
                withContext(Dispatchers.IO) {
                    file.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
                    }
                }
            } finally {
                bitmap.recycle()
            }
            if (top + tileHeight == targetHeight) break
            top += tileHeight - 80 // A small overlap prevents cutting item lines in half.
        }
        require(files.size in 1..4)
        return text to files
    } catch (failure: Exception) {
        files.forEach { it.delete() }
        throw failure
    } finally {
        scrollTo(oldX, oldY)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReceiptWebScreen(
    url: String,
    busy: Boolean,
    serverError: String?,
    onClose: () -> Unit,
    onCaptured: (String, List<File>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<ReceiptDocumentView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentHost by remember { mutableStateOf(URI(url).host.orEmpty()) }
    var reload by remember { mutableIntStateOf(0) }
    var crashed by remember { mutableStateOf(false) }
    val policy = remember { ReceiptPagePolicy() }
    val working = busy || capturing
    BackHandler { if (!working) onClose() }
    LaunchedEffect(web, reload) {
        val view = web ?: return@LaunchedEffect
        ready = false
        error = null
        val allowed = withContext(Dispatchers.IO) { policy.permits(url) }
        if (allowed) view.loadUrl(url)
        else error = "Страница недоступна или адрес не является публичным HTTPS-сайтом."
    }
    DisposableEffect(web) {
        val view = web
        onDispose {
            view?.stopLoading()
            view?.clearHistory()
            view?.clearCache(true)
            view?.removeAllViews()
            view?.destroy()
            // Receipt sites have no access to the separate OkHttp session used by Finora.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClose, enabled = !working) {
                        LineIcon(Glyph.CLOSE, "Закрыть страницу чека")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Электронный чек", style = MaterialTheme.typography.titleLarge)
                        Text(currentHost, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ if (!crashed) reload++ }, enabled = !working && !crashed) {
                        LineIcon(Glyph.REFRESH, "Обновить страницу чека")
                    }
                }
                if (progress < 100)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
            }
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column(
                    Modifier.navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Страницу открывает ваш телефон. Когда чек загрузится, отправьте его на распознавание.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    (error ?: serverError)?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PrimaryButton(
                        if (working) "Готовим чек…" else "Распознать этот чек",
                        {
                            scope.launch {
                                capturing = true
                                error = null
                                try {
                                    val view = requireNotNull(web)
                                    receiptLink(view.url ?: url)
                                    val (text, files) = view.captureDocument()
                                    onCaptured(text, files)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    error =
                                        failure.message?.take(200)
                                            ?: "Не удалось подготовить страницу. Повторите или сфотографируйте чек."
                                } finally {
                                    capturing = false
                                }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = ready && !working && !crashed,
                    )
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = {
                ReceiptDocumentView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        mediaPlaybackRequiresUserGesture = true
                        setGeolocationEnabled(false)
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    ServiceWorkerController.getInstance()
                        .serviceWorkerWebSettings
                        .blockNetworkLoads = true
                    setDownloadListener { _, _, _, _, _ ->
                        error =
                            "Сайт предлагает скачать файл. Сфотографируйте чек или откройте его веб-страницу."
                    }
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onPermissionRequest(request: PermissionRequest) {
                                request.deny()
                            }
                        }
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = runCatching {
                                receiptLink(request.url.toString())
                                false
                            }
                                .getOrDefault(true)

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                if (policy.permits(request.url.toString())) return null
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked",
                                    emptyMap(),
                                    ByteArrayInputStream(byteArrayOf()),
                                )
                            }

                            override fun onPageStarted(
                                view: WebView,
                                target: String,
                                favicon: Bitmap?,
                            ) {
                                ready = false
                                error = null
                                if (runCatching { receiptLink(target) }.isFailure) {
                                    view.stopLoading()
                                    error = "Разрешены только публичные HTTPS-страницы чеков"
                                } else currentHost = URI(target).host.orEmpty()
                            }

                            override fun onPageFinished(view: WebView, target: String) {
                                view.evaluateJavascript(mevReceiptCleanup) {
                                    ready = error == null && !crashed
                                }
                            }

                            override fun onPageCommitVisible(view: WebView, target: String) {
                                view.evaluateJavascript(mevReceiptCleanup, null)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                failure: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    ready = false
                                    error =
                                        "Телефон не смог открыть страницу. Проверьте интернет и повторите."
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                response: WebResourceResponse,
                            ) {
                                if (request.isForMainFrame) {
                                    ready = false
                                    error =
                                        "Сайт чека ответил ${response.statusCode}. Попробуйте другое подключение или фото чека."
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                failure: SslError,
                            ) {
                                handler.cancel()
                                ready = false
                                error = "Не удалось проверить HTTPS-сертификат сайта чека"
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                ready = false
                                crashed = true
                                error =
                                    "Страница использовала слишком много памяти. Закройте её и повторите или сфотографируйте чек."
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                return true
                            }
                        }
                    web = this
                }
            },
        )
    }
}
