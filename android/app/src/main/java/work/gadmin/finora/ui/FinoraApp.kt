package work.gadmin.finora.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import work.gadmin.finora.*

@Composable
fun FinoraApp(vm: FinoraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbars.showSnackbar(it)
            vm.dismissNotice()
        }
    }
    LaunchedEffect(lifecycle, state.organization?.id) {
        if (state.organization != null)
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(5000)
                    vm.refreshProcessing()
                }
            }
    }
    when {
        state.starting ->
            Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    Brand()
                    BrandPulse(Modifier.size(96.dp))
                    Text("Открываем ваше пространство", color = Muted)
                }
            }
        state.user == null -> LoginScreen(state, vm)
        state.choosingOrganization || state.organization == null ->
            OrganizationScreen(
                state,
                vm::selectOrganization,
                { vm.chooseOrganization(false) },
                vm::logout,
            )
        state.camera != null ->
            CameraScreen(
                requireNotNull(state.camera),
                state.busy,
                state.draft.photos.size,
                onClose = { vm.camera(null) },
                onPhoto = vm::addPhoto,
                onQr = vm::setQr,
                error = state.error,
                onError = vm::reportError,
            )
        state.receiptPageUrl != null ->
            ReceiptWebScreen(
                requireNotNull(state.receiptPageUrl),
                state.busy,
                state.error,
                vm::closeReceiptPage,
                vm::receiveReceiptPage,
            )
        state.editingReceipt && state.detail != null -> ReceiptEditor(state, vm)
        else -> {
            val composingChat =
                state.page == Page.CHAT &&
                    state.detailId == null &&
                    WindowInsets.ime.getBottom(LocalDensity.current) > 0
            BackHandler(state.detailId != null) { if (!state.busy) vm.closeDetail() }
            Scaffold(
                containerColor = Paper,
                snackbarHost = { SnackbarHost(snackbars) },
                topBar = {
                    if (composingChat) Spacer(Modifier.statusBarsPadding())
                    else
                        Column(Modifier.statusBarsPadding()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.detailId != null)
                                    IconButton(vm::closeDetail, enabled = !state.busy) {
                                        LineIcon(Glyph.BACK, "Назад к чекам")
                                    }
                                Surface(
                                    onClick = { vm.chooseOrganization() },
                                    enabled = !state.busy,
                                    color = Color.Transparent,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Row(
                                        Modifier.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Surface(
                                            color = SoftGreen,
                                            shape = RoundedCornerShape(13.dp),
                                        ) {
                                            Box(Modifier.padding(10.dp)) {
                                                LineIcon(Glyph.HOME, size = 20.dp)
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "ОРГАНИЗАЦИЯ",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Muted,
                                            )
                                            Text(
                                                requireNotNull(state.organization).name,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        LineIcon(Glyph.DOWN, "Выбрать организацию", size = 18.dp)
                                    }
                                }
                                Spacer(Modifier.width(18.dp))
                                Surface(
                                    onClick = { vm.navigate(Page.PROFILE) },
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = "Открыть профиль"
                                        },
                                    color = Forest,
                                    shape = CircleShape,
                                    enabled = !state.busy,
                                ) {
                                    ProfileAvatar(state.user, vm)
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val updated = state.lastRefreshedAt
                                Text(
                                    if (updated == null) "Потяните вниз, чтобы обновить"
                                    else
                                        "Обновлено в " +
                                            Instant.ofEpochMilli(updated)
                                                .atZone(ZoneId.systemDefault())
                                                .format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                                    Modifier.weight(1f).semantics {
                                        contentDescription =
                                            if (updated == null) "Обновление жестом сверху вниз"
                                            else "Данные обновлены"
                                    },
                                    color = if (updated == null) Muted else Green,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                IconButton(
                                    vm::refreshCurrent,
                                    enabled =
                                        !state.busy && !state.refreshing && !state.workspaceLoading,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    LineIcon(Glyph.REFRESH, "Обновить данные", size = 18.dp)
                                }
                            }
                            state.error?.let { message ->
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            message,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        IconButton(vm::dismissError) {
                                            LineIcon(
                                                Glyph.CLOSE,
                                                "Скрыть сообщение",
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                size = 18.dp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                },
                bottomBar = {
                    if (state.detailId == null && !composingChat)
                        Surface(
                            modifier =
                                Modifier.navigationBarsPadding()
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                            color = SurfaceColor,
                            shape = RoundedCornerShape(26.dp),
                            border = BorderStroke(1.dp, Border.copy(alpha = .65f)),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf(
                                        Triple(Page.OVERVIEW, "Обзор", Glyph.CHART),
                                        Triple(Page.FINANCES, "Финансы", Glyph.WALLET),
                                        Triple(Page.CAPTURE, "Добавить", Glyph.SCAN),
                                        Triple(Page.RECEIPTS, "Чеки", Glyph.RECEIPT),
                                        Triple(Page.CHAT, "Помощник", Glyph.SPARK),
                                    )
                                    .forEach { (page, label, glyph) ->
                                        val capture = page == Page.CAPTURE
                                        val selected = state.page == page
                                        Column(
                                            Modifier.weight(1f)
                                                .heightIn(min = 72.dp)
                                                .selectable(
                                                    selected,
                                                    enabled = !state.busy,
                                                    role = Role.Tab,
                                                    onClick = { vm.navigate(page) },
                                                )
                                                .semantics {
                                                    contentDescription =
                                                        if (capture) "Добавить чек" else label
                                                }
                                                .padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement =
                                                Arrangement.spacedBy(
                                                    2.dp,
                                                    Alignment.Bottom,
                                                ),
                                        ) {
                                            if (capture)
                                                Surface(
                                                    color = Mint,
                                                    shape = CircleShape,
                                                    shadowElevation = 6.dp,
                                                    modifier = Modifier.size(48.dp),
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        LineIcon(glyph, tint = Forest, size = 27.dp)
                                                    }
                                                }
                                            else
                                                Surface(
                                                    color =
                                                        if (selected) SoftGreen
                                                        else Color.Transparent,
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.size(46.dp, 34.dp),
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        LineIcon(
                                                            glyph,
                                                            tint = if (selected) Mint else Muted,
                                                        )
                                                    }
                                                }
                                            Text(
                                                label,
                                                color = if (selected || capture) Mint else Muted,
                                                fontSize = 11.sp,
                                                lineHeight = 14.sp,
                                                fontWeight =
                                                    if (selected || capture) FontWeight.SemiBold
                                                    else FontWeight.Normal,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                            }
                        }
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    RefreshSurface(
                        refreshing = state.refreshing,
                        enabled = !state.busy && !state.workspaceLoading,
                        onRefresh = vm::refreshCurrent,
                        modifier = Modifier.widthIn(max = 700.dp).fillMaxSize(),
                    ) {
                        if (state.detailId != null) ReceiptDetailScreen(state, vm)
                        else
                            AnimatedContent(
                                targetState = state.page,
                                transitionSpec = {
                                    (fadeIn(tween(220)) +
                                        slideInVertically(tween(280)) { it / 18 }) togetherWith
                                        fadeOut(tween(120))
                                },
                                label = "Page transition",
                            ) { page ->
                                when (page) {
                                    Page.CAPTURE -> CaptureScreen(state, vm)
                                    Page.RECEIPTS -> ReceiptsScreen(state, vm)
                                    Page.OVERVIEW -> OverviewScreen(state, vm)
                                    Page.CHAT -> ChatScreen(state, vm)
                                    Page.FINANCES -> FinanceScreen(state, vm)
                                    Page.PROFILE -> ProfileScreen(state, vm)
                                }
                            }
                    }
                }
            }
        }
    }
}
