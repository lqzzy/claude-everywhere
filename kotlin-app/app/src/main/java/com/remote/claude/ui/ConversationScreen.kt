package com.remote.claude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remote.claude.AppViewModel
import com.remote.claude.net.ContentBlock
import com.remote.claude.net.Message
import com.remote.claude.ui.theme.C
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ============================================================================
// Conversation screen (single-session chat view). Recreated from design/d8.html.
// Depends only on base-layer symbols: Glass / BlobBackground / coralBrush / C / kfmt / AppViewModel ...
// No material-icons; all icons use Unicode glyphs.
// ============================================================================

// Status labels (mapped from session.status)
private fun statusText(status: String): String = when (status) {
    "idle" -> "Idle"
    "thinking" -> "Thinking"
    "tool" -> "Running tool"
    "waiting_permission" -> "Awaiting approval"
    "ended" -> "Ended"
    else -> status
}

// ---- Small helpers for reading fields from / flattening JsonElement to text ----

// Get the string value of a key from a JsonObject
private fun JsonElement?.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

// Take the last path segment (file_path -> file name)
private fun baseName(p: String?): String =
    if (p.isNullOrEmpty()) "" else p.substringAfterLast('/').ifEmpty { p }

// Collapse to a single line (for command summaries)
private fun oneLine(s: String?): String =
    s?.replace(Regex("\\s+"), " ")?.trim() ?: ""

// Convert a tool_result's content (JsonElement) into readable text.
// Handles three shapes: plain string / [{type:"text",text:"..."}] array / other objects (toString fallback).
private fun contentToText(el: JsonElement?): String {
    if (el == null) return ""
    return when (el) {
        is JsonPrimitive -> el.contentOrNull ?: el.toString()
        is JsonArray -> el.joinToString("\n") { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull ?: item.toString()
                is JsonObject -> item["text"]?.jsonPrimitive?.contentOrNull ?: item.toString()
                else -> item.toString()
            }
        }
        is JsonObject -> el["text"]?.jsonPrimitive?.contentOrNull ?: el.toString()
    }
}

// Truncate overly long output so tool results don't blow up a full screen
private fun trunc(s: String, n: Int = 3000): String =
    if (s.length > n) s.take(n) + "\n…(${s.length - n} more chars)" else s

// Tool header: icon glyph + summary label (per d8 + old ToolBlock)
private data class ToolHead(val icon: String, val label: String)

private fun toolHead(name: String?, input: JsonElement?): ToolHead = when (name) {
    "Bash" -> ToolHead("$", oneLine(input.str("command")).ifEmpty { "Bash" })
    "Edit", "MultiEdit" -> ToolHead("✎", baseName(input.str("file_path")).ifEmpty { name!! })
    "Write" -> ToolHead("✎", baseName(input.str("file_path")).ifEmpty { "Write" })
    "Read" -> ToolHead("⎙", baseName(input.str("file_path")).ifEmpty { "Read" })
    "Grep" -> ToolHead("⌕", (input.str("pattern") ?: "Grep"))
    "Glob" -> ToolHead("⌕", (input.str("pattern") ?: "Glob"))
    "TodoWrite" -> ToolHead("☑", "Update todos")
    "WebFetch" -> ToolHead("⊕", (input.str("url") ?: "WebFetch"))
    "Task" -> ToolHead("✦", oneLine(input.str("description")).ifEmpty { "Subtask" })
    else -> ToolHead("⚙", name ?: "Tool")
}

private val mono = FontFamily.Monospace

// ============================================================================

