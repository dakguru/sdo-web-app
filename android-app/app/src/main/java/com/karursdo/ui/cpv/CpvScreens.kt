package com.karursdo.ui.cpv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PictureAsPdf
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
            Modifier.fillMaxWidth().background(Brand.HeaderGradient).padding(16.dp)
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
                        CircularProgressIndicator(color = Brand.Teal)
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
                        Button(onClick = { vm.load() }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal)) {
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
                    "${b.total_accounts} accounts",
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
            Column(Modifier.fillMaxWidth().background(Brand.HeaderGradient).padding(12.dp)) {
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
                                        CpvReportMeta(meta.office_name, meta.scheme_label ?: meta.scheme, meta.sol_id, meta.branch_id),
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
        }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand.Teal)
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
                Button(onClick = { vm.load(officeKey) }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal)) { Text("Retry") }
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
                            Pill("Total ${accounts.size}", Brand.BadgeDsBg, Brand.BadgeDsFg)
                            Pill("✓ Verified $verifiedCount", Brand.ChipPaidBg, Brand.ChipPaidFg)
                            Pill("Unverified ${accounts.size - verifiedCount}", Brand.TpOthBg, Brand.TpOthFg)
                        }
                    }
                }
                // Filters
                item {
                    KsdSearchField(query, { query = it }, "Account no / name / address")
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
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.Indigo, selectedLabelColor = Color.White)
                        )
                        FilterChip(
                            selected = verFilter == VerFilter.VERIFIED,
                            onClick = { verFilter = VerFilter.VERIFIED },
                            label = { Text("Verified") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.Teal, selectedLabelColor = Color.White)
                        )
                        FilterChip(
                            selected = verFilter == VerFilter.UNVERIFIED,
                            onClick = { verFilter = VerFilter.UNVERIFIED },
                            label = { Text("Unverified") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.Violet, selectedLabelColor = Color.White)
                        )
                        StatusDropdown(statuses, statusFilter) { statusFilter = it }
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
                // Bulk bar
                if (selection.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Brand.Teal.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${selection.size} selected", fontWeight = FontWeight.Bold, color = Brand.Teal)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = bulkRemark, onValueChange = { bulkRemark = it },
                                    placeholder = { Text("Remark for selected (optional)") },
                                    singleLine = false, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val sel = accounts.filter { it.record.acct in selection }
                                            vm.verify(sel, true, bulkRemark.trim().ifBlank { null })
                                            selection.clear(); bulkRemark = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal)
                                    ) { Text("✓ Verify") }
                                    OutlinedButton(onClick = {
                                        val sel = accounts.filter { it.record.acct in selection }
                                        vm.verify(sel, false, null)
                                        selection.clear()
                                    }) { Text("Unverify") }
                                    OutlinedButton(onClick = { selection.clear(); bulkRemark = "" }) { Text("Clear") }
                                }
                            }
                        }
                    }
                }
                // Accounts
                if (filtered.isEmpty()) {
                    item { EmptyState("🔍", "No accounts match the current filters.") }
                } else {
                    items(filtered, key = { it.record.acct }) { a ->
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

@Composable
private fun AccountCard(
    a: CpvAccount,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleVerify: () -> Unit,
    onSelectCif: () -> Unit,
    onEditRemark: () -> Unit
) {
    val (bg, fg) = statusColors(a.record.status)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Brand.Teal.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp)) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.record.acct, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(inr(a.record.balance), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    a.record.name.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (a.record.type.isNotBlank() || a.record.cif.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val meta = listOfNotNull(
                            a.record.type.ifBlank { null },
                            a.record.cif.ifBlank { null }?.let { "CIF $it" }
                        ).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                        if (a.record.cif.isNotBlank()) {
                            TextButton(onClick = onSelectCif, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text("⧉ CIF", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // Status + transaction date share the remaining space. The status pill
                    // shrinks and ellipsizes so a long "FREEZED/PLEDGED/DISCONTINUED" never
                    // crowds out the date or the Verify control on the right.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val txn = fmtTxn(a.record.date, a.record.dateRaw)
                        Pill(a.record.status.ifBlank { "—" }, bg, fg, Modifier.weight(1f, fill = false))
                        if (txn.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                txn,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // Verify toggle — kept at its natural width so the label is always legible.
                    if (a.verified) {
                        AssistChip(
                            onClick = onToggleVerify,
                            label = { Text("Verified") },
                            leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Brand.ChipPaidBg, labelColor = Brand.ChipPaidFg, leadingIconContentColor = Brand.ChipPaidFg)
                        )
                    } else {
                        Button(
                            onClick = onToggleVerify,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal)
                        ) { Text("Verify", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    }
                }
                // Remark row
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        a.remarks.ifBlank { "No remark" },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (a.remarks.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Amber,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onEditRemark, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(if (a.remarks.isBlank()) "＋ Remark" else "Edit", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (a.verified && (a.verifiedBy.isNotBlank() || a.verifiedAtMs != null)) {
                    Text(
                        "by ${a.verifiedBy}${a.verifiedAtMs?.let { " · ${fmtVerAt(it)}" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Small shim so we can launch coroutines from the detail screen without importing the API twice. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
