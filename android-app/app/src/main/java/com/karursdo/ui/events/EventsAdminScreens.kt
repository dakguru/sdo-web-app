package com.karursdo.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.EventEntity
import com.karursdo.data.repo.EventsRepository
import com.karursdo.data.sync.SyncEngine
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

private val ADMIN_DATE_FMT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH)

@HiltViewModel
class EventsAdminViewModel @Inject constructor(
    private val eventsRepo: EventsRepository,
    private val syncEngine: SyncEngine
) : ViewModel() {
    val events: StateFlow<List<EventEntity>> =
        eventsRepo.events().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(id: String?, date: String, title: String, important: Boolean) = viewModelScope.launch {
        eventsRepo.save(id, date, title, important)
        runCatching { syncEngine.syncEvents() }
    }

    fun delete(id: String) = viewModelScope.launch {
        eventsRepo.delete(id)
        runCatching { syncEngine.syncEvents() }
    }
}

@Composable
fun EventsAdminScreen(
    onBack: () -> Unit,
    vm: EventsAdminViewModel = hiltViewModel()
) {
    val events by vm.events.collectAsState()
    var editing by remember { mutableStateOf<EventEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    if (adding) {
        EventEditorDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { date, title, important -> vm.save(null, date, title, important); adding = false }
        )
    }
    editing?.let { ev ->
        EventEditorDialog(
            initial = ev,
            onDismiss = { editing = null },
            onSave = { date, title, important -> vm.save(ev.id, date, title, important); editing = null }
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← Back") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Events & announcements", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Shown on every user's dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { adding = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text("＋ Add") }
            }
        }
        item {
            SectionCard("Tips") {
                Text(
                    "• Add a date to show a live \"days left\" countdown.\n" +
                        "• Leave the date blank for a standing announcement / important message.\n" +
                        "• Mark as important to pin it to the top and highlight it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (events.isEmpty()) item { EmptyState("📢", "No events or announcements yet.") }
        items(events, key = { it.id }) { ev ->
            PressableCard(onClick = { editing = ev }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(if (ev.important) "⭐" else if (ev.date.isBlank()) "📌" else "🗓️")
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(ev.title, fontWeight = FontWeight.SemiBold, maxLines = 3)
                        Text(
                            ev.date.trim().takeIf { it.isNotBlank() }
                                ?.let { runCatching { LocalDate.parse(it).format(ADMIN_DATE_FMT) }.getOrDefault(it) }
                                ?: "Announcement (no date)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { vm.delete(ev.id) }) { Text("Delete") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Add/edit an event. The date is entered as YYYY-MM-DD (ISO) so the dashboard can count down;
 * leaving it blank makes a standing announcement.
 */
@Composable
private fun EventEditorDialog(
    initial: EventEntity?,
    onDismiss: () -> Unit,
    onSave: (date: String, title: String, important: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date.orEmpty()) }
    var important by remember { mutableStateOf(initial?.important ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun validDate(d: String): Boolean =
        d.isBlank() || runCatching { LocalDate.parse(d.trim()) }.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add event" else "Edit event") },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(160); error = null },
                    label = { Text("Event / message") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = date, onValueChange = { date = it.trim().take(10); error = null },
                    label = { Text("Date (YYYY-MM-DD, optional)") },
                    placeholder = { Text("e.g. 2026-09-15") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = important, onCheckedChange = { important = it })
                    Text("Mark as important (pin & highlight)")
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        title.isBlank() -> error = "Enter the event or message"
                        !validDate(date) -> error = "Use date format YYYY-MM-DD"
                        else -> onSave(date, title, important)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
