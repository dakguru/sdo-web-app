package com.karursdo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.karursdo.data.db.MoProgrammeEntity
import com.karursdo.data.db.UserAccountDao
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.data.repo.MoBeatSeed
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.data.repo.UserDataRepository.Companion.PREF_MO_I_USER
import com.karursdo.data.repo.UserDataRepository.Companion.PREF_MO_II_USER
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.StatCard
import com.karursdo.ui.mo.moBeatShort
import com.karursdo.ui.mo.moOverdueCount
import androidx.lifecycle.viewModelScope
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

private val HOME_DATE_FMT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH)

@HiltViewModel
class HomeViewModel @Inject constructor(
    repo: DirectoryRepository,
    userRepo: UserDataRepository,
    userAccountDao: UserAccountDao,
    val themeController: com.karursdo.ui.theme.ThemeController
) : ViewModel() {
    val dsCount = repo.employeeDao.countByType("DS")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val gdsCount = repo.employeeDao.countByType("GDS")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val outCount = repo.outsiderDao.count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val officeCount = repo.employeeDao.officeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Mail Overseer OVERDUE-office counts, recomputed whenever visits change.
    val moIOverdue = repo.moBeatDao.byBeat(MoBeatSeed.MO_I).map { moOverdueCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val moIIOverdue = repo.moBeatDao.byBeat(MoBeatSeed.MO_II).map { moOverdueCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Today's Mail Overseer programme (both beats).
    val todayProgrammes = repo.programmesForDate(LocalDate.now().toString())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<MoProgrammeEntity>())

    // beat -> admin-tagged Mail Overseer display name, for the programme banner.
    val moNames: StateFlow<Map<String, String>> = combine(
        userRepo.globalPrefFlow(PREF_MO_I_USER),
        userRepo.globalPrefFlow(PREF_MO_II_USER),
        userAccountDao.all()
    ) { pI, pII, users ->
        val nameOf = users.associateBy({ it.username }, { it.displayName })
        buildMap {
            pI?.value?.ifBlank { null }?.let { u -> nameOf[u]?.let { put(MoBeatSeed.MO_I, it) } }
            pII?.value?.ifBlank { null }?.let { u -> nameOf[u]?.let { put(MoBeatSeed.MO_II, it) } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
}

@Composable
fun HomeScreen(
    onOpenImport: () -> Unit,
    onOpenDirectory: () -> Unit,
    onOpenArrangements: () -> Unit,
    onOpenMoBeat: (String) -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val ds by vm.dsCount.collectAsState()
    val gds by vm.gdsCount.collectAsState()
    val out by vm.outCount.collectAsState()
    val offices by vm.officeCount.collectAsState()
    val moIOverdue by vm.moIOverdue.collectAsState()
    val moIIOverdue by vm.moIIOverdue.collectAsState()
    val todayProgrammes by vm.todayProgrammes.collectAsState()
    val moNames by vm.moNames.collectAsState()
    val todayLabel = remember { LocalDate.now().format(HOME_DATE_FMT) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brand.HeaderGradient)
                    .padding(18.dp)
            ) {
                Text(
                    "Karur Sub Division",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Text(
                    "Staff directory · Office management · Mail Overseer beats",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Department of Posts · Karur Sub Division",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Total staff", ds + gds, Modifier.weight(1f))
                StatCard("Offices", offices, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Departmental", ds, Modifier.weight(1f))
                StatCard("GDS", gds, Modifier.weight(1f))
                StatCard("Outsiders", out, Modifier.weight(1f))
            }
        }

        // ---- Today's Mail Overseer programme (shown ABOVE the due-office counts) ----
        item {
            SectionCard("Today's MO Programme · $todayLabel") {
                if (todayProgrammes.isEmpty()) {
                    Text(
                        "No Mail Overseer programme set for today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    todayProgrammes.forEach { p ->
                        // Show the admin-tagged MO's name; fall back to "MO I/II" if untagged.
                        ProgrammeLine(label = moNames[p.beat] ?: moBeatShort(p.beat), p = p)
                    }
                }
            }
        }

        // ---- Mail Overseer due offices ----
        item {
            Text(
                "Mail Overseer — Overdue Offices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    "MO - I", moIOverdue, Modifier.weight(1f),
                    onClick = { onOpenMoBeat(MoBeatSeed.MO_I) }
                )
                StatCard(
                    "MO - II", moIIOverdue, Modifier.weight(1f),
                    onClick = { onOpenMoBeat(MoBeatSeed.MO_II) }
                )
            }
        }

        item {
            SectionCard("Quick access") {
                Button(
                    onClick = onOpenDirectory,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Indigo),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📇 Staff Directory — search & browse") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenArrangements,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🔁 Temporary Arrangements") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenImport,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.PrimaryDarker),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Update monthly data")
                }
            }
        }
        item {
            val mode by vm.themeController.mode.collectAsState()
            SectionCard("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.karursdo.ui.theme.ThemeMode.SYSTEM to "🌗 System",
                        com.karursdo.ui.theme.ThemeMode.LIGHT to "☀️ Light",
                        com.karursdo.ui.theme.ThemeMode.DARK to "🌙 Dark"
                    ).forEach { (m, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = mode == m,
                            onClick = { vm.themeController.setMode(m) },
                            label = { Text(label) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Indigo,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One line of today's programme: the MO's name (or beat) + the details + author. */
@Composable
private fun ProgrammeLine(label: String, p: MoProgrammeEntity) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp).widthIn(max = 120.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(p.details, style = MaterialTheme.typography.bodyMedium)
            p.author?.let {
                Text(
                    "updated by $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
