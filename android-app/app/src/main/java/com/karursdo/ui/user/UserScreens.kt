package com.karursdo.ui.user

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.FavoriteEntity
import com.karursdo.data.db.NoteEntity
import com.karursdo.data.db.UserAccountEntity
import com.karursdo.data.repo.ActivityType
import com.karursdo.data.repo.AuthRepository
import com.karursdo.data.repo.CreateResult
import com.karursdo.data.repo.MoBeatSeed
import com.karursdo.data.repo.ROLE_ADMIN
import com.karursdo.data.repo.ROLE_USER
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.repo.SessionUser
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.data.repo.UserDataRepository.Companion.PREF_MO_I_USER
import com.karursdo.data.repo.UserDataRepository.Companion.PREF_MO_II_USER
import kotlinx.coroutines.flow.map
import com.karursdo.data.sync.SyncEngine
import com.karursdo.data.sync.SyncOutcome
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Read-only profile shown to a user — all fields are fed by the admin. */
data class ProfileView(
    val name: String = "",
    val designation: String = "",
    val doj: String = ""
)

private val DT_FMT = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
private fun fmtDateTime(ms: Long): String = DT_FMT.format(Date(ms))

@HiltViewModel
class UserViewModel @Inject constructor(
    private val userRepo: UserDataRepository,
    private val keyManager: com.karursdo.data.security.DbKeyManager,
    private val syncEngine: SyncEngine,
    private val authRepo: AuthRepository,
    private val session: SessionManager
) : ViewModel() {

    val currentUser: StateFlow<SessionUser?> = session.current

    // Read-only profile: display name from the account, designation + date-of-joining
    // fed by the admin (kept live so admin edits appear after a sync).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val profileView: StateFlow<ProfileView> = session.current.flatMapLatest { u ->
        if (u == null) flowOf(ProfileView())
        else combine(userRepo.designationFlow(u.username), userRepo.dojFlow(u.username)) { desig, doj ->
            ProfileView(u.displayName, desig?.value.orEmpty(), doj?.value.orEmpty())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileView())

    // Whether this user may manage events/announcements (Admin, or ASP/PA designation).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val canManageEvents: StateFlow<Boolean> = session.current.flatMapLatest { u ->
        if (u == null) flowOf(false)
        else userRepo.designationFlow(u.username).map {
            com.karursdo.data.repo.EventsRepository.canManage(u, it?.value)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val syncEnabled: Boolean get() = syncEngine.enabled
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus
    private val _lastSync = MutableStateFlow(keyManager.lastSyncAt)
    val lastSync: StateFlow<Long> = _lastSync

    val favorites: StateFlow<List<FavoriteEntity>> =
        userRepo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes: StateFlow<List<NoteEntity>> =
        userRepo.allNotes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteNote(id: String) = viewModelScope.launch { userRepo.deleteNote(id) }

    fun changeMyPassword(newPassword: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val uname = session.current.value?.username
        val ok = uname != null && authRepo.setPassword(uname, newPassword)
        if (ok) {
            runCatching { userRepo.log(ActivityType.PROFILE, summary = "Password changed") }
            runCatching { syncEngine.syncNow() }
        }
        onResult(ok)
    }

    fun syncNow() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        _syncStatus.value = "Syncing…"
        when (val r = syncEngine.syncNow()) {
            is SyncOutcome.Disabled -> _syncStatus.value = "Cloud sync isn't configured in this build."
            is SyncOutcome.Success -> {
                _syncStatus.value = "Synced · pushed ${r.pushed}, pulled ${r.pulled}"
                _lastSync.value = keyManager.lastSyncAt
            }
            is SyncOutcome.Error -> _syncStatus.value = "Sync failed: ${r.message}"
        }
        _syncing.value = false
    }
}

