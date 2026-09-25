package work.gadmin.finora.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import work.gadmin.finora.AppState
import work.gadmin.finora.BuildConfig
import work.gadmin.finora.FinoraViewModel

@Composable
fun ProfileScreen(state: AppState, vm: FinoraViewModel) {
    val context = LocalContext.current
    var logout by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) vm.updateAvatar(uri)
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text("Ваш профиль", style = MaterialTheme.typography.headlineLarge) }
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatar(state.user, vm, 96.dp)
                    Text("Фото профиля", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Вас узнают в каждой организации",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = !state.busy,
                    ) {
                        LineIcon(Glyph.CAMERA, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.user?.avatar_url == null) "Добавить фото" else "Изменить фото"
                        )
                    }
                    if (state.user?.avatar_url != null)
                        TextButton({ vm.updateAvatar(null) }, enabled = !state.busy) {
                            Text("Удалить фото")
                        }
                    Text(
                        "До 5 МБ · кадрирование по центру",
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (state.busy) BrandLoading("Сохраняем профиль", compact = true)
                }
            }
        }
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(state.user?.name ?: "", style = MaterialTheme.typography.titleLarge)
                    Text("@${state.user?.username}", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Border)
                    Spacer(Modifier.height(8.dp))
                    Text(state.organization?.name ?: "", fontWeight = FontWeight.Medium)
                    Text(
                        if (state.organization?.isAdmin == true) "Администратор организации"
                        else "Участник · чеки, комментарии и статистика",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton({ vm.chooseOrganization() }, contentPadding = PaddingValues(0.dp)) {
                        Text("Сменить организацию")
                        Spacer(Modifier.width(8.dp))
                        LineIcon(Glyph.CHEVRON, size = 17.dp)
                    }
                }
            }
        }
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LineIcon(Glyph.SHIELD)
                        Text("Защищённое подключение", fontWeight = FontWeight.SemiBold)
                    }
                    Text(state.server, color = Green)
                    Text(
                        "Пароль не хранится на телефоне. Сессия защищена Android Keystore. Фото остаются в закрытом хранилище до отправки.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        { context.startActivity(Intent(Intent.ACTION_VIEW, state.server.toUri())) },
                        Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть веб-версию")
                    }
                }
            }
        }
        item {
            InfoCard(
                "QR чека считывается на устройстве. Распознавание фотографий и AI-анализ выполняет ваш сервер с выбранным в нём AI-провайдером.",
                Glyph.SPARK,
            )
        }
        item {
            OutlinedButton(
                { logout = true },
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                enabled = !state.busy,
            ) {
                LineIcon(Glyph.EXIT, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Выйти из аккаунта")
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Brand()
                Text(
                    "Android · ${BuildConfig.VERSION_NAME}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (logout)
        AlertDialog(
            onDismissRequest = { logout = false },
            title = { Text("Выйти из Finora?") },
            text = {
                Text(
                    "Для следующего входа понадобится пароль. Неотправленные черновики останутся на этом устройстве и будут доступны только после входа в тот же аккаунт."
                )
            },
            confirmButton = {
                TextButton({
                    logout = false
                    vm.logout()
                }) {
                    Text("Выйти")
                }
            },
            dismissButton = { TextButton({ logout = false }) { Text("Отмена") } },
        )
}
