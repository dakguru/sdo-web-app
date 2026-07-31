package com.karursdo.ui.directory

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.karursdo.data.db.OfficeSummary
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.InitialsAvatar
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SegmentedToggle
import com.karursdo.ui.components.TypePill
import com.karursdo.ui.theme.Brand

@Composable
fun DirectoryScreen(
    onOpenOffice: (OfficeSummary) -> Unit,
    onOpenEmployee: (type: String, id: String) -> Unit,
    onOpenOutsider: (String) -> Unit,
    vm: DirectoryViewModel = hiltViewModel()
) {
    var tab by remember { mutableStateOf(0) }
    val outsiderCount by vm.outsiderCount.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SegmentedToggle(
            options = listOf("🏢 By Office", "🧑 Outsiders"),
            counts = listOf(null, outsiderCount),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(vertical = 10.dp)
        )
        if (tab == 0) OfficesTab(vm, onOpenOffice, onOpenEmployee)
        else OutsidersTab(vm, onOpenOutsider)
    }
}

@Composable
private fun OfficesTab(
    vm: DirectoryViewModel,
    onOpenOffice: (OfficeSummary) -> Unit,
    onOpenEmployee: (String, String) -> Unit
) {
    val offices by vm.offices.collectAsState()
    val total by vm.totalOfficeCount.collectAsState()
    val search by vm.officeSearch.collectAsState()
    val filter by vm.typeFilter.collectAsState()
    val globalQuery by vm.globalSearch.collectAsState()
    val hits by vm.globalHits.collectAsState()
    var jumpOpen by remember { mutableStateOf(false) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "Offices",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${offices.size} of $total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { jumpOpen = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("— Jump to office —") }
                    DropdownMenu(
                        expanded = jumpOpen,
                        onDismissRequest = { jumpOpen = false },
                        modifier = Modifier.height(400.dp)
                    ) {
                        offices.forEach { o ->
                            DropdownMenuItem(
                                text = { Text("${o.officeName ?: o.officeId}  (${o.dsCount + o.gdsCount})") },
                                onClick = { jumpOpen = false; onOpenOffice(o) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                KsdSearchField(search, { vm.officeSearch.value = it }, "Search office name…")
                Spacer(Modifier.height(8.dp))
                FilterTabs(filter) { vm.typeFilter.value = it }
                Spacer(Modifier.height(4.dp))
                KsdSearchField(
                    globalQuery, { vm.globalSearch.value = it },
                    "Search any staff by name or ID…"
                )
            }
        }

        if (globalQuery.length >= 2) {
            if (hits.isEmpty()) item { EmptyState("🔍", "No staff match \"$globalQuery\".") }
            items(hits, key = { "hit-${it.employee.type}-${it.employee.employeeId}" }) { hit ->
                PressableCard(onClick = { onOpenEmployee(hit.employee.type, hit.employee.employeeId) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        InitialsAvatar(hit.employee.name, size = 38)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(hit.employee.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "${hit.employee.employeeId} · ${hit.employee.officeName ?: "—"}" +
                                    (hit.phone?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        TypePill(hit.employee.type)
                    }
                }
            }
        } else {
            if (offices.isEmpty()) {
                item { EmptyState("🏢", "No offices match the current filters.") }
            }
            items(offices, key = { it.officeId }) { o ->
                OfficeRow(o, filter, onClick = { onOpenOffice(o) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FilterTabs(selected: TypeFilter, onSelect: (TypeFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            TypeFilter.ALL to "All",
            TypeFilter.DS to "Departmental",
            TypeFilter.GDS to "GDS"
        ).forEach { (tf, label) ->
            val on = tf == selected
            val bg by animateColorAsState(
                if (on) Brand.Indigo else MaterialTheme.colorScheme.surface,
                animationSpec = spring(stiffness = 500f), label = "filterBg"
            )
            Text(
                label,
                color = if (on) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(tf) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun OfficeRow(o: OfficeSummary, filter: TypeFilter, onClick: () -> Unit) {
    // Web: "2 dept · 3 GDS"
    val meta = buildString {
        if (o.dsCount > 0) append("${o.dsCount} dept")
        if (o.dsCount > 0 && o.gdsCount > 0) append(" · ")
        if (o.gdsCount > 0) append("${o.gdsCount} GDS")
        if (isEmpty()) append("no staff")
    }
    val count = when (filter) {
        TypeFilter.ALL -> o.dsCount + o.gdsCount
        TypeFilter.DS -> o.dsCount
        TypeFilter.GDS -> o.gdsCount
    }
    PressableCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    o.officeName ?: o.officeId,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Pill("$count", Brand.CntBg, Brand.CntFg)
        }
    }
}

@Composable
private fun OutsidersTab(vm: DirectoryViewModel, onOpenOutsider: (String) -> Unit) {
    val outsiders by vm.outsiders.collectAsState()
    val search by vm.outsiderSearch.collectAsState()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Column {
                Text(
                    "Outsider staff",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                KsdSearchField(
                    search, { vm.outsiderSearch.value = it },
                    "Search name, ID, mobile, PAN…"
                )
            }
        }
        if (outsiders.isEmpty()) item { EmptyState("🧑", "No outsiders match the search.") }
        items(outsiders, key = { it.resourceId }) { o ->
            PressableCard(onClick = { onOpenOutsider(o.resourceId) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    InitialsAvatar(o.fullName, size = 38)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(o.fullName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            (o.officeName ?: o.education ?: "—") +
                                (o.mobile?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Pill(
                        o.gender?.firstOrNull()?.uppercase() ?: "?",
                        Brand.BadgeOutBg, Brand.BadgeOutFg
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
