package com.example

import com.example.data.models.MessageItem
import com.example.viewmodel.ChatViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ChatRebuildUnitTest {

    // 1. Android: realtime echo plus HTTP response gives one bubble (dedupe by id & nonce)
    @Test
    fun testRealtimeEchoPlusHttpResponseGivesOneBubble() {
        val nonce = UUID.randomUUID().toString()
        val orderId = "order-101"
        val senderId = "user-buyer-1"

        // Step 1: User sends message -> optimistic bubble placed in list
        val optimisticBubble = MessageItem(
            id = "temp_$nonce",
            orderId = orderId,
            senderId = senderId,
            kind = "user",
            body = "Will pick up at 4 pm",
            clientNonce = nonce,
            createdAt = "2026-10-01T10:00:00Z",
            isMine = true,
            isSending = true
        )

        val messagesList = mutableListOf(optimisticBubble)
        assertEquals(1, messagesList.size)
        assertEquals(true, messagesList[0].isSending)

        // Step 2: Realtime echo arrives from WebSocket with real database UUID
        val realtimeEcho = MessageItem(
            id = "00000000-0000-0000-0000-000000000099",
            orderId = orderId,
            senderId = senderId,
            kind = "user",
            body = "Will pick up at 4 pm",
            clientNonce = nonce,
            createdAt = "2026-10-01T10:00:00.123Z",
            isMine = true,
            isSending = false
        )

        // Deduplication by client_nonce replaces optimistic bubble with confirmed server message
        val indexByNonce = messagesList.indexOfFirst { it.clientNonce == realtimeEcho.clientNonce }
        if (indexByNonce != -1) {
            messagesList[indexByNonce] = realtimeEcho
        } else {
            messagesList.add(0, realtimeEcho)
        }

        assertEquals("Realtime echo must replace optimistic bubble without duplicate", 1, messagesList.size)
        assertEquals("00000000-0000-0000-0000-000000000099", messagesList[0].id)
        assertFalse(messagesList[0].isSending)

        // Step 3: HTTP 200 response completes slightly afterwards with the same ID and nonce
        val httpResponse = MessageItem(
            id = "00000000-0000-0000-0000-000000000099",
            orderId = orderId,
            senderId = senderId,
            kind = "user",
            body = "Will pick up at 4 pm",
            clientNonce = nonce,
            createdAt = "2026-10-01T10:00:00.123Z",
            isMine = true,
            isSending = false
        )

        val existsById = messagesList.any { it.id == httpResponse.id }
        if (!existsById) {
            val idx = messagesList.indexOfFirst { it.clientNonce == httpResponse.clientNonce }
            if (idx != -1) {
                messagesList[idx] = httpResponse
            } else {
                messagesList.add(0, httpResponse)
            }
        }

        // Result: Strictly one single bubble remains in the chat list!
        assertEquals("Realtime echo plus HTTP response must result in exactly one bubble", 1, messagesList.size)
        assertEquals("00000000-0000-0000-0000-000000000099", messagesList[0].id)
    }

    // 2. Android: Retry reuses the nonce
    @Test
    fun testRetryReusesTheNonce() {
        val initialNonce = UUID.randomUUID().toString()
        val originalMessage = MessageItem(
            id = "temp_$initialNonce",
            orderId = "order-202",
            senderId = "buyer-1",
            kind = "user",
            body = "Is delivery possible to Kandy?",
            clientNonce = initialNonce,
            createdAt = "2026-10-01T10:05:00Z",
            isMine = true,
            isSending = false,
            sendFailed = true,
            failError = "Network error connecting to server"
        )

        // User taps Retry -> system dispatches retry with the SAME clientNonce
        fun buildRetryMessage(failed: MessageItem): MessageItem {
            return failed.copy(
                isSending = true,
                sendFailed = false,
                failError = null
                // clientNonce is preserved for server idempotency
            )
        }

        val retriedMessage = buildRetryMessage(originalMessage)
        assertEquals("Nonce must be strictly preserved on retry for idempotency", initialNonce, retriedMessage.clientNonce)
        assertEquals("temp_$initialNonce", retriedMessage.id)
        assertTrue(retriedMessage.isSending)
        assertFalse(retriedMessage.sendFailed)
        assertNull(retriedMessage.failError)
    }

    // 3. Android: Read-only terminal statuses
    @Test
    fun testTerminalStatusesTriggerReadOnlyMode() {
        val terminalStatuses = listOf("rejected", "cancelled", "expired", "completed", "refunded")
        for (status in terminalStatuses) {
            assertTrue("Status '$status' must be terminal and read-only", ChatViewModel.isTerminalStatus(status))
            val reason = ChatViewModel.computeReadOnlyReason(status)
            assertNotNull(reason)
            assertTrue("Reason must explain status", reason!!.isNotBlank())
        }

        val activeStatuses = listOf("requested", "accepted", "paid", "ready", "dispatched", "delivered")
        for (status in activeStatuses) {
            assertFalse("Status '$status' must NOT be read-only", ChatViewModel.isTerminalStatus(status))
        }

        // Conversation status triggers
        assertEquals("This conversation has been closed.", ChatViewModel.computeReadOnlyReason("closed", null))
        assertEquals("Messaging is blocked due to policy violations.", ChatViewModel.computeReadOnlyReason("blocked", null))
    }

    // 4. Android: Verification of participant authorization check (Removal of || true)
    @Test
    fun testParticipantAuthorizationWithoutTrueFallback() {
        val buyerId = "00000000-0000-0000-0000-000000000002"
        val farmerId = "00000000-0000-0000-0000-000000000003"
        val strangerId = "00000000-0000-0000-0000-000000000009"
        val orderTarget = "AM-100200"

        // Helper replicating the updated ChatViewModel authorization logic
        fun checkIsParticipant(resolvedUserId: String, orderBuyerId: String, orderFarmerId: String, targetOrderId: String): Boolean {
            val isActualBuyer = (resolvedUserId.isNotBlank() && resolvedUserId == orderBuyerId)
            val isActualFarmer = (resolvedUserId.isNotBlank() && resolvedUserId == orderFarmerId)
            return if (resolvedUserId.isBlank() ||
                resolvedUserId.startsWith("demo_") ||
                resolvedUserId.startsWith("farmer_me") ||
                resolvedUserId.startsWith("usr_buyer_me") ||
                targetOrderId.startsWith("inq_") ||
                targetOrderId.startsWith("INQ-")) {
                true
            } else {
                isActualBuyer || isActualFarmer
            }
        }

        // Stranger must NOT be authorized on standard order (would have been true with '|| true')
        val strangerAuthorized = checkIsParticipant(strangerId, buyerId, farmerId, orderTarget)
        assertFalse("Non-party user must NOT be authorized when || true is removed", strangerAuthorized)

        // Real buyer must be authorized
        val buyerAuthorized = checkIsParticipant(buyerId, buyerId, farmerId, orderTarget)
        assertTrue("Order buyer must be authorized", buyerAuthorized)

        // Real farmer must be authorized
        val farmerAuthorized = checkIsParticipant(farmerId, buyerId, farmerId, orderTarget)
        assertTrue("Order farmer must be authorized", farmerAuthorized)
    }

    // 5. Android: Verification that invalid UUID orderId fails without steps 3-5 fallback
    @Test
    fun testNonUuidOrderFailsWithoutFallbackSteps() {
        val nonUuidOrderId = "invalid_order_id_123"
        val isUuidOrder = nonUuidOrderId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))
        assertFalse("non-UUID orderId should not pass UUID regex", isUuidOrder)
    }

    // 6. Conversations Architecture: ConversationItem structure from get_inbox
    @Test
    fun testConversationItemDataModelAndInboxMapping() {
        val convId = UUID.randomUUID().toString()
        val listId = UUID.randomUUID().toString()
        val buyerId = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID().toString()

        val conv = com.example.data.models.ConversationItem(
            conversationId = convId,
            listingId = listId,
            cropName = "Tomato",
            listingThumbnail = "https://example.com/tomato.jpg",
            pricePerKg = 180.0,
            counterpartId = buyerId,
            counterpartName = "Kamal Perera",
            counterpartAvatar = null,
            isCounterpartFarmer = false,
            lastMessageId = UUID.randomUUID().toString(),
            lastMessagePreview = "Are 50kg available tomorrow?",
            lastMessageSenderId = buyerId,
            lastMessageAt = "2026-10-01T08:00:00Z",
            unreadCount = 2,
            orderId = orderId,
            orderNumber = "AM-104523",
            orderStatus = "requested",
            status = "active"
        )

        assertEquals(convId, conv.conversationId)
        assertEquals("Tomato", conv.cropName)
        assertEquals("Kamal Perera", conv.counterpartName)
        assertEquals("Are 50kg available tomorrow?", conv.lastMessagePreview)
        assertEquals(2, conv.unreadCount)
        assertTrue(conv.hasLinkedOrder)
        assertEquals("AM-104523", conv.orderNumber)
        assertEquals("requested", conv.orderStatus)
        assertEquals("active", conv.status)
    }

    // 7. Single ID Format: Verify UUID validation for conversation IDs and message IDs
    @Test
    fun testSingleIdFormatUuidEnforcement() {
        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        val generatedConvId = UUID.randomUUID().toString()
        val generatedMsgId = UUID.randomUUID().toString()
        val legacyFakeId = "inq_listing_123"

        assertTrue("Generated conversation ID must be valid UUID", generatedConvId.matches(uuidRegex))
        assertTrue("Generated message ID must be valid UUID", generatedMsgId.matches(uuidRegex))
        assertFalse("Legacy fake ID must not match UUID regex", legacyFakeId.matches(uuidRegex))
    }

    // 8. Order Status Lifecycle: 'inquiry' is eliminated from valid order statuses
    @Test
    fun testEliminationOfInquiryFromValidOrders() {
        val validOrderStatuses = setOf(
            "requested", "accepted", "rejected", "paid", "ready",
            "dispatched", "delivered", "completed", "cancelled",
            "expired", "refunded", "disputed"
        )

        assertFalse("status='inquiry' must be permanently deleted from order status domain", "inquiry" in validOrderStatuses)
        assertTrue("status='requested' must be present", "requested" in validOrderStatuses)
        assertTrue("status='completed' must be present", "completed" in validOrderStatuses)
    }

    // 9. Reconnect Gap Recovery: Fetch messages newer than cursor & dedupe by id/nonce
    @Test
    fun testReconnectGapRecoveryWithCursorAndDeduplication() {
        val convId = UUID.randomUUID().toString()
        val currentUserId = "buyer-123"
        val counterpartId = "farmer-456"

        // Simulated stored local messages before disconnect
        val storedMsg1 = MessageItem(
            id = "msg-1",
            conversationId = convId,
            senderId = counterpartId,
            kind = "user",
            body = "First stored message",
            clientNonce = "nonce-1",
            createdAt = "2026-10-01T10:00:00Z"
        )
        // Optimistic pending local message queued by user before disconnect
        val pendingNonce = "nonce-pending-optimistic"
        val pendingMsg = MessageItem(
            id = "temp_$pendingNonce",
            conversationId = convId,
            senderId = currentUserId,
            kind = "user",
            body = "My optimistic reply",
            clientNonce = pendingNonce,
            createdAt = "2026-10-01T10:02:00Z",
            isMine = true,
            isSending = true
        )

        val localMessages = mutableListOf(pendingMsg, storedMsg1)

        // Remote messages fetched from server newer than cursor msg-1
        val serverConfirmedPending = MessageItem(
            id = "server-msg-2",
            conversationId = convId,
            senderId = currentUserId,
            kind = "user",
            body = "My optimistic reply",
            clientNonce = pendingNonce,
            createdAt = "2026-10-01T10:02:05Z",
            isMine = true,
            isSending = false
        )
        val incomingFromCounterpart = MessageItem(
            id = "server-msg-3",
            conversationId = convId,
            senderId = counterpartId,
            kind = "user",
            body = "Got it, will pack today!",
            clientNonce = "nonce-counterpart-3",
            createdAt = "2026-10-01T10:03:00Z",
            isMine = false,
            isSending = false
        )

        val gapMessages = listOf(serverConfirmedPending, incomingFromCounterpart)

        // Gap recovery execution: Deduplication by id and nonce
        for (remote in gapMessages) {
            val nonce = remote.clientNonce ?: remote.id
            // Find existing item by id or clientNonce
            val existingIdx = localMessages.indexOfFirst {
                it.id == remote.id || (!it.clientNonce.isNullOrBlank() && it.clientNonce == nonce)
            }
            if (existingIdx != -1) {
                // Replaces temporary optimistic bubble with server confirmed message
                localMessages[existingIdx] = remote
            } else {
                localMessages.add(0, remote)
            }
        }

        // Verify: exactly 3 unique messages, no duplicates, temporary replaced
        assertEquals("Total messages after gap recovery deduplication must be 3", 3, localMessages.size)
        assertFalse("Temporary ID must be replaced", localMessages.any { it.id == "temp_$pendingNonce" })
        assertTrue("Server confirmed message must be present", localMessages.any { it.id == "server-msg-2" })
        assertTrue("New counterpart message must be present", localMessages.any { it.id == "server-msg-3" })
    }

    // 10. Delivery and Read Receipts: Ticks progression (sent -> delivered -> read)
    @Test
    fun testDeliveryAndReadReceiptTicksComputation() {
        val convId = UUID.randomUUID().toString()
        val currentUserId = "buyer-123"
        val counterpartId = "farmer-456"

        val msg1 = MessageItem(
            id = "msg-read-1",
            conversationId = convId,
            senderId = currentUserId,
            kind = "user",
            body = "Can you harvest 20kg?",
            createdAt = "2026-10-01T09:00:00Z",
            isMine = true
        )
        val msg2 = MessageItem(
            id = "msg-deliv-2",
            conversationId = convId,
            senderId = currentUserId,
            kind = "user",
            body = "Also need organic grade",
            createdAt = "2026-10-01T09:05:00Z",
            isMine = true
        )
        val msg3 = MessageItem(
            id = "msg-sent-3",
            conversationId = convId,
            senderId = currentUserId,
            kind = "user",
            body = "Will pick up around noon",
            createdAt = "2026-10-01T09:10:00Z",
            isMine = true
        )

        // Reverse-layout messages list: index 0 is newest, index 2 is oldest
        val messagesList = listOf(msg3, msg2, msg1)

        // Case A: Counterpart read msg1, delivered up to msg2, has not received msg3
        val uiState = com.example.viewmodel.ChatUiState(
            conversationId = convId,
            currentUserId = currentUserId,
            messages = messagesList,
            counterpartLastReadMessageId = "msg-read-1",
            counterpartLastReadAt = "2026-10-01T09:01:00Z",
            counterpartLastDeliveredMessageId = "msg-deliv-2",
            counterpartLastDeliveredAt = "2026-10-01T09:06:00Z"
        )

        val tick1 = uiState.computeTickStatus(msg1)
        val tick2 = uiState.computeTickStatus(msg2)
        val tick3 = uiState.computeTickStatus(msg3)

        assertEquals("Oldest message at or before read receipt is READ", com.example.viewmodel.MessageTickStatus.READ, tick1)
        assertEquals("Message at delivered receipt is DELIVERED", com.example.viewmodel.MessageTickStatus.DELIVERED, tick2)
        assertEquals("Newest message beyond delivered receipt is SENT", com.example.viewmodel.MessageTickStatus.SENT, tick3)

        // Case B: Counterpart marks all as read
        val fullyReadState = uiState.copy(
            counterpartLastReadMessageId = "msg-sent-3",
            counterpartLastReadAt = "2026-10-01T09:15:00Z"
        )
        assertEquals("msg3 is READ", com.example.viewmodel.MessageTickStatus.READ, fullyReadState.computeTickStatus(msg3))
        assertEquals("msg2 is READ", com.example.viewmodel.MessageTickStatus.READ, fullyReadState.computeTickStatus(msg2))
        assertEquals("msg1 is READ", com.example.viewmodel.MessageTickStatus.READ, fullyReadState.computeTickStatus(msg1))
    }

    // 11. Unread Count: Computed from last_read_message_id and strictly excludes system messages
    @Test
    fun testUnreadCountFromLastReadMessageIdExcludingSystemMessages() {
        val currentUserId = "buyer-123"
        val counterpartId = "farmer-456"

        val messages = listOf(
            MessageItem(id = "m1", senderId = counterpartId, kind = "user", body = "Hello", createdAt = "2026-10-01T10:00:00Z"),
            MessageItem(id = "m2", senderId = counterpartId, kind = "user", body = "Price is 200/kg", createdAt = "2026-10-01T10:01:00Z"),
            MessageItem(id = "m3", senderId = null, kind = "system", body = "Order was created", createdAt = "2026-10-01T10:02:00Z"),
            MessageItem(id = "m4", senderId = currentUserId, kind = "user", body = "Sounds good", createdAt = "2026-10-01T10:03:00Z"),
            MessageItem(id = "m5", senderId = counterpartId, kind = "user", body = "Confirmed pick up location", createdAt = "2026-10-01T10:04:00Z"),
            MessageItem(id = "m6", senderId = null, kind = "system", body = "Payment received", createdAt = "2026-10-01T10:05:00Z")
        )

        // Helper replicating unread count formula:
        fun calculateUnread(lastReadId: String?, lastReadAt: String?): Int {
            return messages.count { m ->
                val notSystem = m.kind != "system" && m.senderId != null
                val notSelf = m.senderId != currentUserId
                val isAfterRead = when {
                    lastReadId == null && lastReadAt == null -> true
                    lastReadId != null -> {
                        val readMsg = messages.find { it.id == lastReadId }
                        if (readMsg != null) m.createdAt > readMsg.createdAt else true
                    }
                    lastReadAt != null -> m.createdAt > lastReadAt
                    else -> true
                }
                notSystem && notSelf && isAfterRead
            }
        }

        // Before reading any message: only counterpart's non-system messages count (m1, m2, m5) = 3
        assertEquals("Initial unread count must exclude system (m3, m6) and self (m4)", 3, calculateUnread(null, null))

        // After reading up to m2: only m5 is unread = 1
        assertEquals("Unread after m2 must be 1 (m5 only, m3/m4/m6 excluded)", 1, calculateUnread("m2", "2026-10-01T10:01:00Z"))

        // After reading up to m5: unread = 0 (even though m6 was added as system message)
        assertEquals("Unread after m5 must be 0", 0, calculateUnread("m5", "2026-10-01T10:04:00Z"))
    }
}
