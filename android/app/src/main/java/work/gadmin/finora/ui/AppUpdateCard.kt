package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import work.gadmin.finora.BuildConfig
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.updates.UpdateController
import work.gadmin.finora.updates.UpdatePhase
import work.gadmin.finora.updates.UpdateState

@Composable
fun AppUpdateCard(controller: UpdateController = viewModel()) {
    val data by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launching by remember { mutableStateOf(false) }
    AppUpdateContent(data, launching, controller::check, controller::download, controller::cancel) {
        launching = true
        scope.launch {
            try {
                controller.installIntent()?.let(context::startActivity)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                controller.installerError()
            } finally {
                launching = false
            }
        }
    }
}

@Composable
internal fun AppUpdateContent(
    data: UpdateState,
    launching: Boolean = false,
    check: () -> Unit,
    download: () -> Unit,
    cancel: () -> Unit,
    install: () -> Unit,
) {
    val russian = LanguageRuntime.language == AppLanguage.RUSSIAN
    fun label(en: String, ru: String) = if (russian) ru else en
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                label("App updates", "Обновления приложения"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text(
                when (data.phase) {
                    UpdatePhase.IDLE ->
                        label(
                            "Check for a newer signed Finora release.",
                            "Проверить новую подписанную версию Finora.",
                        )
                    UpdatePhase.CHECKING -> label("Checking…", "Проверка…")
                    UpdatePhase.CURRENT ->
                        label("No newer version is available.", "Более новой версии нет.")
                    UpdatePhase.AVAILABLE ->
                        label(
                            "Available: ${data.manifest?.versionName}",
                            "Доступна: ${data.manifest?.versionName}",
                        )
                    UpdatePhase.DOWNLOADING -> label("Downloading…", "Скачивание…")
                    UpdatePhase.READY ->
                        label(
                            "Verified. Android will ask you to confirm installation.",
                            "Файл проверен. Android попросит подтвердить установку.",
                        )
                    UpdatePhase.ERROR ->
                        label("Update could not be completed.", "Не удалось выполнить обновление.")
                }
            )
            data.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            data.manifest?.releaseNotes?.takeIf { it.isNotBlank() }?.let { Text(it) }
            if (data.phase == UpdatePhase.DOWNLOADING) {
                val total = data.manifest?.sizeBytes ?: 1L
                LinearProgressIndicator(
                    progress = { (data.downloaded.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${data.downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MB")
            }
            when (data.phase) {
                UpdatePhase.CHECKING,
                UpdatePhase.DOWNLOADING ->
                    TextButton(onClick = cancel) { Text(label("Cancel", "Отмена")) }
                UpdatePhase.READY -> {
                    Button(
                        enabled = !launching,
                        onClick = install,
                    ) {
                        Text(label("Install update", "Установить обновление"))
                    }
                    Text(
                        label(
                            "If Android opens permission settings, allow this app and return, then tap Install again. Installation can be cancelled in Android.",
                            "Если Android откроет разрешение, разрешите установку для этого приложения, вернитесь и нажмите «Установить» ещё раз. Установку можно отменить в Android.",
                        )
                    )
                }
                else -> {
                    if (
                        data.manifest != null &&
                            data.manifest!!.versionCode > BuildConfig.VERSION_CODE
                    ) {
                        Button(onClick = download) {
                            Text(label("Download update", "Скачать обновление"))
                        }
                    }
                    TextButton(onClick = check) {
                        Text(label("Check for updates", "Проверить обновления"))
                    }
                }
            }
            Text(
                label(
                    "Only the official HTTPS release channel is used. Downloading does not change financial data.",
                    "Используется официальный HTTPS-канал релизов. Скачивание не меняет финансовые данные.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
