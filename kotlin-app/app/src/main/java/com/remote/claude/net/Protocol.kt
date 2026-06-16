package com.remote.claude.net

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

// Wire protocol that maps one-to-one with server/src/protocol.ts.
// ServerEvent / ClientCommand use a sealed class + @JsonClassDiscriminator("t") to implement {"t":"..."} polymorphism.

@Serializable
data class UsageInfo(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val costUsd: Double = 0.0,
)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    // tool_use
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    // tool_result
    val toolUseId: String? = null,
    val content: JsonElement? = null,
    val isError: Boolean? = null,
)

@Serializable
data class Message(
    val id: String,
    val role: String, // "user" | "assistant"
    val blocks: List<ContentBlock> = emptyList(),
    val ts: Long = 0,
)

@Serializable
data class CurrentActivity(val tool: String, val input: JsonElement? = null)

@Serializable
data class SessionSummary(
    val id: String,
    val claudeSessionId: String? = null,
    val source: String = "app",
    val title: String = "",
    val cwd: String = "",
    val model: String = "",
    val status: String = "idle", // idle | thinking | tool | waiting_permission
    val currentActivity: CurrentActivity? = null,
    val usage: UsageInfo = UsageInfo(),
    val contextTokens: Int = 0,
    val contextLimit: Int = 200000,
    val toolCounts: Map<String, Int> = emptyMap(),
    val permissionMode: String = "bypassPermissions",
    val preview: String = "",
    val previewRole: String = "assistant",
    val archived: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class UsageWindow(val utilization: Int = 0, val resetsAt: Long = 0)

@Serializable
data class UsageQuota(val fiveHour: UsageWindow = UsageWindow(), val sevenDay: UsageWindow = UsageWindow())

@Serializable
data class ImportableItem(
    val claudeSessionId: String,
    val cwd: String = "",
    val title: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class TurnInfo(
    val phase: String, // sent | generating | done
    val sentAt: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

// ---- Server → App ----
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed class ServerEvent {
    @Serializable @SerialName("session.list")
    data class SessionList(val sessions: List<SessionSummary>) : ServerEvent()

    @Serializable @SerialName("session.created")
    data class SessionCreated(val session: SessionSummary) : ServerEvent()

    @Serializable @SerialName("session.updated")
    data class SessionUpdated(val session: SessionSummary) : ServerEvent()

    @Serializable @SerialName("session.removed")
    data class SessionRemoved(val id: String) : ServerEvent()

    @Serializable @SerialName("session.history")
    data class History(
        val id: String,
        val messages: List<Message>,
        val from: Int,
        val total: Int,
        val mode: String, // "replace" | "prepend"
    ) : ServerEvent()

    @Serializable @SerialName("message.delta")
    data class MessageDelta(val id: String, val messageId: String, val text: String) : ServerEvent()

    @Serializable @SerialName("message.complete")
    data class MessageComplete(val id: String, val message: Message) : ServerEvent()

    @Serializable @SerialName("activity")
    data class Activity(val id: String, val tool: String? = null, val input: JsonElement? = null) : ServerEvent()

    @Serializable @SerialName("usage")
    data class Usage(val id: String, val usage: UsageInfo) : ServerEvent()

    @Serializable @SerialName("turn")
    data class Turn(val id: String, val turn: TurnInfo) : ServerEvent()

    @Serializable @SerialName("permission.request")
    data class PermissionRequest(
        val id: String,
        val requestId: String,
        val tool: String,
        val input: JsonElement? = null,
    ) : ServerEvent()

    @Serializable @SerialName("importable.list")
    data class ImportableList(val items: List<ImportableItem>) : ServerEvent()

    @Serializable @SerialName("usage.quota")
    data class UsageQuotaEvent(val quota: UsageQuota) : ServerEvent()

    @Serializable @SerialName("error")
    data class Error(val message: String) : ServerEvent()
}

// ---- App → Server ----
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed class ClientCommand {
    @Serializable @SerialName("auth")
    data class Auth(val token: String) : ClientCommand()

    @Serializable @SerialName("session.start")
    data class SessionStart(val cwd: String? = null, val model: String? = null, val prompt: String? = null) : ClientCommand()

    @Serializable @SerialName("session.input")
    data class SessionInput(val id: String, val text: String) : ClientCommand()

    @Serializable @SerialName("session.interrupt")
    data class SessionInterrupt(val id: String) : ClientCommand()

    @Serializable @SerialName("session.subscribe")
    data class SessionSubscribe(val id: String) : ClientCommand()

    @Serializable @SerialName("session.more")
    data class SessionMore(val id: String, val before: Int) : ClientCommand()

    @Serializable @SerialName("session.delete")
    data class SessionDelete(val id: String) : ClientCommand()

    @Serializable @SerialName("session.archive")
    data class SessionArchive(val id: String) : ClientCommand()

    @Serializable @SerialName("session.listImportable")
    data object SessionListImportable : ClientCommand()

    @Serializable @SerialName("session.import")
    data class SessionImport(val claudeSessionId: String, val cwd: String? = null) : ClientCommand()

    @Serializable @SerialName("usage.get")
    data object UsageGet : ClientCommand()

    @Serializable @SerialName("permission.respond")
    data class PermissionRespond(val requestId: String, val allow: Boolean) : ClientCommand()
}
