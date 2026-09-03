package com.karursdo.ui.directory

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.NoteEntity
import com.karursdo.data.db.OutsiderEntity
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.data.sync.SyncEngine
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.FieldRow
import com.karursdo.ui.components.InitialsAvatar
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.StatusChip
import com.karursdo.ui.components.TypePill
import com.karursdo.ui.theme.Brand
import com.karursdo.ui.theme.LocalHeaderBrush
import com.karursdo.ui.user.NotesSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    val repo: DirectoryRepository,
    val userRepo: UserDataRepository,
    private val syncEngine: SyncEngine
) : ViewModel() {
    val employee = MutableStateFlow<EmployeeEntity?>(null)
    val outsider = MutableStateFlow<OutsiderEntity?>(null)
    val phone = MutableStateFlow<String?>(null)

    fun loadEmployee(type: String, id: String) = viewModelScope.launch {
        employee.value = repo.employeeDao.byId(type, id)
        phone.value = repo.mobileDao.phoneFor(id)
        // Pull any phone edits made by other users, then show the freshest number.
        runCatching { syncEngine.syncPhones() }
        phone.value = repo.mobileDao.phoneFor(id)
    }

    fun loadOutsider(id: String) = viewModelScope.launch {
        outsider.value = repo.outsiderDao.byId(id)
        runCatching { syncEngine.syncPhones() }
        outsider.value = repo.outsiderDao.byId(id)
    }

    // ---- Favorites & notes (login-gated user layer) ----
    fun favFlow(type: String, id: String) = userRepo.isFavorite(type, id)
    fun notesFlow(type: String, id: String) = userRepo.notesFor(type, id)

    fun toggleFav(type: String, id: String, label: String, current: Boolean) =
        viewModelScope.launch { userRepo.toggleFavorite(type, id, label, current) }

    fun addNote(targetType: String, targetId: String?, title: String?, body: String) =
        viewModelScope.launch { userRepo.saveNote(null, targetType, targetId, title, body) }

    fun deleteNote(id: String) = viewModelScope.launch { userRepo.deleteNote(id) }

    /** Persist an edited staff mobile number (mobile_map) and refresh the shown value. */
    fun saveEmployeePhone(employeeId: String, newPhone: String) = viewModelScope.launch {
        val p = newPhone.trim()
        repo.setEmployeePhone(employeeId, p)
        phone.value = p.ifBlank { null }
        runCatching { syncEngine.syncPhones() }   // push to every user immediately
    }

    /** Persist an edited outsider mobile number and refresh the shown value. */
    fun saveOutsiderPhone(resourceId: String, newPhone: String) = viewModelScope.launch {
        val p = newPhone.trim()
        repo.setOutsiderPhone(resourceId, p)
        outsider.value = outsider.value?.copy(mobile = p.ifBlank { null })
        runCatching { syncEngine.syncPhones() }   // push to every user immediately
    }
}

// ---------------- Office detail (staff list) ----------------

