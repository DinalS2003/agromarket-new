package com.example.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.models.ConversationItem
import com.example.data.models.ListingItem
import com.example.data.models.MessageItem
import com.example.data.models.OrderItem
import com.example.data.realtime.RealtimeConnectionState
import com.example.data.repository.AgroMarketRepository
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MessageTickStatus {
    QUEUED,
    SENDING,
    FAILED,
    SENT,
    DELIVERED,
    READ
}

data class ChatUiState(
    val conversationId: String = "",
    val listingId: String = "",
    val cropName: String = "",
    val listingThumbnail: String? = null,
    val pricePerKg: Double = 0.0,
    val counterpartId: String = "",
    val counterpartName: String = "",
    val counterpartAvatar: String? = null,
    val orderId: String? = null,
    val orderNumber: String? = null,
    val orderStatus: String? = null,
    val conversationStatus: String = "active",
    val isReadOnly: Boolean = false,
    val readOnlyReason: String? = null,
    val isFarmer: Boolean = false,
    val isBuyer: Boolean = false,
    val isAuthorized: Boolean = true,
    val currentUserId: String = "",
    val listing: ListingItem? = null,
    val order: OrderItem? = null,
    val messages: List<MessageItem> = emptyList(), // Index 0 is newest for reverseLayout LazyColumn
    val inputText: String = "",
    val isLoadingInitial: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMorePages: Boolean = false,
    val isSending: Boolean = false,
    val warningBanner: String? = null,
    val moderationReasons: List<String> = emptyList(),
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val hasUnseenNewMessages: Boolean = false,
    val counterpartLastReadMessageId: String? = null,
    val counterpartLastReadAt: String? = null,
    val counterpartLastDeliveredMessageId: String? = null,
    val counterpartLastDeliveredAt: String? = null,
    val isCounterpartTyping: Boolean = false,
    val isDebugOverlayVisible: Boolean = false,
    val isSafetyBannerVisible: Boolean = false,
    val isCounterpartBlocked: Boolean = false,
    val fullScreenImageUrl: String? = null,
    val showOfferDialog: Boolean = false,
    val counterOfferTarget: MessageItem? = null,
    val showReportDialog: Boolean = false,
    val reportingMessage: MessageItem? = null,
    val showBlockConfirmDialog: Boolean = false
) {
    val orderIdCompat: String get() = orderId ?: conversationId

    val counterpartFirstName: String
        get() {
            val trimmed = counterpartName.trim()
            if (trimmed.isBlank() || trimmed.equals("Buyer", ignoreCase = true) || trimmed.equals("Farmer", ignoreCase = true)) {
                return if (isFarmer) "Buyer" else "Farmer"
            }
            return trimmed.split(" ").firstOrNull()?.ifBlank { trimmed } ?: trimmed
        }

    fun computeTickStatus(message: MessageItem): MessageTickStatus {
        if (message.sendFailed) return MessageTickStatus.FAILED
        if (message.isSending) return MessageTickStatus.SENDING
        return MessageTickStatus.SENT
    }
}

