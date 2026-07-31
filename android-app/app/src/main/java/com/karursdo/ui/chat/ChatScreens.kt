package com.karursdo.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.karursdo.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karursdo.data.db.ChatDao
import com.karursdo.data.db.ChatMessageEntity
import com.karursdo.data.db.ChatReactionDao
import com.karursdo.data.db.ChatReactionEntity
import com.karursdo.data.db.ChatReadDao
import com.karursdo.data.db.PresenceDao
import com.karursdo.data.db.UserAccountDao
import com.karursdo.data.db.UserAccountEntity
import com.karursdo.data.repo.ROLE_ADMIN
import com.karursdo.data.repo.SessionManager
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.data.sync.ChatSyncStatus
import com.karursdo.data.sync.SyncEngine
import com.karursdo.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

private const val AUTO_SYNC_MS = 2000L
/** A user counts as "online" if their last heartbeat was within this window. */
private const val ONLINE_WINDOW_MS = 90_000L

private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private fun msgTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)
private fun dayOf(ms: Long): LocalDate =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DATE_FMT)
}

private val EMOJIS = listOf(
    "😀", "😄", "😊", "😍", "😂", "🤣", "😉", "😎", "🙂", "🙏",
    "👍", "👏", "🙌", "💪", "🤝", "👌", "❤️", "🔥", "🎉", "✅",
    "❌", "⚠️", "📌", "📝", "📞", "📮", "🏤", "⏰", "💯", "🚀"
)

