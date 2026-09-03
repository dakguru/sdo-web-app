package com.karursdo.ui.offices

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.OfficeMasterEntity
import com.karursdo.data.db.StaffEditEntity
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.data.repo.EventsRepository
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.data.sync.SyncEngine
import androidx.compose.ui.graphics.Brush
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.FieldRow
import com.karursdo.ui.components.initialsOf
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.StatCard
import com.karursdo.ui.components.TypePill
import com.karursdo.ui.theme.Brand
import com.karursdo.ui.theme.LocalHeaderBrush
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
    val repo: DirectoryRepository,
    private val session: SessionManager,
    private val userRepo: UserDataRepository,
    private val syncEngine: SyncEngine
) : ViewModel() {
    val search = MutableStateFlow("")
    val category = MutableStateFlow("All")

    /** employeeId -> mobile number, so each staff card can show a call button. */
    val phones: StateFlow<Map<String, String>> = repo.mobileDao.map()
        .map { rows -> rows.associate { it.employeeId to it.phone } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Whether this user may add staff, set exit details and edit working hours (Admin/ASP/PA). */
    val canManage: StateFlow<Boolean> = session.current.flatMapLatest { u ->
        if (u == null) flowOf(false)
        else userRepo.designationFlow(u.username).map { EventsRepository.canManage(u, it?.value) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Live overlay (exit marks / manual additions) for the currently open office. */
    fun staffEditsForOffice(officeId: String) = repo.staffEditsForOffice(officeId)

    /** Live office master row for the open office (reflects working-hours edits). */
    fun officeFlow(officeId: String) = repo.officeFlow(officeId)

    fun saveOfficeHours(officeId: String, days: String, from: String, to: String) = viewModelScope.launch {
        repo.setOfficeHours(officeId, days, from, to)
        runCatching { syncEngine.syncOfficeHours() }
    }

    fun addStaff(
        type: String, employeeId: String, name: String, designation: String,
        officeId: String, officeName: String?, gender: String,
        dateOfBirth: String, dateOfJoin: String, mobile: String
    ) = viewModelScope.launch {
        repo.addStaff(
            type = type, employeeId = employeeId.trim(), name = name.trim(),
            designation = designation.trim().ifBlank { null }, officeId = officeId, officeName = officeName,
            gender = gender.trim().ifBlank { null }, dateOfBirth = dateOfBirth.trim().ifBlank { null },
            dateOfJoin = dateOfJoin.trim().ifBlank { null }, mobile = mobile.trim().ifBlank { null }
        )
        runCatching { syncEngine.syncStaffEdits() }
        runCatching { syncEngine.syncPhones() }
    }

    fun setStaffExit(type: String, employeeId: String, exitDate: String, exitReason: String) = viewModelScope.launch {
        repo.setStaffExit(type, employeeId, exitDate.trim().ifBlank { null }, exitReason.trim().ifBlank { null })
        runCatching { syncEngine.syncStaffEdits() }
    }

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
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
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
    val o by vm.officeFlow(officeId).collectAsState(initial = null)
    val staff by vm.repo.employeeDao.byOffice(officeId).collectAsState(initial = emptyList())
    val staffEdits by vm.staffEditsForOffice(officeId).collectAsState(initial = emptyList())
    val phones by vm.phones.collectAsState()
    val canManage by vm.canManage.collectAsState()
    val context = LocalContext.current
    val office = o ?: return
    // Branch Offices reporting to this office (relevant for SPO / HPO parents).
    val branchOffices by vm.repo.officeMasterDao
        .childBranchOffices(office.officeName, office.officeId)
        .collectAsState(initial = emptyList())

    // Overlay (exit marks / manual additions) keyed by staff for quick lookup on the cards.
    val exitByStaff = remember(staffEdits) { staffEdits.associateBy { it.type to it.employeeId } }

    var addingStaff by remember { mutableStateOf(false) }
    var exitFor by remember { mutableStateOf<EmployeeEntity?>(null) }
    var editingHours by remember { mutableStateOf(false) }

    if (addingStaff) {
        AddStaffDialog(
            onDismiss = { addingStaff = false },
            onSave = { type, id, name, desig, gender, dob, doj, mobile ->
                vm.addStaff(type, id, name, desig, office.officeId, office.officeName, gender, dob, doj, mobile)
                addingStaff = false
            }
        )
    }
    exitFor?.let { emp ->
        val existing = exitByStaff[emp.type to emp.employeeId]
        ExitDetailsDialog(
            staffName = emp.name,
            currentDate = existing?.exitDate,
            currentReason = existing?.exitReason,
            onDismiss = { exitFor = null },
            onSave = { date, reason -> vm.setStaffExit(emp.type, emp.employeeId, date, reason); exitFor = null }
        )
    }
    if (editingHours) {
        WorkingHoursDialog(
            days = office.workingDays.orEmpty(),
            from = office.workingHoursFrom.orEmpty(),
            to = office.workingHoursTo.orEmpty(),
            onDismiss = { editingHours = false },
            onSave = { d, f, t -> vm.saveOfficeHours(office.officeId, d, f, t); editingHours = false }
        )
    }

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
                    .background(LocalHeaderBrush.current)
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

        // ---- Staff at this office (moved to the TOP, with call buttons & full details) ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Staff at this office (${staff.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    Button(
                        onClick = { addingStaff = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("＋ Add staff") }
                }
            }
        }
        if (staff.isEmpty()) {
            item {
                Text(
                    "No staff mapped to this office in the employee directory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(staff, key = { "${it.type}-${it.employeeId}" }) { e ->
            StaffCard(
                e = e,
                phone = phones[e.employeeId],
                exit = exitByStaff[e.type to e.employeeId],
                canManage = canManage,
                onOpen = { onOpenEmployee(e.type, e.employeeId) },
                onCall = { p ->
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p")))
                },
                onExit = { exitFor = e }
            )
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
                if (canManage) {
                    TextButton(onClick = { editingHours = true }) {
                        Text("✏️ Edit working days & hours")
                    }
                }
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

/**
 * A clean, colourful staff card for Office Management: a type-tinted gradient, a gradient initials
 * avatar, the staff name · Employee ID · designation · DS/GDS badge, and a prominent round call
 * button that opens the dialer prefilled. Tapping the card opens the full staff profile; Admin/ASP/PA
 * users get an overflow menu to record exit / retirement details.
 */
@Composable
private fun StaffCard(
    e: EmployeeEntity,
    phone: String?,
    exit: StaffEditEntity?,
    canManage: Boolean,
    onOpen: () -> Unit,
    onCall: (String) -> Unit,
    onExit: () -> Unit
) {
    val gds = e.type.equals("GDS", ignoreCase = true)
    val accent = if (gds) Brand.Emerald else Brand.Indigo
    val exited = exit?.exitDate != null || exit?.exitReason != null
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        0f to accent.copy(alpha = 0.14f),
                        0.6f to accent.copy(alpha = 0.03f),
                        1f to Color.Transparent
                    )
                )
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Gradient initials avatar, tinted to the staff type.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f))))
            ) {
                Text(initialsOf(e.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    e.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    e.designation ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypePill(e.type)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ID ${e.employeeId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (exited) {
                        Spacer(Modifier.width(8.dp))
                        Pill("Exited", Brand.TpHpoBg, Brand.TpHpoFg)
                    }
                }
            }

            // Overflow (exit / retirement details) for authorised users.
            if (canManage) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (exited) "Edit exit / retirement" else "Exit / retirement details") },
                            onClick = { menuOpen = false; onExit() }
                        )
                    }
                }
            }

            // Round call button — opens the dialer with the number filled in.
            if (!phone.isNullOrBlank()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Brand.Good, Brand.Emerald)))
                        .clickable { onCall(phone) }
                ) {
                    Icon(Icons.Filled.Call, contentDescription = "Call ${e.name}", tint = Color.White, modifier = Modifier.size(23.dp))
                }
            }
        }
    }
}

