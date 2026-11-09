package com.karursdo.ui.sb46

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.repo.CpvRepository
import com.karursdo.data.repo.Sb46NoticeDto
import com.karursdo.data.repo.Sb46Office
import com.karursdo.data.repo.Sb46Repository
import com.karursdo.data.repo.SessionManager
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.inr
import com.karursdo.ui.cpv.isCpvManager
import com.karursdo.ui.cpv.isCpvMo
import com.karursdo.ui.theme.Brand
import com.karursdo.ui.theme.LocalHeaderBrush
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 */

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

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(LocalHeaderBrush.current).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SB-46 Notices", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            "Record what comes back · Karur Sub Division",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reload", tint = Color.White)
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
            if (!state.loading && state.error == null) {
                item { Sb46ScopeBanner(state) }
                item { Sb46OverviewCard(state.offices) }
            }
            item { KsdSearchField(query, { query = it }, "Search office, SOL ID, branch…") }
            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.error != null -> item { Sb46Error(state.error ?: "") { vm.load() } }
                filtered.isEmpty() -> item {
                    EmptyState("📮", when {
                        state.offices.isNotEmpty() -> "No offices match your search."
                        state.isMO -> "No notices are waiting on the offices allotted to you."
                        else -> "No SB-46 notices have been raised yet. Raise a batch in the web app at /sb-46 — it needs the sheet of Speed Post labels."
                    })
                }
                else -> filtered.forEach { o ->
                    item(key = o.officeId) { Sb46OfficeCard(o) { onOpenOffice(o.officeId, o.officeName) } }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Sb46ScopeBanner(state: Sb46ListState) {
    val n = state.offices.size
    when {
        state.isMO -> Sb46Banner(
            "Mail Overseer",
            "You are seeing the $n office${if (n == 1) "" else "s"} allotted to you. You may record what comes back against each notice; raising a batch and writing the Final Report are done at the Sub Divisional office.",
            Brand.ChipPaidBg, Brand.ChipPaidFg
        )
        state.isManager -> Sb46Banner(
            state.role,
            "You are seeing all $n office${if (n == 1) "" else "s"} with notices raised.",
            Brand.BadgeDsBg, Brand.BadgeDsFg
        )
        else -> {}
    }
}

@Composable
private fun Sb46Banner(tag: String, text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = bg.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Pill(tag, bg, fg)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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

@Composable
private fun Sb46OverviewCard(offices: List<Sb46Office>) {
    val issued = offices.sumOf { it.issued }
    val ack = offices.sumOf { it.ack }
    val replied = offices.sumOf { it.replied }
    val returned = offices.sumOf { it.returned }
    val awaiting = (issued - replied - returned).coerceAtLeast(0)
    SectionCard("Overview") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill("Offices ${offices.size}", Brand.BadgeDsBg, Brand.BadgeDsFg)
            Pill("📮 Issued $issued", Brand.BadgeDsBg, Brand.BadgeDsFg)
            Pill("✉ Ack $ack", Brand.TpSpoBg, Brand.TpSpoFg)
            Pill("✓ Replied $replied", Brand.ChipPaidBg, Brand.ChipPaidFg)
            Pill("↩ Returned $returned", Brand.TpHpoBg, Brand.TpHpoFg)
            Pill("⏳ Awaiting $awaiting", Brand.ChipOtherBg, Brand.ChipOtherFg)
        }
    }
}