@Composable
fun ConversationScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val id = state.current ?: return
    val session = state.sessions[id]
    val messages = state.messages[id] ?: emptyList()
    val streaming = state.streaming[id]
    val turn = state.turns[id]

    // tool_result matching: scan all messages, build a toolUseId -> tool_result block index.
    // tool_result usually appears as a standalone user message; it's folded into the tool_use render rather than shown as its own bubble.
    val resultMap: Map<String, ContentBlock> = remember(messages) {
        buildMap {
            messages.forEach { m ->
                m.blocks.forEach { b ->
                    if (b.type == "tool_result" && b.toolUseId != null) put(b.toolUseId, b)
                }
            }
        }
    }

    BlobBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ---------- 1. Top nav pill ----------
            Glass(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp)
                    .fillMaxWidth(),
                radius = 26.dp,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Back (coral)
                    Text(
                        "‹",
                        color = C.accent,
                        fontSize = 24.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { vm.back() }
                            .padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            session?.title?.ifEmpty { "Session" } ?: "Session",
                            color = C.ink,
                            fontSize = 15.5f.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            "${session?.model ?: ""} · ${statusText(session?.status ?: "idle")}",
                            color = C.dim,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    // Coral gradient orb
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(coralBrush())
                    )
                }
            }

            // ---------- 2. Usage panel: Context / 5h usage / 7d usage (progress bar + % + until reset) ----------
            val ctxPct = if (session != null && session.contextLimit > 0)
                (session.contextTokens * 100 / session.contextLimit).coerceIn(0, 100) else 0
            // After entering the session, periodically refresh subscription usage (every 60s)
            LaunchedEffect(id) {
                while (true) {
                    vm.requestUsage()
                    kotlinx.coroutines.delay(60_000)
                }
            }
            Glass(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                radius = 16.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    StatBar("Context", ctxPct, null)
                    val q = state.quota
                    if (q != null) {
                        StatBar("5h usage", q.fiveHour.utilization, untilReset(q.fiveHour.resetsAt))
                        StatBar("7d usage", q.sevenDay.utilization, untilReset(q.sevenDay.resetsAt))
                    } else {
                        Text("Loading usage…", color = C.dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // ---------- 3. Resume hint (resume in terminal) ----------
            val claudeSid = session?.claudeSessionId
            if (!claudeSid.isNullOrEmpty()) {
                val clipboard = LocalClipboardManager.current
                val short = claudeSid.take(8)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .clickable {
                            clipboard.setText(AnnotatedString("claude --resume $claudeSid"))
                        },
                ) {
                    Text(
                        "Resume in terminal: claude --resume $short…  ⧉",
                        color = C.accent,
                        fontSize = 11.5f.sp,
                        fontFamily = mono,
                    )
                }
            }

            // ---------- 4. Message feed (reverseLayout: index 0 = newest at bottom; scroll up = older; pagination appends at the high-index end, no jump) ----------
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Newest first (to match reverseLayout): streaming card → newest message → … → oldest message
            val feedItems = remember(messages, streaming) {
                buildList {
                    if (streaming != null && streaming.text.isNotEmpty()) {
                        add(FeedItem.Stream(streaming.messageId, streaming.text))
                    }
                    for (i in messages.indices.reversed()) {
                        val m = messages[i]
                        if (shouldRender(m)) add(FeedItem.Msg(m))
                    }
                }
            }
            // On new content (newest item changes / streaming body grows), scroll to the bottom (index 0); scrolling up through history won't trigger it
            LaunchedEffect(feedItems.firstOrNull()?.key, streaming?.text) {
                if (feedItems.isNotEmpty()) listState.animateScrollToItem(0)
            }
            // Reached the top of what's loaded (= max index under reverseLayout) and there's older content → load the previous page
            val canLoadOlder = (state.historyFrom[id] ?: 0) > 0
            LaunchedEffect(listState, canLoadOlder, feedItems.size) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .collect { lastIdx ->
                        if (canLoadOlder && lastIdx >= feedItems.size - 2) vm.loadOlder(id)
                    }
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) {
                items(feedItems, key = { it.key }) { item ->
                    when (item) {
                        is FeedItem.Msg -> when (item.m.role) {
                            "user" -> UserMessage(item.m)
                            "system" -> SystemDivider(item.m)
                            else -> AssistantMessage(item.m, resultMap)
                        }

                        is FeedItem.Stream -> StreamingCard(item.text)
                    }
                }
            }

            // ---------- 6. Live heartbeat row ----------
            if (turn != null && turn.phase != "done") {
                LiveBeat(
                    phase = turn.phase,
                    sentAt = turn.sentAt,
                    outputTokens = turn.outputTokens,
                )
            }

            // ---------- 7. composer ----------
            Composer(
                busy = session != null && session.status != "idle" && session.status != "ended",
                onSend = { text -> vm.sendInput(id, text) },
                onInterrupt = { vm.interrupt(id) },
            )
        }
    }
}

// Sealed type for feed items (message / streaming)
private sealed interface FeedItem {
    val key: String
    data class Msg(val m: Message) : FeedItem {
        override val key get() = "m-" + m.id
    }
    data class Stream(val messageId: String, val text: String) : FeedItem {
        override val key get() = "s-" + messageId
    }
}

// Whether the message has visible content (filters out user messages that are pure tool_result, empty text, etc.)
private fun shouldRender(m: Message): Boolean {
    if (m.role == "user") {
        // Show as long as there's a non-empty text block; pure tool_result isn't rendered as a bubble
        return m.blocks.any { it.type == "text" && !it.text.isNullOrBlank() }
    }
    // assistant: has tool_use or non-empty text/thinking
    return m.blocks.any { b ->
        b.type == "tool_use" ||
            ((b.type == "text" || b.type == "thinking") && !b.text.isNullOrBlank())
    }
}

