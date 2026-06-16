package com.remote.claude.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remote.claude.AppViewModel
import com.remote.claude.net.ImportableItem
import com.remote.claude.net.SessionSummary
import com.remote.claude.ui.theme.C

private fun zhStatus(s: String): String = when (s) {
    "thinking" -> "Thinking"
    "tool" -> "Running"
    "waiting_permission" -> "Awaiting approval"
    else -> "Idle"
}

private fun needsAttention(s: String): Boolean =
    s == "waiting_permission" || s == "thinking" || s == "tool"

@Composable
fun SessionListScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var byLocation by remember { mutableStateOf(false) } // false=Time, true=Location
    var pinned by remember { mutableStateOf(setOf<String>()) } // local pins (not yet persisted)
    var showNew by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val all = state.order.mapNotNull { state.sessions[it] }
        .filter { !it.archived }
        .filter {
            val q = query.trim()
            q.isEmpty() || it.title.contains(q, true) || it.preview.contains(q, true) || it.cwd.contains(q, true)
        }

    BlobBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // top title row
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sessions", color = C.ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text(
                    "⤓ Import",
                    color = C.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.listImportable(); showImport = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.connected) C.ok else C.err))
                Spacer(Modifier.width(6.dp))
                Text(if (state.connected) "Connected" else "Connecting…", color = C.subtle, fontSize = 12.sp)
            }

            // search + sort toggle (glass pill)
            Glass(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(46.dp),
                radius = 23.dp,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⌕", color = C.dim, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search title / text / path…", color = C.dim, fontSize = 14.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = C.ink, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SortToggle(byLocation) { byLocation = it }
                }
            }

            Spacer(Modifier.height(6.dp))

            // list body
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (!byLocation) {
                    val attn = all.filter { needsAttention(it.status) }
                    val recent = all.filter { !needsAttention(it.status) }
                        .sortedWith(compareByDescending<SessionSummary> { it.id in pinned }.thenByDescending { it.updatedAt })
                    if (attn.isNotEmpty()) {
                        item { SectionHeader("Needs you", C.accent, attn.size) }
                        items(attn, key = { it.id }) { SessionRow(it, it.id in pinned, vm, { togglePin(pinned, it.id) { p -> pinned = p } }) }
                    }
                    item { SectionHeader("Recent", C.subtle, null) }
                    items(recent, key = { it.id }) { SessionRow(it, it.id in pinned, vm, { togglePin(pinned, it.id) { p -> pinned = p } }) }
                } else {
                    // group by location (cwd), sorted by time within each group
                    val groups = all.groupBy { it.cwd }.toList().sortedByDescending { (_, v) -> v.maxOf { it.updatedAt } }
                    groups.forEach { (cwd, list) ->
                        item(key = "grp_$cwd") { GroupHeader(cwd, list.size) }
                        items(list.sortedByDescending { it.updatedAt }, key = { it.id }) {
                            SessionRow(it, it.id in pinned, vm, { togglePin(pinned, it.id) { p -> pinned = p } })
                        }
                    }
                }
                if (all.isEmpty()) item { Text("No sessions yet. Tap + to create one.", color = C.dim, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(40.dp)) }
                item { Spacer(Modifier.height(90.dp)) } // leave room for the FAB
            }
        }

        // coral-gradient FAB in the bottom-right corner
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)
                .clip(RoundedCornerShape(28.dp)).background(coralBrush())
                .clickable { showNew = true }
                .padding(start = 16.dp, end = 19.dp, top = 13.dp, bottom = 13.dp),
        ) {
            Text("＋ New session", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (showNew) NewSessionDialog(onDismiss = { showNew = false }, onStart = { vm.newSession(it); showNew = false })
    if (showImport) ImportDialog(
        items = state.importable,
        onRefresh = { vm.listImportable() },
        onPick = { vm.importSession(it.claudeSessionId, it.cwd); showImport = false },
        onDismiss = { showImport = false },
    )
}

@Composable
private fun ImportDialog(
    items: List<ImportableItem>,
    onRefresh: () -> Unit,
    onPick: (ImportableItem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = C.accent) } },
        dismissButton = { TextButton(onClick = onRefresh) { Text("Refresh", color = C.dim) } },
        title = { Text("Import a session", color = C.ink) },
        text = {
            if (items.isEmpty()) {
                Text("Nothing to import (or still loading — tap refresh)", color = C.dim, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(items, key = { it.claudeSessionId }) { it ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(it) }.padding(vertical = 9.dp),
                        ) {
                            Text(it.title.ifBlank { "(untitled)" }, color = C.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${it.cwd} · ${relativeTime(it.updatedAt)}",
                                color = C.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(C.glassBorder).padding(top = 6.dp))
                        }
                    }
                }
            }
        },
        containerColor = C.bg,
    )
}

