package work.gadmin.finora.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Labels may be supplied by the app's localization owner without changing common resources. */
data class PushSettingsCopy(
    val title: String = "Push-уведомления",
    val unconfigured: String = "Push пока не настроен на этом сервере.",
    val signedOut: String = "Войдите, чтобы включить уведомления.",
    val disabled: String = "Уведомления выключены.",
    val denied: String = "Разрешите уведомления в настройках Android.",
    val channelDisabled: String = "Канал событий отключён в настройках Android.",
    val registering: String = "Подключаем уведомления…",
    val ready: String = "Push подключён. Финансовые подробности видны только в приложении.",
    val revoked: String = "Регистрация устройства отозвана.",
    val error: String = "Не удалось обновить push. Повторите попытку.",
    val allow: String = "Разрешить уведомления",
    val settings: String = "Настройки Android",
    val retry: String = "Повторить",
)

@Composable
fun PushSettingsUi(
    state: PushState,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onRetry: () -> Unit,
    copy: PushSettingsCopy = PushSettingsCopy(),
) {
    Column {
        Text(copy.title)
        Switch(
            checked = enabled,
            onCheckedChange = onEnabled,
            enabled =
                state !in
                    setOf(PushState.SIGNED_OUT, PushState.UNCONFIGURED, PushState.REGISTERING),
        )
        Text(
            when (state) {
                PushState.UNCONFIGURED -> copy.unconfigured
                PushState.SIGNED_OUT -> copy.signedOut
                PushState.DISABLED -> copy.disabled
                PushState.PERMISSION_DENIED -> copy.denied
                PushState.CHANNEL_DISABLED -> copy.channelDisabled
                PushState.REGISTERING -> copy.registering
                PushState.READY -> copy.ready
                PushState.REVOKED -> copy.revoked
                PushState.ERROR -> copy.error
            }
        )
        if (state == PushState.PERMISSION_DENIED)
            Button(onClick = onRequestPermission) { Text(copy.allow) }
        if (state in setOf(PushState.PERMISSION_DENIED, PushState.CHANNEL_DISABLED))
            Button(onClick = onOpenAndroidSettings) { Text(copy.settings) }
        if (state == PushState.ERROR) Button(onClick = onRetry) { Text(copy.retry) }
    }
}
