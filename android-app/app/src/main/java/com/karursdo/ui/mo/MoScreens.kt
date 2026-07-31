package com.karursdo.ui.mo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.MoBeatOfficeEntity
import com.karursdo.data.db.MoProgrammeEntity
import com.karursdo.data.db.OfficeMasterEntity
import com.karursdo.data.repo.MoBeatSeed
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.FieldRow
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.PressableCard
import com.karursdo.ui.components.SectionCard
import com.karursdo.report.MoReportPdf
import com.karursdo.report.MoReportRow
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

// ------------------------- Beat metadata & date logic -------------------------

/** Interval after which an office becomes due for the next visit. */
private const val VISIT_CYCLE_MONTHS = 3L

private val ISO_DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val STAMP_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH)
private fun fmtStamp(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(STAMP_FMT)

fun moBeatTitle(beat: String): String = when (beat) {
    MoBeatSeed.MO_I -> "Mail Overseer I"
    MoBeatSeed.MO_II -> "Mail Overseer II"
    else -> beat
}

fun moBeatShort(beat: String): String = when (beat) {
    MoBeatSeed.MO_I -> "MO I"
    MoBeatSeed.MO_II -> "MO II"
    else -> beat
}

private fun parseVisits(s: String): List<LocalDate> =
    s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .sorted()

private fun fmt(d: LocalDate): String = d.format(ISO_DISPLAY)

enum class DueStatus(val label: String, val bg: Color, val fg: Color) {
    OVERDUE("Overdue", Color(0xFFFEE2E2), Color(0xFF991B1B)),
    DUE_SOON("Due soon", Color(0xFFFEF3C7), Color(0xFF92400E)),
    ON_TRACK("On track", Brand.BadgeTelBg, Brand.BadgeTelFg),
    NO_VISITS("No visit yet", Brand.TpOthBg, Brand.TpOthFg)
}

/** Computed view of one beat office: last visit, next-due date and urgency. */
data class MoRow(
    val office: MoBeatOfficeEntity,
    val visits: List<LocalDate>,
    val last: LocalDate?,
    val nextDue: LocalDate?,
    val status: DueStatus
)

private fun buildRow(office: MoBeatOfficeEntity, today: LocalDate): MoRow {
    val visits = parseVisits(office.visits)
    val last = visits.maxOrNull()
    val nextDue = last?.plusMonths(VISIT_CYCLE_MONTHS)
    val status = when {
        last == null || nextDue == null -> DueStatus.NO_VISITS
        nextDue.isBefore(today) -> DueStatus.OVERDUE
        !nextDue.isAfter(today.plusDays(30)) -> DueStatus.DUE_SOON  // within a month of the 3-month mark
        else -> DueStatus.ON_TRACK
    }
    return MoRow(office, visits, last, nextDue, status)
}

/** Due offices first (overdue, then soonest); offices with no visit fall to the end. */
private fun sortByDue(rows: List<MoRow>): List<MoRow> =
    rows.sortedWith(
        compareBy(
            { it.status == DueStatus.NO_VISITS },
            { it.nextDue ?: LocalDate.MAX },
            { it.office.serialNo }
        )
    )

/** Number of offices in a beat that are due for a visit (overdue or due-soon). Shared with Home. */
fun moDueCount(offices: List<MoBeatOfficeEntity>, today: LocalDate = LocalDate.now()): Int =
    offices.map { buildRow(it, today) }
        .count { it.status == DueStatus.OVERDUE || it.status == DueStatus.DUE_SOON }

/** Number of offices in a beat that are strictly OVERDUE for a visit. Shared with Home. */
fun moOverdueCount(offices: List<MoBeatOfficeEntity>, today: LocalDate = LocalDate.now()): Int =
    offices.map { buildRow(it, today) }.count { it.status == DueStatus.OVERDUE }

// ------------------------------- ViewModel -------------------------------

@HiltViewModel
class MoViewModel @Inject constructor(
    val repo: DirectoryRepository,
    private val syncEngine: com.karursdo.data.sync.SyncEngine
) : ViewModel() {

    fun beat(beat: String) = repo.moBeatDao.byBeat(beat)
    fun office(id: Long) = repo.moBeatDao.flowById(id)

    fun addVisit(id: Long, date: LocalDate) = viewModelScope.launch {
        repo.addMoVisit(id, date.toString())
        runCatching { syncEngine.syncNow() }   // push this edit so other users see it
    }

    fun removeVisit(id: Long, date: LocalDate) = viewModelScope.launch {
        repo.removeMoVisit(id, date.toString())
        runCatching { syncEngine.syncNow() }
    }

    /** Pull the latest visit updates made by other users. */
    fun refresh() = viewModelScope.launch { runCatching { syncEngine.syncNow() } }

    // ---- Tour programmes ----
    /** Recent history + upcoming programmes for a beat (last ~2 months onward), all editable. */
    fun programmes(beat: String) =
        repo.programmeHistoryForBeat(beat, LocalDate.now().minusMonths(2).toString())

    fun addProgramme(beat: String, dates: List<LocalDate>, details: String) = viewModelScope.launch {
        repo.addProgramme(beat, dates.map { it.toString() }, details)
        runCatching { syncEngine.syncNow() }
    }

    fun editProgramme(id: String, date: LocalDate, details: String) = viewModelScope.launch {
        repo.editProgramme(id, date.toString(), details)
        runCatching { syncEngine.syncNow() }
    }

    fun deleteProgramme(id: String) = viewModelScope.launch {
        repo.deleteProgramme(id)
        runCatching { syncEngine.syncNow() }
    }

    /** Date-wise programmes for a beat within a period — used to build the PDF report. */
    suspend fun programmesInRange(beat: String, from: LocalDate, to: LocalDate) =
        repo.programmesInRange(beat, from.toString(), to.toString())
}

// ------------------------------- Landing (MO tab) -------------------------------

@Composable
fun MoLandingScreen(
    onOpenBeat: (String) -> Unit,
    vm: MoViewModel = hiltViewModel()
) {
    val today = remember { LocalDate.now() }
    val moI by vm.beat(MoBeatSeed.MO_I).collectAsState(initial = emptyList())
    val moII by vm.beat(MoBeatSeed.MO_II).collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { vm.refresh() }   // pull other users' latest visit updates

    // Branch-office picker options for BO-visit programmes: every BO under the sub-division,
    // overdue offices of BOTH beats first.
    val boOptions = remember(moI, moII, today) { buildBoOptions(moI, moII, today) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Column(Modifier.padding(top = 10.dp)) {
                Text("MO Beats", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Mail Overseer visit register · Karur Sub Division 2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            BeatCard(
                beat = MoBeatSeed.MO_I,
                rows = moI.map { buildRow(it, today) },
                bg = Brand.PrimaryDark,
                onClick = { onOpenBeat(MoBeatSeed.MO_I) }
            )
        }
        item {
            BeatCard(
                beat = MoBeatSeed.MO_II,
                rows = moII.map { buildRow(it, today) },
                bg = Brand.PrimaryDark,
                onClick = { onOpenBeat(MoBeatSeed.MO_II) }
            )
        }
        item {
            Text(
                "Mail Overseer Programme",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { ProgrammeSection(MoBeatSeed.MO_I, vm, boOptions) }
        item { ProgrammeSection(MoBeatSeed.MO_II, vm, boOptions) }
        item {
            Text(
                "Programme Reports",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { ReportCard(vm) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BeatCard(
    beat: String,
    rows: List<MoRow>,
    bg: Color,
    onClick: () -> Unit
) {
    val due = rows.count { it.status == DueStatus.OVERDUE || it.status == DueStatus.DUE_SOON }
    val overdue = rows.count { it.status == DueStatus.OVERDUE }
    PressableCard(onClick = onClick) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📮", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            moBeatTitle(beat),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "${rows.size} offices",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tagBg = Color.White.copy(alpha = 0.18f)
                    Pill(if (due > 0) "$due due for visit" else "All on track", tagBg, Color.White)
                    if (overdue > 0) Pill("$overdue overdue", Color(0x33FF0000), Color.White)
                }
                Spacer(Modifier.height(10.dp))
                Text("View offices →", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ------------------------------- Programme entry -------------------------------

/** The three kinds of Mail Overseer programme entry. */
enum class ProgKind(val label: String) { BO("BO Visit"), HQ("Head Quarters"), OTHER("Others") }

/** A branch office the overseer can pick for a BO-visit programme, with its current due status. */
data class BoOption(val name: String, val beat: String, val status: DueStatus)

/** Every BO under the sub-division (both beats), overdue offices first, de-duplicated by name. */
private fun buildBoOptions(
    moI: List<MoBeatOfficeEntity>,
    moII: List<MoBeatOfficeEntity>,
    today: LocalDate
): List<BoOption> {
    val rank = mapOf(
        DueStatus.OVERDUE to 0, DueStatus.DUE_SOON to 1,
        DueStatus.ON_TRACK to 2, DueStatus.NO_VISITS to 3
    )
    return (moI + moII)
        .map { o -> BoOption(o.officeName, o.beat, buildRow(o, today).status) }
        .sortedWith(compareBy({ rank[it.status] ?: 9 }, { it.name.lowercase() }))
        .distinctBy { it.name.lowercase() }
}

/** Compose the stored programme text from the structured entry. */
private fun composeProgramme(kind: ProgKind, place: String, details: String): String {
    val tail = details.trim().let { if (it.isNotEmpty()) " — $it" else "" }
    return when (kind) {
        ProgKind.BO -> "BO Visit: ${place.trim()}$tail"
        ProgKind.HQ -> "Head Quarters$tail"
        ProgKind.OTHER -> "${place.trim()}$tail"
    }
}

private data class ParsedProg(val kind: ProgKind, val place: String, val details: String)

/** Best-effort reverse of [composeProgramme], to pre-fill the edit dialog. */
private fun parseProgramme(stored: String): ParsedProg {
    val s = stored.trim()
    fun split(t: String): Pair<String, String> {
        val i = t.indexOf(" — ")
        return if (i >= 0) t.substring(0, i).trim() to t.substring(i + 3).trim() else t.trim() to ""
    }
    return when {
        s.startsWith("BO Visit:") -> split(s.removePrefix("BO Visit:").trim())
            .let { ParsedProg(ProgKind.BO, it.first, it.second) }
        s.startsWith("Head Quarters") ->
            ParsedProg(ProgKind.HQ, "", s.removePrefix("Head Quarters").trim().removePrefix("—").trim())
        else -> split(s).let { ParsedProg(ProgKind.OTHER, it.first, it.second) }
    }
}

/** Per-beat programme card: lists recent & upcoming entries and lets the overseer add or edit one. */
@Composable
private fun ProgrammeSection(beat: String, vm: MoViewModel, boOptions: List<BoOption>) {
    val programmes by vm.programmes(beat).collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MoProgrammeEntity?>(null) }

    if (showAdd) {
        ProgrammeDialog(
            beat = beat, existing = null, boOptions = boOptions,
            onDismiss = { showAdd = false },
            onSave = { dates, details -> vm.addProgramme(beat, dates, details); showAdd = false }
        )
    }
    editing?.let { e ->
        ProgrammeDialog(
            beat = beat, existing = e, boOptions = boOptions,
            onDismiss = { editing = null },
            onSave = { dates, details -> vm.editProgramme(e.id, dates.first(), details); editing = null }
        )
    }

    val today = remember { LocalDate.now() }
    // Only today's and future programmes are shown; past ones are tucked behind a toggle.
    val upcoming = remember(programmes) { programmes.filter { !LocalDate.parse(it.date).isBefore(today) } }
    val past = remember(programmes) {
        programmes.filter { LocalDate.parse(it.date).isBefore(today) }.sortedByDescending { it.date }
    }
    var showPast by remember { mutableStateOf(false) }

    SectionCard("${moBeatTitle(beat)} — Programme") {
        if (upcoming.isEmpty()) {
            Text(
                "No current or upcoming programme. Tap “Add programme” to plan a tour date.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            upcoming.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                ProgrammeRow(p, onEdit = { editing = p }, onRemove = { vm.deleteProgramme(p.id) })
            }
        }

        if (past.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            TextButton(
                onClick = { showPast = !showPast },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Text(
                    (if (showPast) "▾  Hide previous programmes" else "▸  Previous programmes") + "  (${past.size})",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (showPast) {
                past.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    ProgrammeRow(p, faded = true, onEdit = { editing = p }, onRemove = { vm.deleteProgramme(p.id) })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { showAdd = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Indigo),
            modifier = Modifier.fillMaxWidth()
        ) { Text("＋ Add programme") }
    }
}

@Composable
private fun ProgrammeRow(
    p: MoProgrammeEntity,
    faded: Boolean = false,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .then(if (faded) Modifier.alpha(0.72f) else Modifier)
    ) {
        Text("📅", modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(fmt(LocalDate.parse(p.date)), fontWeight = FontWeight.SemiBold)
            Text(p.details, style = MaterialTheme.typography.bodyMedium)
            p.author?.let {
                Text(
                    "— $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text("Edit")
            }
            TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text("Remove", color = Brand.Bad)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProgrammeDialog(
    beat: String,
    existing: MoProgrammeEntity?,
    boOptions: List<BoOption>,
    onDismiss: () -> Unit,
    onSave: (List<LocalDate>, String) -> Unit
) {
    val editing = existing != null
    val today = remember { LocalDate.now() }
    val parsed = remember(existing) { existing?.let { parseProgramme(it.details) } }

    var dates by remember { mutableStateOf(existing?.let { listOf(LocalDate.parse(it.date)) } ?: emptyList()) }
    var kind by remember { mutableStateOf(parsed?.kind ?: ProgKind.BO) }
    var boName by remember { mutableStateOf(if (parsed?.kind == ProgKind.BO) parsed.place else "") }
    var otherPlace by remember { mutableStateOf(if (parsed?.kind == ProgKind.OTHER) parsed.place else "") }
    var details by remember { mutableStateOf(parsed?.details ?: "") }
    var boQuery by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (dates.maxOrNull() ?: today)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        dates = if (editing) listOf(d) else (dates + d).distinct().sorted()
                    }
                    showPicker = false
                }) { Text(if (editing) "Set date" else "Add date") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state, title = null) }
    }

    val placeOk = when (kind) {
        ProgKind.BO -> boName.isNotBlank()
        ProgKind.HQ -> true
        ProgKind.OTHER -> otherPlace.isNotBlank()
    }
    val filteredBo = remember(boQuery, boOptions) {
        if (boQuery.isBlank()) boOptions else boOptions.filter { it.name.contains(boQuery, true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (editing) "Edit programme · " else "Add programme · ") + moBeatShort(beat)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                FieldLabel(if (editing) "Date" else "Date(s)")
                if (dates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dates.forEach { d ->
                            FilterChip(
                                selected = true,
                                onClick = { if (!editing) dates = dates - d },
                                label = { Text(fmt(d)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedButton(onClick = { showPicker = true }, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        when {
                            editing -> "Change date"
                            dates.isEmpty() -> "＋ Pick a date"
                            else -> "＋ Add another date"
                        }
                    )
                }

                Spacer(Modifier.height(14.dp))
                FieldLabel("Programme type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Indigo, selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                when (kind) {
                    ProgKind.BO -> {
                        FieldLabel("Branch office · overdue first")
                        OutlinedTextField(
                            value = boQuery,
                            onValueChange = { boQuery = it },
                            placeholder = { Text("Search branch office…") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 210.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .verticalScroll(rememberScrollState())
                                .padding(4.dp)
                        ) {
                            if (filteredBo.isEmpty()) {
                                Text(
                                    "No matching branch office.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            filteredBo.forEach { opt ->
                                BoOptionRow(opt, selected = opt.name == boName, onClick = { boName = opt.name })
                            }
                        }
                    }
                    ProgKind.OTHER -> {
                        FieldLabel("Please specify")
                        OutlinedTextField(
                            value = otherPlace,
                            onValueChange = { otherPlace = it.take(80) },
                            placeholder = { Text("e.g. RMS office, Court, Training…") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ProgKind.HQ -> {
                        Text(
                            "Programme at Head Quarters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                FieldLabel("Programme details")
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(500) },
                    placeholder = { Text("Short programme details") },
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val place = if (kind == ProgKind.BO) boName else otherPlace
                    onSave(dates, composeProgramme(kind, place, details))
                },
                enabled = dates.isNotEmpty() && placeOk
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun BoOptionRow(opt: BoOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brand.Indigo.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(
            if (selected) "◉" else "○",
            color = if (selected) Brand.Indigo else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(opt.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                moBeatShort(opt.beat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Pill(opt.status.label, opt.status.bg, opt.status.fg)
    }
}

// ------------------------------- Programme report (PDF) -------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportCard(vm: MoViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var beat by remember { mutableStateOf(MoBeatSeed.MO_I) }
    var from by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (picking != null) {
        val init = if (picking == "from") from else to
        val state = rememberDatePickerState(
            initialSelectedDateMillis = init.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (picking == "from") from = d else to = d
                    }
                    picking = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } }
        ) { DatePicker(state = state, title = null) }
    }

    SectionCard("Mail Overseer Programme Report") {
        Text(
            "Generate a professional, date-wise PDF of a Mail Overseer's tour programme for a period.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MoBeatSeed.MO_I to "MO I", MoBeatSeed.MO_II to "MO II").forEach { (b, lbl) ->
                FilterChip(
                    selected = beat == b,
                    onClick = { beat = b; lastFile = null },
                    label = { Text(lbl) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Indigo, selectedLabelColor = Color.White
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("From", from, Modifier.weight(1f)) { picking = "from" }
            DateField("To", to, Modifier.weight(1f)) { picking = "to" }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                error = null; generating = true; lastFile = null
                val lo = minOf(from, to); val hi = maxOf(from, to)
                scope.launch {
                    runCatching {
                        val progs = vm.programmesInRange(beat, lo, hi)
                        val rows = progs.map { MoReportRow(LocalDate.parse(it.date), it.details, it.author) }
                        withContext(kotlinx.coroutines.Dispatchers.Default) {
                            MoReportPdf.generate(context, moBeatTitle(beat), null, lo, hi, rows)
                        }
                    }.onSuccess {
                        lastFile = it; generating = false; MoReportPdf.open(context, it)
                    }.onFailure {
                        error = it.message ?: "Could not generate the report."; generating = false
                    }
                }
            },
            enabled = !generating,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.PrimaryDark),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text(if (generating) "Generating…" else "📄  Generate PDF report", fontWeight = FontWeight.SemiBold) }

        lastFile?.let { f ->
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { MoReportPdf.open(context, f) },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                ) { Text("Open") }
                OutlinedButton(
                    onClick = { MoReportPdf.share(context, f) },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                ) { Text("Share") }
            }
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Bad)
        }
    }
}

@Composable
private fun DateField(label: String, date: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(fmt(date), fontWeight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------- Beat office list -------------------------------

@Composable
fun MoBeatListScreen(
    beat: String,
    onBack: () -> Unit,
    onOpenOffice: (Long) -> Unit,
    vm: MoViewModel = hiltViewModel()
) {
    val today = remember { LocalDate.now() }
    val offices by vm.beat(beat).collectAsState(initial = emptyList())
    val rows = remember(offices) { sortByDue(offices.map { buildRow(it, today) }) }
    val dueCount = rows.count { it.status == DueStatus.OVERDUE || it.status == DueStatus.DUE_SOON }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← Back to MO Beats") } }
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(moBeatTitle(beat), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${rows.size} offices · $dueCount due for visit · sorted by next visit due",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (rows.isEmpty()) item { EmptyState("📮", "No offices in this beat yet.") }
        items(rows, key = { it.office.id }) { r ->
            PressableCard(onClick = { onOpenOffice(r.office.id) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${r.office.serialNo}. ${r.office.officeName}",
                            fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                        Text(
                            when {
                                r.last == null -> "No visit recorded"
                                r.nextDue != null -> "Last ${fmt(r.last)} · next due ${fmt(r.nextDue)}"
                                else -> "Last ${fmt(r.last)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    DueChip(r, today)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DueChip(r: MoRow, today: LocalDate) {
    val extra = when (r.status) {
        DueStatus.OVERDUE -> r.nextDue?.let { " ${ChronoUnit.DAYS.between(it, today)}d" } ?: ""
        DueStatus.DUE_SOON -> r.nextDue?.let {
            val d = ChronoUnit.DAYS.between(today, it)
            if (d <= 0) "" else " ${d}d"
        } ?: ""
        else -> ""
    }
    Pill(r.status.label + extra, r.status.bg, r.status.fg)
}

// ------------------------------- Office detail -------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoOfficeDetailScreen(
    moOfficeId: Long,
    onBack: () -> Unit,
    onOpenFullOffice: (String) -> Unit,
    vm: MoViewModel = hiltViewModel()
) {
    val today = remember { LocalDate.now() }
    LaunchedEffect(Unit) { vm.refresh() }   // pull any updates other users made
    val office by remember(moOfficeId) { vm.office(moOfficeId) }.collectAsState(initial = null)
    val o = office ?: return
    val row = buildRow(o, today)

    // Matched office-master record (loaded on demand).
    val master by produceState<OfficeMasterEntity?>(initialValue = null, o.matchedOfficeId) {
        value = o.matchedOfficeId?.let { vm.repo.officeMasterDao.byId(it) }
    }

    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.addVisit(o.id, d)
                    }
                    showPicker = false
                }) { Text("Add visit") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state, title = null) }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("← Back to ${moBeatTitle(o.beat)}") } }
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Brand.HeroGradient).padding(18.dp)
            ) {
                Column {
                    Text("🏤 ${o.officeName}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        "${moBeatTitle(o.beat)} · Beat serial ${o.serialNo}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(row.status.label, row.status.bg, row.status.fg)
                        val tagBg = Color.White.copy(alpha = 0.16f)
                        row.last?.let { Pill("Last ${fmt(it)}", tagBg, Color.White) }
                        row.nextDue?.let { Pill("Due ${fmt(it)}", tagBg, Color.White) }
                    }
                }
            }
        }

        // Visit history + add
        item {
            SectionCard("Visit history · ${row.visits.size}") {
                o.updatedAt?.let { at ->
                    Text(
                        "Last updated by ${o.updatedBy ?: "—"} · ${fmtStamp(at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (row.visits.isEmpty()) {
                    Text(
                        "No visits recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Newest first
                    row.visits.sortedDescending().forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            Text("📅", modifier = Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fmt(d), fontWeight = FontWeight.Medium)
                                if (d == row.last) Text(
                                    "Most recent visit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(onClick = { vm.removeVisit(o.id, d) }) {
                                Text("Remove", color = Brand.Bad)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showPicker = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Indigo),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("＋ Add visit date") }
                row.nextDue?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Next visit due by ${fmt(it)} (${VISIT_CYCLE_MONTHS} months after the last visit).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Office details (from the office master, when matched)
        if (master != null) {
            val m = master!!
            item {
                SectionCard("Office record") {
                    FieldRow("Office name", m.officeName)
                    FieldRow("Office ID", m.officeId)
                    FieldRow("Type", m.officeType)
                    FieldRow("Status", m.officeStatus)
                    FieldRow("Sub-division", m.subDivision)
                    FieldRow("Head Office", m.hoName)
                    FieldRow("Sub Office (S.O)", m.soName)
                }
            }
            item {
                SectionCard("Contact & Address") {
                    FieldRow("Contact", m.contact)
                    FieldRow("Email", m.email)
                    FieldRow("Pincode", m.pincode)
                    FieldRow(
                        "Address",
                        listOfNotNull(m.address1, m.address2, m.address3, m.village, m.taluk, m.city)
                            .filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { onOpenFullOffice(m.officeId) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open full office record & staff →") }
            }
        } else {
            item {
                SectionCard("Office record") {
                    Text(
                        "This beat office isn’t linked to a record in the office master " +
                            "(name not found among Karur Sub Division offices). Visit tracking still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