/** WhatsApp-style quick reactions shown on long-press and used for the reaction chips. */
private val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatDao: ChatDao,
    private val session: SessionManager,
    private val syncEngine: SyncEngine,
    userAccountDao: UserAccountDao,
    presenceDao: PresenceDao,
    chatReadDao: ChatReadDao,
    private val chatReactionDao: ChatReactionDao,
    userRepo: UserDataRepository
) : ViewModel() {

    val currentUsername: String? get() = session.current.value?.username
    val isAdmin: Boolean get() = session.current.value?.role == ROLE_ADMIN
    val chatSync: StateFlow<ChatSyncStatus> = syncEngine.chatSync

    val messages: StateFlow<List<ChatMessageEntity>> =
        chatDao.messages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Login accounts shown as the chat group's members — the admin account is hidden. */
    val members: StateFlow<List<UserAccountEntity>> =
        userAccountDao.all().map { list -> list.filter { it.role != ROLE_ADMIN } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Usernames of admin accounts — excluded from the member list and from "Seen by …". */
    val adminUsernames: StateFlow<Set<String>> =
        userAccountDao.all().map { list -> list.filter { it.role == ROLE_ADMIN }.map { it.username }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** username -> admin-assigned designation (ASPOs / PA / MO-I / MO-II / Admin). */
    val designations: StateFlow<Map<String, String>> =
        userRepo.designationsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** username -> last heartbeat epoch millis. */
    val presence: StateFlow<Map<String, Long>> =
        presenceDao.all().map { list -> list.associate { it.username to it.lastSeenAt } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** username -> "read up to" epoch millis (for "Seen by …"). */
    val reads: StateFlow<Map<String, Long>> =
        chatReadDao.all().map { list -> list.associate { it.username to it.lastReadAt } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** messageId -> active reactions on it (empty-emoji tombstones dropped). */
    val reactions: StateFlow<Map<String, List<ChatReactionEntity>>> =
        chatReactionDao.all()
            .map { list -> list.filter { it.emoji.isNotEmpty() }.groupBy { it.messageId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Toggle my reaction on [messageId]: tapping the same emoji again removes it. */
    fun react(messageId: String, emoji: String) {
        val me = currentUsername ?: return
        viewModelScope.launch {
            val cur = chatReactionDao.byKey(messageId, me)
            val next = if (cur?.emoji == emoji) "" else emoji
            runCatching { syncEngine.reactToMessage(messageId, me, next) }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val user = session.current.value
        viewModelScope.launch {
            chatDao.upsert(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    username = user?.username ?: "unknown",
                    displayName = user?.displayName ?: user?.username ?: "Unknown",
                    body = body,
                    createdAt = System.currentTimeMillis(),
                    syncState = "P"
                )
            )
            runCatching { syncEngine.syncMessages() }   // push immediately
        }
    }

    /** Chat-tab poll: messages + read receipts + reactions + presence. */
    fun poll() = viewModelScope.launch {
        runCatching { syncEngine.syncMessages() }
        runCatching { syncEngine.syncChatReads() }
        runCatching { syncEngine.syncReactions() }
        runCatching { syncEngine.syncPresence() }
    }

    /** Record my presence heartbeat (online). */
    fun heartbeat() = viewModelScope.launch {
        currentUsername?.let { runCatching { syncEngine.heartbeat(it) } }
    }

    /** Advance my "read up to" watermark so others see I've seen up to [ts]. */
    fun markReadUpTo(ts: Long?) = viewModelScope.launch {
        val me = currentUsername ?: return@launch
        if (ts != null) runCatching { syncEngine.markChatRead(me, ts) }
    }

    /** Edit one of my own messages. */
    fun edit(id: String, newText: String) {
        val body = newText.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            chatDao.edit(id, body, System.currentTimeMillis())
            runCatching { syncEngine.syncMessages() }
        }
    }

    /** Delete a message. Callers enforce permission (own message, or admin). */
    fun delete(id: String) = viewModelScope.launch {
        chatDao.softDelete(id, System.currentTimeMillis())
        runCatching { syncEngine.syncMessages() }
    }
}

/** Chat-list item: a day separator or a message row. */
private sealed interface ChatItem {
    data class Day(val label: String) : ChatItem
    data class Msg(val m: ChatMessageEntity, val mine: Boolean, val showName: Boolean) : ChatItem
}

private fun buildChatItems(messages: List<ChatMessageEntity>, me: String?): List<ChatItem> {
    val out = ArrayList<ChatItem>(messages.size + 8)
    var lastDay: LocalDate? = null
    var lastSender: String? = null
    for (m in messages) {
        val day = dayOf(m.createdAt)
        if (day != lastDay) {
            out += ChatItem.Day(dayLabel(day))
            lastDay = day
            lastSender = null
        }
        val mine = m.username == me
        out += ChatItem.Msg(m, mine, showName = !mine && m.username != lastSender)
        lastSender = m.username
    }
    return out
}

/**
 * Chat palette. Bubbles are always light (pastel for other senders, mint-green for self) with dark
 * bubble text, so they read the same in light and dark mode; only the canvas and off-bubble text
 * (day separators, "Seen by …") follow the theme.
 */
private data class ChatPalette(
    val bg: Color,          // chat canvas
    val myBubble: Color,    // self bubble — light green
    val bubbleText: Color,  // dark text on the light bubbles
    val bubbleMeta: Color,  // muted time / "edited" on the light bubbles
    val tick: Color,        // blue "read" tick
    val outside: Color,     // muted text on the canvas (day sep + "Seen by …")
    val daySurface: Color,  // day-separator pill background
    val dayText: Color      // day-separator pill text
)

/** Light bubble tints assigned per sender so each participant is visually distinct (WhatsApp-style). */
private val OTHER_BUBBLES = listOf(
    Color(0xFFF5F6F6), // soft white
    Color(0xFFFFF3C4), // light amber
    Color(0xFFE3F2FD), // light blue
    Color(0xFFF3E5F5), // light purple
    Color(0xFFFCE4EC), // light pink
    Color(0xFFE8F5E9), // light green (distinct from self mint)
    Color(0xFFFFF8E1), // cream
    Color(0xFFE0F7FA)  // light cyan
)

/** Matching darker name accents so the sender name stays readable on the light bubble. */
private val NAME_COLORS = listOf(
    Color(0xFF00695C), Color(0xFF9C27B0), Color(0xFF1565C0), Color(0xFF6A1B9A),
    Color(0xFFC2185B), Color(0xFF2E7D32), Color(0xFF8D6E00), Color(0xFF00838F)
)

private fun paletteIndex(username: String, size: Int): Int =
    ((username.hashCode() % size) + size) % size

private fun bubbleColorFor(username: String): Color =
    OTHER_BUBBLES[paletteIndex(username, OTHER_BUBBLES.size)]

private fun nameColorFor(username: String): Color =
    NAME_COLORS[paletteIndex(username, NAME_COLORS.size)]

@Composable
private fun chatPalette(): ChatPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return ChatPalette(
        bg = if (dark) Color(0xFF0B141A) else Color(0xFFECE5DD),
        myBubble = Color(0xFFD9FDD3),
        bubbleText = Color(0xFF111B21),
        bubbleMeta = Color(0xFF667781),
        tick = Color(0xFF34B7F1),
        outside = if (dark) Color(0xFFCBD5E1) else Color(0xFF667781),
        daySurface = if (dark) Color(0xFF1E2A32) else Color.White,
        dayText = if (dark) Color(0xFFE9EDEF) else Color(0xFF667781)
    )
}

/** Designations of OTHER users who have read [m] (fixed order), for the "Seen by …" line. */
private fun seenByLabels(
    m: ChatMessageEntity,
    reads: Map<String, Long>,
    adminUsernames: Set<String>,
    labelOf: (String) -> String?
): List<String> =
    reads.entries
        .filter { (u, ts) -> u != m.username && u !in adminUsernames && ts >= m.createdAt }
        .mapNotNull { (u, _) -> labelOf(u) }
        .distinct()
        .sortedBy { UserDataRepository.DESIGNATIONS.indexOf(it).let { i -> if (i < 0) 99 else i } }

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsState()
    val syncStatus by vm.chatSync.collectAsState()
    val members by vm.members.collectAsState()
    val adminUsernames by vm.adminUsernames.collectAsState()
    val designations by vm.designations.collectAsState()
    val presence by vm.presence.collectAsState()
    val reads by vm.reads.collectAsState()
    val reactions by vm.reactions.collectAsState()
    val me = vm.currentUsername
    val items = remember(messages, me) { buildChatItems(messages, me) }
    val palette = chatPalette()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showEmoji by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var editingMsg by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var showGroupInfo by remember { mutableStateOf(false) }

    // Label for a username in read receipts: designation, else display name.
    val displayNameOf = remember(members) { members.associate { it.username to it.displayName } }
    val labelOf: (String) -> String? = { u ->
        if (u == me) "You" else designations[u] ?: displayNameOf[u]
    }

    val onlineCount = remember(presence, members) {
        val now = System.currentTimeMillis()
        members.count { (presence[it.username] ?: 0L) >= now - ONLINE_WINDOW_MS }
    }

    // Suppress chat notifications while this screen is on-screen.
    DisposableEffect(Unit) {
        com.karursdo.notify.Notifications.chatScreenVisible.set(true)
        onDispose { com.karursdo.notify.Notifications.chatScreenVisible.set(false) }
    }

    // Keep everyone updated: poll every 2 seconds, heartbeat ~every 24s while chat is open.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            vm.poll()
            if (tick % 12 == 0) vm.heartbeat()
            tick++
            delay(AUTO_SYNC_MS)
        }
    }
    // Mark everything up to the newest message as read (I'm looking at the chat).
    LaunchedEffect(messages.lastOrNull()?.createdAt) {
        vm.markReadUpTo(messages.lastOrNull()?.createdAt)
    }
    // Auto-scroll to the newest message.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    // Pad by the union of the keyboard (IME) and navigation-bar insets in a single pass so the
    // input bar hugs the keyboard when it's open (WhatsApp-style) and the nav bar when it's closed,
    // without double-counting the gap the way chaining navigationBarsPadding().imePadding() does.
    Column(
        Modifier.fillMaxSize().background(palette.bg)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        ChatTopBar(
            members = members.size,
            online = onlineCount,
            status = syncStatus,
            onOpenInfo = { showGroupInfo = true }
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Centered app-logo watermark behind the conversation.
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.12f,
                modifier = Modifier.align(Alignment.Center).size(240.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(items) { item ->
                    when (item) {
                        is ChatItem.Day -> DaySeparator(item.label, palette)
                        is ChatItem.Msg -> MessageBubble(
                            msg = item,
                            palette = palette,
                            seenBy = seenByLabels(item.m, reads, adminUsernames, labelOf),
                            reactions = reactions[item.m.id].orEmpty(),
                            me = me,
                            onReactionTap = { emoji -> vm.react(item.m.id, emoji) },
                            onLongPress = { actionMsg = item.m }
                        )
                    }
                }
            }
        }

        if (showEmoji) EmojiRow(onPick = { input += it })

        ChatInputBar(
            value = input,
            onValueChange = { input = it },
            onToggleEmoji = { showEmoji = !showEmoji },
            emojiOpen = showEmoji,
            onSend = {
                vm.send(input)
                input = ""
                showEmoji = false
            }
        )
    }

    if (showGroupInfo) {
        GroupInfoDialog(
            members = members,
            designations = designations,
            presence = presence,
            me = me,
            onDismiss = { showGroupInfo = false }
        )
    }

    actionMsg?.let { m ->
        MessageActionsDialog(
            mine = m.username == me,
            canDelete = m.username == me || vm.isAdmin,
            onReact = { emoji -> vm.react(m.id, emoji); actionMsg = null },
            onEdit = { editingMsg = m; actionMsg = null },
            onDelete = { vm.delete(m.id); actionMsg = null },
            onDismiss = { actionMsg = null }
        )
    }
    editingMsg?.let { m ->
        EditMessageDialog(
            initial = m.body,
            onDismiss = { editingMsg = null },
            onSave = { vm.edit(m.id, it); editingMsg = null }
        )
    }
}

@Composable
private fun MessageActionsDialog(
    mine: Boolean,
    canDelete: Boolean,
    onReact: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("React to message") },
        text = {
            Column {
                // WhatsApp-style quick reaction row — available for every message.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    REACTION_EMOJIS.forEach { e ->
                        Text(
                            e,
                            fontSize = 26.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onReact(e) }
                                .padding(6.dp)
                        )
                    }
                }
                if (mine || canDelete) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (mine) "Edit or delete this message?" else "Delete this message for everyone?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (mine) {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    Spacer(Modifier.width(4.dp))
                }
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFDC2626)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChatTopBar(
    members: Int,
    online: Int,
    status: ChatSyncStatus,
    onOpenInfo: () -> Unit
) {
    val (dotColor, subtitle) = when (status.state) {
        ChatSyncStatus.State.OK ->
            Color(0xFF4CAF50) to (
                "$members member" + (if (members == 1) "" else "s") +
                    (if (online > 0) " · $online online" else "")
                )
        ChatSyncStatus.State.OFFLINE ->
            Color.White.copy(alpha = 0.5f) to "Group chat · offline (not configured)"
        ChatSyncStatus.State.ERROR ->
            Color(0xFFFF5252) to ("Sync error" + (status.detail?.let { " ($it)" } ?: "") + " · retrying…")
    }
    Surface(color = Brand.PrimaryDark) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onOpenInfo)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text("👥", fontSize = 18.sp) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Karur SDO — Staff Group", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            Text("ⓘ", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
        }
    }
}

