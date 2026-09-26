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
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
        item { Text(tr(Message.YOUR_PROFILE), style = MaterialTheme.typography.headlineLarge) }
        item { LanguageSettings() }
        item { AppearanceSettings() }
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatar(state.user, vm, 96.dp)
                    Text(tr(Message.PROFILE_PHOTO), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(Message.RECOGNIZABLE_IN_EVERY_ORGANIZATION),
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
                            if (state.user?.avatar_url == null) tr(Message.ADD_PHOTO)
                            else tr(Message.CHANGE_PHOTO)
                        )
                    }
                    if (state.user?.avatar_url != null)
                        TextButton({ vm.updateAvatar(null) }, enabled = !state.busy) {
                            Text(tr(Message.REMOVE_PHOTO))
                        }
                    Text(
                        tr(Message.UP_TO_5_MB_CROPPED_TO_THE_CENTRE),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (state.busy) BrandLoading(tr(Message.SAVING_YOUR_PROFILE), compact = true)
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
                        if (state.organization?.isAdmin == true)
                            tr(Message.ORGANIZATION_ADMINISTRATOR)
                        else tr(Message.MEMBER_RECEIPTS_COMMENTS_AND_STATISTICS),
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton({ vm.chooseOrganization() }, contentPadding = PaddingValues(0.dp)) {
                        Text(tr(Message.SWITCH_ORGANIZATION))
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
                        Text(tr(Message.SECURE_CONNECTION), fontWeight = FontWeight.SemiBold)
                    }
                    Text(state.server, color = Green)
                    Text(
                        tr(Message.YOUR_PASSWORD_IS_NOT_STORED_ON_YOUR_PHONE_YOUR_SESSION_IS),
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        { context.startActivity(Intent(Intent.ACTION_VIEW, state.server.toUri())) },
                        Modifier.fillMaxWidth(),
                    ) {
                        Text(tr(Message.OPEN_WEBSITE))
                    }
                }
            }
        }
        item {
            InfoCard(
                tr(Message.RECEIPT_QR_CODES_ARE_READ_ON_YOUR_DEVICE_YOUR_SERVER_PROCE),
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
                Text(tr(Message.SIGN_OUT))
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
            title = { Text(tr(Message.SIGN_OUT_OF_FINORA)) },
            text = {
                Text(tr(Message.YOU_WILL_NEED_YOUR_PASSWORD_TO_SIGN_IN_AGAIN_UNSENT_DRAFTS))
            },
            confirmButton = {
                TextButton({
                    logout = false
                    vm.logout()
                }) {
                    Text(tr(Message.SIGN_OUT_026AB))
                }
            },
            dismissButton = { TextButton({ logout = false }) { Text(tr(Message.CANCEL)) } },
        )
}
