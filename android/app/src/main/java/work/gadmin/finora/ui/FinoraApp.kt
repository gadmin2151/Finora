package work.gadmin.finora.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import work.gadmin.finora.*

@Composable
fun FinoraApp(vm: FinoraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = state.camera == null
                isAppearanceLightNavigationBars = state.camera == null
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
                    CircularProgressIndicator(color = Green)
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
            BackHandler(state.detailId != null) { if (!state.busy) vm.closeDetail() }
            Scaffold(
                containerColor = Paper,
                snackbarHost = { SnackbarHost(snackbars) },
                topBar = {
                    Column(Modifier.statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
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
                                    Surface(color = SoftGreen, shape = RoundedCornerShape(13.dp)) {
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
                                color = Forest,
                                shape = CircleShape,
                                enabled = !state.busy,
                            ) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        state.user?.name?.take(1)?.uppercase() ?: "F",
                                        color = Mint,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
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
                    if (state.detailId == null)
                        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                            listOf(
                                    Triple(Page.CAPTURE, "Добавить", Glyph.SCAN),
                                    Triple(Page.RECEIPTS, "Чеки", Glyph.RECEIPT),
                                    Triple(Page.OVERVIEW, "Обзор", Glyph.CHART),
                                    Triple(Page.PROFILE, "Профиль", Glyph.USER),
                                )
                                .forEach { (page, label, glyph) ->
                                    NavigationBarItem(
                                        selected = state.page == page,
                                        onClick = { vm.navigate(page) },
                                        enabled = !state.busy,
                                        icon = {
                                            LineIcon(
                                                glyph,
                                                tint = if (state.page == page) Forest else Muted,
                                            )
                                        },
                                        label = { Text(label) },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                indicatorColor = Mint,
                                                selectedTextColor = Forest,
                                            ),
                                    )
                                }
                        }
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = 700.dp).fillMaxSize()) {
                        if (state.detailId != null) ReceiptDetailScreen(state, vm)
                        else
                            when (state.page) {
                                Page.CAPTURE -> CaptureScreen(state, vm)
                                Page.RECEIPTS -> ReceiptsScreen(state, vm)
                                Page.OVERVIEW -> OverviewScreen(state, vm)
                                Page.PROFILE -> ProfileScreen(state, vm)
                            }
                    }
                }
            }
        }
    }
}
