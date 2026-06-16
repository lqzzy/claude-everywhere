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
// 对话屏(单个会话聊天界面)。照 design/d8.html 还原。
// 只依赖基础层符号:Glass / BlobBackground / coralBrush / C / kfmt / AppViewModel ...
// 无 material-icons,所有图标用 Unicode 字形。
// ============================================================================

// 中文状态文案(对应 session.status)
private fun statusText(status: String): String = when (status) {
    "idle" -> "空闲"
    "thinking" -> "思考中"
    "tool" -> "执行工具"
    "waiting_permission" -> "等待批准"
    "ended" -> "已结束"
    else -> status
}

// ---- JsonElement 取字段 / 转纯文本的小工具 ----

// 从 JsonObject 取某 key 的字符串值
private fun JsonElement?.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

// 文件路径取末段(file_path -> 文件名)
private fun baseName(p: String?): String =
    if (p.isNullOrEmpty()) "" else p.substringAfterLast('/').ifEmpty { p }

// 压成单行(命令摘要用)
private fun oneLine(s: String?): String =
    s?.replace(Regex("\\s+"), " ")?.trim() ?: ""

// 把 tool_result 的 content(JsonElement)转成可读文本。
// 兼容三种形态:纯字符串 / [{type:"text",text:"..."}] 数组 / 其它对象(toString 兜底)。
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

// 截断超长输出,避免一屏被工具结果撑爆
private fun trunc(s: String, n: Int = 3000): String =
    if (s.length > n) s.take(n) + "\n…(还有 ${s.length - n} 字)" else s

// 工具头部:图标字形 + 摘要标签(照 d8 + 旧 ToolBlock)
private data class ToolHead(val icon: String, val label: String)

private fun toolHead(name: String?, input: JsonElement?): ToolHead = when (name) {
    "Bash" -> ToolHead("$", oneLine(input.str("command")).ifEmpty { "Bash" })
    "Edit", "MultiEdit" -> ToolHead("✎", baseName(input.str("file_path")).ifEmpty { name!! })
    "Write" -> ToolHead("✎", baseName(input.str("file_path")).ifEmpty { "Write" })
    "Read" -> ToolHead("⎙", baseName(input.str("file_path")).ifEmpty { "Read" })
    "Grep" -> ToolHead("⌕", (input.str("pattern") ?: "Grep"))
    "Glob" -> ToolHead("⌕", (input.str("pattern") ?: "Glob"))
    "TodoWrite" -> ToolHead("☑", "更新待办")
    "WebFetch" -> ToolHead("⊕", (input.str("url") ?: "WebFetch"))
    "Task" -> ToolHead("✦", oneLine(input.str("description")).ifEmpty { "子任务" })
    else -> ToolHead("⚙", name ?: "工具")
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

    // tool_result 匹配:扫描所有消息,建 toolUseId -> tool_result 块 的索引。
    // tool_result 通常作为独立 user 消息出现,渲染 tool_use 时折进去,不单独显示成气泡。
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
            // ---------- 1. 顶部 nav 药丸 ----------
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
                    // 返回(珊瑚色)
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
                            session?.title?.ifEmpty { "会话" } ?: "会话",
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
                    // 珊瑚渐变 orb
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(coralBrush())
                    )
                }
            }

            // ---------- 2. 用量面板:Context / 5h 额度 / 7d 额度(进度条 + % + 距重置)----------
            val ctxPct = if (session != null && session.contextLimit > 0)
                (session.contextTokens * 100 / session.contextLimit).coerceIn(0, 100) else 0
            // 进入会话后定时刷新订阅额度(每 60s)
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
                        StatBar("5h 额度", q.fiveHour.utilization, untilReset(q.fiveHour.resetsAt))
                        StatBar("7d 额度", q.sevenDay.utilization, untilReset(q.sevenDay.resetsAt))
                    } else {
                        Text("额度加载中…", color = C.dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // ---------- 3. resume 提示(终端续聊) ----------
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
                        "终端续聊: claude --resume $short…  ⧉",
                        color = C.accent,
                        fontSize = 11.5f.sp,
                        fontFamily = mono,
                    )
                }
            }

            // ---------- 4. 消息流(reverseLayout:index0=最新在底部;向上滚=更早;翻页加在高索引端,不跳位)----------
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // 最新在前(配合 reverseLayout):流式卡 → 最新消息 → … → 最早消息
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
            // 有新内容(最新条目变化 / 流式正文增长)时滚到底部(index 0);向上翻历史不会触发
            LaunchedEffect(feedItems.firstOrNull()?.key, streaming?.text) {
                if (feedItems.isNotEmpty()) listState.animateScrollToItem(0)
            }
            // 滚到已加载的最顶(reverseLayout 下=最大索引)且还有更早 → 加载上一页
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

            // ---------- 6. live 心跳行 ----------
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

