package com.karursdo.ui.ingest

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.ingest.ImportType
import com.karursdo.data.ingest.ParsedFile
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.data.repo.FileImportResult
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.TypePill
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Parsing : ImportUiState
    data class Pending(val files: List<ParsedFile>, val undetected: List<String>) : ImportUiState
    data object Saving : ImportUiState
    data class Done(val results: List<FileImportResult>) : ImportUiState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repo: DirectoryRepository,
    private val syncEngine: com.karursdo.data.sync.SyncEngine
) : ViewModel() {

    val state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val replaceWholeSet = MutableStateFlow(false)

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        state.value = ImportUiState.Parsing
        viewModelScope.launch {
            val parsed = mutableListOf<ParsedFile>()
            val undetected = mutableListOf<String>()
            for (uri in uris) {
                val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "file"
                runCatching { repo.parseFile(uri, name) }
                    .onSuccess { pf -> if (pf != null) parsed += pf else undetected += name }
                    .onFailure { undetected += "$name (${it.message})" }
            }
            state.value =
                if (parsed.isEmpty() && undetected.isEmpty()) ImportUiState.Idle
                else ImportUiState.Pending(parsed, undetected)
        }
    }

    fun save() {
        val pending = state.value as? ImportUiState.Pending ?: return
        state.value = ImportUiState.Saving
        viewModelScope.launch {
            val results = repo.applyImport(pending.files, replaceWholeSet.value)
            state.value = ImportUiState.Done(results)
            // Push the new snapshot(s) so every other user gets the update right away.
            runCatching { syncEngine.syncDatasets() }
        }
    }

    fun discard() { state.value = ImportUiState.Idle }
}

@Composable
fun ImportScreen(vm: ImportViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val replace by vm.replaceWholeSet.collectAsState()
    var confirmReplace by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.onFilesPicked(uris) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Text(
                "Update monthly data",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        item {
            SectionCard("📁 Drop payroll files here") {
                Text(
                    "Select DS (Departmental), GS (GDS), Outsiders, the Mobile Numbers roster, or the " +
                        "Temporary Arrangements export (.xls/.xlsx) — several at once. Type is " +
                        "auto-detected; new records are added and existing ones updated. The mobile " +
                        "roster is matched to staff by office & name. Uploading arrangements replaces " +
                        "the previous arrangements set.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        // Opening the system picker backgrounds us; keep the app unlocked so the
                        // picked file is delivered here instead of bouncing to the login screen.
                        com.karursdo.AppLock.suppressNextAutoLock = true
                        picker.launch(
                            arrayOf(
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Indigo),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose files…") }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = replace,
                    onCheckedChange = { vm.replaceWholeSet.value = it }
                )
                Text(
                    "Replace whole set for uploaded type(s) — removes staff not in this upload",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        when (val s = state) {
            is ImportUiState.Parsing, ImportUiState.Saving -> item {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (s == ImportUiState.Parsing) "Parsing files…" else "Saving…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            is ImportUiState.Pending -> {
                item {
                    SectionCard("Pending") {
                        s.files.forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                TypePill(
                                    when (f.type) {
                                        ImportType.DS -> "DS"; ImportType.GDS -> "GDS"
                                        ImportType.OUT -> "OUT"; ImportType.MOBILE -> "TEL"
                                        ImportType.ARRANGEMENT -> "ARR"
                                    }
                                )
                                Text(
                                    "${f.records.size} rows · ${f.fileName}" +
                                        if (f.skippedRows.isNotEmpty())
                                            " · ${f.skippedRows.size} malformed row(s): " +
                                                f.skippedRows.take(8).joinToString(", ")
                                        else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        s.undetected.forEach {
                            Text(
                                "⚠ Could not detect DS / GDS / Outsiders / Mobile type in \"$it\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Brand.Bad,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { if (replace) confirmReplace = true else vm.save() },
                                enabled = s.files.isNotEmpty(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Brand.Indigo)
                            ) { Text("💾 Save & update directory") }
                            OutlinedButton(
                                onClick = { vm.discard() },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Discard pending upload") }
                        }
                    }
                }
            }
            is ImportUiState.Done -> item {
                SectionCard("Result") {
                    s.results.forEach { r ->
                        Text(
                            buildString {
                                append("${r.fileName}: ")
                                when (r.type) {
                                    ImportType.MOBILE ->
                                        append("mapped ${r.matched} of ${r.totalRows} rows to staff (by office & name)")
                                    ImportType.ARRANGEMENT ->
                                        append("loaded ${r.added} arrangements (previous set replaced) · ${r.skipped} skipped")
                                    else ->
                                        append("${r.added} added · ${r.updated} updated · ${r.skipped} skipped")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Text("Saved.", color = Brand.Good, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.discard() }) { Text("Import more files") }
                }
            }
            else -> {}
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace whole set?") },
            text = {
                Text(
                    "This removes ALL existing staff of the uploaded type(s) that are not present " +
                        "in this upload. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; vm.save() }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text("Cancel") }
            }
        )
    }
}
