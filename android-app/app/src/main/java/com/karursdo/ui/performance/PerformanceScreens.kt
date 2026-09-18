package com.karursdo.ui.performance

/* ============================================================================
   Delivery Staff Performance — Karur Sub Division (Android)

   Mirrors the web /performance module. Data is a bundled, verified JSON asset
   (assets/data/performance.json) merged from two Excel sources:
     • Postman POSB performance 2026-27.xlsx  → "SDN Wise" (Karur Sub Division)
     • Karur_Sub_Division_Premium_List.xlsx    → premium procured
   POSB grand total = 1882 (matches the source sheet total exactly).

   Ranking uses an "Overall" composite (POSB + premium, each normalised to the
   division max, equally weighted → 0..100) so a nil-on-everything official can
   never outrank someone who actually procured. POSB / Premium sort modes keep
   the composite as a tie-break so nil-nil always sinks last.
   ========================================================================== */

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.karursdo.ui.components.SegmentedToggle
import com.karursdo.ui.components.initialsOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.NumberFormat
import java.util.Locale

// ─────────────────────────────────────────────────────────────
//  Bundled JSON model
// ─────────────────────────────────────────────────────────────
@Serializable private data class PerfGrandDto(val offices: Int, val staff: Int, val posbTotal: Int, val premiumTotal: Int)
@Serializable private data class PerfStaffDto(
    val name: String, val designation: String = "", val beat: String = "",
    val employeeId: String = "", val agentId: String = "",
    val posbMonths: List<Int>? = null, val posbTotal: Int? = null, val premium: Int? = null
)
@Serializable private data class PerfOfficeDto(
    val office: String, val staffCount: Int, val posbTotal: Int, val premiumTotal: Int, val staff: List<PerfStaffDto>
)
@Serializable private data class PerfDataDto(
    val months: List<String>, val monthsFull: List<String> = emptyList(),
    val period: String, val grand: PerfGrandDto, val offices: List<PerfOfficeDto>
)

// ─────────────────────────────────────────────────────────────
//  Derived, display-ready model (ranks + composite scores)
// ─────────────────────────────────────────────────────────────
data class PerfStaff(
    val office: String, val name: String, val designation: String, val beat: String,
    val employeeId: String, val agentId: String,
    val posbMonths: List<Int>?, val posbTotal: Int?, val premium: Int?,
    val score: Double, val posbRank: Int?, val premRank: Int?, val ovRank: Int?
)
data class PerfOffice(
    val office: String, val staffCount: Int, val posbTotal: Int, val premiumTotal: Int,
    val score: Double, val staff: List<PerfStaff>
)
data class PerfModel(
    val period: String, val months: List<String>,
    val offices: Int, val staff: Int, val posbTotal: Int, val premiumTotal: Int,
    val posbN: Int, val premN: Int, val ovN: Int,
    val officeList: List<PerfOffice>
) {
    fun office(name: String): PerfOffice? = officeList.firstOrNull { it.office == name }
}

enum class PerfMode(val label: String) { OVERALL("🏅 Overall"), POSB("🏦 POSB"), PREM("💰 Premium") }

private var PERF_CACHE: PerfModel? = null

