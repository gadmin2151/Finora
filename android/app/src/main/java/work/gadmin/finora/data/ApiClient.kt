package work.gadmin.finora.data

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

class ApiClient(val server: String, savedCookie: String? = null) {
    private val origin = serverOrigin(server).toHttpUrl()
    @Volatile private var cookie: Cookie? = savedCookie?.let { Cookie.parse(origin, it) }
    @Volatile var csrf: String = ""
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(
                object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        if (sameOrigin(url))
                            cookies
                                .firstOrNull {
                                    it.name == "finance_session" &&
                                        it.secure &&
                                        it.hostOnly &&
                                        it.domain == origin.host
                                }
                                ?.let { cookie = it }
                    }

                    override fun loadForRequest(url: HttpUrl): List<Cookie> =
                        cookie
                            ?.takeIf {
                                sameOrigin(url) &&
                                    it.matches(url) &&
                                    it.expiresAt > System.currentTimeMillis()
                            }
                            ?.let(::listOf) ?: emptyList()
                }
            )
            .build()

    private fun sameOrigin(url: HttpUrl) =
        url.scheme == "https" && url.host == origin.host && url.port == origin.port

    fun savedCookie(): String =
        cookie?.takeIf { it.expiresAt > System.currentTimeMillis() }?.toString()
            ?: throw ApiException(
                401,
                "Сервер не создал защищённую сессию. Проверьте HTTPS и Secure Cookie в настройках сервера.",
            )

    fun cancel() = client.dispatcher.cancelAll()

    private fun request(
        path: String,
        organization: String?,
        method: String = "GET",
        body: RequestBody? = null,
    ): Request {
        require(path.startsWith("/api/") && !path.contains("\\"))
        val url = origin.resolve(path) ?: throw IllegalArgumentException("Некорректный путь API")
        require(sameOrigin(url) && url.encodedPath.startsWith("/api/"))
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // Existing server protocol uses this CSRF guard for all first-party clients.
            .header("X-Finora-Client", "web")
            .header("User-Agent", "Finora-Android/1.0")
            .header("Origin", origin.toString().removeSuffix("/"))
            .apply {
                if (csrf.isNotEmpty()) header("X-CSRF-Token", csrf)
                if (organization != null) header("X-Organization-ID", organization)
            }
            .method(method, body)
            .build()
    }

    private suspend fun execute(request: Request): String = fetch(request).decodeToString()

    private suspend fun fetch(request: Request, limit: Long = 8L * 1024 * 1024): ByteArray {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                // API objects are bounded; never buffer an arbitrary HTML/file
                                // response.
                                val bytes = it.body.bytesBounded(limit)
                                if (!it.isSuccessful) {
                                    val detail = runCatching {
                                        json
                                            .parseToJsonElement(bytes.decodeToString())
                                            .jsonObject["detail"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                    }
                                        .getOrNull()
                                    val fallback =
                                        when (it.code) {
                                            401 ->
                                                "Сессия истекла. Войдите снова — черновик сохранён."
                                            403 -> "Недостаточно прав для этого действия"
                                            409 -> "Данные изменились. Обновите экран и повторите."
                                            413 -> "Фотографии слишком большие"
                                            429 -> "Слишком много попыток. Попробуйте позже."
                                            in 300..399 ->
                                                "Сервер перенаправляет запрос. Укажите его конечный HTTPS-адрес."
                                            else ->
                                                "Сервер недоступен (${it.code}). Попробуйте ещё раз."
                                        }
                                    throw ApiException(it.code, detail?.take(400) ?: fallback)
                                }
                                if (continuation.isActive) continuation.resume(bytes)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }
    }

    private fun ResponseBody.bytesBounded(limit: Long): ByteArray {
        val source = source()
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("Слишком большой ответ сервера")
        return source.readByteArray()
    }

    private fun jsonBody(value: JsonObject) =
        value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private suspend inline fun <reified T> get(path: String, org: String? = null): T =
        json.decodeFromString(execute(request(path, org)))

    suspend fun login(username: String, password: String): User =
        json
            .decodeFromString<User>(
                execute(
                    request(
                        "/api/auth/login",
                        null,
                        "POST",
                        jsonBody(
                            buildJsonObject {
                                put("username", username)
                                put("password", password)
                            }
                        ),
                    )
                )
            )
            .also {
                csrf = it.csrf
                savedCookie()
            }

    suspend fun me(): User = get<User>("/api/auth/me").also { csrf = it.csrf }

    suspend fun logout() {
        execute(request("/api/auth/logout", null, "POST", jsonBody(buildJsonObject {})))
        cookie = null
    }

    suspend fun accounts(org: String): List<Account> = get("/api/accounts", org)

    suspend fun categories(org: String): List<Category> = get("/api/categories", org)

    suspend fun receipts(org: String, search: String, offset: Int): ReceiptPage {
        val url =
            origin
                .newBuilder()
                .encodedPath("/api/receipts")
                .addQueryParameter("search", search.take(100))
                .addQueryParameter("offset", offset.toString())
                .build()
        return get(url.encodedPath + "?" + url.encodedQuery, org)
    }

    suspend fun receipt(org: String, id: String): Receipt = get("/api/receipts/$id", org)

    suspend fun receiptPhoto(org: String, id: String, index: Int): ByteArray {
        require(index in 0 until MAX_PHOTOS)
        return fetch(request("/api/receipts/$id/image/$index", org), 16L * 1024 * 1024)
    }

    suspend fun comments(org: String, id: String): List<ReceiptComment> =
        get("/api/receipts/$id/comments", org)

    suspend fun comment(org: String, id: String, text: String) {
        execute(
            request(
                "/api/receipts/$id/comments",
                org,
                "POST",
                jsonBody(buildJsonObject { put("text", text) }),
            )
        )
    }

    suspend fun dashboard(org: String, month: String): Dashboard =
        get("/api/dashboard?month=$month", org)

    suspend fun insights(org: String, month: String): List<Insight> =
        get("/api/insights?month=$month", org)

    suspend fun retry(org: String, id: String) {
        execute(request("/api/receipts/$id/retry", org, "POST", jsonBody(buildJsonObject {})))
    }

    suspend fun acceptReceipt(org: String, id: String, version: Int, accountId: String): Receipt =
        json.decodeFromString(
            execute(
                request(
                    "/api/receipts/$id/accept",
                    org,
                    "POST",
                    jsonBody(
                        buildJsonObject {
                            put("version", version)
                            put("account_id", accountId)
                        }
                    ),
                )
            )
        )

    suspend fun reviewReceipt(org: String, id: String, form: ReceiptForm): Receipt =
        json.decodeFromString(
            execute(request("/api/receipts/$id/review", org, "POST", jsonBody(form.payload())))
        )

    suspend fun link(org: String, draft: Draft): ReceiptResult =
        json.decodeFromString(
            execute(
                request(
                    "/api/receipts/link",
                    org,
                    "POST",
                    jsonBody(
                        buildJsonObject {
                            put("url", receiptLink(draft.qr))
                            put("review_required", true)
                            put("account_id", draft.accountId?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    ),
                )
            )
        )

    suspend fun upload(
        org: String,
        draft: Draft,
        files: List<File>,
        progress: (Float) -> Unit,
    ): ReceiptResult {
        require(files.size in 1..MAX_PHOTOS)
        val multipart =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("account_id", draft.accountId ?: "")
                .addFormDataPart("fx_rate", "1")
                .addFormDataPart("review_required", "true")
                .addFormDataPart("resolve_qr", "false")
                .apply {
                    if (draft.pageCaptured) {
                        addFormDataPart("page_url", draft.qr)
                        addFormDataPart("page_text", draft.pageText)
                    }
                    files.forEachIndexed { index, file ->
                        addFormDataPart(
                            "files",
                            "receipt-${index + 1}.jpg",
                            file.asRequestBody("image/jpeg".toMediaType()),
                        )
                    }
                }
                .build()
        val body =
            object : RequestBody() {
                override fun contentType() = multipart.contentType()

                override fun contentLength() = multipart.contentLength()

                override fun writeTo(sink: BufferedSink) {
                    var written = 0L
                    var lastPercent = -1
                    val tracking =
                        object : ForwardingSink(sink) {
                                override fun write(source: okio.Buffer, byteCount: Long) {
                                    super.write(source, byteCount)
                                    written += byteCount
                                    val percent = (written * 100 / contentLength()).toInt()
                                    if (percent != lastPercent) {
                                        progress(percent / 100f)
                                        lastPercent = percent
                                    }
                                }
                            }
                            .buffer()
                    multipart.writeTo(tracking)
                    tracking.flush()
                }
            }
        return json.decodeFromString(execute(request("/api/receipts/upload", org, "POST", body)))
    }
}
