package work.gadmin.finora.notifications

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** PackageManager is the authority; no hard-coded release number and no server assertion. */
fun installedPushVersion(context: Context): InstalledPushVersion? = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    InstalledPushVersion(
        PackageInfoCompat.getLongVersionCode(info),
        requireNotNull(info.versionName),
    )
}
    .getOrNull()