@Composable
private fun DaySeparator(label: String, palette: ChatPalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.daySurface,
            shadowElevation = 1.dp
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.dayText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatItem.Msg,
    palette: ChatPalette,
    seenBy: List<String>,
    reactions: List<ChatReactionEntity>,
    me: String?,
    onReactionTap: (String) -> Unit,
    onLongPress: () -> Unit
) {
    val mine = msg.mine
    val bubbleColor = if (mine) palette.myBubble else bubbleColorFor(msg.m.username)
    val textColor = palette.bubbleText
    val timeColor = palette.bubbleMeta
    val edited = msg.m.updatedAt > msg.m.createdAt
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = if (mine) 14.dp else 3.dp, topEnd = if (mine) 3.dp else 14.dp,
                bottomStart = 14.dp, bottomEnd = 14.dp
            ),
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(vertical = 1.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (msg.showName) {
                    Text(
                        msg.m.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = nameColorFor(msg.m.username),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(msg.m.body, color = textColor, fontSize = 15.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                ) {
                    if (edited) Text("edited", color = timeColor, fontSize = 9.sp, fontWeight = FontWeight.Light)
                    Text(msgTime(msg.m.createdAt), color = timeColor, fontSize = 10.sp)
                    if (mine) {
                        // WhatsApp-style receipts: clock while sending, grey double-tick once it
                        // reaches the server (delivered), blue double-tick once someone has read it.
                        val onServer = msg.m.syncState == "S"
                        val seen = seenBy.isNotEmpty()
                        Icon(
                            imageVector = if (onServer) Icons.Filled.DoneAll else Icons.Filled.Schedule,
                            contentDescription = when {
                                !onServer -> "Sending"
                                seen -> "Read"
                                else -> "Delivered"
                            },
                            tint = if (seen) palette.tick else timeColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        // Reaction chips (grouped by emoji, with counts); tapping toggles my own reaction.
        if (reactions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)
            ) {
                reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                    val mineReacted = me != null && list.any { it.username == me }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mineReacted) Color(0xFFD1E7FF) else Color(0xFFF0F2F5),
                        border = if (mineReacted) BorderStroke(1.dp, Color(0xFF34B7F1)) else null,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReactionTap(emoji) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(emoji, fontSize = 12.sp)
                            if (list.size > 1) {
                                Text(
                                    " ${list.size}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF111B21),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
        // WhatsApp-style acknowledgement line so users can confirm who has seen the message.
        if (seenBy.isNotEmpty()) {
            Text(
                "Seen by ${seenBy.joinToString(", ")}",
                color = palette.outside,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun GroupInfoDialog(
    members: List<UserAccountEntity>,
    designations: Map<String, String>,
    presence: Map<String, Long>,
    me: String?,
    onDismiss: () -> Unit
) {
    val now = System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Karur SDO — Staff Group") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${members.size} member" + (if (members.size == 1) "" else "s"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                members.sortedBy { it.displayName.lowercase() }.forEach { u ->
                    val isMe = u.username == me
                    val last = presence[u.username] ?: 0L
                    val online = last >= now - ONLINE_WINDOW_MS
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        val initials = u.displayName.trim().split(" ")
                            .filter { it.isNotEmpty() }.take(2)
                            .joinToString("") { it.first().uppercase() }.ifBlank { "U" }
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(Brand.PrimaryDark),
                            contentAlignment = Alignment.Center
                        ) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.displayName, fontWeight = FontWeight.SemiBold)
                                if (isMe) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = RoundedCornerShape(6.dp), color = Brand.PrimaryDark) {
                                        Text(
                                            "You", color = Color.White, fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                designations[u.username]?.takeIf { it.isNotBlank() } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                when {
                                    isMe -> "You"
                                    online -> "Online"
                                    last > 0L -> "last seen ${DateUtils.getRelativeTimeSpanString(last)}"
                                    else -> "offline"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (online) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
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
private fun EmojiRow(onPick: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EMOJIS.forEach { e ->
                Text(
                    e,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onPick(e) }
                        .padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onToggleEmoji: () -> Unit,
    emojiOpen: Boolean,
    onSend: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = onToggleEmoji) {
                Text(if (emojiOpen) "⌨️" else "😊", fontSize = 22.sp)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = CircleShape,
                color = Brand.PrimaryDark,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}
