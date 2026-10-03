package com.liuml.apptimelimiter.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick, modifier.heightIn(min = 48.dp), enabled, shape, colors, elevation,
    border, contentPadding, interactionSource, content,
)

@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick, modifier.heightIn(min = 48.dp), enabled, shape, colors, elevation,
    border, contentPadding, interactionSource, content,
)

/** The visual track is compact; its accessible/touch area remains at least 48dp. */
@Composable
internal fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val position by animateDpAsState(if (checked) 22.dp else 2.dp, label = "switch position")
    Box(
        modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
            .then(if (onCheckedChange != null) Modifier.toggleable(
                value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange,
            ) else Modifier)
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp, 32.dp).background(
            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            CircleShape,
        )) {
            Box(Modifier.padding(top = 2.dp).offset(x = position).size(28.dp)
                .shadow(1.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}