@Composable
fun ProfileScreen(
    onOpenFavorite: (itemType: String, itemId: String) -> Unit,
    onOpenUserAdmin: () -> Unit,
    onOpenEvents: () -> Unit,
    onLogout: () -> Unit,
    vm: UserViewModel = hiltViewModel()
) {
    val currentUser by vm.currentUser.collectAsState()
    val canManageEvents by vm.canManageEvents.collectAsState()
    val profile by vm.profileView.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val notes by vm.notes.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val lastSync by vm.lastSync.collectAsState()

    var changingPassword by remember { mutableStateOf(false) }
    var pwMsg by remember { mutableStateOf<String?>(null) }

    if (changingPassword) {
        ChangeMyPasswordDialog(
            onDismiss = { changingPassword = false },
            onSubmit = { newPw ->
                vm.changeMyPassword(newPw) { ok ->
                    pwMsg = if (ok) "Password changed" else "Could not change password"
                    if (ok) changingPassword = false
                }
            }
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            ProfileBanner(
                name = profile.name.ifBlank { currentUser?.displayName ?: "User" },
                subtitle = currentUser?.let { "@${it.username} · ${if (it.isAdmin) "Administrator" else "User"}" }
                    ?: "Signed in",
                designation = profile.designation.ifBlank { "—" },
                doj = profile.doj.ifBlank { "—" }
            )
        }

        item {
            SectionCard("Account") {
                pwMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                }
                Button(
                    onClick = { pwMsg = null; changingPassword = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Change my password") }
                if (canManageEvents) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenEvents,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🗓️ Manage events & announcements") }
                }
                if (currentUser?.isAdmin == true) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenUserAdmin,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("👥 Manage users") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("Log out")
                }
            }
        }

        item {
            SectionCard("Cloud sync") {
                if (vm.syncEnabled) {
                    Text(
                        "Your saved records, notes, preferences and activity back up to the cloud " +
                            "and sync across devices signed in with the same login.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (lastSync > 0L) "Last synced ${DateUtils.getRelativeTimeSpanString(lastSync)}"
                        else "Not synced yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.syncNow() },
                        enabled = !syncing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (syncing) "Syncing…" else "☁ Sync now") }
                } else {
                    Text(
                        "Cloud sync is off. This build has no Supabase keys, so everything stays " +
                            "on this device only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                syncStatus?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            SectionCard("Saved (favorites)") {
                if (favorites.isEmpty()) {
                    Text(
                        "Star any staff member or office to pin it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    favorites.forEach { f ->
                        PressableCard(onClick = { onOpenFavorite(f.itemType, f.itemId) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text("★", color = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(f.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        kindLabel(f.itemType),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        item {
            SectionCard("My notes") {
                if (notes.isEmpty()) {
                    Text(
                        "Notes you add to staff or offices appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    notes.forEach { n ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    n.title?.takeIf { it.isNotBlank() } ?: kindLabel(n.targetType),
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = { vm.deleteNote(n.id) }) { Text("Delete") }
                            }
                            Text(n.body, style = MaterialTheme.typography.bodyMedium)
                            NoteByline(n)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * iOS-style "liquid glass" profile banner: a colour gradient base, soft blurred light orbs for
 * depth, a glossy top sheen, and a frosted translucent glass panel (thin light border) holding the
 * content. The blurs degrade to solid shapes below API 31 but the layered look still reads as glass.
 */
@Composable
private fun ProfileBanner(name: String, subtitle: String, designation: String, doj: String) {
    val initials = remember(name) {
        name.trim().split(" ").filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "U" }
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3B2CC9), Color(0xFF7C3AED), Color(0xFFEC4899))
                )
            )
    ) {
        // Blurred translucent orbs — the "liquid" light refractions.
        Box(
            Modifier.size(190.dp).align(Alignment.TopEnd).offset(x = 60.dp, y = (-70).dp)
                .blur(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.30f))
        )
        Box(
            Modifier.size(150.dp).align(Alignment.BottomStart).offset(x = (-50).dp, y = 60.dp)
                .blur(46.dp).clip(CircleShape).background(Color(0xFF22D3EE).copy(alpha = 0.28f))
        )
        // Glossy diagonal sheen across the top.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.22f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.05f)
                )
            )
        )
        // Frosted glass content panel.
        Column(
            Modifier.padding(7.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(60.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                        .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2
                    )
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                }
            }
            Spacer(Modifier.height(16.dp))
            ProfileFactRow("💼", "Designation", designation)
            Spacer(Modifier.height(8.dp))
            ProfileFactRow("📅", "Date of joining", doj)
        }
    }
}

@Composable
private fun ProfileFactRow(icon: String, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun kindLabel(kind: String?): String = when (kind) {
    UserDataRepository.KIND_EMPLOYEE -> "Staff member"
    UserDataRepository.KIND_OUTSIDER -> "Outsider staff"
    UserDataRepository.KIND_OFFICE -> "Office"
    else -> "Note"
}

/** The "— author · date/time" byline shown under every note. */
@Composable
private fun NoteByline(n: NoteEntity) {
    Text(
        "— ${n.authorName ?: "Unknown"} · ${fmtDateTime(n.updatedAt)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * A reusable "Notes" card: lists the notes for a record (each showing who wrote it and
 * when) and lets the user add or delete one. Used from the detail screens.
 */
@Composable
fun NotesSection(
    notes: List<NoteEntity>,
    onAdd: (title: String?, body: String) -> Unit,
    onDelete: (String) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        AddNoteDialog(
            onDismiss = { adding = false },
            onSave = { title, body -> onAdd(title, body); adding = false }
        )
    }
    SectionCard("Notes") {
        if (notes.isEmpty()) {
            Text(
                "No notes yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            notes.forEach { n ->
                val noteTitle = n.title
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!noteTitle.isNullOrBlank()) {
                            Text(noteTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = { onDelete(n.id) }) { Text("Delete") }
                    }
                    Text(n.body, style = MaterialTheme.typography.bodyMedium)
                    NoteByline(n)
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { adding = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text("＋ Add note") }
    }
}

@Composable
private fun AddNoteDialog(onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note") },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(80) },
                    label = { Text("Title (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body, onValueChange = { body = it.take(1000) },
                    label = { Text("Note") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim().ifBlank { null }, body.trim()) },
                enabled = body.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChangeMyPasswordDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change my password") },
        text = {
            Column {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it; error = null },
                    label = { Text("New password (min 6)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it; error = null },
                    label = { Text("Confirm password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pw.length < 6 -> error = "At least 6 characters"
                    pw != confirm -> error = "Passwords don't match"
                    else -> onSubmit(pw)
                }
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// =========================== Admin: manage users ===========================

@HiltViewModel
class UserAdminViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val userRepo: UserDataRepository,
    private val syncEngine: SyncEngine
) : ViewModel() {
    val users: StateFlow<List<UserAccountEntity>> =
        authRepo.users().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Admin-assigned Mail Overseer usernames (shared across all users).
    val moIUser: StateFlow<String?> = userRepo.globalPrefFlow(PREF_MO_I_USER)
        .map { it?.value?.ifBlank { null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val moIIUser: StateFlow<String?> = userRepo.globalPrefFlow(PREF_MO_II_USER)
        .map { it?.value?.ifBlank { null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** username -> designation, for showing on each manage-users row. */
    val designations: StateFlow<Map<String, String>> = userRepo.designationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    suspend fun designationOf(username: String): String = userRepo.getDesignation(username) ?: ""
    suspend fun dojOf(username: String): String = userRepo.getDoj(username) ?: ""

    /** Admin edit of a user's profile: display name, designation, date of joining. */
    fun editProfile(username: String, displayName: String, designation: String, doj: String) =
        viewModelScope.launch {
            authRepo.setDisplayName(username, displayName)
            userRepo.setDesignation(username, designation.trim())
            userRepo.setDoj(username, doj.trim())
            runCatching { syncEngine.syncNow() }
        }

    /** Tag (or untag, if already this user) a user as MO I / MO II. */
    fun assignMo(beat: String, username: String) = viewModelScope.launch {
        val key = if (beat == MoBeatSeed.MO_II) PREF_MO_II_USER else PREF_MO_I_USER
        val current = userRepo.getGlobalPref(key)?.ifBlank { null }
        userRepo.setGlobalPref(key, if (current == username) "" else username)
        runCatching { syncEngine.syncNow() }
    }

    fun addUser(username: String, displayName: String, password: String, role: String, onResult: (CreateResult) -> Unit) =
        viewModelScope.launch {
            val res = authRepo.createUser(username, displayName, password, role)
            if (res is CreateResult.Ok) runCatching { syncEngine.syncNow() }
            onResult(res)
        }

    fun setActive(username: String, active: Boolean) = viewModelScope.launch {
        authRepo.setActive(username, active)
        runCatching { syncEngine.syncNow() }
    }

    fun resetPassword(username: String, newPassword: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = authRepo.setPassword(username, newPassword)
        if (ok) runCatching { syncEngine.syncNow() }
        onResult(ok)
    }
}

@Composable
fun UserAdminScreen(
    onBack: () -> Unit,
    vm: UserAdminViewModel = hiltViewModel()
) {
    val users by vm.users.collectAsState()
    val moIUser by vm.moIUser.collectAsState()
    val moIIUser by vm.moIIUser.collectAsState()
    val designations by vm.designations.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var resetFor by remember { mutableStateOf<String?>(null) }
    var editProfileFor by remember { mutableStateOf<UserAccountEntity?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }

    if (adding) {
        AddUserDialog(
            onDismiss = { adding = false },
            onCreate = { u, dn, pw, role ->
                vm.addUser(u, dn, pw, role) { res ->
                    banner = when (res) {
                        is CreateResult.Ok -> { adding = false; "User '$u' created. Temp password set — they must change it on first login." }
                        is CreateResult.Duplicate -> "That user ID already exists."
                        is CreateResult.Invalid -> res.reason
                    }
                }
            }
        )
    }
    resetFor?.let { uname ->
        ResetPasswordDialog(
            username = uname,
            onDismiss = { resetFor = null },
            onReset = { newPw ->
                vm.resetPassword(uname, newPw) { ok ->
                    banner = if (ok) "Password reset for '$uname'." else "Could not reset password."
                    resetFor = null
                }
            }
        )
    }
    editProfileFor?.let { u ->
        EditUserProfileDialog(
            user = u,
            vm = vm,
            onDismiss = { editProfileFor = null },
            onSaved = {
                banner = "Profile updated for '${u.username}'."
                editProfileFor = null
            }
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← Back") } }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Manage users", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${users.size} account(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { banner = null; adding = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text("＋ Add user") }
            }
        }
        banner?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }

        if (users.isEmpty()) item { EmptyState("👤", "No accounts yet.") }
        items(users, key = { it.username }) { u ->
            SectionCard(u.displayName) {
                Text(
                    "@${u.username} · ${if (u.role == ROLE_ADMIN) "Administrator" else "User"}" +
                        (designations[u.username]?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                        if (u.mustChangePassword) " · must change password" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Active", modifier = Modifier.weight(1f))
                    Switch(checked = u.active, onCheckedChange = { vm.setActive(u.username, it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Mail Overseer",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = moIUser == u.username,
                        onClick = { vm.assignMo(MoBeatSeed.MO_I, u.username) },
                        label = { Text("MO I") }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = moIIUser == u.username,
                        onClick = { vm.assignMo(MoBeatSeed.MO_II, u.username) },
                        label = { Text("MO II") }
                    )
                }
                Row {
                    TextButton(onClick = { banner = null; editProfileFor = u }) { Text("Edit profile") }
                    TextButton(onClick = { banner = null; resetFor = u.username }) { Text("Reset password") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AddUserDialog(
    onDismiss: () -> Unit,
    onCreate: (username: String, displayName: String, password: String, role: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ROLE_USER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add user") },
        text = {
            Column {
                OutlinedTextField(
                    value = username, onValueChange = { username = it.trim().lowercase().take(30) },
                    label = { Text("User ID (letters/digits)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it.take(60) },
                    label = { Text("Display name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it.take(40) },
                    label = { Text("Temp password (min 6)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("Role", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == ROLE_USER, onClick = { role = ROLE_USER }, label = { Text("User") })
                    FilterChip(selected = role == ROLE_ADMIN, onClick = { role = ROLE_ADMIN }, label = { Text("Admin") })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(username, displayName, password, role) },
                enabled = username.length >= 3 && password.length >= 6
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditUserProfileDialog(
    user: UserAccountEntity,
    vm: UserAdminViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var displayName by remember { mutableStateOf(user.displayName) }
    var designation by remember { mutableStateOf("") }
    var doj by remember { mutableStateOf("") }
    // Load the admin-fed designation / date-of-joining for this user.
    LaunchedEffect(user.username) {
        designation = vm.designationOf(user.username)
        doj = vm.dojOf(user.username)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile · @${user.username}") },
        text = {
            Column {
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it.take(60) },
                    label = { Text("Display name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                DesignationDropdown(
                    selected = designation,
                    onSelect = { designation = it }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = doj, onValueChange = { doj = it.take(30) },
                    label = { Text("Date of joining (e.g. 15 Jun 2019)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.editProfile(user.username, displayName, designation, doj); onSaved() },
                enabled = displayName.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Fixed-choice designation picker (ASPOs / PA / MO-I / MO-II / Admin). No free text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesignationDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Designation") },
            placeholder = { Text("Select designation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            UserDataRepository.DESIGNATIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ResetPasswordDialog(username: String, onDismiss: () -> Unit, onReset: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset password") },
        text = {
            Column {
                Text("New temporary password for @$username", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it.take(40) },
                    label = { Text("New password (min 6)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReset(pw) }, enabled = pw.length >= 6) { Text("Reset") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
