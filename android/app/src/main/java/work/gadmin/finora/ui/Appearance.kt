package work.gadmin.finora.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

enum class ThemeMode(private val labelMessage: Message) {
    SYSTEM(Message.SYSTEM),
    LIGHT(Message.LIGHT),
    DARK(Message.DARK);

    val label: String
        get() = tr(labelMessage)

    fun isDark(systemDark: Boolean) = this == DARK || (this == SYSTEM && systemDark)

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Device preference: independent of login, organization and the server. */
class ThemePreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun read() = ThemeMode.fromStored(preferences.getString("theme", null))

    fun save(mode: ThemeMode) {
        preferences.edit().putString("theme", mode.name).apply()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme" || key == null) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(callback)
        return { preferences.unregisterOnSharedPreferenceChangeListener(callback) }
    }
}

@Stable
class AppearanceState(val mode: ThemeMode, val dark: Boolean, val select: (ThemeMode) -> Unit)

val LocalAppearance = staticCompositionLocalOf { AppearanceState(ThemeMode.SYSTEM, false) {} }

@Composable
fun rememberAppearance(): AppearanceState {
    val context = LocalContext.current
    val preferences = remember(context) { ThemePreferences(context) }
    var mode by remember(preferences) { mutableStateOf(preferences.read()) }
    DisposableEffect(preferences) {
        val stop = preferences.observe { mode = preferences.read() }
        onDispose(stop)
    }
    val dark = mode.isDark(isSystemInDarkTheme())
    return AppearanceState(mode, dark) { choice ->
        mode = choice
        preferences.save(choice)
    }
}

@Composable
fun AppearanceSettings() {
    val appearance = LocalAppearance.current
    Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr(Message.APPEARANCE), style = MaterialTheme.typography.titleLarge)
            Text(
                tr(Message.CHOOSE_THE_THEME_THAT_FEELS_RIGHT_FOR_YOU),
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    val selected = appearance.mode == mode
                    Surface(
                        modifier =
                            Modifier.weight(1f)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { appearance.select(mode) },
                                ),
                        color = if (selected) SoftGreen else SurfaceColor,
                        border =
                            BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) Green else Border,
                            ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ThemeMiniature(mode)
                            Text(
                                mode.label,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Surface(
                                color = if (selected) Green else Color.Transparent,
                                border = BorderStroke(1.dp, if (selected) Green else Muted),
                                shape = CircleShape,
                                modifier = Modifier.size(18.dp),
                            ) {
                                if (selected)
                                    Box(contentAlignment = Alignment.Center) {
                                        LineIcon(
                                            Glyph.CHECK,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            size = 12.dp,
                                        )
                                    }
                            }
                        }
                    }
                }
            }
            Text(
                if (appearance.mode == ThemeMode.SYSTEM)
                    tr(
                        Message.CURRENTLY_1_S_FOLLOWS_YOUR_DEVICE_SETTINGS,
                        if (appearance.dark) tr(Message.DARK_9BB13) else tr(Message.LIGHT_AFEA3),
                    )
                else tr(Message.CHOSEN_MANUALLY_YOU_CAN_SWITCH_BACK_TO_THE_SYSTEM_THEME),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tr(Message.YOUR_CHOICE_IS_SAVED_ON_THIS_DEVICE),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ThemeMiniature(mode: ThemeMode) {
    val light = Color(0xFFF5F8F5)
    val dark = Color(0xFF101715)
    val background =
        when (mode) {
            ThemeMode.LIGHT -> Brush.horizontalGradient(listOf(light, light))
            ThemeMode.DARK -> Brush.horizontalGradient(listOf(dark, dark))
            ThemeMode.SYSTEM -> Brush.horizontalGradient(0.5f to light, 0.5f to dark)
        }
    Column(
        Modifier.fillMaxWidth()
            .height(76.dp)
            .background(background, RoundedCornerShape(8.dp))
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(25.dp).height(4.dp).background(Color(0xFF93AF9A), CircleShape))
        Box(
            Modifier.fillMaxWidth()
                .height(26.dp)
                .background(
                    if (mode == ThemeMode.DARK) Color(0xFF37624E) else Color(0xFFCBE5CD),
                    RoundedCornerShape(4.dp),
                )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) {
                Box(
                    Modifier.weight(1f)
                        .height(14.dp)
                        .background(
                            if (mode == ThemeMode.DARK) Color(0xFF25352D) else Color.White,
                            RoundedCornerShape(3.dp),
                        )
                )
            }
        }
    }
}
