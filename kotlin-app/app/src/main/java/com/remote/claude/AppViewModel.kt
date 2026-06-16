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

// Streaming buffer for the current turn (accumulates while messageId stays the same).
data class StreamBuf(val messageId: String, val text: String)

// UI state for the entire app (the equivalent of the old zustand store).
data class UiState(
    val connected: Boolean = false,
    val sessions: Map<String, SessionSummary> = emptyMap(),
    val order: List<String> = emptyList(),
    val messages: Map<String, List<Message>> = emptyMap(),
    val streaming: Map<String, StreamBuf> = emptyMap(),
    val turns: Map<String, TurnInfo> = emptyMap(),
    val current: String? = null, // id of the currently open session; null = on the list screen
    val importable: List<ImportableItem> = emptyList(), // importable past sessions (fetched when "Import" is tapped)
    val historyFrom: Map<String, Int> = emptyMap(), // start index of loaded messages within the full history; >0 means there are older ones above
    val quota: UsageQuota? = null, // subscription quota utilization (5h/7d)
    val needsSetup: Boolean = false, // true = no server configured, show the "Connect" screen
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val client = RemoteClient()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var pendingNew = false // new session: auto-open it once we receive its id
    private var loadingMore = setOf<String>() // sessions currently loading older history (deduped to avoid repeated requests while scrolling)

    init {
        viewModelScope.launch {
            client.connected.collect { c ->
                val was = _state.value.connected
                _state.update { it.copy(connected = c) }
                // On successful reconnect (false→true): the server automatically re-sends session.list to refresh the list;
                // if a session is currently open, proactively re-subscribe once to backfill any messages missed during the outage from the on-disk source of truth.
                if (c && !was) _state.value.current?.let { client.send(ClientCommand.SessionSubscribe(it)) }
            }
        }
        viewModelScope.launch { client.events.collect { handle(it) } }
        // If there's a saved connection config, connect right away; otherwise go to the "Connect" screen (first launch / not configured)
        val saved = Config.load(getApplication())
        if (saved != null) client.configure(saved.url, saved.token)
        else _state.update { it.copy(needsSetup = true) }
    }

    // Save and (re)connect to a new server: called when scanning a deep link QR code or filling in the "Connect" screen manually.
    fun configure(url: String, token: String) {
        Config.save(getApplication(), url, token)
        _state.update { it.copy(needsSetup = false) }
        client.configure(url, token)
    }

    // Called when the app returns to the foreground: re-subscribe to the current session = pull the latest full history from disk (including new messages from the desktop continuing the chat / background turns while the app was backgrounded).
    // Complements "auto re-subscribe on reconnect": when the connection never dropped, this refreshes immediately; when it did drop, the reconnect callback handles it as a fallback.
    fun refreshCurrent() {
        _state.value.current?.let { client.send(ClientCommand.SessionSubscribe(it)) }
    }

    // ---- Actions ----
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
    // Load one older page: use the recorded `from` as `before`; skip if from<=0 or already loading
    fun loadOlder(id: String) {
        val from = _state.value.historyFrom[id] ?: 0
        if (from <= 0 || id in loadingMore) return
        loadingMore = loadingMore + id
        client.send(ClientCommand.SessionMore(id, from))
    }
    fun listImportable() = client.send(ClientCommand.SessionListImportable)
    fun importSession(claudeSessionId: String, cwd: String) = client.send(ClientCommand.SessionImport(claudeSessionId, cwd))
    fun requestUsage() = client.send(ClientCommand.UsageGet)

    // ---- Event handling (the equivalent of the old store's handle) ----
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
                    // The SDK sends a single assistant message in multiple chunks split by thinking/text/tool_use (with the same id),
                    // so we must merge the blocks; otherwise later-arriving body text gets dropped → text "suddenly disappears".
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
            is ServerEvent.Activity -> Unit // currentActivity comes via session.updated, so ignore it here
            is ServerEvent.PermissionRequest -> Unit // permissions are bypassed by default, so this won't happen
            is ServerEvent.Error -> Unit // TODO: could show a toast
        }
    }

    override fun onCleared() {
        client.close()
    }
}
