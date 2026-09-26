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
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
                tr(Message.THE_SERVER_DID_NOT_CREATE_A_SECURE_SESSION_CHECK_HTTPS_AND),
            )

    fun cancel() = client.dispatcher.cancelAll()

    private fun request(
        path: String,
        organization: String?,
        method: String = "GET",
        body: RequestBody? = null,
    ): Request {
        require(path.startsWith("/api/") && !path.contains("\\"))
        val url =
            origin.resolve(path) ?: throw IllegalArgumentException(tr(Message.INVALID_API_PATH))
        require(sameOrigin(url) && url.encodedPath.startsWith("/api/"))
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", LanguageRuntime.language.tag)
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
                                                tr(
                                                    Message
                                                        .YOUR_SESSION_EXPIRED_SIGN_IN_AGAIN_YOUR_DRAFT_IS_SAVED
                                                )
                                            403 ->
                                                tr(
                                                    Message
                                                        .YOU_DO_NOT_HAVE_PERMISSION_FOR_THIS_ACTION
                                                )
                                            409 ->
                                                tr(
                                                    Message
                                                        .THE_DATA_HAS_CHANGED_REFRESH_THE_SCREEN_AND_TRY_AGAIN
                                                )
                                            413 -> tr(Message.THE_PHOTOS_ARE_TOO_LARGE)
                                            429 ->
                                                tr(Message.TOO_MANY_ATTEMPTS_PLEASE_TRY_AGAIN_LATER)
                                            in 300..399 ->
                                                tr(
                                                    Message
                                                        .THE_SERVER_REDIRECTS_THIS_REQUEST_ENTER_ITS_FINAL_HTTPS_AD
                                                )
                                            else ->
                                                tr(
                                                    Message.SERVER_UNAVAILABLE_1_S_PLEASE_TRY_AGAIN,
                                                    it.code,
                                                )
                                        }
                                    throw ApiException(
                                        it.code,
                                        if (it.code >= 500) fallback
                                        else detail?.take(400) ?: fallback,
                                    )
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
        if (source.buffer.size > limit)
            throw IOException(tr(Message.THE_SERVER_RESPONSE_IS_TOO_LARGE))
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

    suspend fun avatar(userId: String): ByteArray =
        fetch(request("/api/users/$userId/avatar", null), 1024L * 1024)

    suspend fun updateAvatar(raw: ByteArray?): User {
        val body = raw?.let {
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "profile-photo",
                    it.toRequestBody("application/octet-stream".toMediaType()),
                )
                .build()
        }
        return json.decodeFromString(
            execute(
                request(
                    "/api/auth/avatar",
                    null,
                    if (raw == null) "DELETE" else "POST",
                    body,
                )
            )
        )
    }

    suspend fun logout() {
        execute(request("/api/auth/logout", null, "POST", jsonBody(buildJsonObject {})))
        cookie = null
    }

    suspend fun accounts(org: String): List<Account> = get("/api/accounts", org)

    suspend fun categories(org: String): List<Category> = get("/api/categories", org)

    suspend fun income(org: String, month: String): IncomeReport =
        get("/api/income?month=$month", org)

    suspend fun incomePlans(org: String): List<IncomePlan> = get("/api/income/templates", org)

    suspend fun debts(org: String): List<Debt> = get("/api/debts", org)

    suspend fun incomeHistory(
        org: String,
        month: String,
        offset: Int = 0,
        account: String? = null,
        limit: Int = 30,
    ): IncomeHistory {
        val url =
            origin
                .newBuilder()
                .encodedPath("/api/transactions")
                .addQueryParameter("kind", "income")
                .addQueryParameter("month", month)
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("limit", limit.toString())
                .apply { account?.let { addQueryParameter("account_id", it) } }
                .build()
        return get(url.encodedPath + "?" + url.encodedQuery, org)
    }

    suspend fun financeWrite(org: String, command: FinanceCommand) {
        execute(
            request(
                command.path,
                org,
                command.method,
                if (command.method == "DELETE") null
                else jsonBody(command.body ?: buildJsonObject {}),
            )
        )
    }

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

    suspend fun chat(org: String, before: String? = null): List<ChatMessage> {
        val url =
            origin
                .newBuilder()
                .encodedPath("/api/chat")
                .apply {
                    before?.let { addQueryParameter("before", it) }
                }
                .build()
        return get(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""), org)
    }

    suspend fun chatJobs(org: String): List<ChatJob> = get("/api/jobs", org)

    suspend fun sendChat(
        org: String,
        text: String,
        month: String,
        requestKey: String,
        report: String? = null,
    ): ChatResult =
        json.decodeFromString(
            execute(
                request(
                    "/api/chat",
                    org,
                    "POST",
                    jsonBody(
                        buildJsonObject {
                            put("text", text)
                            put("month", month)
                            put("request_key", requestKey)
                            report?.let { put("report", it) }
                        }
                    ),
                )
            )
        )

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
