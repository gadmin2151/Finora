package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import work.gadmin.finora.localization.*

private fun LanguagePreference.label(): String =
    when (this) {
        LanguagePreference.SYSTEM -> tr(Message.DEVICE_LANGUAGE)
        LanguagePreference.ENGLISH -> "English"
        LanguagePreference.RUSSIAN -> "Русский"
    }

/** Compact login control keeps credentials in memory when the language changes. */
@Composable
fun LoginLanguageSwitch() {
    val selection = LocalLanguageSelection.current
    var expanded by remember { mutableStateOf(false) }
    val description = tr(Message.LANGUAGE_SWITCH)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(selection.preference.label())
                Spacer(Modifier.width(6.dp))
                LineIcon(Glyph.DOWN, size = 16.dp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LanguagePreference.entries.forEach { preference ->
                    DropdownMenuItem(
                        text = { Text(preference.label()) },
                        trailingIcon = {
                            if (selection.preference == preference)
                                LineIcon(Glyph.CHECK, size = 18.dp)
                        },
                        onClick = {
                            selection.select(preference)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageSettings() {
    val selection = LocalLanguageSelection.current
    Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr(Message.LANGUAGE), style = MaterialTheme.typography.titleLarge)
            Text(
                tr(Message.LANGUAGE_HINT),
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                LanguagePreference.entries.forEach { preference ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = selection.preference == preference,
                                role = Role.RadioButton,
                                onClick = { selection.select(preference) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selection.preference == preference, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(preference.label(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Text(
                tr(Message.YOUR_CHOICE_IS_SAVED_ON_THIS_DEVICE),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
