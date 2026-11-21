package com.karursdo.ui.sb46

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.AssignmentReturn
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LocalPostOffice
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.repo.CpvRepository
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.repo.Sb46NoticeDto
import com.karursdo.data.repo.Sb46Office
import com.karursdo.data.repo.Sb46Repository
import com.karursdo.data.repo.accountCount
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.inr
import com.karursdo.ui.cpv.isCpvManager
import com.karursdo.ui.cpv.isCpvMo
import com.karursdo.ui.theme.LocalHeaderBrush
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

/**
 * SB-46 notice tracking.
 *
 * Cent Percent Verification leaves behind the accounts the Mail Overseer could not verify in the
 * field; the office turns those into SB-46 notices, one per depositor per scheme, each posted
 * under its own Speed Post article number. This module is the other end of that: weeks later,
 * recording for each notice whether the acknowledgement card came back, whether the depositor
 * replied, or whether the cover came back undelivered and why.
 *
 * Raising a batch, allotting article numbers, printing labels and writing the Final Report all
 * need the physical label sheet or a desk, so they stay in the web module at `/sb-46`. Only the
 * tracking is here, because the Mail Overseer already carries the phone.
 *
 * Visibility mirrors Cent Percent Verification exactly: a Mail Overseer sees the offices allotted
 * to them in `app_cpv_allotments`, everyone else sees them all, and anyone who can see an office
 * can record against its notices.
 *
 * The look follows the web register (sb46.html): a hero with the share settled as a ring and one
 * bar split into replied / returned / delivered-no-reply / not-yet-heard-of, four outcome tiles,
 * and on a register a scheme-wise summary whose rows filter the notices.
 */

// ═════════════════════════════════════════════════════════════
//  DESIGN TOKENS — the four outcomes keep one colour everywhere
// ═════════════════════════════════════════════════════════════

private object Sb46Tone {
    val Issued = Color(0xFF6366F1)
    val Delivered = Color(0xFF0891B2)
    val Replied = Color(0xFF059669)
    val Returned = Color(0xFFE11D48)
    val Waiting = Color(0xFFD97706)
    val Unheard = Color(0xFFF59E0B)
}

private val inrCount: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))
private fun n0(v: Int): String = inrCount.format(v)
private fun pctOf(x: Int, t: Int): Int = if (t > 0) Math.round(x * 100f / t) else 0
private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) one else many

@Composable
private fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** A tone readable as text on the current surface — lifted toward white in dark mode. */
@Composable
private fun ink(c: Color): Color = if (isDark()) androidx.compose.ui.graphics.lerp(c, Color.White, 0.45f) else c

/** Hairline border used by every card in the module. */
@Composable
private fun hairline(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (isDark()) 0.7f else 0.9f))

/**
 * Where a set of notices stands, split so the parts add up: replied, returned, delivered (ack.)
 * but no reply yet, and not heard of at all — the web register's standing(). Counted per account.
 */
private data class Standing(
    val issued: Int = 0,
    val covers: Int = 0,
    val ack: Int = 0,
    val replied: Int = 0,
    val returned: Int = 0,
    val ackOnly: Int = 0,
    val unheard: Int = 0
) {
    val settled: Int get() = replied + returned
    val awaiting: Int get() = (issued - settled).coerceAtLeast(0)
    val pct: Int get() = pctOf(settled, issued)
}

private fun standingOf(list: List<Sb46NoticeDto>): Standing {
    var iss = 0; var ack = 0; var rep = 0; var ret = 0; var ackOnly = 0; var none = 0
    list.forEach { n ->
        val k = n.accountCount
        iss += k
        if (n.ack_received) ack += k
        when {
            n.reply_received -> rep += k
            n.returned -> ret += k
            n.ack_received -> ackOnly += k
            else -> none += k
        }
    }
    return Standing(iss, list.size, ack, rep, ret, ackOnly, none)
}

private fun standingOfOffices(offices: List<Sb46Office>) = Standing(
    issued = offices.sumOf { it.issued },
    covers = offices.sumOf { it.covers },
    ack = offices.sumOf { it.ack },
    replied = offices.sumOf { it.replied },
    returned = offices.sumOf { it.returned },
    ackOnly = offices.sumOf { it.ackOnly },
    unheard = offices.sumOf { it.unheard }
)

private fun Sb46Office.standing() =
    Standing(issued, covers, ack, replied, returned, ackOnly, unheard)

