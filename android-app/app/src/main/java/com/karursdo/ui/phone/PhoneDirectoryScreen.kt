package com.karursdo.ui.phone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.repo.DirectoryRepository
import com.karursdo.ui.components.EmptyState
import com.karursdo.ui.components.KsdSearchField
import com.karursdo.ui.components.Pill
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.initialsOf
import com.karursdo.ui.theme.Brand
import com.karursdo.ui.theme.LocalHeaderBrush
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Phone Directory screen — the mobile twin of the web /phone-directory module.
 * Everything is read from the local Room DB, so it works offline; the numbers
 * are whatever the last sync brought down, including other users' edits.
 */
@HiltViewModel
class PhoneDirectoryViewModel @Inject constructor(
    repo: DirectoryRepository
) : ViewModel() {
    val contacts = combine(
        repo.employeeDao.allFlow(),
        repo.outsiderDao.all(),
        repo.officeMasterDao.all(),
        repo.mobileDao.map()
    ) { emps, outs, offices, phones ->
        buildContacts(emps, outs, offices, phones.associate { it.employeeId to it.phone })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private fun catAccent(cat: String): Color = when (cat) {
    "DS" -> Brand.Indigo
    "GDS" -> Brand.Emerald
    else -> Brand.Amber
}
private fun catBadge(cat: String): Triple<String, Color, Color> = when (cat) {
    "DS" -> Triple("Dept", Brand.BadgeDsBg, Brand.BadgeDsFg)
    "GDS" -> Triple("GDS", Brand.BadgeGdsBg, Brand.BadgeGdsFg)
    else -> Triple("Outsider", Brand.BadgeOutBg, Brand.BadgeOutFg)
}

@Composable
fun PhoneDirectoryScreen(onBack: () -> Unit, vm: PhoneDirectoryViewModel = hiltViewModel()) {
    val all by vm.contacts.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var cat by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var onlyMobile by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    val list = remember(all, cat, query, onlyMobile) {
        all.asSequence()
            .filter { cat == "ALL" || it.cat == cat }
            .filter { !onlyMobile || it.mobile.isNotEmpty() }
            .filter { it.matches(query) }
            .sortedWith(
                compareBy(
                    { it.office.lowercase() },
                    { desigRank(it.short) },
                    { it.name.lowercase() }
                )
            )
            .toList()
    }
    // Group by office, preserving the sorted order (built outside LazyListScope).
    val groups = remember(list) { list.groupBy { it.office }.toList() }

    val counts = remember(all) {
        mapOf(
            "ALL" to all.size,
            "DS" to all.count { it.cat == "DS" },
            "GDS" to all.count { it.cat == "GDS" },
            "OUT" to all.count { it.cat == "OUT" }
        )
    }
    val withMobile = remember(all) { all.count { it.mobile.isNotEmpty() } }

    if (showExport) {
        ExportDialog(all = all, onDismiss = { showExport = false })
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Column(Modifier.fillMaxWidth().background(LocalHeaderBrush.current).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back", tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Text("📞", fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Phone Directory",
                            style = MaterialTheme.typography.titleLarge, color = Color.White
                        )
                        Text(
                            "$withMobile of ${all.size} staff have a mobile number",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                KsdSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Office, name, mobile no., designation, pincode"
                )
            }
        }

        item {
            // The four labels with their counts overflow a phone's width, so the
            // category row scrolls sideways rather than wrapping or clipping.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                listOf(
                    "ALL" to "All", "DS" to "Departmental", "GDS" to "GDS", "OUT" to "Outsiders"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = cat == key,
                        onClick = { cat = key },
                        label = { Text("$label  ${counts[key] ?: 0}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                FilterChip(
                    selected = onlyMobile,
                    onClick = { onlyMobile = !onlyMobile },
                    label = { Text("With mobile only") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                AssistChip(
                    onClick = { showExport = true },
                    label = { Text("📥 Save to phone") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Brand.Emerald.copy(alpha = 0.12f),
                        labelColor = Brand.Emerald
                    )
                )
            }
        }

        item {
            Text(
                "${list.size} contact${if (list.size == 1) "" else "s"}" +
                    list.count { it.mobile.isEmpty() }.let {
                        if (it > 0) " · $it without a number" else ""
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }

        if (groups.isEmpty()) {
            item {
                EmptyState(
                    "🔍",
                    if (all.isEmpty()) "No staff on this device yet — run Update data to sync."
                    else "No contacts match. Try another office, category or search term."
                )
            }
        }

        groups.forEach { (office, rows) ->
            val isOpen = office !in collapsed
            item(key = "head-$office") {
                OfficeHeader(
                    office = office,
                    boso = rows.first().boso,
                    pincode = rows.first().pincode,
                    count = rows.size,
                    open = isOpen,
                    onToggle = {
                        collapsed = if (isOpen) collapsed + office else collapsed - office
                    }
                )
            }
            if (isOpen) {
                items(rows, key = { "${it.cat}:${it.id}" }) { c ->
                    ContactCard(
                        c = c,
                        onCall = {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e164(c.mobile)}")))
                        },
                        onSms = {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${e164(c.mobile)}")))
                        },
                        onWhatsApp = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/91${c.mobile}"))
                                )
                            }
                        },
                        onCopy = { clipboard.setText(AnnotatedString(e164(c.mobile))) },
                        onSaveOne = {
                            val f = ContactFiles.write(
                                context,
                                contactName(c).replace(Regex("""[^A-Za-z0-9 ._-]+"""), "-").trim()
                                    .ifEmpty { "contact" } + ".vcf",
                                buildVcf(listOf(c), "Karur SDO - ${ContactCat.of(c.cat).label}")
                            )
                            ContactFiles.open(context, f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OfficeHeader(
    office: String,
    boso: String,
    pincode: String,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp)
        ) {
            Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            // The office name takes all the slack and ellipsises, which pushes the
            // badge, pincode and count to the right edge. Only one child may be
            // weighted here — a second one would halve the name's width.
            Text(
                office,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (boso.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                val (bg, fg) = when (boso) {
                    "BO" -> Brand.TpBpoBg to Brand.TpBpoFg
                    "SO" -> Brand.TpSpoBg to Brand.TpSpoFg
                    "HO" -> Brand.TpHpoBg to Brand.TpHpoFg
                    else -> Brand.TpOthBg to Brand.TpOthFg
                }
                Pill(boso, bg, fg)
            }
            if (pincode.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    pincode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ContactCard(
    c: Contact,
    onCall: () -> Unit,
    onSms: () -> Unit,
    onWhatsApp: () -> Unit,
    onCopy: () -> Unit,
    onSaveOne: () -> Unit
) {
    val accent = catAccent(c.cat)
    val (badge, badgeBg, badgeFg) = catBadge(c.cat)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f))))
                    ) {
                        Text(
                            initialsOf(c.name), color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(c.short, badgeBg, badgeFg)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                c.designation.ifEmpty { badge },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (c.mobile.isEmpty()) {
                    Text(
                        "⚠ No mobile number on record",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                prettyPhone(c.mobile),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TinyAction("📞", "Call", onCall)
                            TinyAction("💬", "WhatsApp", onWhatsApp)
                            TinyAction("✉", "SMS", onSms)
                            TinyAction("📋", "Copy", onCopy)
                            TinyAction("📥", "Save contact", onSaveOne)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Saves as  ${contactName(c)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TinyAction(emoji: String, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick)
    ) { Text(emoji, fontSize = 15.sp) }
}

/**
 * Export sheet: three separate files — Departmental, GDS, Outsiders — each as a
 * vCard (imports straight into the phonebook) or a Google Contacts CSV (import
 * at contacts.google.com so the numbers sync to every device on that account).
 */
@Composable
private fun ExportDialog(all: List<Contact>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save contacts to your phone") },
        text = {
            // Three category cards plus the guidance overflow a small phone,
            // and AlertDialog's text slot does not scroll on its own.
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Each contact is saved as Name · Designation · Office, with single-letter " +
                        "initials dropped. Only staff with a mobile number are exported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ContactCat.entries.forEach { c ->
                    val rows = all.filter { it.cat == c.key && it.mobile.isNotEmpty() }
                    val total = all.count { it.cat == c.key }
                    SectionCard("${c.emoji}  ${c.label}  ·  ${rows.size} / $total") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = rows.isNotEmpty(),
                                onClick = {
                                    val f = ContactFiles.write(
                                        context, ContactFiles.fileName(c.fileTag, "vcf"),
                                        buildVcf(rows, "Karur SDO - ${c.label}")
                                    )
                                    ContactFiles.open(context, f)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("📇 Import", maxLines = 1) }
                            OutlinedButton(
                                enabled = rows.isNotEmpty(),
                                onClick = {
                                    val f = ContactFiles.write(
                                        context, ContactFiles.fileName(c.fileTag, "csv"),
                                        buildGoogleCsv(rows, "Karur SDO - ${c.label}")
                                    )
                                    ContactFiles.share(context, f)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("✉ CSV", maxLines = 1) }
                        }
                    }
                }
                Text(
                    "Import opens the phone's Contacts app with that set ready to add. " +
                        "CSV shares the Google Contacts file — mail it to yourself, then import it " +
                        "at contacts.google.com so it syncs to every device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