@Composable
private fun Sb46OfficeCard(o: Sb46Office, onClick: () -> Unit) {
    PressableCard(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        o.officeName, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            if (o.sol.isNotBlank()) append("SOL ${o.sol} · ")
                            if (o.branch.isNotBlank()) append("Branch ${o.branch} · ")
                            append("${o.issued} notice${if (o.issued == 1) "" else "s"}")
                            if (o.schemes.isNotEmpty()) append(" · ${o.schemes.joinToString(", ")}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Pill("✉ ${o.ack} ack", Brand.TpSpoBg, Brand.TpSpoFg)
                Pill("✓ ${o.replied} replied", Brand.ChipPaidBg, Brand.ChipPaidFg)
                Pill("↩ ${o.returned} returned", Brand.TpHpoBg, Brand.TpHpoFg)
                if (o.awaiting > 0) Pill("⏳ ${o.awaiting} awaiting", Brand.ChipOtherBg, Brand.ChipOtherFg)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { o.pct / 100f },
                    modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(999.dp)),
                    color = Brand.Emerald,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${o.pct}%", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = Brand.Emerald
                )
            }
            Text(
                "settled — replied or returned",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
    val schemes = remember(notices) { notices.map { it.scheme }.filter { it.isNotBlank() }.distinct().sorted() }
    val q = query.trim().lowercase()
    val shown = remember(notices, q, filter, scheme) {
        notices.filter { n ->
            if (scheme != null && n.scheme != scheme) return@filter false
            when (filter) {
                Sb46Filter.ALL -> true
                Sb46Filter.AWAITING -> !n.ack_received && !n.reply_received && !n.returned
                Sb46Filter.ACK -> n.ack_received
                Sb46Filter.REPLIED -> n.reply_received
                Sb46Filter.RETURNED -> n.returned
                // The notice reached the depositor and nothing came of it — what the Final
                // Report counts as acknowledged but unanswered.
                Sb46Filter.SILENT -> n.ack_received && !n.reply_received && !n.returned
            } && run {
                if (q.isEmpty()) true else {
                    val hay = buildString {
                        append(n.depositor ?: ""); append(' ')
                        append(n.address ?: ""); append(' ')
                        append(n.article_no ?: ""); append(' ')
                        append(n.accounts.joinToString(" ") { it.acct })
                    }.lowercase()
                    hay.contains(q)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(LocalHeaderBrush.current).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            officeName, style = MaterialTheme.typography.titleMedium, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "SB-46 register", style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reload", tint = Color.White)
                    }
                }
            }
        }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(pad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { Sb46Error(state.error ?: "") { vm.reload() } }
            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(pad)
            ) {
                item { Sb46RegisterSummary(notices) }
                item { KsdSearchField(query, { query = it }, "Depositor, address, article no, account…") }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Sb46Filter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
                if (schemes.size > 1) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = scheme == null,
                                onClick = { scheme = null },
                                label = { Text("All schemes") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                            schemes.forEach { s ->
                                FilterChip(
                                    selected = scheme == s,
                                    onClick = { scheme = if (scheme == s) null else s },
                                    label = { Text(s) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Showing ${shown.size} of ${notices.size} notice${if (notices.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                item { Spacer(Modifier.height(24.dp)) }
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

@Composable
private fun Sb46RegisterSummary(notices: List<Sb46NoticeDto>) {
    val ack = notices.count { it.ack_received }
    val replied = notices.count { it.reply_received }
    val returned = notices.count { it.returned }
    val awaiting = (notices.size - replied - returned).coerceAtLeast(0)
    val pct = if (notices.isNotEmpty()) (replied + returned) * 100 / notices.size else 0
    SectionCard("The register") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill("📮 Issued ${notices.size}", Brand.BadgeDsBg, Brand.BadgeDsFg)
            Pill("✉ Ack $ack", Brand.TpSpoBg, Brand.TpSpoFg)
            Pill("✓ Replied $replied", Brand.ChipPaidBg, Brand.ChipPaidFg)
            Pill("↩ Returned $returned", Brand.TpHpoBg, Brand.TpHpoFg)
            Pill("⏳ Awaiting $awaiting", Brand.ChipOtherBg, Brand.ChipOtherFg)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(999.dp)),
                color = Brand.Emerald,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "$pct%", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = Brand.Emerald
            )
        }
    }
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // ── head: seq, scheme, serial range ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${n.seq}", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                if (n.scheme.isNotBlank()) Pill(n.scheme, Brand.BadgeDsBg, Brand.BadgeDsFg)
                if (sl.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        sl, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Sb46OutcomePill(n)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                n.depositor?.takeIf { it.isNotBlank() } ?: "—",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
            )
            val addr = buildString {
                append(n.address?.takeIf { it.isNotBlank() } ?: "—")
                n.pincode?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            }
            Text(
                addr, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // ── the accounts this notice covers ──
            if (n.accounts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${n.accounts.size} a/c",
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                n.accounts.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.acct, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        a.balance?.let {
                            Text(
                                inr(it), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // ── the article it travelled under ──
            Spacer(Modifier.height(8.dp))
            Text(
                n.article_no?.takeIf { it.isNotBlank() }?.let { "📮 $it" }
                    ?: "📮 No article number yet — allot it in the web app",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = if (n.article_no.isNullOrBlank()) FontFamily.Default else FontFamily.Monospace,
                color = if (n.article_no.isNullOrBlank()) Brand.Warn else MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            // ── what came back ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sb46MarkButton("✉ Ack", n.ack_received, !saving, Brand.Teal, Modifier.weight(1f), onAck)
                Sb46MarkButton("✓ Reply", n.reply_received, !saving, Brand.Emerald, Modifier.weight(1f), onReply)
                Sb46MarkButton("↩ Returned", n.returned, !saving, Brand.Rose, Modifier.weight(1f), onReturned)
            }
            // The reason written on the cover — only a returned notice has one.
            if (n.returned) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reason written on the cover",
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Sb46Repository.RETURN_REASONS.forEach { r ->
                        val on = n.return_reason == r
                        FilterChip(
                            selected = on,
                            enabled = !saving,
                            onClick = { onReason(if (on) "" else r) },
                            label = { Text(r) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Rose,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                if (n.return_reason.isNullOrBlank()) {
                    Text(
                        "The Final Report splits the returned notices by reason, so please pick one.",
                        style = MaterialTheme.typography.labelSmall, color = Brand.Warn
                    )
                }
            }
            // Remarks
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.remarks?.takeIf { it.isNotBlank() }?.let { "📝 $it" } ?: "No remarks",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n.remarks.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onRemarks, enabled = !saving) {
                    Text(if (n.remarks.isNullOrBlank()) "Add" else "Edit")
                }
            }
        }
    }
}

/** The one thing that has happened to this notice so far, at a glance. */
@Composable
private fun Sb46OutcomePill(n: Sb46NoticeDto) = when {
    n.returned -> Pill(
        "↩ Returned" + (n.return_reason?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        Brand.TpHpoBg, Brand.TpHpoFg
    )
    n.reply_received -> Pill("✓ Replied", Brand.ChipPaidBg, Brand.ChipPaidFg)
    n.ack_received -> Pill("✉ Delivered", Brand.TpSpoBg, Brand.TpSpoFg)
    else -> Pill("⏳ Awaiting", Brand.ChipOtherBg, Brand.ChipOtherFg)
}

/** A mark/unmark button: filled once the thing has come back, outlined while it has not. */
@Composable
private fun Sb46MarkButton(
    label: String,
    on: Boolean,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (on) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White)
        ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
        ) {
            Text(
                label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        title = { Text("Remarks — notice #${notice.seq}") },
        text = {
            Column {
                Text(
                    notice.depositor?.takeIf { it.isNotBlank() } ?: notice.scheme,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("What the depositor said, where the cover went…") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