// ═════════════════════════════════════════════════════════════
//  OFFICE LIST
// ═════════════════════════════════════════════════════════════

data class Sb46ListState(
    val loading: Boolean = true,
    val error: String? = null,
    val offices: List<Sb46Office> = emptyList(),   // already visibility-filtered for the user
    val role: String = "USER",
    val isManager: Boolean = false,
    val isMO: Boolean = false
)

@HiltViewModel
class Sb46ListViewModel @Inject constructor(
    private val repo: Sb46Repository,
    private val cpv: CpvRepository,
    private val session: SessionManager
) : ViewModel() {
    private val _state = MutableStateFlow(Sb46ListState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        val user = session.current.value
        val role = user?.role ?: "USER"
        val mo = isCpvMo(role)
        _state.value = _state.value.copy(
            loading = true, error = null, role = role, isManager = isCpvManager(role), isMO = mo
        )
        viewModelScope.launch {
            try {
                val all = repo.listOffices()
                val visible = if (!mo) all else {
                    // A Mail Overseer works only the branches allotted to them. An office with no
                    // branch code can never be allotted, so it stays hidden — as on the web.
                    val me = (user?.username ?: "").lowercase()
                    val mine = cpv.listAllotments()
                        .filter { it.mo_username.lowercase() == me && it.branch_id.isNotBlank() }
                        .map { it.branch_id }
                        .toSet()
                    all.filter { it.branch.isNotBlank() && it.branch in mine }
                }
                _state.value = _state.value.copy(loading = false, error = null, offices = visible)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not read the SB-46 register."
                )
            }
        }
    }
}

@Composable
fun Sb46ListScreen(
    onBack: () -> Unit,
    onOpenOffice: (officeId: String, officeName: String) -> Unit,
    vm: Sb46ListViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }

    val q = query.trim().lowercase()
    val filtered = remember(state.offices, q) {
        if (q.isEmpty()) state.offices
        else state.offices.filter { (it.officeName + " " + it.sol + " " + it.branch).lowercase().contains(q) }
    }
    val overall = remember(state.offices) { standingOfOffices(state.offices) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Sb46TopBar(
                title = "SB-46 Notices",
                subtitle = "Notice tracking · Karur Sub Division",
                onBack = onBack,
                onReload = { vm.load() }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            when {
                state.loading -> item { Sb46Loading() }
                state.error != null -> item { Sb46Error(state.error ?: "") { vm.load() } }
                else -> {
                    item { Sb46ScopeNote(state) }
                    if (overall.issued > 0) {
                        item { Sb46Hero(overall, caption = "${n0(state.offices.size)} ${plural(state.offices.size, "office")} with notices raised") }
                        item { Sb46OutcomeGrid(overall) }
                    }
                    item { Sb46SectionLabel("Offices", "${n0(filtered.size)} of ${n0(state.offices.size)}") }
                    item { KsdSearchField(query, { query = it }, "Search office, SOL ID, branch…") }
                    if (filtered.isEmpty()) item {
                        EmptyState("📮", when {
                            state.offices.isNotEmpty() -> "No offices match your search."
                            state.isMO -> "No notices are waiting on the offices allotted to you."
                            else -> "No SB-46 notices have been raised yet. Raise a batch in the web app at /sb-46 — it needs the sheet of Speed Post labels."
                        })
                    } else filtered.forEach { o ->
                        item(key = o.officeId) { Sb46OfficeCard(o) { onOpenOffice(o.officeId, o.officeName) } }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Shared building blocks
// ─────────────────────────────────────────────────────────────

@Composable
private fun Sb46TopBar(title: String, subtitle: String, onBack: () -> Unit, onReload: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(LocalHeaderBrush.current)
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    title, style = MaterialTheme.typography.titleLarge, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle, style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onReload) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Reload", tint = Color.White)
            }
        }
    }
}

@Composable
private fun Sb46Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
    }
}