/** Add a staff member manually to the current office (Admin/ASP/PA only). */
@Composable
private fun AddStaffDialog(
    onDismiss: () -> Unit,
    onSave: (type: String, id: String, name: String, desig: String, gender: String, dob: String, doj: String, mobile: String) -> Unit
) {
    var type by remember { mutableStateOf("DS") }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var desig by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var doj by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add staff") },
        text = {
            Column {
                Text("Staff type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "DS", onClick = { type = "DS" }, label = { Text("Departmental") })
                    FilterChip(selected = type == "GDS", onClick = { type = "GDS" }, label = { Text("GDS") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(id, { id = it.trim().take(24) }, label = { Text("Employee ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(desig, { desig = it.take(60) }, label = { Text("Designation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(mobile, { mobile = it.filter { c -> c.isDigit() || c in "+ -" }.take(20) }, label = { Text("Mobile (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(gender, { gender = it.take(12) }, label = { Text("Gender (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(dob, { dob = it.take(20) }, label = { Text("Date of birth (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(doj, { doj = it.take(20) }, label = { Text("Date of joining (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(type, id, name, desig, gender, dob, doj, mobile) },
                enabled = id.isNotBlank() && name.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Record / edit a staff member's exit or retirement details (Admin/ASP/PA only). */
@Composable
private fun ExitDetailsDialog(
    staffName: String,
    currentDate: String?,
    currentReason: String?,
    onDismiss: () -> Unit,
    onSave: (date: String, reason: String) -> Unit
) {
    var date by remember { mutableStateOf(currentDate.orEmpty()) }
    var reason by remember { mutableStateOf(currentReason.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit / Retirement details") },
        text = {
            Column {
                Text(staffName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(date, { date = it.take(20) }, label = { Text("Exit date (e.g. 30 Jun 2026)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(reason, { reason = it.take(80) }, label = { Text("Reason (retirement / transfer …)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Leave both blank and save to clear the exit and restore the staff as working.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(date, reason) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Edit an office's working days/hours (Admin/ASP/PA only); syncs to every user. */
@Composable
private fun WorkingHoursDialog(
    days: String,
    from: String,
    to: String,
    onDismiss: () -> Unit,
    onSave: (days: String, from: String, to: String) -> Unit
) {
    var d by remember { mutableStateOf(days) }
    var f by remember { mutableStateOf(from) }
    var t by remember { mutableStateOf(to) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Working days & hours") },
        text = {
            Column {
                OutlinedTextField(d, { d = it.take(40) }, label = { Text("Working days (e.g. Mon–Sat)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(f, { f = it.take(16) }, label = { Text("From (e.g. 09:00)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(t, { t = it.take(16) }, label = { Text("To (e.g. 17:00)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d, f, t) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