private fun togglePin(cur: Set<String>, id: String, set: (Set<String>) -> Unit) {
    set(if (id in cur) cur - id else cur + id)
}

@Composable
private fun SortToggle(byLocation: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.5f)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegItem("Time", !byLocation) { onChange(false) }
        SegItem("Location", byLocation) { onChange(true) }
    }
}

@Composable
private fun SegItem(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(if (on) Color.Transparent else Color.Transparent)
            .then(if (on) Modifier.background(coralBrush(), RoundedCornerShape(11.dp)) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, color = if (on) Color.White else C.subtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionHeader(label: String, color: Color, count: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.35f)))
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", color = C.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GroupHeader(cwd: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▾ ", color = C.accent, fontSize = 12.sp)
        Text(cwd, color = C.ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.5f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
            Text("$count", color = C.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(s: SessionSummary, isPinned: Boolean, vm: AppViewModel, onTogglePin: () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> vm.archive(s.id) // swipe right to archive
                SwipeToDismissBoxValue.EndToStart -> vm.delete(s.id)  // swipe left to delete
                else -> {}
            }
            false // actual removal is driven by state updates; don't let the component dismiss itself
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = {
            // only draw the archive/delete background while actively swiping; transparent when Settled, otherwise the gold shows through the translucent glass card
            val dir = dismiss.dismissDirection
            if (dir != SwipeToDismissBoxValue.Settled) {
                val toEnd = dir == SwipeToDismissBoxValue.EndToStart
                val color = if (toEnd) C.err else C.warn
                val label = if (toEnd) "Delete" else "Archive"
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(color),
                    contentAlignment = if (toEnd) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp))
                }
            }
        },
    ) {
        SessionCard(s, isPinned, onClick = { vm.open(s.id) }, onLongClick = onTogglePin)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: SessionSummary, isPinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Glass(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        radius = 20.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp)) {
            Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(statusColor(s.status)))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPinned) { Text("📌 ", fontSize = 11.sp) }
                    Text(s.title, color = C.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(s.updatedAt), color = C.dim, fontSize = 11.sp)
                }
                Text(s.cwd, color = C.subtle, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (s.preview.isNotBlank()) {
                    val prefix = if (s.previewRole == "user") "Me: " else "Claude: "
                    Text(prefix + s.preview, color = C.subtle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp), lineHeight = 18.sp)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tok = s.usage.inputTokens + s.usage.outputTokens
                    MetaTag("${kfmt(tok)} tok")
                    Spacer(Modifier.width(7.dp))
                    Text("$%.3f".format(s.usage.costUsd), color = C.dim, fontSize = 11.sp)
                    if (needsAttention(s.status)) {
                        Spacer(Modifier.width(7.dp))
                        Text("◐ ${zhStatus(s.status)}", color = C.warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaTag(text: String) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = C.subtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NewSessionDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onStart(text.trim()) }) { Text("Start", color = C.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.dim) } },
        title = { Text("New session", color = C.ink) },
        text = {
            Glass(Modifier.fillMaxWidth().height(110.dp), radius = 14.dp) {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    if (text.isEmpty()) Text("Type your first message…", color = C.dim, fontSize = 14.sp)
                    BasicTextField(value = text, onValueChange = { text = it }, textStyle = TextStyle(color = C.ink, fontSize = 14.sp), modifier = Modifier.fillMaxSize())
                }
            }
        },
        containerColor = C.bg,
    )
}
