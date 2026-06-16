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

// WebSocket 客户端:连服务、收事件(Flow)、发命令,断线自动重连。
// 地址/令牌运行时可配(首启走「连接」页或扫码),configure() 即(重)连。
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

    // 事件用 UNLIMITED Channel 背书:onMessage 在 IO 线程会高频涌入(长回复逐字符的 message.delta
    // 叠加 turn/usage/session.updated),而消费端在主线程还要跑昂贵的玻璃模糊渲染。旧版
    // MutableSharedFlow(512)+tryEmit 在缓冲溢出时会"静默丢事件",表现为长回复只显示一半、
    // message.complete 丢失要重进才出现。Channel.UNLIMITED + trySend 永不溢出,绝不丢事件。
    private val _events = Channel<ServerEvent>(Channel.UNLIMITED)
    val events: Flow<ServerEvent> = _events.receiveAsFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // 设置/更换服务器并(重)连接。用于首启配置或扫码切换。
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
        val url = serverWs ?: return // 未配置:不连,等 configure()
        val req = Request.Builder().url("$url?token=$token").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = try {
                    json.decodeFromString(ServerEvent.serializer(), text)
                } catch (_: Exception) {
                    return // 未知/坏事件:忽略,不崩
                }
                _events.trySend(ev) // UNLIMITED channel:必定成功,绝不丢事件
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
