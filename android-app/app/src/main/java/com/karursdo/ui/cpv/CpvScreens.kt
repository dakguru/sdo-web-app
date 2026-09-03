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
import com.karursdo.data.repo.CpvBatchDto
import com.karursdo.data.repo.CpvRepository
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

data class CpvListState(
    val loading: Boolean = true,
    val error: String? = null,
    val batches: List<CpvBatchDto> = emptyList()
)

@HiltViewModel
class CpvListViewModel @Inject constructor(
    private val repo: CpvRepository
) : ViewModel() {
    private val _state = MutableStateFlow(CpvListState())
    val state = _state.asStateFlow()
    val enabled: Boolean get() = repo.enabled

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val b = repo.listBatches()
                _state.value = CpvListState(false, null, b)
            } catch (e: Exception) {
                _state.value = CpvListState(false, e.message ?: "Could not load lists.", emptyList())
            }
        }
    }
}

@Composable
fun CpvListScreen(
    onBack: () -> Unit,
    onOpenBatch: (officeKey: String, title: String) -> Unit,
    vm: CpvListViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // Header band
        Column(
            Modifier.fillMaxWidth().background(LocalHeaderBrush.current).padding(16.dp)
        ) {
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

        val q = query.trim().lowercase()
        val filtered = remember(state.batches, q) {
            if (q.isEmpty()) state.batches
            else state.batches.filter {
                (it.office_name + " " + (it.sol_id ?: "") + " " + (it.branch_id ?: "") + " " + it.scheme)
                    .lowercase().contains(q)
            }
        }
        // Group by office (branch|office), preserving office order.
        val groups = remember(filtered) {
            filtered.groupBy { (it.branch_id ?: "") + "|" + it.office_name }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                KsdSearchField(query, { query = it }, "Search office, SOL ID, branch…")
            }
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
                        Text(
                            state.error ?: "Could not load.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.load() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Text("Retry")
                        }
                    }
                }
                filtered.isEmpty() -> item {
                    EmptyState("🗂️", if (state.batches.isEmpty()) "No account lists stored yet. Upload a Last Balance Report from the web app to begin." else "No lists match your search.")
                }
                else -> groups.forEach { (_, items) ->
                    val g = items.first()
                    val totAcc = items.sumOf { it.total_accounts }
                    item {
                        SectionCard(g.office_name) {
                            Text(
                                buildString {
                                    g.sol_id?.takeIf { it.isNotBlank() }?.let { append("SOL $it · ") }
                                    g.branch_id?.takeIf { it.isNotBlank() }?.let { append("Branch $it · ") }
                                    append("$totAcc accounts")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            items.sortedBy { it.scheme }.forEach { b ->
                                SchemeRow(b) { onOpenBatch(b.office_key, "${b.office_name} · ${b.scheme}") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SchemeRow(b: CpvBatchDto, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Pill(b.scheme, Brand.BadgeDsBg, Brand.BadgeDsFg)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    b.scheme_label ?: b.scheme,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${b.total_accounts} ${if (isPliScheme(b.scheme)) "policies" else "accounts"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
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
    val message: String? = null
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
                _state.value = CpvDetailState(false, null, b.meta, b.accounts)
            } catch (e: Exception) {
                _state.value = CpvDetailState(false, e.message ?: "Could not load list.")
            }
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
                        if (meta == null || filtered.isEmpty()) return@IconButton
                        val snapshot = filtered.toList()
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
                            Pill("${if (pli) "Policies" else "Total"} ${accounts.size}", Brand.BadgeDsBg, Brand.BadgeDsFg)
                            Pill("✓ Verified $verifiedCount", Brand.ChipPaidBg, Brand.ChipPaidFg)
                            Pill("Unverified ${accounts.size - verifiedCount}", Brand.TpOthBg, Brand.TpOthFg)
                        }
                        if (pli) {
                            val sa = accounts.sumOf { it.record.sumAssured ?: 0.0 }
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
                // (Bulk action bar is pinned to the bottom of the screen — see Scaffold bottomBar.)
                // Accounts
                if (filtered.isEmpty()) {
                    item { EmptyState("🔍", "No accounts match the current filters.") }
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
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
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
