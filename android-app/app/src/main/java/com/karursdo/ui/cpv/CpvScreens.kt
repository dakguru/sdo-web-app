package com.karursdo.ui.cpv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.repo.CpvAccount
import com.karursdo.data.repo.CpvAllotmentDto
import com.karursdo.data.repo.CpvBatchDto
import com.karursdo.data.repo.CpvConsRow
import com.karursdo.data.repo.CpvExtraDto
import com.karursdo.data.repo.CpvOfficeConsolidated
import com.karursdo.data.repo.CpvRecordDto
import com.karursdo.data.repo.CpvRepository
import com.karursdo.data.repo.CpvUserDto
import com.karursdo.data.repo.PliMasterDto
import com.karursdo.data.repo.SessionManager
import com.karursdo.report.CpvReportMeta
import com.karursdo.report.CpvReportPdf
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.inr
import com.karursdo.ui.theme.Brand
import com.karursdo.ui.theme.LocalHeaderBrush
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────
//  Shared helpers
// ─────────────────────────────────────────────────────────────

private val DMY = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
private fun fmtVerAt(ms: Long?): String = ms?.let { DMY.format(java.util.Date(it)) } ?: ""

/** Web statusClass() mirror → chip colours. */
private fun statusColors(status: String?): Pair<Color, Color> {
    val t = (status ?: "").lowercase()
    return when {
        t.contains("active") -> Brand.ChipPaidBg to Brand.ChipPaidFg
        t.contains("dorm") -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        t.contains("freez") || t.contains("froz") -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
        else -> Brand.TpOthBg to Brand.TpOthFg
    }
}

/** dd-MM-yyyy from the record's iso `date`, falling back to the raw text. */
private fun fmtTxn(iso: String, raw: String): String {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(iso)
    return if (m != null) "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}" else raw
}

/** PLI / RPLI insurance lists render a different column set than bank schemes. */
private fun isPliScheme(scheme: String?): Boolean {
    val s = (scheme ?: "").uppercase()
    return s == "PLI" || s == "RPLI"
}
private val MONTHS_ABBR = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
/** MMM-YYYY (e.g. "Jun-2026") from an iso yyyy-mm-dd, falling back to a stored label/raw. */
private fun monYear(iso: String, label: String, raw: String): String {
    val m = Regex("^(\\d{4})-(\\d{2})").find(iso)
    return when {
        label.isNotBlank() -> label
        m != null -> "${MONTHS_ABBR[m.groupValues[2].toInt() - 1]}-${m.groupValues[1]}"
        else -> raw
    }
}

/**
 * Long CBS account-type labels are shortened for the card's type pill so they never crowd the
 * holder's name. "MINOR A/C OPERATED BY GUARDIAN" → "Minor Ac OPG" (small letters); any other
 * type passes through unchanged.
 */
private fun shortAcctType(type: String): String {
    val low = type.trim().lowercase()
    return when {
        low.contains("minor") && low.contains("guardian") -> "Minor Ac OPG"
        else -> type.trim()
    }
}

// ═════════════════════════════════════════════════════════════
//  LIST SCREEN
// ═════════════════════════════════════════════════════════════

// ── CPV role logic (mirrors the web cpv.html) ──
private fun cpvRoleNorm(r: String?): String = (r ?: "").uppercase().replace(Regex("[^A-Z]"), "")
private val CPV_MANAGER_ROLES = setOf("ADMIN", "ASP", "IP", "PA")  // see every office
private val CPV_ALLOT_ROLES = setOf("ADMIN", "ASP", "PA")          // may allot offices to MOs
fun isCpvManager(role: String?): Boolean = cpvRoleNorm(role) in CPV_MANAGER_ROLES
fun canCpvAllot(role: String?): Boolean = cpvRoleNorm(role) in CPV_ALLOT_ROLES
fun isCpvMo(role: String?): Boolean = cpvRoleNorm(role) == "MO"

data class CpvListState(
    val loading: Boolean = true,
    val error: String? = null,
    val batches: List<CpvBatchDto> = emptyList(),   // already visibility-filtered for the user
    val role: String = "USER",
    val isManager: Boolean = false,
    val canAllot: Boolean = false,
    val isMO: Boolean = false,
    val allot: Map<String, Set<String>> = emptyMap(), // branch_id -> lowercase MO usernames
    val vCounts: Map<String, Int> = emptyMap(),        // office_key -> verified account count
    val extraTot: Map<String, Int> = emptyMap(),       // office_key -> extra-policy count (counted in totals)
    val extraVer: Map<String, Int> = emptyMap(),       // office_key -> verified extra-policy count
    val moUsers: List<CpvUserDto> = emptyList(),
    val message: String? = null
)

