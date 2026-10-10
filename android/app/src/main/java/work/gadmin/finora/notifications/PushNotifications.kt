package work.gadmin.finora.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Only opaque event IDs and a local scope digest enter Android's notification/intent storage. */
class PushNotifications(
    private val context: Context,
    private val activityClass: Class<*>,
    private val icon: Int,
) {
    private fun updateRecord(scope: PushScope) =
        android.util.AtomicFile(
            java.io.File(
                context.noBackupFilesDir,
                "push-update-notified-version-" + PushTapPolicy.updateRecordKey(scope),
            )
        )

    companion object {
        const val CHANNEL = "finora_events_v1"
        const val ACTION = "work.gadmin.finora.OPEN_PUSH_EVENT"
        private const val EVENT = "finora_push_event"
        private const val SCOPE = "finora_push_scope"

        private const val RECEIPT_MODE = "finora_push_receipt_all_organizations"
        private const val CATEGORY = "finora_push_category"

        fun scopeDigest(scope: PushScope): String = PushTapPolicy.scopeDigest(scope)

        fun receiptIdentityDigest(scope: PushScope): String =
            PushTapPolicy.receiptIdentityDigest(scope)

        fun isAllOrganizationReceiptTap(intent: Intent): Boolean =
            intent.action == ACTION &&
                intent.getBooleanExtra(RECEIPT_MODE, false) &&
                intent.getStringExtra(CATEGORY) == "receipts"

        fun consumeTap(
            intent: Intent,
            current: PushScope,
            allOrganizationsEnabled: Boolean = false,
        ): String? {
            if (intent.action != ACTION) return null
            val id = intent.getStringExtra(EVENT)
            val digest = intent.getStringExtra(SCOPE)
            val receiptMode = intent.getBooleanExtra(RECEIPT_MODE, false)
            val category = intent.getStringExtra(CATEGORY)
            intent.removeExtra(EVENT)
            intent.removeExtra(SCOPE)
            intent.removeExtra(RECEIPT_MODE)
            intent.removeExtra(CATEGORY)
            intent.action = null
            if (
                !PushTapPolicy.accepts(
                    digest,
                    current,
                    receiptMode,
                    category,
                    allOrganizationsEnabled,
                )
            )
                return null
            return id?.let { PushEnvelope.eventId(mapOf("event_id" to it)) }
        }
    }

    fun createChannel(name: String = "События Finora") {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Уведомления без финансовых подробностей"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setShowBadge(false)
                }
            )
    }

    fun permission(): PushPermission {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return PushPermission.DENIED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
            return PushPermission.DENIED
        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL)
        return if (channel?.importance == NotificationManager.IMPORTANCE_NONE)
            PushPermission.CHANNEL_DISABLED
        else PushPermission.GRANTED
    }

    /** Same data-only path in foreground/background/cold service; never trusts provider text. */
    @Synchronized
    fun show(data: Map<String, String>, scope: PushScope?, enabled: Boolean): Boolean {
        val eventId = PushEnvelope.eventId(data) ?: return false
        if (scope == null || !enabled) return false
        val updateRecord = updateRecord(scope)
        val receiptMode =
            PushTapPolicy.receiptIdentityMode(
                data["category"],
                PushInstallationStore(context).receiptAllOrganizations(),
            )
        val isUpdate = data["category"] == PushUpdatePolicy.CATEGORY
        val candidate =
            if (isUpdate) {
                val installed = installedPushVersion(context)?.code ?: return false
                val last = runCatching {
                    updateRecord.readFully().decodeToString().toLong()
                }
                    .getOrDefault(0)
                PushUpdatePolicy.incoming(data, installed, last) ?: return false
            } else null
        createChannel()
        if (permission() != PushPermission.GRANTED) return false
        val digest = if (receiptMode) receiptIdentityDigest(scope) else scopeDigest(scope)
        val intent =
            Intent(context, activityClass).apply {
                action = ACTION
                // Unique identity: PendingIntent extras do not participate in Android equality.
                this.data =
                    android.net.Uri.Builder()
                        .scheme("finora-push")
                        .authority("event")
                        .appendPath(digest)
                        .appendPath(eventId)
                        .build()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EVENT, eventId)
                putExtra(SCOPE, digest)
                if (receiptMode) {
                    putExtra(RECEIPT_MODE, true)
                    putExtra(CATEGORY, "receipts")
                }
            }
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle("Finora")
                .setContentText(
                    if (isUpdate) "Доступно обновление Finora. Откройте приложение для проверки."
                    else "Есть новое событие. Откройте приложение для просмотра."
                )
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(icon)
                        .setContentTitle("Finora")
                        .setContentText("Новое событие")
                        .build()
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        return try {
            NotificationManagerCompat.from(context).notify("push:$digest:$eventId", 1, notification)
            candidate?.let { version ->
                runCatching {
                    val stream = updateRecord.startWrite()
                    try {
                        stream.write(version.code.toString().toByteArray())
                        updateRecord.finishWrite(stream)
                    } catch (error: Exception) {
                        updateRecord.failWrite(stream)
                        throw error
                    }
                }
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun clear() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.tag?.startsWith("push:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