// ---------------------------------------------------------------------------
// chips
// ---------------------------------------------------------------------------

@Composable
private fun Chip(content: @Composable () -> Unit) {
    Glass(radius = 16.dp) {
        Box(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) { content() }
    }
}

// Compose plain text + a bold segment into one line
@Composable
private fun ChipLine(prefix: String, bold: String, suffix: String = "") {
    Text(
        buildAnnotatedString {
            if (prefix.isNotEmpty()) append(prefix)
            withStyle(SpanStyle(color = C.ink, fontWeight = FontWeight.Bold)) { append(bold) }
            if (suffix.isNotEmpty()) append(suffix)
        },
        color = Color(0xFF6F6053),
        fontSize = 11.5f.sp,
    )
}

// A single usage progress bar: label | bar | percent | (until reset). Color shifts as utilization rises: coral → gold → red.
@Composable
private fun StatBar(label: String, pct: Int, sub: String?) {
    val color = when {
        pct >= 90 -> C.err
        pct >= 70 -> C.warn
        else -> C.accent
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = C.subtle, fontSize = 11.sp, modifier = Modifier.width(56.dp))
        Box(
            Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.5f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        Text(
            "$pct%",
            color = C.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).width(34.dp),
        )
        if (!sub.isNullOrEmpty()) {
            Text(sub, color = C.dim, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(58.dp))
        }
    }
}

// System divider (e.g. /compact's "Context compacted"): centered thin line + text, not treated as a chat bubble.
@Composable
private fun SystemDivider(m: Message) {
    val text = m.blocks.firstOrNull { it.type == "text" && !it.text.isNullOrBlank() }?.text ?: return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(C.dim.copy(alpha = 0.35f)))
        Text("📦 $text", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(C.dim.copy(alpha = 0.35f)))
    }
}

// ---------------------------------------------------------------------------
// User message: right-aligned coral gradient bubble
// ---------------------------------------------------------------------------

@Composable
private fun UserMessage(m: Message) {
    val text = m.blocks.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }.trim()
    if (text.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 23.dp, topEnd = 23.dp, bottomEnd = 7.dp, bottomStart = 23.dp))
                .background(coralBrush())
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(text, color = Color.White, fontSize = 14.5f.sp, lineHeight = 21.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// assistant message: glass card, rendered by blocks
// ---------------------------------------------------------------------------

@Composable
private fun AssistantMessage(m: Message, resultMap: Map<String, ContentBlock>) {
    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        radius = 24.dp,
    ) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp)) {
            m.blocks.forEach { b ->
                when (b.type) {
                    "text" -> if (!b.text.isNullOrBlank()) {
                        Text(
                            renderInline(b.text!!),
                            color = C.ink,
                            fontSize = 14.5f.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    "thinking" -> if (!b.text.isNullOrBlank()) ThinkingBlock(b.text!!)
                    "tool_use" -> {
                        val res = b.id?.let { resultMap[it] }
                        ToolBlock(b, res)
                    }
                    // tool_result isn't rendered directly in the assistant card (already folded into its tool_use)
                }
            }
        }
    }
}