@HiltViewModel
class CpvListViewModel @Inject constructor(
    private val repo: CpvRepository,
    private val session: SessionManager
) : ViewModel() {
    private val _state = MutableStateFlow(CpvListState())
    val state = _state.asStateFlow()
    val enabled: Boolean get() = repo.enabled

    init { load() }

    fun load() {
        val user = session.current.value
        val role = user?.role ?: "USER"
        val manager = isCpvManager(role); val allotAble = canCpvAllot(role); val mo = isCpvMo(role)
        _state.value = _state.value.copy(
            loading = true, error = null, role = role, isManager = manager, canAllot = allotAble, isMO = mo
        )
        viewModelScope.launch {
            try {
                val all = repo.listBatches()
                val allotMap = repo.listAllotments()
                    .filter { it.branch_id.isNotBlank() }
                    .groupBy({ it.branch_id }, { it.mo_username.lowercase() })
                    .mapValues { it.value.toSet() }
                val vCounts = repo.verifiedCountsByOffice()
                val (exTot, exVer) = runCatching { repo.extraCountsByOffice() }.getOrDefault(emptyMap<String, Int>() to emptyMap<String, Int>())
                val moUsers = if (allotAble) repo.listMoUsers() else emptyList()
                val myName = (user?.username ?: "").lowercase()
                val visible = if (mo) all.filter { (allotMap[it.branch_id ?: ""] ?: emptySet()).contains(myName) } else all
                _state.value = _state.value.copy(
                    loading = false, error = null, batches = visible,
                    allot = allotMap, vCounts = vCounts, extraTot = exTot, extraVer = exVer, moUsers = moUsers
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Could not load lists.")
            }
        }
    }

    /** Managers: set the MOs allotted to one office, then reload. */
    fun saveAllotment(branch: String, officeName: String?, sol: String?, selected: Set<String>) {
        val prev = _state.value.allot[branch] ?: emptySet()
        val by = session.authorName() ?: "web"
        viewModelScope.launch {
            try {
                repo.setAllotments(branch, officeName, sol, selected, prev, by)
                _state.value = _state.value.copy(message = "Allotment saved ✓")
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: "Could not save allotment.")
            }
        }
    }

    /** Fetch one office's verified+pending rows across all categories (for the consolidated download). */
    suspend fun consolidated(branch: String): CpvOfficeConsolidated = repo.loadOfficeConsolidated(branch)

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@Composable
fun CpvListScreen(
    onBack: () -> Unit,
    onOpenBatch: (officeKey: String, title: String) -> Unit,
    vm: CpvListViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScopeCompat()
    val snackHost = remember { androidx.compose.material3.SnackbarHostState() }
    var allotFor by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackHost.showSnackbar(it); vm.clearMessage() }
    }

    fun runDownload(branch: String, office: String, sol: String, kind: String) {
        if (downloading) return
        downloading = true
        scope.launch {
            runCatching {
                val data = vm.consolidated(branch)
                val rows = if (kind == "Verified") data.verified else data.pending
                if (rows.isEmpty()) { snackHost.showSnackbar("No ${kind.lowercase()} accounts in $office."); return@runCatching }
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    CpvReportPdf.generateConsolidated(context, data.officeName.ifBlank { office }, data.sol.ifBlank { sol }, branch, kind, rows)
                }
                CpvReportPdf.open(context, file)
            }.onFailure { snackHost.showSnackbar("Could not build report: ${it.message}") }
            downloading = false
        }
    }

    val q = query.trim().lowercase()
    val filtered = remember(state.batches, q) {
        if (q.isEmpty()) state.batches
        else state.batches.filter {
            (it.office_name + " " + (it.sol_id ?: "") + " " + (it.branch_id ?: "") + " " + it.scheme)
                .lowercase().contains(q)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { (it.branch_id ?: "") + "|" + it.office_name } }

    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackHost) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(LocalHeaderBrush.current).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("Cent Percent Verification", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            "Verify accounts during field visits · Karur Sub Division",
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
            if (!state.loading && state.error == null) {
                item { CpvScopeBanner(state) }
                item { CpvOverviewCard(state) }
            }
            item { KsdSearchField(query, { query = it }, "Search office, SOL ID, branch…") }
            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.error != null -> item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🛠️", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.error ?: "Could not load.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.load() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text("Retry") }
                    }
                }
                filtered.isEmpty() -> item {
                    EmptyState("🗂️", when {
                        state.isMO -> "No offices have been allotted to you yet. Please contact your Sub Divisional office."
                        state.batches.isEmpty() -> "No account lists stored yet. Upload a Last Balance Report from the web app to begin."
                        else -> "No lists match your search."
                    })
                }
                else -> groups.forEach { (_, items) ->
                    val g = items.first()
                    val branch = g.branch_id ?: ""
                    val totAcc = items.sumOf { cpvTotalFor(it, state.extraTot) }
                    val totVer = items.sumOf { cpvVerifiedFor(it, state.vCounts, state.extraVer, state.extraTot) }
                    val pending = (totAcc - totVer).coerceAtLeast(0)
                    val pct = if (totAcc > 0) totVer * 100 / totAcc else 0
                    item {
                        SectionCard(g.office_name) {
                            Text(
                                buildString {
                                    g.sol_id?.takeIf { it.isNotBlank() }?.let { append("SOL $it · ") }
                                    if (branch.isNotBlank()) append("Branch $branch · ")
                                    append("$totAcc accounts")
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("✓ $totVer verified", Brand.ChipPaidBg, Brand.ChipPaidFg)
                                Pill("$pending pending", Brand.TpOthBg, Brand.TpOthFg)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { pct / 100f },
                                    modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(999.dp)),
                                    color = Brand.Emerald,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("$pct%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Brand.Emerald)
                            }
                            // Allotment (managers only)
                            if (state.canAllot) {
                                Spacer(Modifier.height(10.dp))
                                val moSet = state.allot[branch] ?: emptySet()
                                val names = moSet.mapNotNull { un -> state.moUsers.firstOrNull { it.username.lowercase() == un }?.display_name ?: un }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (names.isEmpty()) "👤 Not allotted" else "👤 " + names.joinToString(", "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (names.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Emerald,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(onClick = { allotFor = Triple(branch, g.office_name, g.sol_id ?: "") }) {
                                        Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("Allot")
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            items.sortedBy { it.scheme }.forEach { b ->
                                SchemeRow(b, cpvVerifiedFor(b, state.vCounts, state.extraVer, state.extraTot), cpvTotalFor(b, state.extraTot)) { onOpenBatch(b.office_key, "${b.office_name} · ${b.scheme}") }
                            }
                            // Per-office consolidated download (all categories)
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            Text("⬇ Consolidated · all categories", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { runDownload(branch, g.office_name, g.sol_id ?: "", "Verified") }, enabled = !downloading, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Verified")
                                }
                                OutlinedButton(onClick = { runDownload(branch, g.office_name, g.sol_id ?: "", "Pending") }, enabled = !downloading, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Pending")
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Allotment dialog (managers)
    allotFor?.let { (branch, office, sol) ->
        CpvAllotDialog(
            office = office, branch = branch,
            moUsers = state.moUsers,
            current = state.allot[branch] ?: emptySet(),
            onDismiss = { allotFor = null },
            onSave = { selected -> vm.saveAllotment(branch, office, sol, selected); allotFor = null }
        )
    }
}

/** verified accounts for one stored list, clamped to its total. */
// Verified count for a batch (clamped to its own records), plus any verified extra policies.
private fun cpvVerifiedFor(b: CpvBatchDto, vCounts: Map<String, Int>, extraVer: Map<String, Int> = emptyMap(), extraTot: Map<String, Int> = emptyMap()): Int =
    (vCounts[b.office_key] ?: 0).coerceAtMost(b.total_accounts) +
        (extraVer[b.office_key] ?: 0).coerceAtMost(extraTot[b.office_key] ?: 0)
// Total accounts/policies for a batch — its own records plus any extras attached.
private fun cpvTotalFor(b: CpvBatchDto, extraTot: Map<String, Int>): Int =
    b.total_accounts + (extraTot[b.office_key] ?: 0)

@Composable
private fun CpvScopeBanner(state: CpvListState) {
    when {
        state.isMO -> InfoBanner("Mail Overseer", "You can see & verify ${state.batches.map { it.branch_id }.distinct().size} office(s) allotted to you. Others are hidden.", Brand.ChipPaidBg, Brand.ChipPaidFg)
        state.isManager -> InfoBanner(state.role, "You can see all offices" + (if (state.canAllot) " and allot them to Mail Overseers." else "."), Brand.BadgeDsBg, Brand.BadgeDsFg)
        else -> {}
    }
}

@Composable
private fun InfoBanner(tag: String, text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = bg.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(tag, bg, fg)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CpvOverviewCard(state: CpvListState) {
    val offices = state.batches.map { it.branch_id ?: it.office_name }.distinct().size
    val lists = state.batches.size
    val totAcc = state.batches.sumOf { cpvTotalFor(it, state.extraTot) }
    val totVer = state.batches.sumOf { cpvVerifiedFor(it, state.vCounts, state.extraVer, state.extraTot) }
    val pending = (totAcc - totVer).coerceAtLeast(0)
    SectionCard("Overview") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Offices $offices", Brand.BadgeDsBg, Brand.BadgeDsFg)
            Pill("Lists $lists", Brand.BadgeDsBg, Brand.BadgeDsFg)
            Pill("Accounts $totAcc", Brand.TpOthBg, Brand.TpOthFg)
            Pill("✓ Verified $totVer", Brand.ChipPaidBg, Brand.ChipPaidFg)
            Pill("Yet to verify $pending", Brand.TpOthBg, Brand.TpOthFg)
        }
    }
}

@Composable
private fun SchemeRow(b: CpvBatchDto, verified: Int, total: Int = b.total_accounts, onClick: () -> Unit) {
    val pending = (total - verified).coerceAtLeast(0)
    val pct = if (total > 0) verified * 100 / total else 0
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(b.scheme, Brand.BadgeDsBg, Brand.BadgeDsFg)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.scheme_label ?: b.scheme, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("$total ${if (isPliScheme(b.scheme)) "policies" else "accounts"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$pct%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Brand.Emerald)
                Spacer(Modifier.width(8.dp))
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("✓ $verified verified", Brand.ChipPaidBg, Brand.ChipPaidFg)
                Pill("⏳ $pending pending", Brand.TpOthBg, Brand.TpOthFg)
            }
        }
    }
}

@Composable
private fun CpvAllotDialog(
    office: String,
    branch: String,
    moUsers: List<CpvUserDto>,
    current: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(current) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allot — $office") },
        text = {
            if (moUsers.isEmpty()) {
                Text("No Mail Overseer accounts found. Create users with the role MO in the web User Management, then allot offices here.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    Text("Choose the Mail Overseer(s) who may see and verify this office (Branch $branch).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        moUsers.forEach { u ->
                            val un = u.username.lowercase()
                            val on = selected.contains(un)
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) selected.remove(un) else selected.add(un) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = { if (on) selected.remove(un) else selected.add(un) })
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(u.display_name.ifBlank { u.username }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("@${u.username}" + if (!u.active) " · disabled" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected.toSet()) }, enabled = moUsers.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ═════════════════════════════════════════════════════════════
//  DETAIL SCREEN
// ═════════════════════════════════════════════════════════════

data class CpvDetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val meta: CpvBatchDto? = null,
    val accounts: List<CpvAccount> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    // Extra policies pulled from the master pool into this office (kept separate).
    val extras: List<CpvExtraDto> = emptyList(),
    // Master-pool search (dialog) state.
    val masterResults: List<PliMasterDto> = emptyList(),
    val masterSearching: Boolean = false
)

@HiltViewModel
class CpvDetailViewModel @Inject constructor(
    private val repo: CpvRepository,
    private val session: SessionManager
) : ViewModel() {
    private val _state = MutableStateFlow(CpvDetailState())
    val state = _state.asStateFlow()
    private var key: String? = null

    fun load(officeKey: String) {
        if (key == officeKey && !_state.value.loading && _state.value.error == null && _state.value.meta != null) return
        key = officeKey
        _state.value = CpvDetailState(loading = true)
        viewModelScope.launch {
            try {
                val b = repo.loadBatch(officeKey)
                val ex = runCatching { repo.listExtras(officeKey) }.getOrDefault(emptyList())
                _state.value = CpvDetailState(false, null, b.meta, b.accounts, extras = ex)
            } catch (e: Exception) {
                _state.value = CpvDetailState(false, e.message ?: "Could not load list.")
            }
        }
    }

    // ── Master pool search + per-office extra policies ──

    /** Search the division master pool (policy no / insured name). */
    fun searchMaster(term: String) {
        if (term.trim().length < 2) { _state.value = _state.value.copy(masterResults = emptyList(), masterSearching = false); return }
        _state.value = _state.value.copy(masterSearching = true)
        viewModelScope.launch {
            val res = runCatching { repo.searchMaster(term) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(masterResults = res, masterSearching = false)
        }
    }
    fun clearMasterResults() { _state.value = _state.value.copy(masterResults = emptyList(), masterSearching = false) }

    /** Add a master policy into this office as an extra — optionally already verified. */
    fun addExtra(m: PliMasterDto, verify: Boolean = false) {
        val k = key ?: return
        if (_state.value.accounts.any { it.record.acct == m.policy }) { _state.value = _state.value.copy(message = "Already in this office's list."); return }
        if (_state.value.extras.any { it.policy == m.policy }) { _state.value = _state.value.copy(message = "Already added."); return }
        val by = session.authorName() ?: "MO"
        val now = System.currentTimeMillis()
        val row = CpvExtraDto(
            office_key = k, policy = m.policy, name = m.name, address = m.address, doe = m.doe,
            sum_assured = m.sum_assured, premium = m.premium, paid_to = m.paid_to, months_paid = m.months_paid,
            verified = verify, verified_by = if (verify) by else null, verified_at_ms = if (verify) now else null,
            added_by = by, added_at_ms = now
        )
        _state.value = _state.value.copy(extras = _state.value.extras + row)
        viewModelScope.launch {
            try {
                if (verify) repo.saveExtra(row) else repo.addExtra(k, m, by)
                _state.value = _state.value.copy(message = if (verify) "Added & verified ${m.policy} ✓" else "Added ${m.policy} ✓")
            } catch (e: Exception) {
                _state.value = _state.value.copy(extras = _state.value.extras.filterNot { it.policy == m.policy }, message = "Add failed — ${e.message}")
            }
        }
    }

    /** Verify / unverify an extra policy. */
    fun toggleExtra(row: CpvExtraDto) {
        val by = session.authorName() ?: "MO"
        val next = !row.verified
        val updated = row.copy(verified = next, verified_by = if (next) by else null, verified_at_ms = if (next) System.currentTimeMillis() else null)
        _state.value = _state.value.copy(extras = _state.value.extras.map { if (it.policy == row.policy) updated else it })
        viewModelScope.launch {
            runCatching { repo.saveExtra(updated) }.onFailure {
                _state.value = _state.value.copy(extras = _state.value.extras.map { if (it.policy == row.policy) row else it }, message = "Save failed — ${it.message}")
            }
        }
    }

    /** Save a remark on an extra policy (optionally also mark it verified). */
    fun saveExtraRemark(row: CpvExtraDto, remark: String, verify: Boolean) {
        val by = session.authorName() ?: "MO"
        val v = verify || row.verified
        val updated = row.copy(
            remarks = remark.ifBlank { null }, verified = v,
            verified_by = if (v) by else row.verified_by, verified_at_ms = if (v) System.currentTimeMillis() else row.verified_at_ms
        )
        _state.value = _state.value.copy(extras = _state.value.extras.map { if (it.policy == row.policy) updated else it })
        viewModelScope.launch {
            runCatching { repo.saveExtra(updated) }.onFailure { _state.value = _state.value.copy(message = "Save failed — ${it.message}") }
        }
    }

    /** Remove an extra policy from this office. */
    fun removeExtra(row: CpvExtraDto) {
        val k = key ?: return
        _state.value = _state.value.copy(extras = _state.value.extras.filterNot { it.policy == row.policy })
        viewModelScope.launch {
            runCatching { repo.removeExtra(k, row.policy) }.onFailure {
                _state.value = _state.value.copy(extras = _state.value.extras + row, message = "Remove failed — ${it.message}")
            }.onSuccess { _state.value = _state.value.copy(message = "Removed ${row.policy}.") }
        }
    }

    /** Verify / unverify a set of accounts. remarks==null keeps each account's own remark. */
    fun verify(accts: List<CpvAccount>, verified: Boolean, remarks: String?) {
        val k = key ?: return
        if (accts.isEmpty()) return
        val by = session.authorName() ?: "MO"
        val now = System.currentTimeMillis()
        val acctSet = accts.map { it.record.acct }.toSet()
        val updated = _state.value.accounts.map {
            if (it.record.acct in acctSet)
                it.copy(verified = verified, remarks = remarks ?: it.remarks, verifiedBy = by, verifiedAtMs = now)
            else it
        }
        _state.value = _state.value.copy(accounts = updated, busy = true, message = null)
        viewModelScope.launch {
            try {
                repo.saveVerifications(k, accts, verified, remarks, by)
                _state.value = _state.value.copy(busy = false, message = if (verified) "Verified ✓" else "Updated ✓")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = "Save failed — ${e.message}")
                load(k)   // reconcile with the server
            }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

private enum class VerFilter { ALL, VERIFIED, UNVERIFIED }

@Composable
fun CpvDetailScreen(
    officeKey: String,
    title: String,
    onBack: () -> Unit,
    vm: CpvDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(officeKey) { vm.load(officeKey) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScopeCompat()

    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var verFilter by remember { mutableStateOf(VerFilter.ALL) }
    val selection = remember { mutableStateListOf<String>() }
    var bulkRemark by remember { mutableStateOf("") }
    var remarkDialogFor by remember { mutableStateOf<CpvAccount?>(null) }
    // Master pool: lookup dialog + remark dialog for an extra policy.
    var showMasterSearch by remember { mutableStateOf(false) }
    var masterQuery by remember { mutableStateOf("") }
    var extraRemarkFor by remember { mutableStateOf<CpvExtraDto?>(null) }

    // Reset transient UI when the list changes.
    LaunchedEffect(officeKey) { query = ""; statusFilter = null; verFilter = VerFilter.ALL; selection.clear(); bulkRemark = "" }

    val accounts = state.accounts
    val pli = isPliScheme(state.meta?.scheme)
    val statuses = remember(accounts) { accounts.mapNotNull { it.record.status.ifBlank { null } }.distinct().sorted() }
    val q = query.trim().lowercase()
    val filtered = remember(accounts, q, statusFilter, verFilter) {
        accounts.filter { a ->
            if (q.isNotEmpty()) {
                val hay = (a.record.acct + " " + a.record.name + " " + a.record.cif + " " + a.record.address).lowercase()
                if (!hay.contains(q)) return@filter false
            }
            if (statusFilter != null && a.record.status != statusFilter) return@filter false
            when (verFilter) {
                VerFilter.VERIFIED -> if (!a.verified) return@filter false
                VerFilter.UNVERIFIED -> if (a.verified) return@filter false
                VerFilter.ALL -> {}
            }
            true
        }
    }
    // Keep selection within the visible set.
    LaunchedEffect(filtered) {
        val visible = filtered.map { it.record.acct }.toSet()
        selection.retainAll(visible)
    }

    val verifiedCount = accounts.count { it.verified }
    // Extra (from-master) policies are counted into this office's totals & verified figures.
    val extras = if (pli) state.extras else emptyList()
    val extraVerified = extras.count { it.verified }
    val totalCount = accounts.size + extras.size
    val totalVerified = verifiedCount + extraVerified
    // The office search & verification filter also apply to the additional policies.
    val extrasShown = remember(extras, q, verFilter) {
        extras.filter { e ->
            if (q.isNotEmpty()) {
                val hay = (e.policy + " " + e.name + " " + e.address).lowercase()
                if (!hay.contains(q)) return@filter false
            }
            when (verFilter) { VerFilter.VERIFIED -> e.verified; VerFilter.UNVERIFIED -> !e.verified; VerFilter.ALL -> true }
        }
    }

    // one-shot messages
    val snackHost = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackHost.showSnackbar(it); vm.clearMessage() }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackHost) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(LocalHeaderBrush.current).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val meta = state.meta
                        // Include the attached extra policies so they're exported and counted too.
                        val extraRows = extras.map { it.toAccount(meta?.scheme ?: "") }
                        if (meta == null || (filtered.isEmpty() && extraRows.isEmpty())) return@IconButton
                        val snapshot = filtered.toList() + extraRows
                        scope.launch {
                            runCatching {
                                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    CpvReportPdf.generate(
                                        context,
                                        CpvReportMeta(meta.office_name, meta.scheme_label ?: meta.scheme, meta.sol_id, meta.branch_id, meta.scheme),
                                        snapshot
                                    )
                                }
                                CpvReportPdf.open(context, file)
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = "PDF report", tint = Color.White)
                    }
                }
            }
        },
        // Bulk action bar pinned to the bottom so Verify all / Unverify / Clear stay reachable
        // no matter how far the account list is scrolled.
        bottomBar = {
            CpvBulkBar(
                visible = selection.isNotEmpty(),
                count = selection.size,
                remark = bulkRemark,
                onRemarkChange = { bulkRemark = it },
                onVerify = {
                    val sel = accounts.filter { it.record.acct in selection }
                    vm.verify(sel, true, bulkRemark.trim().ifBlank { null })
                    selection.clear(); bulkRemark = ""
                },
                onUnverify = {
                    val sel = accounts.filter { it.record.acct in selection }
                    vm.verify(sel, false, null)
                    selection.clear()
                },
                onClear = { selection.clear(); bulkRemark = "" }
            )
        }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(pad).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("⚠️", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.error ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.load(officeKey) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text("Retry") }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(pad)
            ) {
                // Summary
                item {
                    val meta = state.meta
                    SectionCard(meta?.office_name ?: "") {
                        Text(
                            buildString {
                                meta?.sol_id?.takeIf { it.isNotBlank() }?.let { append("SOL $it · ") }
                                meta?.branch_id?.takeIf { it.isNotBlank() }?.let { append("Branch $it · ") }
                                append(meta?.scheme_label ?: meta?.scheme ?: "")
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("${if (pli) "Policies" else "Total"} $totalCount" + (if (extras.isNotEmpty()) " (+${extras.size})" else ""), Brand.BadgeDsBg, Brand.BadgeDsFg)
                            Pill("✓ Verified $totalVerified", Brand.ChipPaidBg, Brand.ChipPaidFg)
                            Pill("Unverified ${totalCount - totalVerified}", Brand.TpOthBg, Brand.TpOthFg)
                        }
                        if (pli) {
                            val sa = accounts.sumOf { it.record.sumAssured ?: 0.0 } + extras.sumOf { it.sum_assured ?: 0.0 }
                            Spacer(Modifier.height(6.dp))
                            Pill("Sum Assured ${inr(sa)}", Brand.TpOthBg, Brand.TpOthFg)
                        }
                    }
                }
                // Filters
                item {
                    KsdSearchField(query, { query = it }, if (pli) "Policy no / name / address" else "Account no / name / address")
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Verification filter chips
                        FilterChip(
                            selected = verFilter == VerFilter.ALL,
                            onClick = { verFilter = VerFilter.ALL },
                            label = { Text("All") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                        )
                        FilterChip(
                            selected = verFilter == VerFilter.VERIFIED,
                            onClick = { verFilter = VerFilter.VERIFIED },
                            label = { Text("Verified") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.Emerald, selectedLabelColor = Color.White)
                        )
                        FilterChip(
                            selected = verFilter == VerFilter.UNVERIFIED,
                            onClick = { verFilter = VerFilter.UNVERIFIED },
                            label = { Text("Unverified") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                        )
                        // Account status doesn't apply to insurance policies.
                        if (!pli) StatusDropdown(statuses, statusFilter) { statusFilter = it }
                    }
                }
                // Count + select-all
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Showing ${filtered.size} of ${accounts.size}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val visible = filtered.map { it.record.acct }
                            if (selection.containsAll(visible)) selection.clear()
                            else { selection.clear(); selection.addAll(visible) }
                        }) { Text(if (filtered.isNotEmpty() && selection.containsAll(filtered.map { it.record.acct })) "Clear all" else "Select all shown") }
                    }
                }
                // Master-pool lookup (PLI/RPLI only) — search every policy in the division,
                // even ones not in this office's own list, and add them here to verify.
                if (pli) {
                    item {
                        OutlinedButton(
                            onClick = { masterQuery = query; showMasterSearch = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🔎 Search all policies (master pool)") }
                    }
                }
                // (Bulk action bar is pinned to the bottom of the screen — see Scaffold bottomBar.)
                // Accounts
                if (filtered.isEmpty()) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState("🔍", if (pli) "No policy in this office matches the search." else "No accounts match the current filters.")
                            if (pli && query.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { masterQuery = query; showMasterSearch = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                                ) { Text("🔎 Search “${query.trim()}” in all policies") }
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.record.acct }) { a ->
                        if (pli) {
                            // Policies of one customer share the insured name (no CIF) → allow
                            // bulk-selecting all of a customer's policies, like the CIF group in bank lists.
                            val nameKey = a.record.name.trim().uppercase()
                            val groupable = nameKey.isNotBlank() && accounts.count { it.record.name.trim().uppercase() == nameKey } > 1
                            PliPolicyCard(
                                a = a,
                                selected = a.record.acct in selection,
                                groupable = groupable,
                                onToggleSelect = {
                                    if (a.record.acct in selection) selection.remove(a.record.acct) else selection.add(a.record.acct)
                                },
                                onSelectCustomer = {
                                    filtered.filter { it.record.name.trim().uppercase() == nameKey }
                                        .forEach { if (it.record.acct !in selection) selection.add(it.record.acct) }
                                },
                                onToggleVerify = { vm.verify(listOf(a), !a.verified, null) },
                                onEditRemark = { remarkDialogFor = a }
                            )
                        } else {
                            AccountCard(
                                a = a,
                                selected = a.record.acct in selection,
                                onToggleSelect = {
                                    if (a.record.acct in selection) selection.remove(a.record.acct) else selection.add(a.record.acct)
                                },
                                onToggleVerify = { vm.verify(listOf(a), !a.verified, null) },
                                onSelectCif = {
                                    val cif = a.record.cif
                                    if (cif.isNotBlank()) filtered.filter { it.record.cif == cif }.forEach { if (it.record.acct !in selection) selection.add(it.record.acct) }
                                },
                                onEditRemark = { remarkDialogFor = a }
                            )
                        }
                    }
                }
                // Additional (extra) policies pulled from the master pool — kept separate from
                // this office's official list and totals, but verifiable in the same way.
                if (pli && extras.isNotEmpty()) {
                    item {
                        val vc = extras.count { it.verified }
                        val isFiltered = extrasShown.size != extras.size
                        Column(Modifier.padding(top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Additional policies", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Pill("from master pool", Brand.BadgeDsBg, Brand.BadgeDsFg)
                            }
                            Text(
                                (if (isFiltered) "${extrasShown.size} shown of " else "") + "${extras.size} added · $vc verified · counted in this office's totals & exports",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (extrasShown.isEmpty()) {
                        item { EmptyState("🔍", "No additional policies match the current search.") }
                    } else {
                        items(extrasShown, key = { "extra:" + it.policy }) { ex ->
                            ExtraPolicyCard(
                                ex = ex,
                                onToggleVerify = { vm.toggleExtra(ex) },
                                onEditRemark = { extraRemarkFor = ex },
                                onRemove = { vm.removeExtra(ex) }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // Master pool search dialog
    if (showMasterSearch) {
        MasterSearchDialog(
            officeName = state.meta?.office_name ?: "",
            initialQuery = masterQuery,
            searching = state.masterSearching,
            results = state.masterResults,
            inOffice = remember(accounts) { accounts.map { it.record.acct }.toSet() },
            inExtra = remember(state.extras) { state.extras.map { it.policy }.toSet() },
            onQuery = { vm.searchMaster(it) },
            onAdd = { vm.addExtra(it, verify = false) },
            onAddVerify = { vm.addExtra(it, verify = true) },
            onDismiss = { showMasterSearch = false; vm.clearMasterResults() }
        )
    }

    // Remark dialog for an extra policy (Save remark, or Save & Verify)
    extraRemarkFor?.let { ex ->
        var text by remember(ex.policy) { mutableStateOf(ex.remarks ?: "") }
        AlertDialog(
            onDismissRequest = { extraRemarkFor = null },
            title = { Text("Remark — Policy ${ex.policy}") },
            text = {
                Column {
                    Text(ex.name.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Note any discrepancy…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveExtraRemark(ex, text.trim(), verify = true); extraRemarkFor = null }) { Text("Save & Verify") }
            },
            dismissButton = {
                TextButton(onClick = { vm.saveExtraRemark(ex, text.trim(), verify = false); extraRemarkFor = null }) { Text("Save remark") }
            }
        )
    }

    // Remark dialog
    remarkDialogFor?.let { acc ->
        var text by remember(acc.record.acct) { mutableStateOf(acc.remarks) }
        AlertDialog(
            onDismissRequest = { remarkDialogFor = null },
            title = { Text("Remark — A/c ${acc.record.acct}") },
            text = {
                Column {
                    Text(acc.record.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Note any discrepancy…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.verify(listOf(acc), true, text.trim())
                    remarkDialogFor = null
                }) { Text("Save & Verify") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.verify(listOf(acc), acc.verified, text.trim())
                    remarkDialogFor = null
                }) { Text("Save remark") }
            }
        )
    }
}

@Composable
private fun StatusDropdown(statuses: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(selected?.take(14) ?: "Status", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All statuses") }, onClick = { onSelect(null); open = false })
            statuses.forEach { s ->
                DropdownMenuItem(text = { Text(s) }, onClick = { onSelect(s); open = false })
            }
        }
    }
}

/** Strong accent colour for the status: green active · amber dormant · red frozen · grey other. */
private fun statusAccent(status: String?): Color {
    val t = (status ?: "").lowercase()
    return when {
        t.contains("active") -> Brand.Emerald
        t.contains("dorm") -> Brand.Amber
        t.contains("freez") || t.contains("froz") || t.contains("pledg") || t.contains("discont") -> Brand.Rose
        else -> Brand.Muted
    }
}

/**
 * The Cent-Percent-Verification account card — compact, clean and colourful. A status-coloured
 * accent rail and a single-select checkbox + CIF bulk-select sit on the left; the body shows
 * Account No · Amount, Name · Type, a status/CIF/date meta line and a compact remark, with the
 * Verify control anchored in the bottom-right corner. Selection & verify transitions animate.
 */
@Composable
private fun AccountCard(
    a: CpvAccount,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleVerify: () -> Unit,
    onSelectCif: () -> Unit,
    onEditRemark: () -> Unit
) {
    val (stBg, stFg) = statusColors(a.record.status)
    val accent = statusAccent(a.record.status)

    val container by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            a.verified -> Brand.Emerald.copy(alpha = 0.06f)
            else -> MaterialTheme.colorScheme.surface
        }, tween(240), label = "cardBg"
    )
    val borderColor by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary
            a.verified -> Brand.Emerald.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        }, tween(240), label = "cardBorder"
    )
    val elevation by animateDpAsState(if (selected) 5.dp else 1.5.dp, tween(200), label = "cardElev")

    val txn = fmtTxn(a.record.date, a.record.dateRaw)
    val meta = listOfNotNull(
        a.record.type.ifBlank { null },
        a.record.cif.ifBlank { null }?.let { "CIF $it" }
    ).joinToString(" · ")

    Surface(
        onClick = onToggleSelect,
        shape = RoundedCornerShape(16.dp),
        color = container,
        shadowElevation = elevation,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Status accent rail down the left edge.
            Box(Modifier.fillMaxHeight().width(4.dp).background(accent))

            // Left control rail: single-select checkbox + CIF bulk-select.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp)
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary)
                )
                if (a.record.cif.isNotBlank()) {
                    CifBulkButton(onClick = onSelectCif)
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Main content — tight vertical rhythm, no wasted space.
            Column(Modifier.weight(1f).padding(start = 6.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
                // Account No + Amount.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.record.acct,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        letterSpacing = 0.3.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inr(a.record.balance),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = Brand.Emerald,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(2.dp))

                // Name + Account Type. The full holder name takes priority; long CBS type
                // labels (e.g. "MINOR A/C OPERATED BY GUARDIAN") are shortened to a compact
                // tag so they never crowd or truncate the account holder's name.
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        a.record.name.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (a.record.type.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Pill(shortAcctType(a.record.type), Brand.BadgeDsBg, Brand.BadgeDsFg, Modifier.padding(top = 1.dp))
                    }
                }

                // Meta line (CIF · type already carried in the type pill → show CIF only) + verifier.
                val cifLine = buildString {
                    a.record.cif.ifBlank { null }?.let { append("CIF $it") }
                    if (a.verified && a.verifiedBy.isNotBlank()) {
                        if (isNotEmpty()) append("  ·  ")
                        append("✓ ${a.verifiedBy}")
                        a.verifiedAtMs?.let { append(" · ${fmtVerAt(it)}") }
                    }
                }
                if (cifLine.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        cifLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (a.verified) Brand.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Bottom row: status + date on the left, Verify anchored bottom-right.
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(a.record.status.ifBlank { "—" }, stBg, stFg, Modifier.weight(1f, fill = false))
                            if (txn.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    txn,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        // Compact remark with inline edit.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.remarks.ifBlank { "No remark" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (a.remarks.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Warn,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            TextButton(
                                onClick = onEditRemark,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text(
                                    if (a.remarks.isBlank()) "＋ Remark" else "Edit",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = a.verified,
                        transitionSpec = {
                            (scaleIn(spring()) + fadeIn(tween(180))) togetherWith
                                (scaleOut(tween(140)) + fadeOut(tween(120)))
                        },
                        label = "verifyToggle"
                    ) { isVerified ->
                        if (isVerified) {
                            AssistChip(
                                onClick = onToggleVerify,
                                label = { Text("Verified", fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Brand.ChipPaidBg, labelColor = Brand.ChipPaidFg, leadingIconContentColor = Brand.Emerald
                                )
                            )
                        } else {
                            Button(
                                onClick = onToggleVerify,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) { Text("Verify", fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * PLI / RPLI policy card — the insurance counterpart of [AccountCard]. Shows
 * Policy No · Sum Assured, Insured Name · Premium, the address, and a
 * Date-of-entry · Paid-upto · Months-paid meta line, with the same select /
 * verify / remark controls (verification is keyed on the policy number).
 */
@Composable
private fun PliPolicyCard(
    a: CpvAccount,
    selected: Boolean,
    groupable: Boolean,
    onToggleSelect: () -> Unit,
    onSelectCustomer: () -> Unit,
    onToggleVerify: () -> Unit,
    onEditRemark: () -> Unit
) {
    val accent = if (a.verified) Brand.Emerald else MaterialTheme.colorScheme.primary

    val container by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            a.verified -> Brand.Emerald.copy(alpha = 0.06f)
            else -> MaterialTheme.colorScheme.surface
        }, tween(240), label = "cardBg"
    )
    val borderColor by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary
            a.verified -> Brand.Emerald.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        }, tween(240), label = "cardBorder"
    )
    val elevation by animateDpAsState(if (selected) 5.dp else 1.5.dp, tween(200), label = "cardElev")

    val doe = fmtTxn(a.record.doeIso, a.record.doeRaw)
    val paid = monYear(a.record.paidIso, a.record.paidUpto, a.record.paidRaw)
    // Entry date + months-paid stay in the small meta line; Premium & Paid-upto get their
    // own prominent labelled row below so the MO can check them at a glance while verifying.
    val metaLine = buildString {
        if (doe.isNotBlank()) append("Entry $doe")
        a.record.monthsPaid?.let { if (isNotEmpty()) append("  ·  "); append("$it mo") }
    }

    Surface(
        onClick = onToggleSelect,
        shape = RoundedCornerShape(16.dp),
        color = container,
        shadowElevation = elevation,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.fillMaxHeight().width(4.dp).background(accent))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp)
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, checkmarkColor = MaterialTheme.colorScheme.onPrimary)
                )
                if (groupable) {
                    CustomerBulkButton(onClick = onSelectCustomer)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 6.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
                // Policy No + Sum Assured.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.record.policy.ifBlank { a.record.acct },
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        letterSpacing = 0.3.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inr(a.record.sumAssured),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = Brand.Emerald,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(2.dp))

                // Insured name.
                Text(
                    a.record.name.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )

                // Address.
                if (a.record.address.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        a.record.address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }

                // Premium & Paid-upto — the two figures the MO checks against the policy bond,
                // shown as clear labelled facts (Paid upto in MMM-YYYY, e.g. "Jun-2026").
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PliFact("Premium", a.record.premium?.let { inr(it) } ?: "—", Modifier.weight(1f))
                    PliFact("Paid upto", paid.ifBlank { "—" }, Modifier.weight(1f))
                }

                // Entry · Paid upto · Months + verifier.
                val line = buildString {
                    append(metaLine)
                    if (a.verified && a.verifiedBy.isNotBlank()) {
                        if (isNotEmpty()) append("  ·  ")
                        append("✓ ${a.verifiedBy}")
                        a.verifiedAtMs?.let { append(" · ${fmtVerAt(it)}") }
                    }
                }
                if (line.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (a.verified) Brand.Emerald else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Remark + Verify.
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            a.remarks.ifBlank { "No remark" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (a.remarks.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Warn,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TextButton(
                            onClick = onEditRemark,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                if (a.remarks.isBlank()) "＋ Remark" else "Edit",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = a.verified,
                        transitionSpec = {
                            (scaleIn(spring()) + fadeIn(tween(180))) togetherWith
                                (scaleOut(tween(140)) + fadeOut(tween(120)))
                        },
                        label = "verifyToggle"
                    ) { isVerified ->
                        if (isVerified) {
                            AssistChip(
                                onClick = onToggleVerify,
                                label = { Text("Verified", fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Brand.ChipPaidBg, labelColor = Brand.ChipPaidFg, leadingIconContentColor = Brand.Emerald
                                )
                            )
                        } else {
                            Button(
                                onClick = onToggleVerify,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) { Text("Verify", fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

/** Render an extra policy as a CpvAccount so it flows through the shared PDF/report code. */
private fun CpvExtraDto.toAccount(scheme: String): CpvAccount = CpvAccount(
    record = CpvRecordDto(
        acct = policy, policy = policy, name = name, address = address, type = scheme,
        doeRaw = doe, sumAssured = sum_assured, premium = premium,
        paidRaw = paid_to, monthsPaid = months_paid
    ),
    verified = verified,
    remarks = remarks ?: "",
    verifiedBy = verified_by ?: "",
    verifiedAtMs = verified_at_ms
)

/**
 * Master-pool lookup dialog. Searches every policy in the division (app_pli_master) by number
 * or insured name, and lets the officer add a match into the open office as an EXTRA to verify.
 */
@Composable
private fun MasterSearchDialog(
    officeName: String,
    initialQuery: String,
    searching: Boolean,
    results: List<PliMasterDto>,
    inOffice: Set<String>,
    inExtra: Set<String>,
    onQuery: (String) -> Unit,
    onAdd: (PliMasterDto) -> Unit,
    onAddVerify: (PliMasterDto) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf(initialQuery) }
    // Debounced search on every keystroke (and once when the dialog opens).
    LaunchedEffect(q) { kotlinx.coroutines.delay(220); onQuery(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔎 Search all policies") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Look up any policy in the division master pool and add it to “$officeName” to verify.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Policy number or insured name…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                when {
                    searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    }
                    q.trim().length < 2 -> Text(
                        "Type at least 2 characters. Leading zeros are ignored for policy numbers.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    results.isEmpty() -> Text("No policy in the master pool matches “${q.trim()}”.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results, key = { it.policy }) { m ->
                            MasterResultRow(
                                m = m,
                                state = when { inOffice.contains(m.policy) -> "in"; inExtra.contains(m.policy) -> "added"; else -> "add" },
                                onAdd = { onAdd(m) },
                                onAddVerify = { onAddVerify(m) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun MasterResultRow(m: PliMasterDto, state: String, onAdd: () -> Unit, onAddVerify: () -> Unit) {
    val doe = fmtTxn("", m.doe)
    val paid = monYear("", "", m.paid_to)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.policy, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.name.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (doe.isNotBlank()) append("Entry $doe · ")
                        append("SA ${inr(m.sum_assured)} · Prem ${inr(m.premium)}")
                        if (paid.isNotBlank()) append(" · Paid $paid")
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                "in" -> Pill("In list", Brand.ChipPaidBg, Brand.ChipPaidFg)
                "added" -> Pill("Added", Brand.BadgeDsBg, Brand.BadgeDsFg)
                else -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onAdd, shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text("＋ Add", maxLines = 1) }
                    Button(
                        onClick = onAddVerify, shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Emerald, contentColor = Color.White)
                    ) { Text("✓ Add & verify", maxLines = 1) }
                }
            }
        }
    }
}

/**
 * A card for an EXTRA policy (pulled from the master pool into this office). Mirrors the PLI
 * card's fields, with its own Verify / Remark / Remove actions — but no selection checkbox,
 * since extras are handled individually and stay out of the office's official totals.
 */
@Composable
private fun ExtraPolicyCard(ex: CpvExtraDto, onToggleVerify: () -> Unit, onEditRemark: () -> Unit, onRemove: () -> Unit) {
    val accent = if (ex.verified) Brand.Emerald else MaterialTheme.colorScheme.primary
    val doe = fmtTxn("", ex.doe)
    val paid = monYear("", "", ex.paid_to)
    val remark = ex.remarks ?: ""
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (ex.verified) Brand.Emerald.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (ex.verified) Brand.Emerald.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.fillMaxHeight().width(4.dp).background(accent))
            Column(Modifier.weight(1f).padding(start = 8.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ex.policy, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(inr(ex.sum_assured), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Brand.Emerald, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                Text(ex.name.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ex.address.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(ex.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PliFact("Premium", ex.premium?.let { inr(it) } ?: "—", Modifier.weight(1f))
                    PliFact("Paid upto", paid.ifBlank { "—" }, Modifier.weight(1f))
                }
                val meta = buildString {
                    if (doe.isNotBlank()) append("Entry $doe")
                    ex.months_paid?.let { if (isNotEmpty()) append("  ·  "); append("$it mo") }
                    if (ex.verified && !ex.verified_by.isNullOrBlank()) {
                        if (isNotEmpty()) append("  ·  "); append("✓ ${ex.verified_by}")
                        ex.verified_at_ms?.let { append(" · ${fmtVerAt(it)}") }
                    }
                }
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = if (ex.verified) Brand.Emerald else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(remark.ifBlank { "No remark" }, style = MaterialTheme.typography.labelSmall, color = if (remark.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        TextButton(onClick = onEditRemark, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                            Text(if (remark.isBlank()) "＋ Remark" else "Edit", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                            Text("Remove", style = MaterialTheme.typography.labelSmall, color = Brand.Warn)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (ex.verified) {
                        AssistChip(
                            onClick = onToggleVerify,
                            label = { Text("Verified", fontWeight = FontWeight.Bold) },
                            leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Brand.ChipPaidBg, labelColor = Brand.ChipPaidFg, leadingIconContentColor = Brand.Emerald)
                        )
                    } else {
                        Button(onClick = onToggleVerify, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Text("Verify", fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single labelled figure on a PLI card (Premium / Paid upto) — a small tinted box with a
 * muted caption over a bold value, so the number the MO must check stands out on the card.
 */
@Composable
private fun PliFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.BadgeDsBg.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Bulk action bar pinned to the bottom of the detail screen. Slides in whenever at least one
 * account is selected so Verify all / Unverify / Clear stay reachable regardless of scroll.
 */
@Composable
private fun CpvBulkBar(
    visible: Boolean,
    count: Int,
    remark: String,
    onRemarkChange: (String) -> Unit,
    onVerify: () -> Unit,
    onUnverify: () -> Unit,
    onClear: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(tween(180)),
        exit = slideOutVertically { it } + fadeOut(tween(140))
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$count selected", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = remark, onValueChange = onRemarkChange,
                    placeholder = { Text("Remark for selected (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onVerify,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Text("✓ Verify $count") }
                    OutlinedButton(onClick = onUnverify, modifier = Modifier.weight(1f)) { Text("Unverify") }
                }
            }
        }
    }
}

/** The "CIF" bulk-select control on the card's left rail — selects every account sharing this CIF. */
@Composable
private fun CifBulkButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Icon(Icons.Rounded.Groups, contentDescription = "Select all accounts with this CIF", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("CIF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** PLI counterpart of [CifBulkButton] — selects every policy sharing this insured name. */
@Composable
private fun CustomerBulkButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Icon(Icons.Rounded.Groups, contentDescription = "Select all policies of this customer", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("Cust", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** Small shim so we can launch coroutines from the detail screen without importing the API twice. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