/** Parse + rank the bundled JSON once and cache it (static monthly data). */
fun loadPerformance(ctx: Context): PerfModel {
    PERF_CACHE?.let { return it }
    val raw = ctx.assets.open("data/performance.json").bufferedReader().use { it.readText() }
    val dto = Json { ignoreUnknownKeys = true }.decodeFromString(PerfDataDto.serializer(), raw)

    // reference-identity builders so ranking can tag each staff in place
    class SB(val office: String, val d: PerfStaffDto) {
        var score = 0.0; var posbRank: Int? = null; var premRank: Int? = null; var ovRank: Int? = null
    }
    val builders = dto.offices.flatMap { o -> o.staff.map { SB(o.office, it) } }
    val maxPosb = maxOf(1, builders.maxOf { it.d.posbTotal ?: 0 })
    val maxPrem = maxOf(1, builders.maxOf { it.d.premium ?: 0 })
    builders.forEach { it.score = 50.0 * (it.d.posbTotal ?: 0) / maxPosb + 50.0 * (it.d.premium ?: 0) / maxPrem }

    fun rank(list: List<SB>, valueOf: (SB) -> Double, set: (SB, Int) -> Unit) {
        var rank = 0; var prev = Double.NaN; var seen = 0
        list.sortedByDescending(valueOf).forEach { sb ->
            seen++; val v = valueOf(sb); if (v != prev) { rank = seen; prev = v }; set(sb, rank)
        }
    }
    rank(builders.filter { it.d.posbTotal != null }, { it.d.posbTotal!!.toDouble() }) { s, r -> s.posbRank = r }
    rank(builders.filter { it.d.premium != null }, { it.d.premium!!.toDouble() }) { s, r -> s.premRank = r }
    rank(builders, { it.score }) { s, r -> s.ovRank = r }
    val posbN = builders.count { it.d.posbTotal != null }
    val premN = builders.count { it.d.premium != null }

    val maxOffPosb = maxOf(1, dto.offices.maxOf { it.posbTotal })
    val maxOffPrem = maxOf(1, dto.offices.maxOf { it.premiumTotal })

    val byOffice = builders.groupBy { it.office }
    val officeList = dto.offices.map { o ->
        val staff = byOffice[o.office].orEmpty().map { sb ->
            PerfStaff(
                office = o.office, name = sb.d.name, designation = sb.d.designation, beat = sb.d.beat,
                employeeId = sb.d.employeeId, agentId = sb.d.agentId,
                posbMonths = sb.d.posbMonths, posbTotal = sb.d.posbTotal, premium = sb.d.premium,
                score = sb.score, posbRank = sb.posbRank, premRank = sb.premRank, ovRank = sb.ovRank
            )
        }
        PerfOffice(
            office = o.office, staffCount = o.staffCount, posbTotal = o.posbTotal, premiumTotal = o.premiumTotal,
            score = 50.0 * o.posbTotal / maxOffPosb + 50.0 * o.premiumTotal / maxOffPrem, staff = staff
        )
    }
    return PerfModel(
        period = dto.period, months = dto.months,
        offices = dto.grand.offices, staff = dto.grand.staff, posbTotal = dto.grand.posbTotal, premiumTotal = dto.grand.premiumTotal,
        posbN = posbN, premN = premN, ovN = builders.size, officeList = officeList
    ).also { PERF_CACHE = it }
}

// ─────────────────────────────────────────────────────────────
//  Formatting / palette helpers
// ─────────────────────────────────────────────────────────────
private val EN_IN = NumberFormat.getNumberInstance(Locale("en", "IN"))
private fun inrFull(n: Int) = "₹" + EN_IN.format(n)
private fun inrShort(n: Int): String = when {
    n >= 100000 -> "₹" + String.format(Locale.ENGLISH, if (n >= 1000000) "%.1f" else "%.2f", n / 100000.0) + "L"
    n >= 1000 -> "₹" + String.format(Locale.ENGLISH, "%.1f", n / 1000.0) + "k"
    else -> "₹$n"
}
private fun metricPrimary(s: PerfStaff, mode: PerfMode): Double = when (mode) {
    PerfMode.POSB -> (s.posbTotal ?: 0).toDouble()
    PerfMode.PREM -> (s.premium ?: 0).toDouble()
    PerfMode.OVERALL -> s.score
}
private fun staffOrder(list: List<PerfStaff>, mode: PerfMode): List<PerfStaff> =
    list.sortedWith(
        compareByDescending<PerfStaff> { metricPrimary(it, mode) }
            .thenByDescending { it.score }
            .thenByDescending { it.posbTotal ?: 0 }
            .thenByDescending { it.premium ?: 0 }
    )
