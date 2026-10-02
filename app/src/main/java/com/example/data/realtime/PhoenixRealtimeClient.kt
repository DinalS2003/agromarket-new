package com.example.data.realtime

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class RealtimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RESYNCING
}

data class RealtimeMessagePayload(
    val id: String,
    val conversationId: String,
    val orderId: String?,
    val senderId: String?,
    val kind: String,
    val body: String,
    val clientNonce: String?,
    val createdAt: String
)

data class RealtimeReceiptPayload(
    val conversationId: String,
    val userId: String,
    val lastReadMessageId: String? = null,
    val lastReadAt: String? = null,
    val lastDeliveredMessageId: String? = null,
    val lastDeliveredAt: String? = null
)

class PhoenixRealtimeClient(
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive WebSocket
        .build(),
    private val onMessageReceived: suspend (RealtimeMessagePayload) -> Unit,
    private val onReceiptReceived: suspend (RealtimeReceiptPayload) -> Unit = {},
    private val onResyncRequired: suspend () -> Int = { 0 }
) {
    private val tag = "PhoenixRealtime"

    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _lastHeartbeatTime = MutableStateFlow(0L)
    val lastHeartbeatTime: StateFlow<Long> = _lastHeartbeatTime.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    private val _recoveredGapCount = MutableStateFlow(0)
    val recoveredGapCount: StateFlow<Int> = _recoveredGapCount.asStateFlow()

    private val _typingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typingStates: StateFlow<Map<String, Boolean>> = _typingStates.asStateFlow()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val isStarted = AtomicBoolean(false)

    private var currentUserId: String? = null
    private var backoffMs = 1000L
    private val maxBackoffMs = 30000L
    private val heartbeatIntervalMs = 25000L

    fun start(userId: String) {
        if (isStarted.getAndSet(true)) {
            if (currentUserId == userId && _connectionState.value != RealtimeConnectionState.DISCONNECTED) {
                return
            }
        }
        currentUserId = userId
        connect()
    }

    fun stop() {
        isStarted.set(false)
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    fun reconnect() {
        if (!isStarted.get()) return
        scope.launch {
            webSocket?.cancel()
            webSocket = null
            connect()
        }
    }

    private fun connect() {
        if (!isStarted.get()) return
        _connectionState.value = RealtimeConnectionState.CONNECTING

        val anonKey = BuildConfig.SUPABASE_ANON_KEY
        val rawUrl = BuildConfig.SUPABASE_URL
        val wsUrl = rawUrl.replace("https://", "wss://").replace("http://", "ws://") +
                "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

        val request = Request.Builder().url(wsUrl).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(tag, "WebSocket opened. Subscribing to messages table channel...")
                backoffMs = 1000L

                // Join public:messages topic
                joinTopic(ws, "realtime:public:messages")

                // Start Phoenix Heartbeat timer
                startHeartbeat(ws)

                // Resync
                triggerResync()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(ws, text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed ($code): $reason")
                scheduleReconnect()
            }
        })
    }

    private fun joinTopic(ws: WebSocket, topic: String) {
        val joinPayload = JSONObject().apply {
            put("topic", topic)
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("broadcast", JSONObject().apply {
                        put("ack", false)
                        put("self", false)
                    })
                })
            })
            put("ref", "join_${topic}_${System.currentTimeMillis()}")
        }
        ws.send(joinPayload.toString())
    }

    fun triggerResync() {
        scope.launch {
            _connectionState.value = RealtimeConnectionState.RESYNCING
            try {
                val recovered = onResyncRequired()
                _recoveredGapCount.value += recovered
                _lastSyncTime.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(tag, "Gap recovery warning: ${e.message}")
            } finally {
                _connectionState.value = RealtimeConnectionState.CONNECTED
            }
        }
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isStarted.get()) {
                delay(heartbeatIntervalMs)
                try {
                    val sendTs = System.currentTimeMillis()
                    val hbMsg = JSONObject().apply {
                        put("topic", "phoenix")
                        put("event", "heartbeat")
                        put("payload", JSONObject())
                        put("ref", "hb_$sendTs")
                    }
                    val sent = ws.send(hbMsg.toString())
                    if (!sent) {
                        scheduleReconnect()
                        break
                    }
                    _lastHeartbeatTime.value = sendTs
                } catch (e: Exception) {
                    Log.w(tag, "Heartbeat failed", e)
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun handleIncomingMessage(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")
            val ref = json.optString("ref")

            // Handle heartbeat replies
            if (ref.startsWith("hb_") && event == "phx_reply") {
                val sentTs = ref.removePrefix("hb_").toLongOrNull() ?: 0L
                if (sentTs > 0) {
                    _latencyMs.value = (System.currentTimeMillis() - sentTs).coerceAtLeast(1L)
                }
                return
            }

            // Handle Postgres changes on messages table
            if (event == "postgres_changes" || event == "INSERT") {
                val payload = json.optJSONObject("payload")
                val data = payload?.optJSONObject("data") ?: payload
                val record = data?.optJSONObject("record") ?: data

                if (record != null && record.has("body")) {
                    val msgId = record.optString("id")
                    val convId = record.optString("conversation_id")
                    if (msgId.isNotBlank() && convId.isNotBlank()) {
                        val senderId = record.optString("sender_id").ifBlank { null }
                        val msg = RealtimeMessagePayload(
                            id = msgId,
                            conversationId = convId,
                            orderId = record.optString("order_id").ifBlank { null },
                            senderId = senderId,
                            kind = if (senderId == null) "system" else "user",
                            body = record.optString("body"),
                            clientNonce = record.optString("client_nonce").ifBlank { msgId },
                            createdAt = record.optString("created_at")
                        )
                        scope.launch { onMessageReceived(msg) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing incoming realtime message", e)
        }
    }

    private fun scheduleReconnect() {
        if (!isStarted.get()) return
        _connectionState.value = RealtimeConnectionState.DISCONNECTED
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            Log.d(tag, "Attempting reconnect (backoff: ${backoffMs}ms)...")
            connect()
        }
    }
}
