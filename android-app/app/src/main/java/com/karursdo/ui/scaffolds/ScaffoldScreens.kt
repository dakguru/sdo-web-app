package com.karursdo.ui.scaffolds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.ArrangementEntity
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.StatCard
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ArrangementsViewModel @Inject constructor(
    repo: DirectoryRepository,
    private val syncEngine: com.karursdo.data.sync.SyncEngine
) : ViewModel() {
    val arrangements = repo.arrangementDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ArrangementEntity>())
    val meta = repo.importMetaDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<com.karursdo.data.db.ImportMetaEntity>())

    /** Pull the latest arrangements another user may have uploaded. */
    fun refresh() = viewModelScope.launch { runCatching { syncEngine.syncDatasets() } }
}

private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)
private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val DISPLAY_DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

private fun parseDmy(s: String?): LocalDate? =
    s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it, DMY) }.getOrNull() }

private fun fmtDate(s: String?): String = parseDmy(s)?.format(DISPLAY_DMY) ?: (s ?: "—")

/** True when an arrangement is in effect at any point during [month]. */
private fun activeInMonth(a: ArrangementEntity, month: YearMonth): Boolean {
    val from = parseDmy(a.fromDate) ?: return false
    val to = parseDmy(a.toDate)               // null / open-ended => still running
    val monthStart = month.atDay(1)
    val monthEnd = month.atEndOfMonth()
    return !from.isAfter(monthEnd) && (to == null || !to.isBefore(monthStart))
}

private fun catShort(category: String?): String = when (category) {
    "GDS" -> "GDS · Outsource"
    "NONSANC_OUT" -> "Non-Sanc · Outsource"
    "NONSANC_GDS" -> "Non-Sanc · GDS"
    else -> category ?: "—"
}

private fun catColors(category: String?): Pair<Color, Color> = when (category) {
    "GDS" -> Brand.BadgeDsBg to Brand.BadgeDsFg
    "NONSANC_GDS" -> Brand.BadgeTelBg to Brand.BadgeTelFg
    else -> Brand.BadgeOutBg to Brand.BadgeOutFg
}

/**
 * Temporary Arrangements — Karur Sub Division, office-wise for a selected month
 * (this month / previous month). Shows the three tracked categories: GDS-post and
 * Non-Sanctioned-post cover by outsource resources or GDS employees.
 */
@Composable
fun ArrangementsScreen(
    onOpenImport: () -> Unit,
    vm: ArrangementsViewModel = hiltViewModel()
) {
    val items by vm.arrangements.collectAsState()
    val meta by vm.meta.collectAsState()
    val arrMeta = meta.firstOrNull { it.type == "ARR" }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    val thisMonth = remember { YearMonth.now() }
    var showPrevious by remember { mutableStateOf(false) }
    val month = if (showPrevious) thisMonth.minusMonths(1) else thisMonth

    // Active-in-month rows (Cancelled excluded — they aren't in effect), grouped office-wise.
    val monthItems = remember(items, month) {
        items.filter { activeInMonth(it, month) && !it.status.equals("Cancelled", true) }
    }
    val byOffice = remember(monthItems) {
        monthItems.groupBy { it.officeName ?: "—" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Column {
                Text(
                    "Temporary Arrangements",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    "Karur Sub Division · office-wise · ${month.format(MONTH_LABEL)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Arrangements", monthItems.size, Modifier.weight(1f))
                    StatCard("Offices", byOffice.size, Modifier.weight(1f))
                    StatCard(
                        "Substitutes",
                        monthItems.mapNotNull { it.personId }.distinct().size,
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Month toggle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showPrevious,
                        onClick = { showPrevious = false },
                        label = { Text("This month") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    FilterChip(
                        selected = showPrevious,
                        onClick = { showPrevious = true },
                        label = { Text("Previous month") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
                SectionCard("Update arrangements data") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        com.karursdo.ui.components.TypePill("ARR")
                        Text(
                            arrMeta?.let {
                                "${it.recordCount} arrangements · ${it.fileName} · ${it.updatedAt}"
                            } ?: "No arrangements uploaded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Button(
                        onClick = onOpenImport,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📤 Upload arrangements export (.xls / .xlsx)") }
                }
            }
        }

        if (items.isEmpty()) {
            item {
                EmptyState(
                    "📋",
                    "No arrangements loaded yet.\nUpload the HRMS Temporary Arrangements export " +
                        "above — Karur Sub Division rows for GDS-post and Non-Sanctioned-post cover " +
                        "are kept and shared with every user."
                )
            }
        } else if (byOffice.isEmpty()) {
            item {
                EmptyState(
                    "🗓",
                    "No temporary arrangements in effect during ${month.format(MONTH_LABEL)}."
                )
            }
        }

        byOffice.forEach { (office, rows) ->
            item(key = office) {
                SectionCard("🏤 $office  (${rows.size})") {
                    rows.sortedBy { it.post ?: "" }.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        val (bg, fg) = catColors(a.category)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(catShort(a.category), bg, fg)
                            Text(
                                "  ${a.post ?: "—"}",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        Text(
                            a.personName ?: "—",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${fmtDate(a.fromDate)} → ${a.toDate?.let { fmtDate(a.toDate) } ?: "continuing"}" +
                                (a.status?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Inspection Reports — scaffold destination in the same house style. */
@Composable
fun InspectionReportsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Inspection Reports",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            "Branch/Sub Office inspection reports — list & detail",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        EmptyState(
            "📄",
            "The Inspection Report builder lives in the desktop tool for now.\n" +
                "This module is scaffolded and ready to receive the report list, " +
                "clause library and deficiency tracker."
        )
    }
}
