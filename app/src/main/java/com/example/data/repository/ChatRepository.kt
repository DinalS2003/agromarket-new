package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.AgroMarketApp
import com.example.data.api.ApiClient
import com.example.data.api.SupabaseService
import com.example.data.local.AppDatabase
import com.example.data.local.SessionManager
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.MessageEntity
import com.example.data.local.entities.OutboxStatus
import com.example.data.models.ConversationItem
import com.example.data.models.ListingItem
import com.example.data.models.MessageItem
import com.example.data.realtime.PhoenixRealtimeClient
import com.example.data.realtime.RealtimeConnectionState
import com.example.data.realtime.RealtimeMessagePayload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ContentBlockedException(message: String, val reasons: List<String> = emptyList()) : Exception(message)
class OrderClosedException(message: String) : Exception(message)
class RateLimitedException(message: String) : Exception(message)
class NotAPartyException(message: String) : Exception(message)
class SuspendedChatException(message: String) : Exception(message)

/**
 * AgroMarket Simple Chat Repository (Text only)
 * One conversation per (buyer, seller) pair. Messages are plain text.
 */
class ChatRepository(
    private val service: SupabaseService = ApiClient.service,
    private val sessionManager: SessionManager,
    context: Context? = null
) {
    private val tag = "ChatRepository"
    private val appContext: Context? = context ?: try { AgroMarketApp.instance } catch (_: Exception) { null }
    val database: AppDatabase? = appContext?.let { AppDatabase.getInstance(it) }

    private val rawClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for Realtime
        .build()

    val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active conversation ID being viewed
    private var activeConversationId: String? = null

    // Realtime Phoenix Client
    val realtimeClient: PhoenixRealtimeClient by lazy {
        PhoenixRealtimeClient(
            scope = repositoryScope,
            okHttpClient = rawClient,
            onMessageReceived = { payload ->
                handleRealtimeMessage(payload)
            },
            onResyncRequired = {
                recoverReconnectGap()
            }
        )
    }

    val connectionState: StateFlow<RealtimeConnectionState> get() = realtimeClient.connectionState
    val latencyMs: StateFlow<Long> get() = realtimeClient.latencyMs
    val lastHeartbeatTime: StateFlow<Long> get() = realtimeClient.lastHeartbeatTime
    val lastSyncTime: StateFlow<Long> get() = realtimeClient.lastSyncTime
    val recoveredGapCount: StateFlow<Int> get() = realtimeClient.recoveredGapCount
    val typingStates: StateFlow<Map<String, Boolean>> get() = realtimeClient.typingStates

    // Fallback in-memory cache for standalone unit tests or quick access
    private val localMessagesByConv = ConcurrentHashMap<String, MutableList<MessageItem>>()
    private val inMemoryConversations = MutableStateFlow<List<ConversationItem>>(emptyList())
    private val inMemoryMessageFlow = MutableSharedFlow<MessageItem>(extraBufferCapacity = 64)

    init {
        repositoryScope.launch {
            try {
                val uid = sessionManager.getUserId()
                if (!uid.isNullOrBlank()) {
                    realtimeClient.start(uid)
                }
            } catch (e: Exception) {
                Log.e(tag, "Startup initialization error", e)
            }
        }
    }

    fun startRealtimeForUser(userId: String) {
        realtimeClient.start(userId)
    }

    fun stopRealtime() {
        realtimeClient.stop()
    }

    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    // ========================================================================
    // CONVERSATION INITIALIZATION
    // ========================================================================

    /**
     * Resolves or creates conversation for listing or order, loads messages and marks read.
     */
    suspend fun initChat(
        orderIdOrListingId: String,
        orderStatus: String = "active",
        listing: ListingItem? = null,
        currentUserId: String = "",
        isFarmerView: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val uid = currentUserId.ifBlank { sessionManager.getUserId() ?: "" }
        var resolvedConvId = ""

        // Case 1: Entering from a listing (e.g. "listing_123" or listing passed)
        if (orderIdOrListingId.startsWith("listing_")) {
            val listingId = orderIdOrListingId.removePrefix("listing_")
            try {
                val res = service.getOrCreateConversation(mapOf("p_listing_id" to listingId))
                if (res.isSuccessful && res.body() != null) {
                    val raw = res.body()!!.string().trim().removeSurrounding("\"")
                    if (raw.isNotBlank()) resolvedConvId = raw
                }
            } catch (e: Exception) {
                Log.e(tag, "get_or_create_conversation error for listing $listingId", e)
            }
        }
        // Case 2: Entering from an order
        else if (orderIdOrListingId.isNotBlank()) {
            try {
                val res = service.openOrderConversation(mapOf("p_order_id" to orderIdOrListingId))
                if (res.isSuccessful && res.body() != null) {
                    val raw = res.body()!!.string().trim().removeSurrounding("\"")
                    if (raw.isNotBlank()) resolvedConvId = raw
                }
            } catch (e: Exception) {
                Log.w(tag, "open_order_conversation failed for orderId $orderIdOrListingId: ${e.message}")
            }

            // Case 3: Already a conversation UUID
            if (resolvedConvId.isBlank()) {
                resolvedConvId = orderIdOrListingId
            }
        }

        if (resolvedConvId.isBlank()) {
            resolvedConvId = orderIdOrListingId
        }

        activeConversationId = resolvedConvId

        // Fetch messages from remote
        refreshMessages(resolvedConvId, uid)

        // Mark as read
        markConversationRead(resolvedConvId)

        resolvedConvId
    }

    /**
     * Fetch messages directly from Supabase for this conversation
     */
    suspend fun refreshMessages(conversationId: String, currentUserId: String = ""): List<MessageItem> = withContext(Dispatchers.IO) {
        val uid = currentUserId.ifBlank { sessionManager.getUserId() ?: "" }
        val db = database
        try {
            val res = service.getConversationMessages(conversationFilter = "eq.$conversationId")
            if (res.isSuccessful && res.body() != null) {
                val remoteMessages = res.body()!!
                val mapped = remoteMessages.map { m ->
                    val mine = if (m.isMine != null) m.isMine else (uid.isNotBlank() && m.senderId == uid)
                    m.copy(isMine = mine, conversationId = conversationId)
                }

                // Cache in Room
                if (db != null) {
                    val entities = mapped.map { MessageEntity.fromMessageItem(it, uid) }
                    db.messageDao().insertOrUpdateAll(entities)
                }

                // Cache in-memory
                localMessagesByConv[conversationId] = mapped.toMutableList()

                return@withContext mapped
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh messages for $conversationId", e)
        }

        // Return cached messages if remote failed
        if (db != null) {
            val cached = db.messageDao().getMessagesList(conversationId).map { it.toMessageItem() }
            return@withContext cached
        }
        return@withContext localMessagesByConv[conversationId] ?: emptyList()
    }

    // ========================================================================
    // MESSAGE FLOW & SENDING
    // ========================================================================

    fun getMessagesFlow(conversationId: String): Flow<List<MessageItem>> {
        val db = database
        return if (db != null) {
            db.messageDao().getMessagesFlow(conversationId)
                .map { list ->
                    list.map { entity ->
                        entity.toMessageItem()
                    }
                }
        } else {
            flow {
                val initial = localMessagesByConv[conversationId] ?: emptyList()
                emit(initial)
                inMemoryMessageFlow.collect {
                    val updated = localMessagesByConv[conversationId] ?: emptyList()
                    emit(updated)
                }
            }
        }
    }

    suspend fun clearUserCache(userId: String) = withContext(Dispatchers.IO) {
        try {
            database?.conversationDao()?.clearUserConversations(userId)
        } catch (_: Exception) {}
        localMessagesByConv.clear()
        inMemoryConversations.value = emptyList()
    }

    /**
     * Send plain text message directly
     */
    suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientNonce: String? = null,
        senderId: String? = null
    ): Result<MessageItem> = withContext(Dispatchers.IO) {
        val uid = senderId ?: sessionManager.getUserId()
        if (uid.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("User not authenticated"))
        }

        val trimmedBody = body.trim()
        if (trimmedBody.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Message cannot be empty"))
        }

        val messageId = UUID.randomUUID().toString()
        val nowIso = Instant.now().toString()

        val optimisticItem = MessageItem(
            id = messageId,
            conversationId = conversationId,
            senderId = uid,
            kind = "user",
            body = trimmedBody,
            clientNonce = clientNonce ?: messageId,
            createdAt = nowIso,
            isMine = true,
            isSending = true,
            sendFailed = false
        )

        // 1. Optimistic local insert
        val db = database
        if (db != null) {
            db.messageDao().insertOrUpdate(MessageEntity.fromMessageItem(optimisticItem, uid))
        }
        val list = localMessagesByConv.getOrPut(conversationId) { mutableListOf() }
        synchronized(list) {
            list.add(0, optimisticItem)
        }
        inMemoryMessageFlow.emit(optimisticItem)

        // 2. Direct HTTP insert into messages table
        return@withContext try {
            val payload = mapOf(
                "id" to messageId,
                "conversation_id" to conversationId,
                "sender_id" to uid,
                "body" to trimmedBody
            )
            val res = service.insertMessageDirect(payload)
            if (res.isSuccessful) {
                val confirmed = optimisticItem.copy(isSending = false, sendFailed = false)
                if (db != null) {
                    db.messageDao().insertOrUpdate(MessageEntity.fromMessageItem(confirmed, uid))
                }
                synchronized(list) {
                    val idx = list.indexOfFirst { it.id == messageId }
                    if (idx != -1) list[idx] = confirmed
                }
                inMemoryMessageFlow.emit(confirmed)
                Result.success(confirmed)
            } else {
                val err = res.errorBody()?.string() ?: res.message()
                Log.e(tag, "insertMessageDirect failed: HTTP ${res.code()} - $err")
                val failed = optimisticItem.copy(isSending = false, sendFailed = true, failError = err)
                if (db != null) {
                    db.messageDao().insertOrUpdate(MessageEntity.fromMessageItem(failed, uid))
                }
                synchronized(list) {
                    val idx = list.indexOfFirst { it.id == messageId }
                    if (idx != -1) list[idx] = failed
                }
                inMemoryMessageFlow.emit(failed)
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during sendMessage", e)
            val failed = optimisticItem.copy(isSending = false, sendFailed = true, failError = e.message)
            if (db != null) {
                db.messageDao().insertOrUpdate(MessageEntity.fromMessageItem(failed, uid))
            }
            synchronized(list) {
                val idx = list.indexOfFirst { it.id == messageId }
                if (idx != -1) list[idx] = failed
            }
            inMemoryMessageFlow.emit(failed)
            Result.failure(e)
        }
    }

    suspend fun markConversationRead(conversationId: String) = withContext(Dispatchers.IO) {
        try {
            service.markConversationRead(mapOf("p_conversation_id" to conversationId))
            val uid = sessionManager.getUserId() ?: ""
            val nowIso = java.time.Instant.now().toString()
            database?.conversationDao()?.markRead(conversationId, uid)
            database?.messageDao()?.markMessagesRead(conversationId, uid, nowIso)
            inMemoryConversations.value = inMemoryConversations.value.map {
                if (it.conversationId == conversationId) it.copy(unreadCount = 0) else it
            }
        } catch (e: Exception) {
            Log.w(tag, "markConversationRead warning for $conversationId: ${e.message}")
        }
    }

    // ========================================================================
    // INBOX & CONVERSATIONS
    // ========================================================================

    suspend fun loadConversations(userId: String = "", isFarmer: Boolean = false): List<ConversationItem> = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { sessionManager.getUserId() ?: "" }
        try {
            val res = service.getInbox()
            if (res.isSuccessful && res.body() != null) {
                val inbox = res.body()!!
                val db = database
                if (db != null) {
                    val entities = inbox.map { ConversationEntity.fromConversationItem(it, uid) }
                    db.conversationDao().insertOrUpdateAll(entities)
                }
                inMemoryConversations.value = inbox
                return@withContext inbox
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load inbox", e)
        }

        // Fallback to local DB
        val db = database
        if (db != null) {
            val local = db.conversationDao().getConversationsList(uid).map { it.toConversationItem() }
            if (local.isNotEmpty()) {
                inMemoryConversations.value = local
                return@withContext local
            }
        }
        return@withContext inMemoryConversations.value
    }

    fun getTotalUnreadCountFlow(userId: String): Flow<Int> {
        val db = database
        return if (db != null) {
            db.messageDao().getTotalUnreadCountFlow(userId)
        } else {
            inMemoryConversations.map { list -> list.sumOf { it.unreadCount } }
        }
    }

    // ========================================================================
    // REALTIME HANDLING & RECOVERY
    // ========================================================================

    private fun handleRealtimeMessage(payload: RealtimeMessagePayload) {
        repositoryScope.launch {
            val uid = sessionManager.getUserId() ?: ""
            val isMine = (uid.isNotBlank() && payload.senderId == uid)

            val item = MessageItem(
                id = payload.id,
                conversationId = payload.conversationId,
                orderId = payload.orderId,
                senderId = payload.senderId,
                kind = payload.kind,
                body = payload.body,
                clientNonce = payload.clientNonce ?: payload.id,
                createdAt = payload.createdAt,
                isMine = isMine,
                isSending = false
            )

            // Save to Room DB
            val db = database
            if (db != null) {
                // Remove matching temp/nonce item if existing
                val existing = db.messageDao().findMessage(payload.id, payload.clientNonce)
                if (existing != null && existing.id != payload.id) {
                    db.messageDao().deleteMessage(existing.id, payload.clientNonce)
                }
                db.messageDao().insertOrUpdate(MessageEntity.fromMessageItem(item, uid))

                // Update conversation preview
                val msgTs = try { Instant.parse(payload.createdAt).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
                db.conversationDao().updateLastMessage(
                    convId = payload.conversationId,
                    lastMsgId = payload.id,
                    preview = payload.body,
                    senderId = payload.senderId,
                    time = payload.createdAt,
                    timestamp = msgTs,
                    updatedAt = System.currentTimeMillis()
                )
            }

            // Save to in-memory list
            val list = localMessagesByConv.getOrPut(payload.conversationId) { mutableListOf() }
            synchronized(list) {
                val idx = list.indexOfFirst { it.id == payload.id || (payload.clientNonce != null && it.clientNonce == payload.clientNonce) }
                if (idx != null && idx != -1) {
                    list[idx] = item
                } else {
                    list.add(0, item)
                }
            }
            inMemoryMessageFlow.emit(item)

            // If active conversation is currently open, mark read
            if (activeConversationId == payload.conversationId && !isMine) {
                markConversationRead(payload.conversationId)
            }
        }
    }

    suspend fun recoverReconnectGap(): Int = withContext(Dispatchers.IO) {
        val convId = activeConversationId ?: return@withContext 0
        val messages = refreshMessages(convId)
        return@withContext messages.size
    }

    // ========================================================================
    // BACKWARD COMPATIBILITY STUBS
    // ========================================================================

    suspend fun createChatOffer(vararg args: Any?): Result<Unit> = Result.failure(UnsupportedOperationException("Offers are disabled in simple chat"))
    suspend fun respondToChatOffer(vararg args: Any?): Result<Unit> = Result.failure(UnsupportedOperationException("Offers are disabled in simple chat"))
    suspend fun sendImage(vararg args: Any?): Result<Unit> = Result.failure(UnsupportedOperationException("Images are disabled in simple chat"))
    suspend fun blockUser(vararg args: Any?): Result<Boolean> = Result.success(true)
    suspend fun unblockUser(vararg args: Any?): Result<Boolean> = Result.success(true)
    suspend fun isUserBlocked(vararg args: Any?): Result<Boolean> = Result.success(false)
    suspend fun reportUser(vararg args: Any?): Result<String> = Result.success("ok")
    suspend fun deleteConversation(convId: String, userId: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { sessionManager.getUserId() ?: "" }
        try {
            service.deleteConversation(mapOf("p_conversation_id" to convId))
            val db = database
            if (db != null) {
                db.conversationDao().deleteConversation(convId, uid)
                db.conversationDao().deleteConversation(convId)
                db.messageDao().deleteMessagesForConversation(convId)
            }
            inMemoryConversations.value = inMemoryConversations.value.filter { it.conversationId != convId }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "deleteConversation error for $convId", e)
            Result.failure(e)
        }
    }
    suspend fun recoverOutbox() {}
    suspend fun syncAllReceipts() {}
    suspend fun markMessagesDelivered(convId: String, msgId: String) {}
}