// Minimal inline markdown: **bold** and `inline code`. Anything that won't parse is treated as plain text.
private fun renderInline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        // **bold**
        if (src.startsWith("**", i)) {
            val end = src.indexOf("**", i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        // `inline code`
        if (src[i] == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i) {
                withStyle(
                    SpanStyle(
                        fontFamily = mono,
                        color = C.inlText,
                        background = C.inlBg,
                    )
                ) { append(src.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(src[i])
        i++
    }
}

// ---------------------------------------------------------------------------
// thinking collapsible block
// ---------------------------------------------------------------------------

@Composable
private fun ThinkingBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            "${if (open) "▼" else "▶"} 💭 Thinking",
            color = C.dim,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 2.dp),
        )
        if (open) {
            Text(
                text,
                color = C.subtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tool collapsible block: header icon + summary + status; expanded shows input details + tool_result
// ---------------------------------------------------------------------------

@Composable
private fun ToolBlock(block: ContentBlock, result: ContentBlock?) {
    var open by remember { mutableStateOf(false) }
    val h = toolHead(block.name, block.input)
    val running = result == null
    val isError = result?.isError == true

    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        radius = 18.dp,
    ) {
        Column {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (open) "▼" else "▶", color = C.dim, fontSize = 10.sp)
                Text(
                    h.icon,
                    color = C.accent,
                    fontSize = 13.sp,
                    fontFamily = mono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    h.label,
                    color = Color(0xFF5E5346),
                    fontSize = 12.5f.sp,
                    fontFamily = mono,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (running) "…" else if (isError) "✗" else "✓",
                    color = if (running) C.dim else if (isError) C.err else C.ok,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Expanded body
            if (open) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x14BEA08C)) // Light tint for the top divider; approximates d8's border-top area
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                ) {
                    ToolInputView(block.name, block.input)
                    if (result != null) {
                        val out = trunc(contentToText(result.content))
                        if (out.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            Text("Output", color = C.dim, fontSize = 11.sp)
                            Spacer(Modifier.size(2.dp))
                            Text(
                                out,
                                color = if (isError) C.err else C.ink,
                                fontSize = 12.sp,
                                fontFamily = mono,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Tool input details: Bash shows the command; Edit/Write show the path + colored diff; everything else shows JSON.
@Composable
private fun ToolInputView(name: String?, input: JsonElement?) {
    when (name) {
        "Bash" -> CodeText(input.str("command") ?: "")
        "Edit", "MultiEdit" -> {
            input.str("file_path")?.let { PathText(it) }
            // Single Edit: old_string / new_string; MultiEdit is simplified to the single-edit path too (only shows the object's old/new if present)
            val oldS = input.str("old_string")
            val newS = input.str("new_string")
            if (oldS != null || newS != null) {
                DiffLines(oldS, newS)
            } else {
                // Complex structures like MultiEdit's edits array: JSON fallback
                CodeText(trunc((input as? JsonObject)?.get("edits")?.toString() ?: input.toString(), 1200))
            }
        }
        "Write" -> {
            input.str("file_path")?.let { PathText(it) }
            CodeText(trunc(input.str("content") ?: "", 1500))
        }
        else -> CodeText(trunc(input?.toString() ?: "{}", 1200))
    }
}

@Composable
private fun CodeText(s: String) {
    if (s.isEmpty()) return
    Text(s, color = C.ink, fontFamily = mono, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun PathText(s: String) {
    Text(s, color = C.accent, fontFamily = mono, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
}

// Diff coloring: old lines red (-), new lines green (+)
@Composable
private fun DiffLines(oldS: String?, newS: String?) {
    Column(Modifier.padding(top = 4.dp)) {
        oldS?.split("\n")?.forEach { line ->
            Text(
                "- $line",
                color = C.del,
                fontFamily = mono,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(C.delBg, RoundedCornerShape(3.dp)),
            )
        }
        newS?.split("\n")?.forEach { line ->
            Text(
                "+ $line",
                color = C.add,
                fontFamily = mono,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(C.addBg, RoundedCornerShape(3.dp)),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 5. Streaming "generating" card
// ---------------------------------------------------------------------------

@Composable
private fun StreamingCard(text: String) {
    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        radius = 24.dp,
    ) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 15.dp)) {
            Text(renderInline(text), color = C.ink, fontSize = 14.5f.sp, lineHeight = 23.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// 6. Live heartbeat row
// ---------------------------------------------------------------------------

@Composable
private fun LiveBeat(phase: String, sentAt: Long, outputTokens: Int) {
    // Refresh the elapsed-time display about every 300ms
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sentAt, phase) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(300)
        }
    }
    val elapsed = ((now - sentAt) / 1000).coerceAtLeast(0)
    val label = if (phase == "sent") "Sent · waiting…" else "Generating"
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Coral glowing dot
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(C.accent)
        )
        Text(
            "$label · this turn ${kfmt(outputTokens)} tok ↑ · ${elapsed}s",
            color = C.accent,
            fontSize = 12.sp,
            fontFamily = mono,
        )
    }
}

// ---------------------------------------------------------------------------
// 7. composer
// ---------------------------------------------------------------------------

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onInterrupt: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            // Keyboard up = top inset of ime height (sits right on the keyboard); down = nav bar height. Take the max; single computation, no stacking.
            .padding(WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Glass(
            modifier = Modifier.weight(1f),
            radius = 28.dp,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 15.dp),
            ) {
                if (text.isEmpty()) {
                    Text("Message Claude…", color = C.dim, fontSize = 14.5f.sp)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = C.ink, fontSize = 14.5f.sp),
                    cursorBrush = coralBrush(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Send / interrupt round button
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(coralBrush())
                .clickable {
                    if (busy) {
                        onInterrupt()
                    } else {
                        val t = text.trim()
                        if (t.isNotEmpty()) {
                            onSend(t)
                            text = ""
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (busy) "⏹" else "↑",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
