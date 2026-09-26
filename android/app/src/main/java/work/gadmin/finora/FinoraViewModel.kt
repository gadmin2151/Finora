package work.gadmin.finora

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

enum class Page {
    CAPTURE,
    RECEIPTS,
    OVERVIEW,
    CHAT,
    FINANCES,
    PROFILE,
}

enum class CameraMode {
    QR,
    PHOTO,
    LONG_RECEIPT,
}

data class AppState(
    val starting: Boolean = true,
    val user: User? = null,
    val server: String = DEFAULT_SERVER,
    val organization: Organization? = null,
    val choosingOrganization: Boolean = false,
    val page: Page = Page.CAPTURE,
    val camera: CameraMode? = null,
    val receiptPageUrl: String? = null,
    val editingReceipt: Boolean = false,
    val busy: Boolean = false,
    val workspaceLoading: Boolean = false,
    val refreshing: Boolean = false,
    val lastRefreshedAt: Long? = null,
    val progress: Float? = null,
    val error: String? = null,
    val notice: String? = null,
    val draft: Draft = Draft(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val receipts: List<Receipt> = emptyList(),
    val receiptCount: Int = 0,
    val search: String = "",
    val receiptsLoading: Boolean = false,
    val detailId: String? = null,
    val detail: Receipt? = null,
    val detailLoading: Boolean = false,
    val comments: List<ReceiptComment> = emptyList(),
    val dashboard: Dashboard? = null,
    val insights: List<Insight> = emptyList(),
    val dashboardLoading: Boolean = false,
    val month: String = YearMonth.now().toString(),
    val chat: List<ChatMessage> = emptyList(),
    val chatJobs: List<ChatJob> = emptyList(),
    val chatDraft: String = "",
    val chatLoading: Boolean = false,
    val chatHasOlder: Boolean = true,
    val chatSyncError: String? = null,
)

class FinoraViewModel(application: Application) : AndroidViewModel(application) {
    private val sessions = SessionStore(application)
    private val mutable = MutableStateFlow(AppState(server = sessions.lastServer))
    val state = mutable.asStateFlow()
    val lastUsername
        get() = sessions.lastUsername

    private var api: ApiClient? = null
    private var drafts: DraftStore? = null
    private var workspaceJob: Job? = null
    private var receiptsJob: Job? = null
    private var detailJob: Job? = null
    private var dashboardJob: Job? = null
    private var chatJob: Job? = null
    private var localizedContentJob: Job? = null
    private var lastLanguage = LanguageRuntime.language
    private var localizedContentStale = false
    private var chatRequest: Pair<String, String>? = null
    private var avatarCache: Pair<String, ByteArray>? = null
    private val refresh =
        RefreshController(
            viewModelScope,
            onRefreshing = { loading -> mutable.update { it.copy(refreshing = loading) } },
            onError = ::handleError,
        )

    val finance =
        FinanceController(
            viewModelScope,
            { requireNotNull(api) },
            { mutable.value.organization },
            { mutable.value.month },
            { mutable.value.busy },
            ::writeAction,
            ::handleError,
            { notice ->
                mutable.update { it.copy(notice = notice) }
                loadDashboard()
            },
        )

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { sessions.read() }
            if (saved != null) {
                api = ApiClient(saved.server, saved.cookie).also { it.csrf = saved.user.csrf }
                try {
                    val user = requireNotNull(api).me()
                    persist(user)
                    mutable.update {
                        it.copy(user = user, server = saved.server, choosingOrganization = true)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (canUseSavedSession(error)) {
                        mutable.update {
                            it.copy(
                                user = saved.user,
                                server = saved.server,
                                choosingOrganization = true,
                                error =
                                    tr(
                                        Message
                                            .CANNOT_REACH_THE_SERVER_YOU_CAN_PREPARE_A_DRAFT_AND_SEND_I
                                    ),
                            )
                        }
                    } else handleError(error)
                }
            }
            mutable.update { it.copy(starting = false) }
        }
    }

    private suspend fun persist(user: User) {
        val client = requireNotNull(api)
        withContext(Dispatchers.IO) {
            sessions.save(SavedSession(client.server, client.savedCookie(), user))
        }
    }

    private fun handleError(error: Exception) {
        if (error is CancellationException) throw error
        if (error is ApiException && error.code == 401) {
            sessions.clear()
            cancelWorkspace()
            drafts = null
            mutable.update { AppState(starting = false, server = it.server, error = error.message) }
            return
        }
        val message =
            when (error) {
                is ApiException,
                is IllegalArgumentException ->
                    error.message ?: tr(Message.CHECK_THE_DETAILS_YOU_ENTERED)
                is javax.net.ssl.SSLException ->
                    tr(Message.COULD_NOT_VERIFY_THE_HTTPS_CERTIFICATE_CHECK_THE_SERVER_AD)
                is IOException ->
                    tr(Message.CANNOT_REACH_THE_SERVER_CHECK_YOUR_CONNECTION_AND_TRY_AGAI)
                else -> tr(Message.THE_ACTION_COULD_NOT_BE_COMPLETED_PLEASE_TRY_AGAIN)
            }
        mutable.update { it.copy(error = message) }
    }

    private fun writeAction(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        refresh.cancel()
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (error: Exception) {
                handleError(error)
            } finally {
                mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    fun login(server: String, username: String, password: String) = writeAction {
        require(username.isNotBlank() && password.isNotEmpty()) {
            tr(Message.ENTER_YOUR_USERNAME_AND_PASSWORD)
        }
        val client = ApiClient(serverOrigin(server))
        val user = client.login(username.trim(), password)
        api?.cancel()
        api = client
        persist(user)
        mutable.update {
            AppState(
                starting = false,
                user = user,
                server = client.server,
                choosingOrganization = true,
                busy = true,
            )
        }
    }

    fun chooseOrganization(show: Boolean = true) {
        if (!mutable.value.busy) {
            refresh.cancel()
            mutable.update { it.copy(choosingOrganization = show) }
        }
    }

    private fun cancelWorkspace() {
        refresh.cancel()
        finance.reset()
        workspaceJob?.cancel()
        receiptsJob?.cancel()
        detailJob?.cancel()
        dashboardJob?.cancel()
        chatJob?.cancel()
        localizedContentJob?.cancel()
        localizedContentStale = false
        chatRequest = null
    }

    fun selectOrganization(org: Organization) {
        if (
            mutable.value.busy ||
                mutable.value.user?.organizations?.none { it.id == org.id } != false
        )
            return
        cancelWorkspace()
        val current = mutable.value
        drafts =
            DraftStore(
                getApplication(),
                scopeKey(current.server, requireNotNull(current.user).id, org.id),
            )
        mutable.value =
            AppState(
                starting = false,
                user = current.user,
                server = current.server,
                organization = org,
                workspaceLoading = true,
            )
        workspaceJob = viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { requireNotNull(drafts).read() }
                mutable.update { it.copy(draft = draft) }
                fetchWorkspace(org.id)
            } catch (error: Exception) {
                handleError(error)
            } finally {
                if (currentCoroutineContext().isActive)
                    mutable.update { it.copy(workspaceLoading = false) }
            }
        }
        loadReceipts()
        loadDashboard()
    }

    fun navigate(page: Page) {
        if (mutable.value.busy) return
        closeDetail()
        mutable.update { it.copy(page = page, error = null, lastRefreshedAt = null) }
        if (localizedContentStale) refreshLocalizedContent()
        when (page) {
            Page.RECEIPTS -> loadReceipts()
            Page.OVERVIEW -> loadDashboard()
            Page.CHAT -> loadChat()
            Page.FINANCES -> finance.load()
            else -> Unit
        }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    fun reportError(message: String) = mutable.update { it.copy(error = message) }

    fun camera(mode: CameraMode?) {
        if (mutable.value.busy || mutable.value.workspaceLoading) return
        refresh.cancel()
        if (
            mode in setOf(CameraMode.PHOTO, CameraMode.LONG_RECEIPT) &&
                mutable.value.draft.photos.size >= MAX_PHOTOS
        ) {
            reportError(tr(Message.A_RECEIPT_CAN_HAVE_UP_TO_4_PHOTOS))
            return
        }
        mutable.update { it.copy(camera = mode, error = null) }
    }

    private suspend fun saveDraft(draft: Draft) {
        withContext(Dispatchers.IO) { requireNotNull(drafts).save(draft) }
        mutable.update { it.copy(draft = draft) }
    }

    fun setQr(value: String) = writeAction {
        require(mutable.value.draft.photos.isEmpty()) {
            tr(Message.YOUR_DRAFT_ALREADY_HAS_PHOTOS_SEND_OR_CLEAR_IT_BEFORE_SCAN)
        }
        saveDraft(
            mutable.value.draft.copy(qr = receiptLink(value), pageCaptured = false, pageText = "")
        )
        mutable.update { it.copy(camera = null) }
        submitDraft()
    }

    fun setAccount(id: String?) = writeAction {
        saveDraft(mutable.value.draft.copy(accountId = id))
    }

    fun photoFile(name: String): File? = drafts?.photo(name)

    fun addPhotos(uris: List<Uri>) = writeAction {
        require(uris.size + mutable.value.draft.photos.size <= MAX_PHOTOS) {
            tr(Message.CHOOSE_NO_MORE_THAN_1_S_PHOTOS, MAX_PHOTOS - mutable.value.draft.photos.size)
        }
        require(mutable.value.draft.qr.isBlank()) {
            tr(Message.SEND_OR_CLEAR_THE_CURRENT_QR_CODE_FIRST)
        }
        for (uri in uris) {
            val name = withContext(Dispatchers.IO) { requireNotNull(drafts).importPhoto(uri) }
            saveDraft(mutable.value.draft.copy(photos = mutable.value.draft.photos + name))
        }
    }

    fun addPhoto(file: File) = writeAction {
        try {
            require(
                mutable.value.draft.photos.size < MAX_PHOTOS && mutable.value.draft.qr.isBlank()
            ) {
                tr(Message.CLEAR_OR_SEND_YOUR_CURRENT_DRAFT)
            }
            val name = withContext(Dispatchers.IO) { requireNotNull(drafts).importPhoto(file) }
            saveDraft(mutable.value.draft.copy(photos = mutable.value.draft.photos + name))
            mutable.update { it.copy(camera = null) }
        } finally {
            file.delete()
        }
    }

    fun removePhoto(name: String) = writeAction {
        saveDraft(mutable.value.draft.copy(photos = mutable.value.draft.photos - name))
        withContext(Dispatchers.IO) { requireNotNull(drafts).remove(name) }
    }

    fun movePhoto(index: Int, delta: Int) = writeAction {
        val photos = mutable.value.draft.photos.toMutableList()
        if (index !in photos.indices || index + delta !in photos.indices) return@writeAction
        val name = photos.removeAt(index)
        photos.add(index + delta, name)
        saveDraft(mutable.value.draft.copy(photos = photos))
    }

    fun discardDraft() = writeAction {
        withContext(Dispatchers.IO) { requireNotNull(drafts).clear() }
        mutable.update { it.copy(draft = Draft()) }
    }

    fun sendDraft() = writeAction { submitDraft() }

    fun closeReceiptPage() {
        if (!mutable.value.busy) mutable.update { it.copy(receiptPageUrl = null) }
    }

    fun receiveReceiptPage(text: String, files: List<File>) = writeAction {
        val store = requireNotNull(drafts)
        val imported = mutableListOf<String>()
        try {
            require(files.size in 1..MAX_PHOTOS && mutable.value.draft.qr.isNotBlank())
            require(mutable.value.draft.photos.isEmpty()) {
                tr(Message.THE_DRAFT_ALREADY_CONTAINS_PHOTOS)
            }
            for (file in files) imported += withContext(Dispatchers.IO) { store.importPhoto(file) }
            saveDraft(
                mutable.value.draft.copy(
                    photos = imported.toList(),
                    pageCaptured = true,
                    pageText = text.take(50000),
                )
            )
            imported
                .clear() // From this point the durable draft owns the files, including on upload
            // failure.
            mutable.update { it.copy(receiptPageUrl = null) }
            submitDraft()
        } finally {
            withContext(Dispatchers.IO) {
                imported.forEach(store::remove)
                files.forEach { it.delete() }
            }
        }
    }

    private suspend fun submitDraft() {
        val current = mutable.value
        val org = requireNotNull(current.organization)
        require(current.draft.hasContent) { tr(Message.ADD_A_QR_CODE_OR_PHOTO) }
        if (current.draft.qr.isNotBlank() && !current.draft.pageCaptured) {
            mutable.update { it.copy(receiptPageUrl = current.draft.qr) }
            return
        }
        require(current.draft.photos.isNotEmpty()) {
            tr(Message.OPEN_THE_RECEIPT_PAGE_OR_ADD_A_RECEIPT_PHOTO_FIRST)
        }
        val result = run {
            mutable.update { it.copy(progress = 0f) }
            requireNotNull(api).upload(
                org.id,
                current.draft,
                current.draft.photos.map { requireNotNull(drafts).photo(it) },
            ) { progress ->
                mutable.update { it.copy(progress = progress) }
            }
        }
        withContext(Dispatchers.IO) { requireNotNull(drafts).clear() }
        mutable.update {
            it.copy(
                draft = Draft(),
                page = Page.RECEIPTS,
                notice =
                    if (result.duplicate) tr(Message.THIS_RECEIPT_IS_ALREADY_IN_YOUR_HISTORY)
                    else tr(Message.READING_YOUR_DRAFT_CHECK_THE_ITEMS_AND_TOTAL_BEFORE_CONFIR),
            )
        }
        loadReceipts()
        loadDashboard()
        openReceipt(result.receipt.id)
    }

    fun acceptReceipt(accountId: String) = writeAction {
        val current = mutable.value
        val receipt = requireNotNull(current.detail)
        val confirmed =
            requireNotNull(api)
                .acceptReceipt(
                    requireNotNull(current.organization).id,
                    receipt.id,
                    receipt.version,
                    accountId,
                )
        mutable.update {
            it.copy(detail = confirmed, notice = tr(Message.RECEIPT_CONFIRMED_EXPENSE_ADDED))
        }
        loadReceipts()
        loadDashboard()
    }

    fun editReceipt(editing: Boolean) {
        if (!mutable.value.busy) {
            refresh.cancel()
            mutable.update { it.copy(editingReceipt = editing, error = null) }
        }
    }

    fun confirmReview(form: ReceiptForm) = writeAction {
        val current = mutable.value
        val receipt = requireNotNull(current.detail)
        val confirmed =
            requireNotNull(api)
                .reviewReceipt(requireNotNull(current.organization).id, receipt.id, form)
        mutable.update {
            it.copy(
                detail = confirmed,
                editingReceipt = false,
                notice = tr(Message.RECEIPT_CONFIRMED_EXPENSE_ADDED),
            )
        }
        loadReceipts()
        loadDashboard()
    }

    fun search(value: String) {
        refresh.cancel()
        mutable.update { it.copy(search = value.take(100), lastRefreshedAt = null) }
        loadReceipts(debounce = true)
    }

    fun loadReceipts(more: Boolean = false, debounce: Boolean = false) {
        val current = mutable.value
        current.organization ?: return
        receiptsJob?.cancel()
        receiptsJob = viewModelScope.launch {
            if (debounce) delay(350)
            mutable.update { it.copy(receiptsLoading = true) }
            try {
                fetchReceipts(current, more)
            } catch (error: Exception) {
                handleError(error)
            } finally {
                if (currentCoroutineContext().isActive)
                    mutable.update { it.copy(receiptsLoading = false) }
            }
        }
    }

    fun openReceipt(id: String) {
        val org = mutable.value.organization ?: return
        refresh.cancel()
        detailJob?.cancel()
        mutable.update {
            it.copy(
                detailId = id,
                detail = it.receipts.firstOrNull { receipt -> receipt.id == id },
                detailLoading = true,
                editingReceipt = false,
                comments = if (it.detailId == id) it.comments else emptyList(),
                lastRefreshedAt = null,
            )
        }
        detailJob = viewModelScope.launch {
            try {
                fetchDetail(org.id, id)
            } catch (error: Exception) {
                handleError(error)
            } finally {
                if (currentCoroutineContext().isActive)
                    mutable.update { it.copy(detailLoading = false) }
            }
        }
    }

    fun closeDetail() {
        refresh.cancel()
        detailJob?.cancel()
        mutable.update {
            it.copy(
                detailId = null,
                detail = null,
                comments = emptyList(),
                detailLoading = false,
                editingReceipt = false,
            )
        }
    }

    suspend fun receiptPhoto(id: String, index: Int): ByteArray {
        val org = requireNotNull(mutable.value.organization)
        return requireNotNull(api).receiptPhoto(org.id, id, index)
    }

    suspend fun avatarPhoto(): ByteArray? {
        val user = mutable.value.user ?: return null
        val version = user.avatar_url ?: return null
        avatarCache
            ?.takeIf { it.first == version }
            ?.let {
                return it.second
            }
        val bytes = requireNotNull(api).avatar(user.id)
        currentCoroutineContext().ensureActive()
        if (mutable.value.user?.id == user.id) avatarCache = version to bytes
        return bytes
    }

    fun updateAvatar(uri: Uri?) = writeAction {
        val bytes = uri?.let {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(5 * 1024 * 1024 + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = stream.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    val raw = buffer.copyOf(count)
                    require(raw.size <= 5 * 1024 * 1024) { tr(Message.CHOOSE_A_PHOTO_UP_TO_5_MB) }
                    raw
                } ?: throw IllegalArgumentException(tr(Message.COULD_NOT_OPEN_THE_PHOTO))
            }
        }
        val user = requireNotNull(api).updateAvatar(bytes)
        persist(user)
        avatarCache = null
        mutable.update {
            it.copy(
                user = user,
                notice =
                    if (uri == null) tr(Message.PHOTO_REMOVED)
                    else tr(Message.PROFILE_PHOTO_UPDATED),
            )
        }
    }

    fun addComment(text: String, onSuccess: () -> Unit) = writeAction {
        val current = mutable.value
        require(text.isNotBlank() && text.length <= 2000) {
            tr(Message.A_COMMENT_MUST_CONTAIN_1_2_000_CHARACTERS)
        }
        requireNotNull(api)
            .comment(
                requireNotNull(current.organization).id,
                requireNotNull(current.detailId),
                text.trim(),
            )
        onSuccess()
        openReceipt(current.detailId)
    }

    fun retryReceipt() = writeAction {
        val current = mutable.value
        require(current.organization?.isAdmin == true) { tr(Message.AVAILABLE_TO_ADMINISTRATORS) }
        requireNotNull(api).retry(current.organization.id, requireNotNull(current.detailId))
        openReceipt(current.detailId)
        loadReceipts()
    }

    fun month(delta: Long) {
        val next = YearMonth.parse(mutable.value.month).plusMonths(delta)
        if (next.year !in 1990..2100 || mutable.value.busy) return
        refresh.cancel()
        mutable.update {
            it.copy(
                month = next.toString(),
                dashboard = null,
                insights = emptyList(),
                lastRefreshedAt = null,
            )
        }
        if (mutable.value.page == Page.OVERVIEW) loadDashboard()
        if (mutable.value.page == Page.FINANCES) finance.load()
    }

    fun loadDashboard() {
        val current = mutable.value
        current.organization ?: return
        dashboardJob?.cancel()
        dashboardJob = viewModelScope.launch {
            mutable.update { it.copy(dashboardLoading = true) }
            try {
                fetchDashboard(current)
            } catch (error: Exception) {
                handleError(error)
            } finally {
                if (currentCoroutineContext().isActive)
                    mutable.update { it.copy(dashboardLoading = false) }
            }
        }
    }

    /**
     * Refresh only server-owned UI copy; never re-authenticate, reset drafts or rewrite chat
     * history.
     */
    fun languageChanged(language: AppLanguage) {
        if (lastLanguage == language) return
        lastLanguage = language
        localizedContentJob?.cancel()
        localizedContentStale = true
        refreshLocalizedContent()
    }

    private fun refreshLocalizedContent() {
        val current = mutable.value
        val client = api ?: return
        val org = current.organization?.id ?: return
        if (current.user == null || localizedContentJob?.isActive == true) return
        localizedContentJob = viewModelScope.launch {
            try {
                val result = coroutineScope {
                    val insights = async { client.insights(org, current.month) }
                    val jobs = async { client.chatJobs(org) }
                    insights.await() to jobs.await()
                }
                ensureActive()
                mutable.update {
                    if (it.organization?.id == org && it.month == current.month)
                        it.copy(insights = result.first, chatJobs = result.second)
                    else it
                }
                localizedContentStale = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // Secondary refresh: retain the current screen and retry on the next navigation.
                localizedContentStale = true
            } catch (_: ApiException) {
                // Normal screen requests handle authentication; language changes preserve the
                // session.
                localizedContentStale = true
            } catch (error: Exception) {
                localizedContentStale = true
                handleError(error)
            }
        }
    }

    fun refreshProcessing() {
        val current = mutable.value
        if (
            current.busy ||
                current.refreshing ||
                current.editingReceipt ||
                current.camera != null ||
                current.receiptPageUrl != null
        )
            return
        if (current.page == Page.CHAT && current.detailId == null && !current.chatLoading)
            loadChat(background = true)
        if (current.detail?.isProcessing == true && !current.detailLoading)
            openReceipt(requireNotNull(current.detailId))
        else if (
            current.detailId == null &&
                current.receipts.any(Receipt::isProcessing) &&
                !current.receiptsLoading
        )
            loadReceipts()
    }

    fun refreshCurrent() {
        val current = mutable.value
        val org = current.organization ?: return
        if (
            current.busy || current.refreshing || current.workspaceLoading || current.editingReceipt
        )
            return
        when {
            current.detailId != null -> detailJob?.cancel()
            current.page == Page.RECEIPTS -> receiptsJob?.cancel()
            current.page == Page.OVERVIEW -> dashboardJob?.cancel()
            current.page == Page.CHAT -> chatJob?.cancel()
        }
        mutable.update {
            it.copy(
                error = null,
                lastRefreshedAt = null,
                receiptsLoading = if (current.page == Page.RECEIPTS) false else it.receiptsLoading,
                dashboardLoading =
                    if (current.page == Page.OVERVIEW) false else it.dashboardLoading,
                detailLoading = false,
                chatLoading = false,
            )
        }
        refresh.launch(
            onSuccess = { mutable.update { it.copy(lastRefreshedAt = System.currentTimeMillis()) } }
        ) {
            when {
                current.detailId != null -> fetchDetail(org.id, current.detailId)
                current.page == Page.RECEIPTS -> fetchReceipts(current)
                current.page == Page.OVERVIEW -> fetchDashboard(current)
                current.page == Page.CAPTURE -> fetchWorkspace(org.id)
                current.page == Page.CHAT -> fetchChat(current)
                current.page == Page.FINANCES -> finance.refresh()
                else -> {
                    val user = requireNotNull(api).me()
                    persist(user)
                    currentCoroutineContext().ensureActive()
                    val membership = user.organizations.firstOrNull { it.id == org.id }
                    mutable.update {
                        it.copy(
                            user = user,
                            organization = membership,
                            choosingOrganization = membership == null,
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchWorkspace(org: String) = coroutineScope {
        val client = requireNotNull(api)
        val accounts = async { client.accounts(org) }
        val categories = async { client.categories(org) }
        val resultAccounts = accounts.await().filterNot(Account::archived)
        val resultCategories = categories.await()
        ensureActive()
        mutable.update { it.copy(accounts = resultAccounts, categories = resultCategories) }
    }

    fun chatDraft(text: String) = mutable.update { it.copy(chatDraft = text.take(3000)) }

    fun sendChat(report: String? = null) = writeAction {
        val current = mutable.value
        val org = requireNotNull(current.organization).id
        val text =
            when (report) {
                "summary" -> tr(Message.SHOW_MY_FINANCIAL_SUMMARY)
                "categories" -> tr(Message.SHOW_SPENDING_BY_CATEGORY)
                "prices" -> tr(Message.COMPARE_PRICES_IN_MY_RECEIPTS)
                null -> current.chatDraft.trim()
                else -> throw IllegalArgumentException(tr(Message.UNKNOWN_REPORT))
            }
        require(text.isNotBlank()) { tr(Message.ENTER_A_QUESTION) }
        val signature = "$org:${current.month}:$report:$text"
        val key =
            chatRequest?.takeIf { it.first == signature }?.second
                ?: java.util.UUID.randomUUID().toString()
        chatRequest = signature to key
        requireNotNull(api).sendChat(org, text, current.month, key, report)
        chatRequest = null
        mutable.update {
            it.copy(chatDraft = if (it.chatDraft.trim() == text) "" else it.chatDraft)
        }
        fetchChat(current)
    }

    fun loadChat(older: Boolean = false, background: Boolean = false) {
        val current = mutable.value
        current.organization ?: return
        if (current.chatLoading || (older && !current.chatHasOlder)) return
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            mutable.update { it.copy(chatLoading = true) }
            try {
                fetchChat(current, older)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!background || error is ApiException && error.code in setOf(401, 403))
                    handleError(error)
                else
                    mutable.update {
                        it.copy(
                            chatSyncError =
                                tr(Message.NO_CONNECTION_SHOWING_THE_LATEST_LOADED_MESSAGES)
                        )
                    }
            } finally {
                if (currentCoroutineContext().isActive)
                    mutable.update { it.copy(chatLoading = false) }
            }
        }
    }

    private suspend fun fetchChat(current: AppState, older: Boolean = false) = coroutineScope {
        val org = requireNotNull(current.organization).id
        val client = requireNotNull(api)
        val messages = async {
            client.chat(org, if (older) current.chat.firstOrNull()?.id else null)
        }
        val jobs = async { client.chatJobs(org) }
        val result = messages.await()
        val resultJobs = jobs.await()
        ensureActive()
        mutable.update {
            if (it.organization?.id != org) it
            else
                it.copy(
                    chat = mergeChatMessages(it.chat, result),
                    chatJobs = resultJobs,
                    chatSyncError = null,
                    chatHasOlder =
                        if (older || it.chat.isEmpty())
                            result.size == 60 && it.chat.size + result.size < 600
                        else it.chatHasOlder,
                )
        }
    }

    private suspend fun fetchReceipts(current: AppState, more: Boolean = false) {
        val result =
            requireNotNull(api)
                .receipts(
                    requireNotNull(current.organization).id,
                    current.search,
                    if (more) current.receipts.size else 0,
                )
        currentCoroutineContext().ensureActive()
        mutable.update {
            it.copy(
                receipts =
                    if (more) (it.receipts + result.items).distinctBy(Receipt::id)
                    else result.items,
                receiptCount = result.total,
            )
        }
    }

    private suspend fun fetchDetail(org: String, id: String) = coroutineScope {
        val client = requireNotNull(api)
        val receipt = async { client.receipt(org, id) }
        val comments = async { client.comments(org, id) }
        val result = receipt.await()
        val resultComments = comments.await()
        ensureActive()
        mutable.update {
            it.copy(
                detail = result,
                comments = resultComments,
                receipts = it.receipts.map { row -> if (row.id == id) result else row },
            )
        }
    }

    private suspend fun fetchDashboard(current: AppState) = coroutineScope {
        val client = requireNotNull(api)
        val org = requireNotNull(current.organization).id
        val dashboard = async { client.dashboard(org, current.month) }
        val insights = async { client.insights(org, current.month) }
        val result = dashboard.await()
        val resultInsights = insights.await()
        ensureActive()
        mutable.update { it.copy(dashboard = result, insights = resultInsights) }
    }

    fun logout() = writeAction {
        var serverRevoked = false
        // Local sign-out must still work without a network connection.
        withTimeoutOrNull(5000) {
            try {
                requireNotNull(api).logout()
                serverRevoked = true
            } catch (error: IOException) {
                /* Delete the local credential below even when offline. */
            } catch (error: ApiException) {
                if (error.code == 401) serverRevoked = true
            }
        }
        withContext(Dispatchers.IO) { sessions.clear() }
        cancelWorkspace()
        api?.cancel()
        api = null
        avatarCache = null
        drafts = null
        mutable.update {
            AppState(
                starting = false,
                server = it.server,
                error =
                    if (serverRevoked) null
                    else tr(Message.YOU_ARE_SIGNED_OUT_ON_THIS_DEVICE_THE_SERVER_WAS_UNAVAILAB),
            )
        }
    }

    override fun onCleared() {
        api?.cancel()
    }
}
