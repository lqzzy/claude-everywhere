package com.remote.claude.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// WebSocket client: connects to the server, receives events (as a Flow), sends commands, and auto-reconnects on disconnect.
// The address/token are configurable at runtime (first launch via the "Connect" screen or QR scan); configure() (re)connects.
class RemoteClient {
    @Volatile private var serverWs: String? = null
    @Volatile private var token: String = ""
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ws: WebSocket? = null
    private var closed = false

    // Events are backed by an UNLIMITED Channel: onMessage floods in at high frequency on the IO thread (character-by-character
    // message.delta for long replies, stacked with turn/usage/session.updated), while the consumer on the main thread also has to run
    // expensive frosted-glass blur rendering. The old MutableSharedFlow(512)+tryEmit would "silently drop events" on buffer overflow,
    // showing up as long replies only rendering halfway, or a missing message.complete that only appeared after re-entering.
    // Channel.UNLIMITED + trySend never overflows and never drops an event.
    private val _events = Channel<ServerEvent>(Channel.UNLIMITED)
    val events: Flow<ServerEvent> = _events.receiveAsFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Set/change the server and (re)connect. Used for first-launch configuration or switching via QR scan.
    fun configure(url: String, token: String) {
        this.serverWs = url
        this.token = token
        closed = false
        ws?.close(1000, null)
        ws = null
        open()
    }

    fun connect() {
        closed = false
        if (serverWs != null) open()
    }

    private fun open() {
        val url = serverWs ?: return // not configured: don't connect, wait for configure()
        val req = Request.Builder().url("$url?token=$token").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = try {
                    json.decodeFromString(ServerEvent.serializer(), text)
                } catch (_: Exception) {
                    return // unknown/malformed event: ignore it, don't crash
                }
                _events.trySend(ev) // UNLIMITED channel: always succeeds, never drops an event
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnect()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect()
        })
    }

    private fun reconnect() {
        _connected.value = false
        ws = null
        if (closed) return
        scope.launch {
            delay(1500)
            if (!closed) open()
        }
    }

    fun send(cmd: ClientCommand) {
        try {
            ws?.send(json.encodeToString(ClientCommand.serializer(), cmd))
        } catch (_: Exception) {
        }
    }

    fun close() {
        closed = true
        ws?.close(1000, null)
        ws = null
    }
}