private fun officeOrder(list: List<PerfOffice>, mode: PerfMode): List<PerfOffice> =
    list.sortedWith(
        compareByDescending<PerfOffice> { when (mode) { PerfMode.POSB -> it.posbTotal.toDouble(); PerfMode.PREM -> it.premiumTotal.toDouble(); PerfMode.OVERALL -> it.score } }
            .thenByDescending { it.score }.thenByDescending { it.posbTotal }
    )

private val DESIG = mapOf(
    "PM" to "Postman", "PW" to "Postwoman", "Post Man" to "Postman", "Deliver Agent" to "Delivery Agent",
    "Dak Sevak" to "Dak Sevak", "Sorting Postman" to "Sorting Postman", "Cash Overseer" to "Cash Overseer"
)
private fun desigLabel(d: String) = DESIG[d] ?: d.ifBlank { "Staff" }
private fun isGds(d: String) = d == "Deliver Agent" || d == "Dak Sevak"

private data class Hue(val a: Color, val b: Color)
private val HUES = listOf(
    Hue(Color(0xFF818CF8), Color(0xFF6366F1)), Hue(Color(0xFF34D399), Color(0xFF10B981)),
    Hue(Color(0xFF22D3EE), Color(0xFF0891B2)), Hue(Color(0xFFFB7185), Color(0xFFE11D48)),
    Hue(Color(0xFFFBBF24), Color(0xFFF59E0B)), Hue(Color(0xFFA78BFA), Color(0xFF7C3AED)),
    Hue(Color(0xFFFB923C), Color(0xFFEA580C)), Hue(Color(0xFFF472B6), Color(0xFFDB2777)),
    Hue(Color(0xFF60A5FA), Color(0xFF2563EB)), Hue(Color(0xFF2DD4BF), Color(0xFF0D9488))
)
private fun hueFor(name: String): Hue {
    var h = 0; for (c in name) h = h * 31 + c.code
    return HUES[((h % HUES.size) + HUES.size) % HUES.size]
}
private val PosbColor = Color(0xFF10B981)
private val PremColor = Color(0xFFF59E0B)

