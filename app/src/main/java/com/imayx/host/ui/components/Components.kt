package com.imayx.host.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imayx.host.ui.theme.Ix

/** Lets any screen show a snackbar without plumbing the host state around. */
val LocalSnack = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun IxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Ix.c
    val shape = RoundedCornerShape(20.dp)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(c.card, shape)
        .border(1.dp, c.border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(modifier = m.padding(padding), content = content)
}

@Composable
fun StatusDot(color: Color, pulse: Boolean = false, size: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "dotScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "dotAlpha"
    )
    Box(modifier = Modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (pulse) {
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(scale)
                    .alpha(alpha)
                    .background(color, CircleShape)
            )
        }
        Box(modifier = Modifier.size(size).background(color, CircleShape))
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f), shape)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Ix.c.soft,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

enum class BtnKind { Primary, Secondary, Danger, Ghost }

@Composable
fun IxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: BtnKind = BtnKind.Primary,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val c = Ix.c
    val shape = RoundedCornerShape(14.dp)
    val bg: Color
    val fg: Color
    val borderColor: Color
    when (kind) {
        BtnKind.Primary -> {
            bg = c.accent; fg = c.onAccent; borderColor = c.accent
        }
        BtnKind.Secondary -> {
            bg = c.card2; fg = c.text; borderColor = c.border
        }
        BtnKind.Danger -> {
            bg = c.danger.copy(alpha = 0.14f); fg = c.danger; borderColor = c.danger.copy(alpha = 0.4f)
        }
        BtnKind.Ghost -> {
            bg = Color.Transparent; fg = c.accent; borderColor = Color.Transparent
        }
    }
    val active = enabled && !loading
    Row(
        modifier = modifier
            .heightIn(min = 46.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = active, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IxChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ix.c
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.18f) else c.card2, shape)
            .border(1.dp, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) c.accent else c.soft, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun IxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val c = Ix.c
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        singleLine = singleLine,
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        supportingText = if (supportingText != null) ({ Text(supportingText) }) else null,
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
            focusedContainerColor = c.card2,
            unfocusedContainerColor = c.card2,
            disabledContainerColor = c.card2,
            cursorColor = c.accent,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.border,
            errorBorderColor = c.danger,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.soft,
            errorLabelColor = c.danger,
            focusedPlaceholderColor = c.soft,
            unfocusedPlaceholderColor = c.soft,
            focusedSupportingTextColor = c.soft,
            unfocusedSupportingTextColor = c.soft,
            errorSupportingTextColor = c.danger
        )
    )
}

/** Password-style field for secrets. The stored value is never loaded back into the UI. */
@Composable
fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hasSaved: Boolean,
    modifier: Modifier = Modifier,
    supportingText: String? = null
) {
    var visible by remember { mutableStateOf(false) }
    IxTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        placeholder = if (hasSaved) "Saved securely — enter a new value to replace" else "Paste value",
        supportingText = supportingText,
        monospace = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                    tint = Ix.c.soft
                )
            }
        }
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val c = Ix.c
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, color = c.soft, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.soft,
                uncheckedTrackColor = c.card2,
                uncheckedBorderColor = c.border
            )
        )
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null, mono: Boolean = false) {
    val c = Ix.c
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = c.soft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Text(
            value,
            color = valueColor ?: c.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun Meter(label: String, fraction: Float?, valueText: String, modifier: Modifier = Modifier) {
    val c = Ix.c
    val animated by animateFloatAsState(
        targetValue = (fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "meter"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.soft, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(valueText, color = if (fraction == null) c.soft else c.text, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(c.card2)
        ) {
            if (fraction != null) {
                val color = when {
                    animated > 0.9f -> c.danger
                    animated > 0.75f -> c.warn
                    else -> c.accent
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                )
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    val c = Ix.c
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.card2, shape)
            .border(1.dp, c.border, shape)
            .padding(14.dp)
    ) {
        Text(label, color = c.soft, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = c.soft, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val c = Ix.c
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, color = c.soft, style = MaterialTheme.typography.bodyMedium)
        }
        actions()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val c = Ix.c
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(c.accent.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = c.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = c.soft, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            IxButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
    onRetry: (() -> Unit)? = null
) {
    val c = Ix.c
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(c.danger.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = c.danger, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = c.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, color = c.soft, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(18.dp))
            IxButton(retryLabel, onRetry, kind = BtnKind.Secondary)
        }
    }
}

@Composable
fun LoadingState(label: String = "Loading…", modifier: Modifier = Modifier) {
    val c = Ix.c
    val transition = rememberInfiniteTransition(label = "loading")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = c.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(14.dp))
        Text(label, color = c.soft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(pulse))
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    val c = Ix.c
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        textContentColor = c.soft,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            IxButton(
                confirmText,
                onClick = {
                    onConfirm()
                },
                kind = if (destructive) BtnKind.Danger else BtnKind.Primary
            )
        },
        dismissButton = { IxButton("Cancel", onDismiss, kind = BtnKind.Ghost) }
    )
}

@Composable
fun PromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validator: ((String) -> String?)? = null,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val c = Ix.c
    var text by remember { mutableStateOf(initial) }
    val error = validator?.invoke(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.text,
        textContentColor = c.soft,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            IxTextField(
                value = text,
                onValueChange = { text = it },
                label = label,
                isError = error != null && text.isNotEmpty(),
                supportingText = if (text.isNotEmpty()) error else null,
                keyboardType = keyboardType
            )
        },
        confirmButton = {
            IxButton(confirmText, onClick = { onConfirm(text) }, enabled = text.isNotBlank() && error == null)
        },
        dismissButton = { IxButton("Cancel", onDismiss, kind = BtnKind.Ghost) }
    )
}

@Composable
fun FullSize(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) { content() }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** Dropdown selector. [options] are (value, label) pairs. */
@Composable
fun IxSelect(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = Ix.c
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .clip(shape)
                .background(c.card2, shape)
                .border(1.dp, c.border, shape)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = c.soft, style = MaterialTheme.typography.labelSmall)
                Text(
                    selectedLabel.ifBlank { "—" },
                    color = c.text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = c.soft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(
                    text = { Text(text, color = c.text) },
                    onClick = {
                        open = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}