/** Small uppercase heading with a muted count on the right. */
@Composable
private fun Sb46SectionLabel(title: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
        )
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Who is looking and what they may do — one quiet line, not a banner. */
@Composable
private fun Sb46ScopeNote(state: Sb46ListState) {
    val n = state.offices.size
    val (tag, text) = when {
        state.isMO -> "Mail Overseer" to
            "Showing the ${n0(n)} ${plural(n, "office")} allotted to you. Record what comes back against each notice; batches and the Final Report are handled at the Sub Divisional office."
        state.isManager -> state.role to "Showing all ${n0(n)} ${plural(n, "office")} with notices raised."
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark()) 0.45f else 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Text(
                tag.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 1.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** A progress ring drawn on a canvas — [track] behind, [tone] for the share. */
@Composable
private fun Sb46Ring(
    pct: Int,
    size: Dp,
    stroke: Dp,
    tone: Color,
    track: Color,
    content: @Composable () -> Unit
) {
    val sweep by animateFloatAsState(pct.coerceIn(0, 100) * 3.6f, tween(900), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val d = this.size.minDimension - w
            val tl = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
            if (sweep > 0f) drawArc(tone, -90f, sweep, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** One bar split into replied · returned · delivered-no-reply · not-yet-heard-of. */
@Composable
private fun Sb46StackBar(s: Standing, height: Dp, track: Color, modifier: Modifier = Modifier) {
    val parts = listOf(
        s.replied to Sb46Tone.Replied,
        s.returned to Sb46Tone.Returned,
        s.ackOnly to Sb46Tone.Delivered,
        s.unheard to Sb46Tone.Unheard.copy(alpha = 0.55f)
    )
    Row(
        modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)
    ) {
        if (s.issued > 0) parts.forEach { (v, c) ->
            if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(c))
        }
    }
}

/** The hero: % settled as a ring, the split bar and its legend, on the theme's header colour. */
@Composable
private fun Sb46Hero(s: Standing, caption: String) {
    val onHero = Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(LocalHeaderBrush.current)
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sb46Ring(s.pct, 84.dp, 9.dp, Color(0xFF34D399), onHero.copy(alpha = 0.16f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${s.pct}%", color = onHero, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text("settled", color = onHero.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "NOTICES ISSUED", color = onHero.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Text(
                        n0(s.issued), color = onHero, fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp, letterSpacing = (-0.5).sp
                    )
                    Text(
                        "one per account · in ${n0(s.covers)} ${plural(s.covers, "cover")}",
                        color = onHero.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${n0(s.settled)} of ${n0(s.issued)} settled", color = onHero,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)
                )
                Text(caption, color = onHero.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Sb46StackBar(s, 10.dp, onHero.copy(alpha = 0.16f))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Sb46LegendItem("Replied", s.replied, Sb46Tone.Replied, onHero, Modifier.weight(1f))
                Sb46LegendItem("Returned", s.returned, Sb46Tone.Returned, onHero, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Sb46LegendItem("Delivered, no reply", s.ackOnly, Sb46Tone.Delivered, onHero, Modifier.weight(1f))
                Sb46LegendItem("Not yet heard of", s.unheard, Sb46Tone.Unheard, onHero, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Settled = reply received or cover returned",
                color = onHero.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun Sb46LegendItem(label: String, value: Int, dot: Color, fg: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(
            label, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(5.dp))
        Text(n0(value), color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** The four outcome tiles — 2 × 2, each with its share of notices issued. */
@Composable
private fun Sb46OutcomeGrid(s: Standing) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sb46StatTile("Ack. received", s.ack, s.issued, Icons.Rounded.MarkEmailRead, Sb46Tone.Delivered, Modifier.weight(1f))
            Sb46StatTile("Replies", s.replied, s.issued, Icons.Rounded.TaskAlt, Sb46Tone.Replied, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sb46StatTile("Returned", s.returned, s.issued, Icons.AutoMirrored.Rounded.AssignmentReturn, Sb46Tone.Returned, Modifier.weight(1f))
            Sb46StatTile("Awaiting", s.awaiting, s.issued, Icons.Rounded.HourglassTop, Sb46Tone.Waiting, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Sb46StatTile(
    label: String,
    value: Int,
    of: Int,
    icon: ImageVector,
    tone: Color,
    modifier: Modifier = Modifier
) {
    val p = pctOf(value, of)
    val bar by animateFloatAsState(p / 100f, tween(800), label = "tile")
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = hairline(),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, contentDescription = null, tint = ink(tone), modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(
                    label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                n0(value), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "$p% of issued", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))
            ) {
                Box(Modifier.fillMaxWidth(bar).fillMaxHeight().clip(CircleShape).background(tone))
            }
        }
    }
}

@Composable
private fun Sb46Error(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🛠️", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            "If this is the first time, migration_v25_sb46.sql may not have been run in Supabase yet.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text("Retry") }
    }
}

/** One office: identity, a ring of % settled, the split bar and the four counts. */
@Composable
private fun Sb46OfficeCard(o: Sb46Office, onClick: () -> Unit) {
    val s = o.standing()
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = hairline(),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Apartment, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        o.officeName, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            o.sol.takeIf { it.isNotBlank() }?.let { "SOL $it" },
                            o.branch.takeIf { it.isNotBlank() }?.let { "Branch $it" }
                        ).joinToString("  ·  ").ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Sb46Ring(o.pct, 46.dp, 5.dp, Sb46Tone.Replied, track) {
                    Text(
                        "${o.pct}%", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    n0(o.issued), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${plural(o.issued, "notice")} · ${n0(o.covers)} ${plural(o.covers, "cover")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(bottom = 2.dp)
                )
                if (o.schemes.isNotEmpty()) {
                    Text(
                        o.schemes.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp).width(110.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Sb46StackBar(s, 6.dp, track)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Sb46MiniStat("Ack", o.ack, Sb46Tone.Delivered, Modifier.weight(1f))
                Sb46MiniStat("Replied", o.replied, Sb46Tone.Replied, Modifier.weight(1f))
                Sb46MiniStat("Returned", o.returned, Sb46Tone.Returned, Modifier.weight(1f))
                Sb46MiniStat("Awaiting", o.awaiting, Sb46Tone.Waiting, Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** A count over its label, the count dimmed when it is zero. */
@Composable
private fun Sb46MiniStat(label: String, value: Int, tone: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            n0(value), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold,
            color = if (value > 0) ink(tone) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═════════════════════════════════════════════════════════════
//  ONE OFFICE'S REGISTER
// ═════════════════════════════════════════════════════════════

/** The register's filters — the web module's status dropdown, as chips. */
enum class Sb46Filter(val label: String) {
    ALL("All"),
    AWAITING("Awaiting"),
    ACK("Ack received"),
    REPLIED("Replied"),
    RETURNED("Returned"),
    SILENT("Delivered, no reply")
}

private fun Sb46Filter.matches(n: Sb46NoticeDto): Boolean = when (this) {
    Sb46Filter.ALL -> true
    Sb46Filter.AWAITING -> !n.ack_received && !n.reply_received && !n.returned
    Sb46Filter.ACK -> n.ack_received
    Sb46Filter.REPLIED -> n.reply_received
    Sb46Filter.RETURNED -> n.returned
    // The notice reached the depositor and nothing came of it — what the Final
    // Report counts as acknowledged but unanswered.
    Sb46Filter.SILENT -> n.ack_received && !n.reply_received && !n.returned
}

data class Sb46DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val notices: List<Sb46NoticeDto> = emptyList(),
    /** Notices with a write in flight — their controls wait rather than queue a second change. */
    val saving: Set<String> = emptySet(),
    val message: String? = null
)

@HiltViewModel
class Sb46DetailViewModel @Inject constructor(
    private val repo: Sb46Repository,
    private val session: SessionManager
) : ViewModel() {
    private val _state = MutableStateFlow(Sb46DetailState())
    val state = _state.asStateFlow()
    private var officeId: String? = null

    fun load(id: String, force: Boolean = false) {
        if (!force && officeId == id && !_state.value.loading && _state.value.error == null) return
        officeId = id
        _state.value = Sb46DetailState(loading = true)
        viewModelScope.launch {
            try {
                _state.value = Sb46DetailState(loading = false, notices = repo.listNotices(id))
            } catch (e: Exception) {
                _state.value = Sb46DetailState(loading = false, error = e.message ?: "Could not load the register.")
            }
        }
    }

    fun reload() { officeId?.let { load(it, force = true) } }

    private val by: String get() = session.authorName() ?: "MO"

    /**
     * Apply one tracking change to a notice. The row on screen only changes once the server has
     * taken it, so what the Mail Overseer sees is always what is recorded; while a write is in
     * flight that notice's controls are held, so an impatient second tap cannot undo the first.
     */
    private fun apply(n: Sb46NoticeDto, what: String, write: suspend (String) -> Sb46NoticeDto) {
        val id = n.notice_id
        if (id.isBlank() || id in _state.value.saving) return
        _state.value = _state.value.copy(saving = _state.value.saving + id)
        viewModelScope.launch {
            try {
                val after = write(by)
                _state.value = _state.value.copy(
                    notices = _state.value.notices.map { if (it.notice_id == id) after else it },
                    message = what
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Not saved — ${e.message}")
            } finally {
                _state.value = _state.value.copy(saving = _state.value.saving - id)
            }
        }
    }

    fun toggleAck(n: Sb46NoticeDto) =
        apply(n, if (n.ack_received) "Acknowledgement cleared" else "Acknowledgement received ✓") {
            repo.setAck(n, !n.ack_received, it)
        }

    fun toggleReply(n: Sb46NoticeDto) =
        apply(n, if (n.reply_received) "Reply cleared" else "Reply received ✓") {
            repo.setReply(n, !n.reply_received, it)
        }

    fun toggleReturned(n: Sb46NoticeDto) =
        apply(n, if (n.returned) "Return cleared" else "Marked returned undelivered ↩") {
            repo.setReturned(n, !n.returned, it)
        }

    fun setReason(n: Sb46NoticeDto, reason: String) =
        apply(n, if (reason.isBlank()) "Reason cleared" else "Reason: $reason") {
            repo.setReturnReason(n, reason, it)
        }

    fun setRemarks(n: Sb46NoticeDto, remarks: String) =
        apply(n, "Remarks saved ✓") { repo.setRemarks(n, remarks, it) }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@Composable
fun Sb46DetailScreen(
    officeId: String,
    officeName: String,
    onBack: () -> Unit,
    vm: Sb46DetailViewModel = hiltViewModel()
) {
    LaunchedEffect(officeId) { vm.load(officeId) }
    val state by vm.state.collectAsState()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Sb46Filter.ALL) }
    var scheme by remember { mutableStateOf<String?>(null) }
    var remarkFor by remember { mutableStateOf<Sb46NoticeDto?>(null) }

    LaunchedEffect(officeId) { query = ""; filter = Sb46Filter.ALL; scheme = null }

    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackHost.showSnackbar(it); vm.clearMessage() }
    }

    val notices = state.notices
    val overall = remember(notices) { standingOf(notices) }
    val bySchemeAll = remember(notices) {
        notices.groupBy { it.scheme.ifBlank { "—" } }.toSortedMap().map { (k, v) -> k to standingOf(v) }
    }
    val q = query.trim().lowercase()
    // Notices in the chosen scheme — the base the status chips count against.
    val inScheme = remember(notices, scheme) {
        if (scheme == null) notices else notices.filter { it.scheme.ifBlank { "—" } == scheme }
    }
    val shown = remember(inScheme, q, filter) {
        inScheme.filter { n ->
            filter.matches(n) && (q.isEmpty() || buildString {
                append(n.depositor ?: ""); append(' ')
                append(n.address ?: ""); append(' ')
                append(n.article_no ?: ""); append(' ')
                append(n.accounts.joinToString(" ") { it.acct })
            }.lowercase().contains(q))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = { Sb46TopBar(officeName, "SB-46 register", onBack) { vm.reload() } }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad)) { Sb46Loading() }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(pad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { Sb46Error(state.error ?: "") { vm.reload() } }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(pad)
            ) {
                if (overall.issued > 0) {
                    item { Sb46Hero(overall, caption = "${bySchemeAll.size} ${plural(bySchemeAll.size, "scheme")}") }
                    item { Sb46OutcomeGrid(overall) }
                }
                if (bySchemeAll.isNotEmpty()) {
                    item {
                        Sb46SchemeTable(
                            rows = bySchemeAll,
                            total = overall,
                            selected = scheme,
                            onSelect = { k -> scheme = if (scheme == k) null else k }
                        )
                    }
                }
                item {
                    Sb46SectionLabel(
                        "Notices" + (scheme?.let { " · $it" } ?: ""),
                        "${n0(shown.size)} of ${n0(notices.size)} ${plural(notices.size, "cover")} · ${n0(shown.sumOf { it.accountCount })} ${plural(shown.sumOf { it.accountCount }, "notice")}"
                    )
                }
                item { KsdSearchField(query, { query = it }, "Depositor, address, article no, account…") }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Sb46Filter.entries.forEach { f ->
                            Sb46Chip(
                                label = f.label,
                                count = inScheme.count { f.matches(it) },
                                selected = filter == f,
                                onClick = { filter = f }
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        EmptyState("🔍", if (notices.isEmpty())
                            "No notices have been raised for this office yet. A batch is raised in the web app at /sb-46, against the sheet of Speed Post labels."
                        else "No notices match these filters.")
                    }
                } else {
                    shown.forEach { n ->
                        item(key = n.notice_id) {
                            Sb46NoticeCard(
                                n = n,
                                saving = n.notice_id in state.saving,
                                onAck = { vm.toggleAck(n) },
                                onReply = { vm.toggleReply(n) },
                                onReturned = { vm.toggleReturned(n) },
                                onReason = { r -> vm.setReason(n, r) },
                                onRemarks = { remarkFor = n }
                            )
                        }
                    }
                }
            }
        }
    }

    remarkFor?.let { n ->
        Sb46RemarkDialog(
            notice = n,
            onDismiss = { remarkFor = null },
            onSave = { text -> vm.setRemarks(n, text); remarkFor = null }
        )
    }
}

/** A pill-shaped filter with its count — filled when chosen. */
@Composable
private fun Sb46Chip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = CircleShape,
        color = if (selected) primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else hairline(),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
            count?.let {
                Spacer(Modifier.width(7.dp))
                Text(
                    n0(it), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Scheme-wise summary — one row per scheme list with notices (covers beneath), the split bar and
 * % settled, and its ack / replied / returned / awaiting. Tapping a row lists only its notices.
 */
@Composable
private fun Sb46SchemeTable(
    rows: List<Pair<String, Standing>>,
    total: Standing,
    selected: String?,
    onSelect: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = hairline(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SCHEME-WISE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                Text(
                    if (selected != null) "Tap again for all" else "Tap a scheme to filter",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            rows.forEach { (k, s) ->
                Sb46SchemeRow(k, s, selected == k, onClick = { onSelect(k) })
            }
            if (rows.size > 1) {
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Sb46SchemeRow("Total", total, selected = false, isTotal = true, onClick = null)
            }
        }
    }
}

@Composable
private fun Sb46SchemeRow(
    key: String,
    s: Standing,
    selected: Boolean,
    isTotal: Boolean = false,
    onClick: (() -> Unit)?
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) primary.copy(alpha = 0.09f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isTotal) {
                Text(
                    "Total", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    key, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) primary else MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "${s.pct}% settled", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    n0(s.issued), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${n0(s.covers)} ${plural(s.covers, "cover")}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Sb46StackBar(s, 6.dp, track)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Sb46InlineCount("Ack", s.ack, Sb46Tone.Delivered, Modifier.weight(1f))
            Sb46InlineCount("Replied", s.replied, Sb46Tone.Replied, Modifier.weight(1f))
            Sb46InlineCount("Returned", s.returned, Sb46Tone.Returned, Modifier.weight(1f))
            Sb46InlineCount("Awaiting", s.awaiting, Sb46Tone.Waiting, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Sb46InlineCount(label: String, value: Int, tone: Color, modifier: Modifier = Modifier) {
    val zero = value == 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (zero) tone.copy(alpha = 0.3f) else tone))
        Spacer(Modifier.width(5.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (zero) 0.55f else 1f)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            n0(value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = if (zero) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The single state a notice is in, with its tone — drives the card's accent and badge. */
private data class Outcome(val label: String, val tone: Color)

private fun outcomeOf(n: Sb46NoticeDto): Outcome = when {
    n.returned -> Outcome("Returned" + (n.return_reason?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), Sb46Tone.Returned)
    n.reply_received -> Outcome("Replied", Sb46Tone.Replied)
    n.ack_received -> Outcome("Delivered", Sb46Tone.Delivered)
    else -> Outcome("Awaiting", Sb46Tone.Waiting)
}

/** One notice: what was sent, and the three things that can come back. */
@Composable
private fun Sb46NoticeCard(
    n: Sb46NoticeDto,
    saving: Boolean,
    onAck: () -> Unit,
    onReply: () -> Unit,
    onReturned: () -> Unit,
    onReason: (String) -> Unit,
    onRemarks: () -> Unit
) {
    val sl = when {
        n.sl_from == null -> ""
        n.sl_to == null || n.sl_to == n.sl_from -> "Sl. ${n.sl_from}"
        else -> "Sl. ${n.sl_from}–${n.sl_to}"
    }
    val out = outcomeOf(n)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = hairline(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // status accent down the left edge
            Box(Modifier.width(4.dp).fillMaxHeight().background(out.tone))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp).animateContentSize()) {
                // ── head: seq, scheme, serial range, outcome ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "#${n.seq}", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = muted
                    )
                    if (n.scheme.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            n.scheme, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (sl.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(sl, style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    Spacer(Modifier.weight(1f))
                    Sb46StatusBadge(out)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    n.depositor?.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val addr = buildString {
                    append(n.address?.takeIf { it.isNotBlank() } ?: "—")
                    n.pincode?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                }
                Spacer(Modifier.height(2.dp))
                Text(addr, style = MaterialTheme.typography.bodySmall, color = muted)

                // ── the accounts this notice covers ──
                if (n.accounts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark()) 0.6f else 1f))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            "${n.accounts.size} ${plural(n.accounts.size, "ACCOUNT")}",
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp, color = muted
                        )
                        n.accounts.forEach { a ->
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    a.acct, style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                a.balance?.let {
                                    Text(
                                        inr(it), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // ── the article it travelled under ──
                Spacer(Modifier.height(10.dp))
                val art = n.article_no?.takeIf { it.isNotBlank() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ConfirmationNumber, contentDescription = null,
                        tint = if (art == null) ink(Sb46Tone.Waiting) else muted, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        art ?: "No article number yet — allot it in the web app",
                        style = if (art == null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontFamily = if (art == null) FontFamily.Default else FontFamily.Monospace,
                        color = if (art == null) ink(Sb46Tone.Waiting) else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)))
                Spacer(Modifier.height(12.dp))

                // ── what came back ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sb46MarkButton("Ack", Icons.Rounded.MarkEmailRead, n.ack_received, !saving, Sb46Tone.Delivered, Modifier.weight(1f), onAck)
                    Sb46MarkButton("Reply", Icons.Rounded.TaskAlt, n.reply_received, !saving, Sb46Tone.Replied, Modifier.weight(1f), onReply)
                    Sb46MarkButton("Returned", Icons.AutoMirrored.Rounded.AssignmentReturn, n.returned, !saving, Sb46Tone.Returned, Modifier.weight(1f), onReturned)
                }
                // The reason written on the cover — only a returned notice has one.
                if (n.returned) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "REASON WRITTEN ON THE COVER",
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp, color = muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Sb46Repository.RETURN_REASONS.forEach { r ->
                            Sb46ReasonChip(r, n.return_reason == r, !saving) {
                                onReason(if (n.return_reason == r) "" else r)
                            }
                        }
                    }
                    if (n.return_reason.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The Final Report splits returned notices by reason — please pick one.",
                            style = MaterialTheme.typography.labelSmall, color = ink(Sb46Tone.Waiting)
                        )
                    }
                }
                // Remarks
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !saving, onClick = onRemarks)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        n.remarks?.takeIf { it.isNotBlank() } ?: "No remarks",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (n.remarks.isNullOrBlank()) muted else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (n.remarks.isNullOrBlank()) "Add" else "Edit",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** The notice's state as a soft tinted badge with a dot. */
@Composable
private fun Sb46StatusBadge(o: Outcome) {
    Row(
        Modifier.clip(CircleShape).background(o.tone.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(o.tone))
        Spacer(Modifier.width(6.dp))
        Text(
            o.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = ink(o.tone), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 1.dp)
        )
    }
}

@Composable
private fun Sb46ReasonChip(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tone = Sb46Tone.Returned
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) tone else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(hairline(), CircleShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** A mark/unmark toggle: solid in its tone once the thing has come back, a quiet tint while not. */
@Composable
private fun Sb46MarkButton(
    label: String,
    icon: ImageVector,
    on: Boolean,
    enabled: Boolean,
    tone: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        on -> tone
        else -> tone.copy(alpha = if (isDark()) 0.16f else 0.08f)
    }
    val fg = if (on) Color.White else ink(tone)
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = fg.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1
        )
    }
}

@Composable
private fun Sb46RemarkDialog(
    notice: Sb46NoticeDto,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(notice.notice_id) { mutableStateOf(notice.remarks ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Rounded.LocalPostOffice, contentDescription = null) },
        title = { Text("Remarks — notice #${notice.seq}") },
        text = {
            Column {
                Text(
                    notice.depositor?.takeIf { it.isNotBlank() } ?: notice.scheme,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("What the depositor said, where the cover went…") },
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }, shape = RoundedCornerShape(12.dp)) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
