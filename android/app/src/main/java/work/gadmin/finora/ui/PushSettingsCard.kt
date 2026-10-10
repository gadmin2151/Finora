package work.gadmin.finora.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import work.gadmin.finora.localization.*
import work.gadmin.finora.notifications.*

@Composable
fun PushSettingsCard(host: PushHost) {
    val data by host.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            host.synchronize()
        }
    val russian = LanguageRuntime.language == AppLanguage.RUSSIAN
    fun text(en: String, ru: String) = if (russian) ru else en
    DisposableEffect(owner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) host.synchronize()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PushSettingsUi(
                data.status,
                data.preferences.enabled,
                host::setEnabled,
                {
                    if (Build.VERSION.SDK_INT >= 33)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else host.synchronize()
                },
                {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, PushNotifications.CHANNEL)
                        )
                    }
                },
                { host.synchronize() },
                if (russian) PushSettingsCopy()
                else
                    PushSettingsCopy(
                        "Push notifications",
                        "Push is not configured on this server.",
                        "Sign in to enable notifications.",
                        "Notifications are off.",
                        "Allow notifications in Android settings.",
                        "The event channel is disabled in Android.",
                        "Connecting notifications…",
                        "Push is connected. Financial details stay inside the app.",
                        "Device registration was revoked.",
                        "Could not update push. Retry.",
                        "Allow notifications",
                        "Android settings",
                        "Retry",
                        enabledLabel = "Notifications on this device",
                    ),
            )
            val editable =
                data.status !in
                    setOf(PushState.UNCONFIGURED, PushState.SIGNED_OUT, PushState.REGISTERING)
            val p = data.preferences
            listOf(
                    text("Payment reminders", "Напоминания о платежах") to p.payments,
                    text("New receipts from other participants", "Новые чеки других участников") to
                        p.receipts,
                    text("Important events", "Важные события") to p.important,
                )
                .forEachIndexed { index, (label, checked) ->
                    PreferenceSwitchRow(
                        label,
                        checked,
                        enabled = editable,
                        onCheckedChange = { value ->
                            host.setCategories(
                                if (index == 0) value else p.payments,
                                if (index == 1) value else p.receipts,
                                if (index == 2) value else p.important,
                            )
                        },
                    )
                }
            if (data.receiptAllOrganizationsSupported) {
                PreferenceSwitchRow(
                    text(
                        "Receipts from all subscribed organizations",
                        "Чеки из всех подключённых организаций",
                    ),
                    data.receiptAllOrganizations,
                    host::setReceiptAllOrganizations,
                    enabled = editable,
                )
                Text(
                    text(
                        "Uses existing receipt subscriptions; does not enable another organization's notifications.",
                        "Использует существующие подписки на чеки; не включает уведомления другой организации.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (data.updateNotificationsSupported) {
                PreferenceSwitchRow(
                    text("App updates", "Обновления приложения"),
                    p.updates,
                    host::setUpdates,
                    enabled = editable,
                )
            }
            Text(
                text(
                    "Choose categories; enabling push alone sends no events.",
                    "Выберите категории: без них события не отправляются.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text("Payment reminder time", "Время напоминания о платеже") +
                    ": ${p.paymentHour.toString().padStart(2, '0')}:00 (${p.timezone})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
