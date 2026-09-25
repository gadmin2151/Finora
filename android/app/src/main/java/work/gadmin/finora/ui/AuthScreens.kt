package work.gadmin.finora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.Organization

@Composable
fun LoginScreen(state: AppState, vm: FinoraViewModel) {
    var server by rememberSaveable { mutableStateOf(state.server) }
    var username by rememberSaveable { mutableStateOf(vm.lastUsername) }
    // Password is intentionally neither saved across process death nor persisted.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Brand(Modifier.padding(top = 20.dp, bottom = 24.dp))
        Text("Ваши деньги.\nВсё понятно.", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Чеки, покупки и спокойствие за бюджет — всегда под рукой.",
            color = Muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(26.dp), color = Color.White) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Войти в Finora", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    server,
                    { server = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Сервер HTTPS") },
                    singleLine = true,
                    leadingIcon = { LineIcon(Glyph.LOCK) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    username,
                    { username = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Логин") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation =
                        if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ showPassword = !showPassword }) {
                            LineIcon(
                                Glyph.EYE,
                                if (showPassword) "Скрыть пароль" else "Показать пароль",
                            )
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { if (!state.busy) vm.login(server, username, password) }
                        ),
                    enabled = !state.busy,
                )
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PrimaryButton(
                    if (state.busy) "Подключаемся…" else "Войти",
                    { vm.login(server, username, password) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(),
                    icon = Glyph.ARROW,
                )
            }
        }
        InfoCard(
            "Защищённое соединение с вашим сервером. Используйте тот же логин, что и в веб-версии."
        )
        Text(
            "УЧЁТ БЕЗ ЛИШНИХ УСИЛИЙ",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
        )
    }
}

@Composable
fun OrganizationScreen(
    state: AppState,
    onSelect: (Organization) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Brand(Modifier.weight(1f))
            if (state.organization != null)
                IconButton(onBack) { LineIcon(Glyph.CLOSE, "Закрыть выбор организации") }
        }
        Spacer(Modifier.height(24.dp))
        Text("Какой бюджет\nведём сегодня?", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Выберите организацию. Чеки и статистика будут общими для её участников.",
            color = Muted,
        )
        Spacer(Modifier.height(8.dp))
        state.user?.organizations?.forEach { organization ->
            Surface(
                onClick = { onSelect(organization) },
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(color = SoftGreen, shape = RoundedCornerShape(16.dp)) {
                        Box(Modifier.padding(14.dp)) { LineIcon(Glyph.HOME) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(organization.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (organization.isAdmin) "Администратор" else "Участник",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LineIcon(Glyph.CHEVRON)
                }
            }
        }
        if (state.user?.organizations.isNullOrEmpty())
            InfoCard(
                "Пока нет доступных организаций. Попросите администратора добавить вас через веб-версию.",
                Glyph.HOME,
            )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onLogout, enabled = !state.busy) {
            Text("Выйти из аккаунта", fontWeight = FontWeight.Medium)
        }
    }
}