@Composable
fun OfficeDetailScreen(
    officeId: String,
    officeName: String,
    onOpenEmployee: (String, String) -> Unit,
    vm: DetailViewModel = hiltViewModel()
) {
    val staff by vm.repo.employeeDao.byOffice(officeId).collectAsState(initial = emptyList())
    val isFav by remember(officeId) { vm.favFlow(UserDataRepository.KIND_OFFICE, officeId) }
        .collectAsState(initial = false)
    val notes by remember(officeId) { vm.notesFlow(UserDataRepository.KIND_OFFICE, officeId) }
        .collectAsState(initial = emptyList())

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(officeName, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Office ID $officeId · ${staff.size} staff",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        vm.toggleFav(UserDataRepository.KIND_OFFICE, officeId, officeName, isFav)
                    }) {
                        Text(if (isFav) "★ Saved" else "☆ Save")
                    }
                }
            }
        }
        item {
            NotesSection(
                notes = notes,
                onAdd = { title, body ->
                    vm.addNote(UserDataRepository.KIND_OFFICE, officeId, title, body)
                },
                onDelete = { vm.deleteNote(it) }
            )
        }
        if (staff.isEmpty()) item { EmptyState("🏢", "No staff recorded for this office.") }
        items(staff, key = { "${it.type}-${it.employeeId}" }) { e ->
            PressableCard(onClick = { onOpenEmployee(e.type, e.employeeId) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    InitialsAvatar(e.name, size = 40)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "${e.employeeId} · ${e.designation ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TypePill(e.type)
                        Spacer(Modifier.height(4.dp))
                        StatusChip(e.status)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------- Employee detail ----------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EmployeeDetailScreen(
    type: String,
    employeeId: String,
    onBack: () -> Unit,
    vm: DetailViewModel = hiltViewModel()
) {
    LaunchedEffect(type, employeeId) { vm.loadEmployee(type, employeeId) }
    val emp by vm.employee.collectAsState()
    val phone by vm.phone.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var editingPhone by remember { mutableStateOf(false) }

    val e = emp ?: return

    if (editingPhone) {
        EditPhoneDialog(
            personName = e.name,
            current = phone,
            onDismiss = { editingPhone = false },
            onSave = {
                vm.saveEmployeePhone(e.employeeId, it)
                editingPhone = false
            }
        )
    }

    val favId = "${e.type}:${e.employeeId}"
    val isFav by remember(favId) { vm.favFlow(UserDataRepository.KIND_EMPLOYEE, favId) }
        .collectAsState(initial = false)
    val notes by remember(favId) { vm.notesFlow(UserDataRepository.KIND_EMPLOYEE, favId) }
        .collectAsState(initial = emptyList())

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("← Back to ${e.officeName ?: "office"}") }
        }
        item {
            // Gradient hero card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LocalHeaderBrush.current)
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(e.name, size = 56, onHero = true)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                e.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                "${e.designation ?: "—"} · ${e.officeName ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tagBg = Color.White.copy(alpha = 0.16f)
                        Pill(if (e.type == "DS") "Departmental" else "GDS", tagBg, Color.White)
                        Pill("ID ${e.employeeId}", tagBg, Color.White)
                        e.level?.let { Pill("Level $it", tagBg, Color.White) }
                        Pill(e.status ?: "—", tagBg, Color.White)
                        phone?.let { p ->
                            Pill(
                                "📱 $p", tagBg, Color.White,
                                Modifier.combinedClickable(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$p"))
                                        )
                                    },
                                    onLongClick = { clipboard.setText(AnnotatedString(p)) }
                                )
                            )
                        }
                    }
                    Row {
                        phone?.let { p ->
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$p"))
                                )
                            }) { Text("Call", color = Color.White) }
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$p"))
                                )
                            }) { Text("SMS", color = Color.White) }
                        }
                        TextButton(onClick = { editingPhone = true }) {
                            Text(if (phone == null) "＋ Add mobile" else "✏️ Edit", color = Color.White)
                        }
                        TextButton(onClick = {
                            vm.toggleFav(UserDataRepository.KIND_EMPLOYEE, favId, e.name, isFav)
                        }) {
                            Text(if (isFav) "★ Saved" else "☆ Save", color = Color.White)
                        }
                    }
                }
            }
        }
        item {
            NotesSection(
                notes = notes,
                onAdd = { title, body ->
                    vm.addNote(UserDataRepository.KIND_EMPLOYEE, favId, title, body)
                },
                onDelete = { vm.deleteNote(it) }
            )
        }
        item {
            SectionCard("Service & Personal") {
                FieldRow("Employee name", e.name)
                FieldRow("Employee ID", e.employeeId)
                FieldRow("Gender", e.gender)
                FieldRow("Date of birth", e.dateOfBirth)
                FieldRow("Date of joining", e.dateOfJoin)
                FieldRow("Level", e.level)
                FieldRow("Designation", e.designation)
                FieldRow("Establishment", e.estDescription)
                FieldRow("PAN", e.pan)
                FieldRow("Status", e.status)
                FieldRow("Mobile", phone)
            }
        }
        item {
            SectionCard("Office & Identifiers") {
                FieldRow("Office", e.officeName)
                FieldRow("Office ID", e.officeId)
                FieldRow("DDO office ID", e.ddoOfficeId)
                FieldRow("Payroll ID", e.payrollId)
                FieldRow("Post ID", e.postId)
                FieldRow("Est. key", e.estKey)
            }
        }
        item {
            SectionCard("Bank") {
                FieldRow("Account no.", e.bankAccNo)
                FieldRow("IFSC", e.ifsc)
                FieldRow("Bank type", e.bankType)
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

// ---------------- Outsider detail ----------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun OutsiderDetailScreen(
    resourceId: String,
    onBack: () -> Unit,
    vm: DetailViewModel = hiltViewModel()
) {
    LaunchedEffect(resourceId) { vm.loadOutsider(resourceId) }
    val out by vm.outsider.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var editingPhone by remember { mutableStateOf(false) }
    val o = out ?: return

    if (editingPhone) {
        EditPhoneDialog(
            personName = o.fullName,
            current = o.mobile,
            onDismiss = { editingPhone = false },
            onSave = {
                vm.saveOutsiderPhone(o.resourceId, it)
                editingPhone = false
            }
        )
    }

    val isFav by remember(o.resourceId) { vm.favFlow(UserDataRepository.KIND_OUTSIDER, o.resourceId) }
        .collectAsState(initial = false)
    val notes by remember(o.resourceId) { vm.notesFlow(UserDataRepository.KIND_OUTSIDER, o.resourceId) }
        .collectAsState(initial = emptyList())

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← Back to outsiders") } }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LocalHeaderBrush.current)
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(o.fullName, size = 56, onHero = true)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(o.fullName, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text(
                                "${o.officeName ?: "—"} · ${o.education ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tagBg = Color.White.copy(alpha = 0.16f)
                        Pill("Outsider", tagBg, Color.White)
                        Pill("Resource ${o.resourceId}", tagBg, Color.White)
                        o.gender?.let { Pill(it, tagBg, Color.White) }
                        o.resourceStatus?.let { Pill(it, tagBg, Color.White) }
                        o.mobile?.let { p ->
                            Pill(
                                "📱 $p", tagBg, Color.White,
                                Modifier.combinedClickable(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$p"))
                                        )
                                    },
                                    onLongClick = { clipboard.setText(AnnotatedString(p)) }
                                )
                            )
                        }
                    }
                    Row {
                        o.mobile?.let { p ->
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$p"))
                                )
                            }) { Text("Call", color = Color.White) }
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$p"))
                                )
                            }) { Text("SMS", color = Color.White) }
                        }
                        TextButton(onClick = { editingPhone = true }) {
                            Text(if (o.mobile == null) "＋ Add mobile" else "✏️ Edit", color = Color.White)
                        }
                        TextButton(onClick = {
                            vm.toggleFav(UserDataRepository.KIND_OUTSIDER, o.resourceId, o.fullName, isFav)
                        }) {
                            Text(if (isFav) "★ Saved" else "☆ Save", color = Color.White)
                        }
                    }
                }
            }
        }
        item {
            NotesSection(
                notes = notes,
                onAdd = { title, body ->
                    vm.addNote(UserDataRepository.KIND_OUTSIDER, o.resourceId, title, body)
                },
                onDelete = { vm.deleteNote(it) }
            )
        }
        item {
            SectionCard("Identity") {
                FieldRow("Full name", o.fullName)
                FieldRow("Father / Guardian", o.fatherName)
                FieldRow("Gender", o.gender)
                FieldRow("Date of birth", o.dateOfBirth)
                FieldRow("Community", o.community)
            }
        }
        item {
            SectionCard("Contact") {
                FieldRow("Mobile", o.mobile)
                FieldRow("Email", o.email)
                FieldRow(
                    "Address",
                    listOfNotNull(o.address1, o.address2, o.address3, o.address4)
                        .filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
                )
                FieldRow("Pin code", o.pinCode)
            }
        }
        item {
            SectionCard("Posting") {
                FieldRow("Office", o.officeName)
                FieldRow("Office ID", o.officeId)
                FieldRow("Sub-division ID", o.subDivisionId)
                FieldRow("Division", o.divisionName)
                FieldRow("Status", o.resourceStatus)
            }
        }
        item {
            SectionCard("Bank") {
                FieldRow("Account no.", o.bankAccNo)
                FieldRow("IFSC", o.ifsc)
                FieldRow("Bank type", o.bankType)
            }
        }
        item {
            SectionCard("Identifiers & Qualification") {
                FieldRow("Resource ID", o.resourceId)
                FieldRow("Aadhaar", o.aadhaar)
                FieldRow("PAN", o.pan)
                FieldRow("Education", o.education)
                FieldRow("Approver post ID", o.approverPostId)
                FieldRow("Login required", o.loginRequired)
                FieldRow("Maker ID", o.makerId)
                FieldRow("Remarks", o.remarks)
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

// ---------------- Shared: edit mobile number ----------------

/**
 * Dialog for adding or editing a staff/outsider mobile number. Accepts digits and
 * the usual phone punctuation (+, space, -), capped at 20 chars. An empty value
 * clears the number.
 */
@Composable
private fun EditPhoneDialog(
    personName: String,
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current.isNullOrBlank()) "Add mobile number" else "Edit mobile number") },
        text = {
            Column {
                Text(
                    personName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() || it in "+ -" }.take(20)
                    },
                    label = { Text("Mobile number") },
                    placeholder = { Text("e.g. 9876543210") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