// 渲染条目的密封类型(消息 / 流式)
private sealed interface FeedItem {
    val key: String
    data class Msg(val m: Message) : FeedItem {
        override val key get() = "m-" + m.id
    }
    data class Stream(val messageId: String, val text: String) : FeedItem {
        override val key get() = "s-" + messageId
    }
}

// 该消息是否有可见内容(过滤掉纯 tool_result 的 user 消息、空文本等)
private fun shouldRender(m: Message): Boolean {
    if (m.role == "user") {
        // 只要有非空文本块就显示;纯 tool_result 不显示成气泡
        return m.blocks.any { it.type == "text" && !it.text.isNullOrBlank() }
    }
    // assistant:有 tool_use 或非空 text/thinking
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

// 普通文字 + 加粗段拼成一行
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

// 一条用量进度条:标签 | 进度条 | 百分比 | (距重置)。颜色随利用率升高:珊瑚→金→红。
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

// 系统分隔条(如 /compact 的"上下文已压缩"):居中细线 + 文字,不当成对话气泡。
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
// 用户消息:右对齐珊瑚渐变气泡
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
// assistant 消息:玻璃卡,按 blocks 渲染
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
                    // tool_result 不在 assistant 卡里直接渲染(已折进对应 tool_use)
                }
            }
        }
    }
}

// 极简内联 markdown:**粗体** 与 `行内代码`。解析不了就当纯文本。
private fun renderInline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        // **粗体**
        if (src.startsWith("**", i)) {
            val end = src.indexOf("**", i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        // `行内代码`
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
// thinking 折叠块
// ---------------------------------------------------------------------------

@Composable
private fun ThinkingBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            "${if (open) "▼" else "▶"} 💭 思考",
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
// 工具折叠块:头部图标+摘要+状态;展开显示 input 详情 + tool_result
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
            // 头部
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
            // 展开体
            if (open) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x14BEA08C)) // 顶部分隔的淡色;近似 d8 的 border-top 区
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                ) {
                    ToolInputView(block.name, block.input)
                    if (result != null) {
                        val out = trunc(contentToText(result.content))
                        if (out.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            Text("输出", color = C.dim, fontSize = 11.sp)
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

// 工具 input 详情:Bash 显示命令;Edit/Write 显示路径 + diff 染色;其它显示 JSON。
@Composable
private fun ToolInputView(name: String?, input: JsonElement?) {
    when (name) {
        "Bash" -> CodeText(input.str("command") ?: "")
        "Edit", "MultiEdit" -> {
            input.str("file_path")?.let { PathText(it) }
            // 单 Edit:old_string / new_string;MultiEdit 简化为也走单条(只展示对象里的 old/new 若有)
            val oldS = input.str("old_string")
            val newS = input.str("new_string")
            if (oldS != null || newS != null) {
                DiffLines(oldS, newS)
            } else {
                // MultiEdit 的 edits 数组等复杂结构:JSON 兜底
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

// diff 染色:old 行红(-)、new 行绿(+)
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
// 5. 流式"正在生成"卡
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
// 6. live 心跳行
// ---------------------------------------------------------------------------

@Composable
private fun LiveBeat(phase: String, sentAt: Long, outputTokens: Int) {
    // 每 ~300ms 刷新一次耗时显示
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sentAt, phase) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(300)
        }
    }
    val elapsed = ((now - sentAt) / 1000).coerceAtLeast(0)
    val label = if (phase == "sent") "已发送 · 等待响应…" else "生成中"
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 珊瑚发光点
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(C.accent)
        )
        Text(
            "$label · 本轮 ${kfmt(outputTokens)} tok ↑ · ${elapsed}s",
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
            // 键盘弹起=顶上 ime 高度(正好贴键盘);收起=导航栏高度。取 max,单次计算不叠加。
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
                    Text("发消息给 Claude…", color = C.dim, fontSize = 14.5f.sp)
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
        // 发送 / 中断 圆形按钮
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