class ChatViewModel(
    private val repository: AgroMarketRepository,
    private val chatRepository: ChatRepository = repository.chatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Realtime connection & diagnostics
    val connectionState: StateFlow<RealtimeConnectionState> = chatRepository.connectionState
    val latencyMs: StateFlow<Long> = chatRepository.latencyMs
    val lastHeartbeatTime: StateFlow<Long> = chatRepository.lastHeartbeatTime
    val recoveredGapCount: StateFlow<Int> = chatRepository.recoveredGapCount
    val lastSyncTime: StateFlow<Long> = chatRepository.lastSyncTime

    // Inbox Conversations for MessagesScreen
    private val _conversations = MutableStateFlow<List<ConversationItem>>(emptyList())
    val conversations: StateFlow<List<ConversationItem>> = _conversations.asStateFlow()

    private val _isLoadingConversations = MutableStateFlow(false)
    val isLoadingConversations: StateFlow<Boolean> = _isLoadingConversations.asStateFlow()

    // Total unread count for bottom nav badge
    val totalUnreadCount: StateFlow<Int> = _uiState
        .map { it.currentUserId }
        .distinctUntilChanged()
        .flatMapLatest { uid ->
            if (uid.isNotBlank()) chatRepository.getTotalUnreadCountFlow(uid) else flowOf(0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var messagesJob: Job? = null

    companion object {
        val TERMINAL_ORDER_STATUSES = setOf("rejected", "cancelled", "expired", "completed", "refunded")
        val READ_ONLY_STATUSES = TERMINAL_ORDER_STATUSES

        fun isTerminalStatus(status: String?): Boolean {
            return status?.lowercase() in TERMINAL_ORDER_STATUSES
        }

        fun computeReadOnlyReason(ordStatus: String?): String? {
            return when (ordStatus?.lowercase()) {
                "completed" -> "Order completed. Chat is preserved for your records."
                "rejected" -> "Order rejected by farmer."
                "cancelled" -> "Order was cancelled."
                "expired" -> "Order expired due to payment timeout."
                "refunded" -> "Order has been refunded."
                else -> null
            }
        }

        fun computeReadOnlyReason(convStatus: String?, ordStatus: String?): String? {
            return computeReadOnlyReason(ordStatus)
        }
    }

    fun setCurrentUser(userId: String) {
        _uiState.update { it.copy(currentUserId = userId) }
        chatRepository.startRealtimeForUser(userId)
    }

    /**
     * Initializes the chat session for an order, listing, or conversation
     */
    fun initChat(
        orderId: String,
        orderStatus: String,
        listing: ListingItem? = null,
        currentUserId: String,
        isFarmerView: Boolean,
        counterpartName: String? = null
    ) {
        val isReadOnly = isTerminalStatus(orderStatus)
        val readOnlyReason = computeReadOnlyReason(orderStatus)

        // Find from existing loaded conversations if possible
        val existingConv = _conversations.value.find {
            it.conversationId == orderId ||
            (!it.orderId.isNullOrBlank() && it.orderId == orderId) ||
            (it.listingId.isNotBlank() && it.listingId == orderId.removePrefix("listing_"))
        }

        val cpName = when {
            !counterpartName.isNullOrBlank() && !counterpartName.equals("Me", ignoreCase = true) -> counterpartName
            existingConv != null && existingConv.counterpartName.isNotBlank() -> existingConv.counterpartName
            isFarmerView -> "Buyer"
            else -> listing?.farmerFirstName ?: "Farmer"
        }

        _uiState.update {
            it.copy(
                orderId = orderId,
                orderStatus = orderStatus,
                listing = listing,
                cropName = listing?.cropName ?: "",
                pricePerKg = listing?.pricePerKg ?: 0.0,
                listingThumbnail = listing?.photos?.firstOrNull(),
                counterpartName = cpName,
                isFarmer = isFarmerView,
                isBuyer = !isFarmerView,
                isReadOnly = isReadOnly,
                readOnlyReason = readOnlyReason,
                currentUserId = currentUserId,
                isLoadingInitial = true
            )
        }

        chatRepository.startRealtimeForUser(currentUserId)

        viewModelScope.launch {
            try {
                val resolvedConvId = chatRepository.initChat(
                    orderIdOrListingId = orderId,
                    orderStatus = orderStatus,
                    listing = listing,
                    currentUserId = currentUserId,
                    isFarmerView = isFarmerView
                )

                _uiState.update { it.copy(conversationId = resolvedConvId) }
                _conversations.update { list ->
                    list.map { if (it.conversationId == resolvedConvId) it.copy(unreadCount = 0) else it }
                }

                // Check inbox or remote to ensure counterpart name is accurate
                val convInfo = _conversations.value.find { it.conversationId == resolvedConvId }
                if (convInfo != null && convInfo.counterpartName.isNotBlank()) {
                    _uiState.update { it.copy(counterpartName = convInfo.counterpartName, counterpartId = convInfo.counterpartId) }
                } else if (_uiState.value.counterpartName == "Buyer" || _uiState.value.counterpartName == "Farmer") {
                    try {
                        val freshInbox = chatRepository.loadConversations(currentUserId, isFarmerView)
                        val matched = freshInbox.find { it.conversationId == resolvedConvId }
                        if (matched != null && matched.counterpartName.isNotBlank()) {
                            _uiState.update { it.copy(counterpartName = matched.counterpartName, counterpartId = matched.counterpartId) }
                        }
                    } catch (_: Exception) {}
                }

                // Observe messages for this conversation
                messagesJob?.cancel()
                messagesJob = launch {
                    chatRepository.getMessagesFlow(resolvedConvId).collect { msgList ->
                        // Reverse sort so newest is at index 0 for reverseLayout = true in LazyColumn
                        val sorted = msgList.sortedWith(
                            compareByDescending<MessageItem> { it.createdAt }
                                .thenByDescending { it.id }
                        )
                        _uiState.update {
                            it.copy(
                                messages = sorted,
                                isLoadingInitial = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingInitial = false,
                        errorMessage = e.message
                    )
                }
            }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text, errorMessage = null, warningBanner = null) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isSending || _uiState.value.isReadOnly) return

        val modResult = com.example.util.ChatModerator.checkMessage(text)
        if (modResult.isBlocked) {
            _uiState.update {
                it.copy(
                    errorMessage = modResult.warningMessage,
                    warningBanner = modResult.warningMessage,
                    moderationReasons = modResult.detectedViolations
                )
            }
            return
        }

        val convId = _uiState.value.conversationId.ifBlank { _uiState.value.orderId ?: return }
        val uid = _uiState.value.currentUserId

        _uiState.update { it.copy(inputText = "", isSending = true, errorMessage = null, warningBanner = null) }

        viewModelScope.launch {
            val res = chatRepository.sendMessage(
                conversationId = convId,
                body = text,
                senderId = uid
            )
            _uiState.update { it.copy(isSending = false) }
            if (res.isFailure) {
                _uiState.update {
                    it.copy(errorMessage = res.exceptionOrNull()?.message ?: "Failed to send message")
                }
            }
        }
    }

    fun sendQuickReply(replyText: String) {
        val modResult = com.example.util.ChatModerator.checkMessage(replyText)
        if (modResult.isBlocked) {
            _uiState.update {
                it.copy(
                    errorMessage = modResult.warningMessage,
                    warningBanner = modResult.warningMessage
                )
            }
            return
        }

        val convId = _uiState.value.conversationId.ifBlank { _uiState.value.orderId ?: return }
        val uid = _uiState.value.currentUserId
        viewModelScope.launch {
            chatRepository.sendMessage(
                conversationId = convId,
                body = replyText,
                senderId = uid
            )
        }
    }

    fun loadConversations(userId: String, isFarmer: Boolean) {
        _isLoadingConversations.value = true
        viewModelScope.launch {
            try {
                val list = chatRepository.loadConversations(userId, isFarmer)
                _conversations.value = list
            } catch (_: Exception) {
            } finally {
                _isLoadingConversations.value = false
            }
        }
    }

    fun onResumeScreen() {
        val convId = _uiState.value.conversationId
        if (convId.isNotBlank()) {
            chatRepository.setActiveConversation(convId)
            viewModelScope.launch {
                chatRepository.markConversationRead(convId)
            }
        }
    }

    fun onPauseScreen() {
        chatRepository.setActiveConversation(null)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissWarningBanner() {
        _uiState.update { it.copy(warningBanner = null, moderationReasons = emptyList()) }
    }

    fun toggleDebugOverlay() {
        _uiState.update { it.copy(isDebugOverlayVisible = !it.isDebugOverlayVisible) }
    }

    fun loadOlderMessages() {
        // No-op in simple chat (all messages streamed)
    }

    // ========================================================================
    // BACKWARD COMPATIBILITY STUBS
    // ========================================================================

    fun sendImage(context: Context, uri: Uri) {
        _uiState.update { it.copy(errorMessage = "Images are disabled in simple chat") }
    }

    fun openReportDialog(target: MessageItem? = null) {}
    fun dismissReportDialog() {}
    fun submitReport(reason: String, details: String) {}
    fun openBlockConfirmDialog() {}
    fun dismissBlockConfirmDialog() {}
    fun blockUser() {}
    fun unblockUser() {}
    fun setFullScreenImage(url: String?) {}
    fun openOfferDialog(target: MessageItem? = null) {}
    fun dismissOfferDialog() {}
    fun createOffer(price: Double, quantity: Double, date: String) {}
    fun acceptOffer(offer: MessageItem) {}
    fun declineOffer(offer: MessageItem) {}
    fun deleteConversation(convId: String, userId: String = "", onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                chatRepository.deleteConversation(convId, userId)
                _conversations.update { list -> list.filter { it.conversationId != convId } }
                onDeleted()
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "deleteConversation failed", e)
            }
        }
    }
}
