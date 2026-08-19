package com.karursdo.ui.people

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.initialsOf
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

private val D_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val MON3 = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val MON_FULL = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

/** Shared source of the (already Karur-scoped) birthday & retirement lists. */
@HiltViewModel
class PeopleEventsViewModel @Inject constructor(
    repo: DirectoryRepository
) : ViewModel() {
    val birthdays = combine(repo.employeeDao.allFlow(), repo.outsiderDao.all()) { emps, outs ->
        buildBirthdays(emps, outs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val retirements = repo.employeeDao.allFlow()
        .map { buildRetirements(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

// ── colour helpers by category ───────────────────────────────────────────────
private fun catAccent(cat: String): Color = when (cat) {
    "DS" -> Brand.Indigo
    "GDS" -> Brand.Emerald
    else -> Brand.Amber
}
private fun catBadge(cat: String): Triple<String, Color, Color> = when (cat) {
    "DS" -> Triple("Dept", Brand.BadgeDsBg, Brand.BadgeDsFg)
    "GDS" -> Triple("GDS", Brand.BadgeGdsBg, Brand.BadgeGdsFg)
    else -> Triple("Outsider", Brand.BadgeOutBg, Brand.BadgeOutFg)
}

@Composable
private fun HeaderBand(logo: String, title: String, subtitle: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brand.HeaderGradient).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(2.dp))
            Text(logo, fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/** Mini stat tile used by both screens. */
@Composable
private fun MiniTile(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "$value",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
        }
    }
}

@Composable
private fun CatChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Indigo, selectedLabelColor = Color.White
                )
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  BIRTHDAYS
// ═════════════════════════════════════════════════════════════

@Composable
fun BirthdaysScreen(onBack: () -> Unit, vm: PeopleEventsViewModel = hiltViewModel()) {
    val all by vm.birthdays.collectAsState()
    var cat by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    val today = remember { LocalDate.now() }

    val q = query.trim().lowercase()
    val list = remember(all, cat, q) {
        all.filter { p ->
            (cat == "ALL" || p.cat == cat) &&
                (q.isEmpty() || "${p.name} ${p.office} ${p.id} ${p.designation}".lowercase().contains(q))
        }
    }
    val todays = list.filter { it.days == 0 }
    val next7 = list.count { it.days in 0..7 }
    val next30 = list.count { it.days in 0..30 }
    // Group by month of next birthday, chronologically (must be built outside LazyListScope).
    val groups = remember(list) {
        val order = LinkedHashMap<String, MutableList<BirthdayPerson>>()
        for (p in list) order.getOrPut("${p.next.year}-${p.next.monthValue}") { mutableListOf() }.add(p)
        order
    }

    Column(Modifier.fillMaxSize()) {
        HeaderBand("🎂", "Staff Birthdays", "Departmental · GDS · Outsource — Karur Sub Division", onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Today hero
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .background(Brush.linearGradient(listOf(Brand.Pink, Brand.Violet)))
                            .padding(18.dp)
                    ) {
                        Text(
                            "🎉  Today's Birthdays · ${today.format(MON3)} ${today.dayOfMonth}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        if (todays.isEmpty()) {
                            val nxt = list.firstOrNull { it.days > 0 }
                            Text(
                                if (nxt == null) "No upcoming birthdays on record."
                                else "No birthdays today. Next in ${nxt.days} day${if (nxt.days == 1) "" else "s"} — ${nxt.name}.",
                                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f)
                            )
                        } else {
                            todays.forEach { p ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                            .background(Color.White.copy(alpha = 0.24f))
                                    ) { Text(initialsOf(p.name), color = Color.White, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(p.designation, p.office, "turning ${p.turning}")
                                                .joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Tiles
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTile("Today", todays.size, Brand.Pink, Modifier.weight(1f))
                    MiniTile("Next 7 days", next7, Brand.Violet, Modifier.weight(1f))
                    MiniTile("Next 30 days", next30, Brand.Indigo, Modifier.weight(1f))
                }
            }
            item {
                CatChips(
                    listOf("ALL" to "All", "DS" to "Dept", "GDS" to "GDS", "OUT" to "Outsiders"),
                    cat
                ) { cat = it }
            }
            item { KsdSearchField(query, { query = it }, "Search name, office, ID…") }

            if (list.isEmpty()) {
                item { EmptyState("🎂", "No birthdays match your filter.") }
            } else {
                groups.forEach { (_, people) ->
                    item {
                        val head = people.first().next
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Pill(head.format(MON_FULL), Brand.BadgeDsBg, Brand.BadgeDsFg)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${people.size} ${if (people.size == 1) "birthday" else "birthdays"}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(people, key = { it.cat + it.id }) { p -> BirthdayCard(p) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BirthdayCard(p: BirthdayPerson) {
    val (badge, bBg, bFg) = catBadge(p.cat)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            // Date chip
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.size(52.dp, 56.dp).clip(RoundedCornerShape(13.dp)).background(catAccent(p.cat)).padding(vertical = 6.dp)
            ) {
                Text("${p.day}", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Text(p.next.format(MON3).uppercase(), color = Color.White.copy(alpha = 0.95f), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name.ifBlank { "—" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(p.office?.ifBlank { null }, p.id.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(badge, bBg, bFg)
                    Spacer(Modifier.width(8.dp))
                    Text("turning ${p.turning}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (p.days == 0) {
                    Text("Today!", fontWeight = FontWeight.ExtraBold, color = Brand.Pink)
                } else {
                    Text("${p.days}", fontWeight = FontWeight.ExtraBold, color = Brand.Pink, style = MaterialTheme.typography.titleMedium)
                    Text(if (p.days == 1) "day" else "days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  RETIREMENTS
// ═════════════════════════════════════════════════════════════

@Composable
fun RetirementsScreen(onBack: () -> Unit, vm: PeopleEventsViewModel = hiltViewModel()) {
    val all by vm.retirements.collectAsState()
    var cat by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    val today = remember { LocalDate.now() }

    val q = query.trim().lowercase()
    // Upcoming only (retire on/after today), matching the web tool.
    val list = remember(all, cat, q) {
        all.filter { p ->
            !p.retire.isBefore(today) &&
                (cat == "ALL" || p.cat == cat) &&
                (q.isEmpty() || "${p.name} ${p.office} ${p.id} ${p.designation}".lowercase().contains(q))
        }
    }
    val thisMonthEnd = YearMonth.from(today).atEndOfMonth()
    val thisMonth = list.count { !it.retire.isBefore(today) && !it.retire.isAfter(thisMonthEnd) }
    val next90 = list.count { it.days in 0..90 }
    val cyLeft = list.count { it.retire.year == today.year }
    // Group by retirement year (must be built outside LazyListScope).
    val groups = remember(list) {
        val order = LinkedHashMap<Int, MutableList<RetirementPerson>>()
        for (p in list) order.getOrPut(p.retire.year) { mutableListOf() }.add(p)
        order
    }

    Column(Modifier.fillMaxSize()) {
        HeaderBand("🗓️", "Retirements", "Superannuation (60) · GDS discharge (65) — Karur Sub Division", onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTile("This month", thisMonth, Brand.Rose, Modifier.weight(1f))
                    MiniTile("Next 90 days", next90, Brand.Amber, Modifier.weight(1f))
                    MiniTile("${today.year} left", cyLeft, Brand.Indigo, Modifier.weight(1f))
                }
            }
            item { CatChips(listOf("ALL" to "All", "DS" to "Dept", "GDS" to "GDS"), cat) { cat = it } }
            item { KsdSearchField(query, { query = it }, "Search name, office, ID…") }

            if (list.isEmpty()) {
                item { EmptyState("🗓️", "No upcoming retirements match your filter.") }
            } else {
                groups.forEach { (year, people) ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Pill(
                                "$year",
                                if (year == today.year) Brand.ChipOtherBg else Brand.BadgeDsBg,
                                if (year == today.year) Brand.ChipOtherFg else Brand.BadgeDsFg
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${people.size} ${if (people.size == 1) "person" else "people"}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(people, key = { it.cat + it.id }) { p -> RetirementCard(p) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RetirementCard(p: RetirementPerson) {
    val (badge, bBg, bFg) = catBadge(p.cat)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(catAccent(p.cat))
            ) { Text(initialsOf(p.name), color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name.ifBlank { "—" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Pill(badge, bBg, bFg)
                }
                Text(
                    listOfNotNull(p.designation?.ifBlank { null }, p.office?.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        "${if (p.cat == "DS") "Retires" else "Discharge"} ",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(p.retire.format(D_FMT), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Brand.Rose)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${p.days}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