// ═════════════════════════════════════════════════════════════
//  SCREEN 1 — Office grid
// ═════════════════════════════════════════════════════════════
@Composable
fun PerformanceHomeScreen(
    onBack: () -> Unit,
    onOpenOffice: (office: String, mode: String) -> Unit
) {
    val ctx = LocalContext.current
    val model = remember { loadPerformance(ctx) }
    var modeIdx by rememberSaveable { mutableStateOf(PerfMode.OVERALL.ordinal) }
    val mode = PerfMode.values()[modeIdx]
    val offices = remember(mode) { officeOrder(model.officeList, mode) }

    Scaffold(
        topBar = {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF0E7490), Color(0xFF3730A3))))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("🏅 Delivery Performance", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            "POSB account opening · premium procurement · ${model.period}",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            item { DivisionKpis(model) }
            item {
                SegmentedToggle(
                    options = PerfMode.values().map { it.label },
                    selectedIndex = modeIdx,
                    onSelect = { modeIdx = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    when (mode) {
                        PerfMode.POSB -> "Offices ranked by POSB accounts opened"
                        PerfMode.PREM -> "Offices ranked by premium procured"
                        PerfMode.OVERALL -> "Offices ranked by overall performance (POSB + premium)"
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(offices, key = { it.office }) { o ->
                OfficeCard(o, offices.indexOf(o) + 1, onClick = { onOpenOffice(o.office, mode.name) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DivisionKpis(m: PerfModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCell("🏤", m.offices.toString(), "Delivery Offices", Color(0xFF6366F1), Modifier.weight(1f))
            KpiCell("👥", m.staff.toString(), "Delivery Staff", Color(0xFF10B981), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCell("🏦", EN_IN.format(m.posbTotal), "POSB A/Cs Opened", Color(0xFF0891B2), Modifier.weight(1f))
            KpiCell("💰", inrShort(m.premiumTotal), "Premium Procured", Color(0xFFF59E0B), Modifier.weight(1f))
        }
    }
}

@Composable
private fun KpiCell(emoji: String, value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(500), label = "kpi")
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp,
        modifier = modifier.graphicsLayer { this.alpha = alpha }
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.7f))))
            ) { Text(emoji, fontSize = 18.sp) }
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OfficeCard(o: PerfOffice, rank: Int, onClick: () -> Unit) {
    val hue = hueFor(o.office)
    val monthly = remember(o) {
        val m = IntArray(5); o.staff.forEach { s -> s.posbMonths?.forEachIndexed { i, v -> m[i] += v } }; m.toList()
    }
    Surface(
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(5.dp).background(Brush.horizontalGradient(listOf(hue.a, hue.b))))
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.office, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${o.staffCount} delivery ${if (o.staffCount == 1) "official" else "officials"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(hue.a, hue.b)))
                    ) { Text("#$rank", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(EN_IN.format(o.posbTotal), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = PosbColor)
                        Text("POSB A/CS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(inrShort(o.premiumTotal), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = PremColor)
                        Text("PREMIUM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MiniBars(monthly, hue.a, hue.b, Modifier.width(96.dp).height(34.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("View team  →", style = MaterialTheme.typography.labelMedium, color = hue.b, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Animated vertical mini bar chart (Apr–Aug). */
@Composable
private fun MiniBars(values: List<Int>, c1: Color, c2: Color, modifier: Modifier = Modifier) {
    val max = remember(values) { maxOf(1, values.maxOrNull() ?: 1) }
    var grow by remember { mutableStateOf(false) }
    LaunchedEffect(values) { grow = true }
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        values.forEach { v ->
            val frac by animateFloatAsState(if (grow) (v.toFloat() / max) else 0f, tween(700), label = "bar")
            Box(
                Modifier.weight(1f).height((frac.coerceAtLeast(0.04f) * 34).dp)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(Brush.verticalGradient(listOf(c1, c2)))
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  SCREEN 2 — Office team leaderboard
// ═════════════════════════════════════════════════════════════
@Composable
fun PerformanceOfficeScreen(
    officeName: String,
    initialMode: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val model = remember { loadPerformance(ctx) }
    val office = remember(officeName) { model.office(officeName) }
    var modeIdx by rememberSaveable { mutableStateOf(runCatching { PerfMode.valueOf(initialMode) }.getOrDefault(PerfMode.OVERALL).ordinal) }
    val mode = PerfMode.values()[modeIdx]
    var selected by remember { mutableStateOf<PerfStaff?>(null) }
    val hue = hueFor(officeName)

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(hue.a, hue.b))).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(officeName, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val onBeat = office?.staff?.count { it.posbTotal != null } ?: 0
                        Text("${office?.staffCount ?: 0} officials · $onBeat on POSB beats", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
    ) { pad ->
        if (office == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("Office not found") }
            return@Scaffold
        }
        val ordered = remember(mode) { staffOrder(office.staff, mode) }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiCell("🏦", EN_IN.format(office.posbTotal), "POSB Accounts", Color(0xFF0891B2), Modifier.weight(1f))
                    KpiCell("💰", inrFull(office.premiumTotal), "Premium Procured", Color(0xFFF59E0B), Modifier.weight(1f))
                }
            }
            item {
                SegmentedToggle(
                    options = PerfMode.values().map { it.label },
                    selectedIndex = modeIdx,
                    onSelect = { modeIdx = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            itemsIndexed(ordered) { i, s -> StaffRow(s, i + 1) { selected = s } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    selected?.let { s ->
        PerformanceCardDialog(staff = s, mode = mode, model = model, onDismiss = { selected = null })
    }
}

@Composable
private fun StaffRow(s: PerfStaff, rank: Int, onClick: () -> Unit) {
    val hue = hueFor(s.office)
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RankBadge(rank)
            Spacer(Modifier.width(10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(hue.a, hue.b)))
            ) { Text(initialsOf(s.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TagChip(if (isGds(s.designation)) "GDS" else "DEPT", if (isGds(s.designation)) Color(0xFF7C3AED) else Color(0xFF2563EB))
                    TagChip(desigLabel(s.designation).uppercase(), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(s.posbTotal?.let { EN_IN.format(it) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = if (s.posbTotal != null) PosbColor else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("POSB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(s.premium?.let { inrShort(it) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = if (s.premium != null) PremColor else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("PREMIUM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int) {
    val medal = when (rank) {
        1 -> listOf(Color(0xFFFDE68A), Color(0xFFF59E0B))
        2 -> listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8))
        3 -> listOf(Color(0xFFFBBF9C), Color(0xFFD97706))
        else -> null
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
            .background(medal?.let { Brush.linearGradient(it) } ?: Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
    ) {
        Text("$rank", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = if (medal != null) Color(0xFF3A2600) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TagChip(text: String, fg: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(fg.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold, maxLines = 1) }
}

// ═════════════════════════════════════════════════════════════
//  3D immersive PERFORMANCE CARD (finger-tilt)
// ═════════════════════════════════════════════════════════════
@Composable
private fun PerformanceCardDialog(staff: PerfStaff, mode: PerfMode, model: PerfModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC05070E)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss
            ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(visible = true, enter = fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 0.9f)) {
                PerformanceCard(staff, mode, model, onDismiss)
            }
        }
    }
}

@Composable
private fun PerformanceCard(s: PerfStaff, mode: PerfMode, model: PerfModel, onClose: () -> Unit) {
    val hue = hueFor(s.office)
    val office = model.office(s.office)!!
    val posbOfficeRank = if (s.posbTotal != null)
        office.staff.filter { it.posbTotal != null }.sortedByDescending { it.posbTotal }.indexOfFirst { it === s } + 1 else null
    val premOfficeRank = if (s.premium != null)
        office.staff.filter { it.premium != null }.sortedByDescending { it.premium }.indexOfFirst { it === s } + 1 else null
    val divRank = when (mode) { PerfMode.POSB -> s.posbRank; PerfMode.PREM -> s.premRank; PerfMode.OVERALL -> s.ovRank }
    val modeLbl = when (mode) { PerfMode.POSB -> "POSB"; PerfMode.PREM -> "Premium"; PerfMode.OVERALL -> "Overall" }
    val elite = (s.ovRank ?: 99) <= 3

    // finger-tilt state
    var rx by remember { mutableStateOf(0f) }
    var ry by remember { mutableStateOf(0f) }
    val arx by animateFloatAsState(rx, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "rx")
    val ary by animateFloatAsState(ry, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "ry")

    Box(
        Modifier
            .padding(22.dp)
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .graphicsLayer {
                rotationX = arx; rotationY = ary; cameraDistance = 16f * density
            }
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(hue.a, hue.b, hue.a)))
            .padding(2.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF121a2e), Color(0xFF0b1120))))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { rx = 0f; ry = 0f }, onDragCancel = { rx = 0f; ry = 0f }
                ) { change, drag ->
                    change.consume()
                    ry = (ry + drag.x * 0.16f).coerceIn(-16f, 16f)
                    rx = (rx - drag.y * 0.16f).coerceIn(-16f, 16f)
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(22.dp)
    ) {
        // sheen
        Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent, hue.b.copy(alpha = 0.14f))))
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // ribbon
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (divRank != null && divRank <= 3) Brush.linearGradient(listOf(Color(0xFFFDE68A), Color(0xFFF59E0B))) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.16f))))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val txt = when {
                        divRank == null -> "Delivery Official"
                        divRank <= 3 -> listOf("🥇 Rank 1", "🥈 Rank 2", "🥉 Rank 3")[divRank - 1] + " · $modeLbl"
                        else -> "Division #$divRank · $modeLbl"
                    }
                    Text(txt, color = if (divRank != null && divRank <= 3) Color(0xFF3A2600) else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.height(10.dp))
            // avatar
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(hue.a, hue.b)))
                    .border(2.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(26.dp))
            ) { Text(initialsOf(s.name), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp) }
            Spacer(Modifier.height(12.dp))
            Text(s.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip(if (isGds(s.designation)) "GDS" else "DEPARTMENTAL", Color.White.copy(alpha = 0.85f))
                TagChip(desigLabel(s.designation).uppercase(), Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(8.dp))
            Text("🏤 ${s.office}${if (s.beat.isNotBlank()) " · ${s.beat.replace('_', ' ')}" else ""}", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)

            Spacer(Modifier.height(18.dp))
            // metric blocks
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricBlock("POSB A/CS OPENED", s.posbTotal?.let { EN_IN.format(it) } ?: "—", if (s.posbTotal != null) PosbColor else Color.White.copy(alpha = 0.4f), s.posbRank?.let { "#$it/${model.posbN}" }, s.posbMonths?.let { "Apr–Aug 2026" } ?: "Not on beat", Modifier.weight(1f))
                MetricBlock("PREMIUM PROCURED", s.premium?.let { inrShort(it) } ?: "—", if (s.premium != null) PremColor else Color.White.copy(alpha = 0.4f), s.premRank?.let { "#$it/${model.premN}" }, if (s.agentId.isNotBlank()) "Agent ${s.agentId}" else "from Apr 2026", Modifier.weight(1f))
            }

            // monthly chart
            val pm = s.posbMonths
            if (pm != null) {
                Spacer(Modifier.height(18.dp))
                val best = pm.indices.maxByOrNull { pm[it] } ?: 0
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("POSB ACCOUNTS · MONTHLY", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Best: ${model.months[best]} (${pm[best]})", color = PosbColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                MonthlyChart(pm, model.months)
            } else {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f)).padding(14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("No POSB delivery-beat record in Apr–Aug 2026.", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, textAlign = TextAlign.Center) }
            }

            // footer chips
            Spacer(Modifier.height(16.dp))
            val chips = buildList {
                add("Perf. index" to s.score.toInt().toString())
                s.ovRank?.let { add("Overall in div." to "#$it") }
                posbOfficeRank?.let { add("POSB in office" to "#$it") }
                premOfficeRank?.let { add("Premium in office" to "#$it") }
                if (s.posbMonths != null) add("A/Cs / month" to String.format(Locale.ENGLISH, "%.1f", s.posbTotal!! / 5.0))
            }
            chips.chunked(3).forEach { rowChips ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowChips.forEach { (l, v) -> FootChip(l, v, Modifier.weight(1f)) }
                    repeat(3 - rowChips.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (elite) {
                Spacer(Modifier.height(12.dp))
                Text("⭐ TOP 3 OVERALL PERFORMER ⭐", color = Color(0xFFFDE68A), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("Drag to tilt · tap outside to close", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun MetricBlock(head: String, value: String, valueColor: Color, rankTag: String?, sub: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(14.dp)) {
        rankTag?.let { Text(it, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(head, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(value, color = valueColor, fontWeight = FontWeight.ExtraBold, fontSize = if (value.length > 6) 22.sp else 28.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MonthlyChart(months: List<Int>, labels: List<String>) {
    val max = maxOf(1, months.maxOrNull() ?: 1)
    var grow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { grow = true }
    Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        months.forEachIndexed { i, v ->
            val frac by animateFloatAsState(if (grow) v.toFloat() / max else 0f, tween(800), label = "mbar")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text("$v", color = PosbColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier.fillMaxWidth().height((frac * 60).dp.coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(Brush.verticalGradient(listOf(PosbColor, Color(0xFF0F766E))))
                )
                Spacer(Modifier.height(4.dp))
                Text(labels[i], color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun FootChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
    }
}
