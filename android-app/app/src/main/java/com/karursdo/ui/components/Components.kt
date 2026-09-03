package com.karursdo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karursdo.ui.theme.Brand

/**
 * Pill badge (web .tag / .badge / .type-badge). The palette pairs (bg, fg) are tuned for LIGHT
 * mode; in dark mode we derive a readable variant — a lightened saturated label on a subtle
 * tinted chip — so badge text never renders as dark-on-dark.
 */
@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fgc = if (dark) androidx.compose.ui.graphics.lerp(fg, Color.White, 0.55f) else fg
    val bgc = if (dark) fgc.copy(alpha = 0.18f) else bg
    Text(
        text = text,
        color = fgc,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .background(bgc)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun TypePill(type: String, modifier: Modifier = Modifier) = when (type.uppercase()) {
    "DS", "DEPARTMENTAL" -> Pill("DS", Brand.BadgeDsBg, Brand.BadgeDsFg, modifier)
    "GDS" -> Pill("GDS", Brand.BadgeGdsBg, Brand.BadgeGdsFg, modifier)
    "OUT", "OUTSIDER" -> Pill("OUT", Brand.BadgeOutBg, Brand.BadgeOutFg, modifier)
    "TEL" -> Pill("TEL", Brand.BadgeTelBg, Brand.BadgeTelFg, modifier)
    "ARR" -> Pill("ARR", Brand.TpSdoBg, Brand.TpSdoFg, modifier)
    else -> Pill(type, Brand.TpOthBg, Brand.TpOthFg, modifier)
}

@Composable
fun StatusChip(status: String?, modifier: Modifier = Modifier) {
    val paid = status?.contains("paid", ignoreCase = true) == true
    Pill(
        status ?: "—",
        if (paid) Brand.ChipPaidBg else Brand.ChipOtherBg,
        if (paid) Brand.ChipPaidFg else Brand.ChipOtherFg,
        modifier
    )
}

/** Initials avatar: first char of first two name words (web app behavior). */
fun initialsOf(name: String?): String {
    val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    return words.take(2).joinToString("") { it.first().uppercase() }
}

@Composable
fun InitialsAvatar(
    name: String?,
    size: Int = 44,
    onHero: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(
                if (onHero) SolidColor(Color.White.copy(alpha = 0.18f))
                else Brush.linearGradient(listOf(Brand.Indigo, Brand.Violet))
            )
    ) {
        Text(
            initialsOf(name),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.36f).sp
        )
    }
}

/** Dashboard stat card with count-up animation. Tappable when [onClick] is supplied. */
@Composable
fun StatCard(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    var target by remember { mutableStateOf(0) }
    LaunchedEffect(value) { target = value }
    val animated by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 900),
        label = "statCount"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (onClick != null) {
                    Text(
                        " ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                "%,d".format(animated),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Segmented toggle with an animated sliding indicator (web .viewtabs / .seg). */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<Int?> = emptyList()
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Row(Modifier.padding(4.dp)) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val bg by animateColorAsState(
                    if (selected) Brand.Indigo else Color.Transparent,
                    animationSpec = spring(stiffness = 500f), label = "segBg"
                )
                val fg by animateColorAsState(
                    if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = spring(stiffness = 500f), label = "segFg"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (selected) Brush.linearGradient(
                                listOf(Brand.Indigo, Brand.Violet, Brand.Pink)
                            ) else Brush.linearGradient(listOf(bg, bg))
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    counts.getOrNull(i)?.let { c ->
                        Text(
                            "  $c",
                            color = if (selected) Color.White.copy(alpha = .85f) else Brand.CntFg,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

/** Search field styled like the web app's rounded inputs. */
@Composable
fun KsdSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Labelled field row inside a detail section (web .kv). */
@Composable
fun FieldRow(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.58f)
        )
    }
}

/** Detail section card with title (web .card > .sect). */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            content()
        }
    }
}

/** Card with press-scale + ripple touch feedback. */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 600f), label = "pressScale"
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Brand.BadgeDsBg.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        shadowElevation = if (pressed) 0.dp else 1.dp,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) { content() }
}

/** Rich empty state (web .empty: big faded emoji + guidance). */
@Composable
fun EmptyState(emoji: String, text: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp)
    ) {
        Text(emoji, fontSize = 40.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** ₹ money formatting matching the web inr() helper (en-IN grouping, 2 decimals). */
fun inr(value: Double?): String {
    if (value == null) return "—"
    val formatter = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return "₹" + formatter.format(value)
}

fun inrOrRaw(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    return raw.toDoubleOrNull()?.let { inr(it) } ?: raw
}
