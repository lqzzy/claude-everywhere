package com.remote.claude

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remote.claude.net.ClientCommand
import com.remote.claude.net.ImportableItem
import com.remote.claude.net.Message
import com.remote.claude.net.RemoteClient
import com.remote.claude.net.ServerEvent
import com.remote.claude.net.SessionSummary
import com.remote.claude.net.TurnInfo
import com.remote.claude.net.UsageQuota
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 本轮流式缓冲(messageId 一致则累加)。
data class StreamBuf(val messageId: String, val text: String)

// 整个 App 的 UI 状态(对应旧 zustand store)。
data class UiState(
    val connected: Boolean = false,
    val sessions: Map<String, SessionSummary> = emptyMap(),
    val order: List<String> = emptyList(),
    val messages: Map<String, List<Message>> = emptyMap(),
    val streaming: Map<String, StreamBuf> = emptyMap(),
    val turns: Map<String, TurnInfo> = emptyMap(),
    val current: String? = null, // 当前打开的会话 id;null = 在列表页
    val importable: List<ImportableItem> = emptyList(), // 可导入的历史会话(点导入时拉取)
    val historyFrom: Map<String, Int> = emptyMap(), // 已加载消息在全量中的起始下标;>0 表示上面还有更早的
    val quota: UsageQuota? = null, // 订阅额度利用率(5h/7d)
    val needsSetup: Boolean = false, // true=未配置服务器,显示「连接」页
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val client = RemoteClient()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var pendingNew = false // 新建会话:拿到 id 后自动进入
    private var loadingMore = setOf<String>() // 正在加载更早历史的会话(去重,防滚动时重复请求)

    init {
        viewModelScope.launch {
            client.connected.collect { c ->
                val was = _state.value.connected
                _state.update { it.copy(connected = c) }
                // 断线重连成功(false→true):server 会自动补发 session.list 刷新列表;
                // 若此刻正打开某会话,主动重订阅一次,把断线期间漏掉的消息从磁盘真相补全。
                if (c && !was) _state.value.current?.let { client.send(ClientCommand.SessionSubscribe(it)) }
            }
        }
        viewModelScope.launch { client.events.collect { handle(it) } }
        // 有已保存的连接配置就直接连;否则进入「连接」页(首启 / 未配置)
        val saved = Config.load(getApplication())
        if (saved != null) client.configure(saved.url, saved.token)
        else _state.update { it.copy(needsSetup = true) }
    }

    // 保存并(重)连接到新服务器:扫码 deep link 或「连接」页手动填写时调用。
    fun configure(url: String, token: String) {
        Config.save(getApplication(), url, token)
        _state.update { it.copy(needsSetup = false) }
        client.configure(url, token)
    }

    // App 回到前台时调用:重订阅当前会话 = 从磁盘拉最新全量(含后台期间电脑端续聊 / 后台轮次的新消息)。
    // 与"重连自动重订阅"互补:连接没断时由它即时刷新,连接断了则由重连回调兜底。
    fun refreshCurrent() {
        _state.value.current?.let { client.send(ClientCommand.SessionSubscribe(it)) }
    }

    // ---- 动作 ----
    fun open(id: String) {
        client.send(ClientCommand.SessionSubscribe(id))
        _state.update { it.copy(current = id) }
    }

    fun back() = _state.update { it.copy(current = null) }

    fun newSession(prompt: String) {
        pendingNew = true
        client.send(ClientCommand.SessionStart(prompt = prompt))
    }

    fun sendInput(id: String, text: String) = client.send(ClientCommand.SessionInput(id, text))
    fun interrupt(id: String) = client.send(ClientCommand.SessionInterrupt(id))
    fun delete(id: String) = client.send(ClientCommand.SessionDelete(id))
    fun archive(id: String) = client.send(ClientCommand.SessionArchive(id))
    // 加载更早一页:用已记录的 from 作为 before;from<=0 或正在加载则跳过
    fun loadOlder(id: String) {
        val from = _state.value.historyFrom[id] ?: 0
        if (from <= 0 || id in loadingMore) return
        loadingMore = loadingMore + id
        client.send(ClientCommand.SessionMore(id, from))
    }
    fun listImportable() = client.send(ClientCommand.SessionListImportable)
    fun importSession(claudeSessionId: String, cwd: String) = client.send(ClientCommand.SessionImport(claudeSessionId, cwd))
    fun requestUsage() = client.send(ClientCommand.UsageGet)

    // ---- 事件处理(对应旧 store 的 handle) ----
    private fun handle(e: ServerEvent) {
        when (e) {
            is ServerEvent.SessionList -> _state.update {
                it.copy(
                    sessions = e.sessions.associateBy { s -> s.id },
                    order = e.sessions.map { s -> s.id },
                )
            }

            is ServerEvent.SessionCreated -> _state.update { st ->
                val order = if (st.order.contains(e.session.id)) st.order else listOf(e.session.id) + st.order
                var cur = st.current
                if (pendingNew) {
                    pendingNew = false
                    cur = e.session.id
                    client.send(ClientCommand.SessionSubscribe(e.session.id))
                }
                st.copy(sessions = st.sessions + (e.session.id to e.session), order = order, current = cur)
            }

            is ServerEvent.SessionUpdated -> _state.update { st ->
                val order = if (st.order.contains(e.session.id)) st.order else listOf(e.session.id) + st.order
                st.copy(sessions = st.sessions + (e.session.id to e.session), order = order)
            }

            is ServerEvent.SessionRemoved -> _state.update { st ->
                st.copy(
                    sessions = st.sessions - e.id,
                    order = st.order - e.id,
                    current = if (st.current == e.id) null else st.current,
                )
            }

            is ServerEvent.History -> {
                loadingMore = loadingMore - e.id
                _state.update { st ->
                    val cur = st.messages[e.id] ?: emptyList()
                    val merged = if (e.mode == "prepend") e.messages + cur else e.messages
                    st.copy(
                        messages = st.messages + (e.id to merged),
                        historyFrom = st.historyFrom + (e.id to e.from),
                    )
                }
            }

            is ServerEvent.MessageDelta -> _state.update { st ->
                val prev = st.streaming[e.id]
                val text = if (prev != null && prev.messageId == e.messageId) prev.text + e.text else e.text
                st.copy(streaming = st.streaming + (e.id to StreamBuf(e.messageId, text)))
            }

            is ServerEvent.MessageComplete -> _state.update { st ->
                val list = (st.messages[e.id] ?: emptyList()).toMutableList()
                val idx = list.indexOfFirst { m -> m.id == e.message.id }
                if (idx >= 0) {
                    // SDK 对同一条 assistant 消息按 thinking/text/tool_use 分块多次下发(同 id),
                    // 必须合并 blocks,否则后到的正文会被丢弃 → 文字"突然消失"。
                    val ex = list[idx]
                    list[idx] = ex.copy(blocks = ex.blocks + e.message.blocks)
                } else {
                    list.add(e.message)
                }
                val streaming = if (e.message.role == "assistant") st.streaming - e.id else st.streaming
                st.copy(messages = st.messages + (e.id to list), streaming = streaming)
            }

            is ServerEvent.Turn -> _state.update { it.copy(turns = it.turns + (e.id to e.turn)) }

            is ServerEvent.Usage -> _state.update { st ->
                val s = st.sessions[e.id] ?: return@update st
                st.copy(sessions = st.sessions + (e.id to s.copy(usage = e.usage)))
            }

            is ServerEvent.ImportableList -> _state.update { it.copy(importable = e.items) }
            is ServerEvent.UsageQuotaEvent -> _state.update { it.copy(quota = e.quota) }
            is ServerEvent.Activity -> Unit // currentActivity 走 session.updated,这里忽略
            is ServerEvent.PermissionRequest -> Unit // 默认 bypass,不会发生
            is ServerEvent.Error -> Unit // TODO: 可加 toast
        }
    }

    override fun onCleared() {
        client.close()
    }
}
