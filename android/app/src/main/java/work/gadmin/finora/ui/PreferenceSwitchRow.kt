package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** One labelled accessibility node and a full-width touch target, including wrapped labels. */
@Composable
fun PreferenceSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = if (enabled) Ink else Muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.width(56.dp).clearAndSetSemantics {},
            thumbContent =
                if (checked) ({ LineIcon(Glyph.CHECK, tint = Green, size = 16.dp) }) else null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = OnPrimary,
                    checkedTrackColor = Green,
                    checkedBorderColor = Green,
                    uncheckedThumbColor = Muted,
                    uncheckedTrackColor = SurfaceRaised,
                    uncheckedBorderColor = Muted,
                    disabledCheckedThumbColor = SurfaceColor,
                    disabledCheckedTrackColor = Muted,
                    disabledCheckedBorderColor = Muted,
                    disabledUncheckedThumbColor = Muted,
                    disabledUncheckedTrackColor = SurfaceRaised,
                    disabledUncheckedBorderColor = Muted,
                ),
        )
    }
}
