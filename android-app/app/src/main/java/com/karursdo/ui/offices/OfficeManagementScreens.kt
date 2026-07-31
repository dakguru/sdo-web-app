package com.karursdo.ui.offices

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.OfficeMasterEntity
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.FieldRow
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.StatCard
import com.karursdo.ui.components.TypePill
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Office categories shown as filter chips. `key` matches [officeCategory]; label is user-facing. */
val OFFICE_CATEGORIES = listOf(
    "All" to "All",
    "HPO" to "HPO (HPO & BPC)",
    "SPO" to "SPO",
    "BPO" to "BPO",
    "ADMIN" to "Admin (PDN & SDO)"
)

/** Map an office's HRMS type to one of the display categories. */
fun officeCategory(type: String?): String = when (type?.uppercase()) {
    "HPO", "BPC" -> "HPO"
    "SPO" -> "SPO"
    "BPO" -> "BPO"
    "PDN", "SDO" -> "ADMIN"
    else -> "OTHER"
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class OfficeMgmtViewModel @Inject constructor(
    val repo: DirectoryRepository
) : ViewModel() {
    val search = MutableStateFlow("")
    val category = MutableStateFlow("All")

    private val allOffices: StateFlow<List<OfficeMasterEntity>> =
        repo.officeMasterDao.all()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Office ids that have a staff member matching the search term (for search-by-staff).
    private val staffMatchOfficeIds: StateFlow<Set<String>> =
        search.debounce(150).flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptySet())
            else repo.employeeDao.officeIdsByStaffName(q).map { it.toSet() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val offices: StateFlow<List<OfficeMasterEntity>> =
        combine(allOffices, search.debounce(150), category, staffMatchOfficeIds) { list, q, cat, staffIds ->
            list.filter { o ->
                val mq = q.isBlank() || o.officeName.contains(q, true) ||
                    (o.pincode ?: "").contains(q, true) || (o.solId ?: "").contains(q, true) ||
                    o.officeId in staffIds
                val mc = cat == "All" || officeCategory(o.officeType) == cat
                mq && mc
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val detail = MutableStateFlow<OfficeMasterEntity?>(null)
    fun loadDetail(officeId: String) = viewModelScope.launch {
        detail.value = repo.officeMasterDao.byId(officeId)
    }
}

private fun typePill(type: String?): Pair<Color, Color> = when (type?.uppercase()) {
    "BPO" -> Brand.TpBpoBg to Brand.TpBpoFg
    "SPO" -> Brand.TpSpoBg to Brand.TpSpoFg
    "SDO" -> Brand.TpSdoBg to Brand.TpSdoFg
    "HPO" -> Brand.TpHpoBg to Brand.TpHpoFg
    else -> Brand.TpOthBg to Brand.TpOthFg
}

@Composable
fun OfficeManagementScreen(
    onOpenOffice: (String) -> Unit,
    vm: OfficeMgmtViewModel = hiltViewModel()
) {
    val offices by vm.offices.collectAsState()
    val selectedCat by vm.category.collectAsState()
    val search by vm.search.collectAsState()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Column {
                Text(
                    "Office Management",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
                Text(
                    "Sub-division-wise office details · hierarchy, contact & operations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Offices", offices.size, Modifier.weight(1f))
                    StatCard(
                        "Branch POs",
                        offices.count { it.officeType.equals("BPO", true) },
                        Modifier.weight(1f)
                    )
                    StatCard(
                        "Sub/Head POs",
                        offices.count { (it.officeType ?: "").uppercase() in setOf("SPO", "SDO", "HPO") },
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                KsdSearchField(search, { vm.search.value = it }, "Search office, staff name, pincode, SOL…")
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    OFFICE_CATEGORIES.forEach { (key, label) ->
                        FilterChip(
                            selected = key == selectedCat,
                            onClick = { vm.category.value = key },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Indigo,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        }
        if (offices.isEmpty()) {
            item {
                EmptyState(
                    "🏛",
                    "No offices loaded yet. Import the HRMS Office Management export " +
                        "from the Update Monthly Data screen."
                )
            }
        }
        items(offices, key = { it.officeId }) { o ->
            PressableCard(onClick = { onOpenOffice(o.officeId) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.officeName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${o.subDivision ?: "—"} · ${o.hoName ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val (bg, fg) = typePill(o.officeType)
                    Pill(o.officeType ?: "OTH", bg, fg)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun OfficeMasterDetailScreen(
    officeId: String,
    onBack: () -> Unit,
    onOpenEmployee: (String, String) -> Unit,
    onOpenOffice: (String) -> Unit = {},
    vm: OfficeMgmtViewModel = hiltViewModel()
) {
    LaunchedEffect(officeId) { vm.loadDetail(officeId) }
    val o by vm.detail.collectAsState()
    val staff by vm.repo.employeeDao.byOffice(officeId).collectAsState(initial = emptyList())
    val context = LocalContext.current
    val office = o ?: return
    // Branch Offices reporting to this office (relevant for SPO / HPO parents).
    val branchOffices by vm.repo.officeMasterDao
        .childBranchOffices(office.officeName, office.officeId)
        .collectAsState(initial = emptyList())

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← Back to offices") } }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brand.HeroGradient)
                    .padding(18.dp)
            ) {
                Column {
                    Text("🏤 ${office.officeName}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        "${office.subDivision ?: "—"} Sub-Division · ${office.division ?: "—"} · ${office.hoName ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tagBg = Color.White.copy(alpha = 0.16f)
                        office.officeType?.let { Pill(it, tagBg, Color.White) }
                        office.officeStatus?.let { Pill(it, tagBg, Color.White) }
                        Pill("${staff.size} staff", tagBg, Color.White)
                    }
                }
            }
        }
        item {
            SectionCard("Identity & Hierarchy") {
                FieldRow("Office name", office.officeName)
                FieldRow("Office ID", office.officeId)
                FieldRow("Type", office.officeType)
                FieldRow("Class", office.officeClass)
                FieldRow("Status", office.officeStatus)
                FieldRow("Sub-division", office.subDivision)
                FieldRow("Division", office.division)
                FieldRow("Head Office", office.hoName)
                FieldRow("Sub Office (S.O)", office.soName)
                FieldRow("Region", office.region)
                FieldRow("Circle", office.circle)
            }
        }
        item {
            SectionCard("Contact & Address") {
                FieldRow("Email", office.email)
                FieldRow("Contact", office.contact)
                FieldRow("Pincode", office.pincode)
                FieldRow("Village", office.village)
                FieldRow("Taluk", office.taluk)
                FieldRow("City", office.city)
                FieldRow(
                    "Address",
                    listOfNotNull(
                        office.address1, office.address2, office.address3,
                        office.village, office.taluk, office.city, office.state
                    ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
                )
            }
        }
        item {
            SectionCard("Operations") {
                FieldRow("Working days", office.workingDays)
                FieldRow(
                    "Hours",
                    listOfNotNull(office.workingHoursFrom, office.workingHoursTo)
                        .filter { it.isNotBlank() }
                        .joinToString(" – ").ifBlank { null }
                )
                FieldRow("SOL ID", office.solId)
                FieldRow("CSI facility ID", office.csiFacilityId)
                FieldRow("PLI ID", office.pliId)
                FieldRow("GSTN", office.gstn)
                FieldRow("PAO code", office.paoCode)
                FieldRow("Delivery office", office.deliveryOffice)
                FieldRow("Head of office (ID)", office.headOfOfficeId)
                if (!office.latitude.isNullOrBlank() && !office.longitude.isNullOrBlank()) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("geo:${office.latitude},${office.longitude}?q=${office.latitude},${office.longitude}")
                            )
                        )
                    }) { Text("📍 Open location in Maps") }
                }
            }
        }
        item {
            SectionCard("Staff at this office (${staff.size})") {
                if (staff.isEmpty()) {
                    Text(
                        "No staff mapped to this office in the employee directory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                staff.forEach { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            TextButton(
                                onClick = { onOpenEmployee(e.type, e.employeeId) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text(e.name, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                "${e.employeeId} · ${e.designation ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TypePill(e.type)
                    }
                }
            }
        }
        if (branchOffices.isNotEmpty()) {
            item {
                SectionCard("Branch Offices under this office (${branchOffices.size})") {
                    branchOffices.forEach { bo ->
                        PressableCard(onClick = { onOpenOffice(bo.officeId) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(bo.officeName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${bo.officeId} · ${bo.pincode ?: "—"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val (bg, fg) = typePill(bo.officeType)
                                Pill(bo.officeType ?: "BO", bg, fg)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
