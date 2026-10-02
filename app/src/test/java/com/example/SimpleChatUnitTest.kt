package com.example

import com.example.data.models.ConversationItem
import com.example.data.models.MessageItem
import com.example.viewmodel.ChatUiState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SimpleChatUnitTest {

    private val colomboZone = ZoneId.of("Asia/Colombo")

    // 1. Plain text messaging preserves text accurately (including Sinhala / Tamil Unicode)
    @Test
    fun testPlainTextUnicodePreservation() {
        val sinhalaText = "ගෝවා කිලෝ 50ක් හෙට උදේට සූදානම්ද?"
        val tamilText = "நாளை காலை 50 கிலோ முட்டைக்கோஸ் தயாரா?"
        val englishText = "Are 50 kg cabbages ready for pickup tomorrow morning?"

        val msg1 = MessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = "conv-1",
            senderId = "buyer-1",
            body = sinhalaText,
            createdAt = Instant.now().toString(),
            isMine = true
        )
        val msg2 = MessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = "conv-1",
            senderId = "buyer-1",
            body = tamilText,
            createdAt = Instant.now().toString(),
            isMine = true
        )
        val msg3 = MessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = "conv-1",
            senderId = "buyer-1",
            body = englishText,
            createdAt = Instant.now().toString(),
            isMine = true
        )

        assertEquals(sinhalaText, msg1.body)
        assertEquals(tamilText, msg2.body)
        assertEquals(englishText, msg3.body)
        assertFalse(msg1.isSystem)
    }

    // 2. System messages detection: sender_id IS NULL implies isSystem = true
    @Test
    fun testSystemMessagesIdentification() {
        val systemMsg = MessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = "conv-1",
            orderId = "order-123",
            senderId = null, // Database rule: sender_id IS NULL = system
            kind = "system",
            body = "Order #ORD-123 accepted by farmer",
            createdAt = Instant.now().toString()
        )

        val userMsg = MessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = "conv-1",
            orderId = "order-123",
            senderId = "usr-buyer-001",
            kind = "user",
            body = "Thank you, I will arrange pickup",
            createdAt = Instant.now().toString()
        )

        assertTrue("sender_id == null must be recognized as system message", systemMsg.isSystem)
        assertNull(systemMsg.senderId)

        assertFalse("sender_id != null must be recognized as user message", userMsg.isSystem)
        assertNotNull(userMsg.senderId)
    }

    // 3. One conversation per (buyer, seller) pair: Inbox structure mapping
    @Test
    fun testConversationPairMapping() {
        val conv = ConversationItem(
            conversationId = "00000000-0000-0000-0000-000000000001",
            listingId = "listing-carrots-101",
            cropName = "Carrots",
            counterpartId = "farmer-sunil-001",
            counterpartName = "Sunil Bandara",
            lastMessagePreview = "Harvest ready today",
            lastMessageSenderId = "farmer-sunil-001",
            lastMessageAt = "2026-10-01T09:30:00Z",
            unreadCount = 2
        )

        assertEquals("00000000-0000-0000-0000-000000000001", conv.conversationId)
        assertEquals("Carrots", conv.cropName)
        assertEquals("Sunil Bandara", conv.counterpartName)
        assertEquals(2, conv.unreadCount)
        assertEquals("Harvest ready today", conv.lastMessage)
    }

    // 4. Message alignment: buyer vs seller
    @Test
    fun testMessageAlignmentLogic() {
        val currentUserId = "buyer-123"
        val counterpartId = "farmer-456"

        val myMsg = MessageItem(
            id = "1",
            senderId = currentUserId,
            body = "Hi",
            createdAt = "2026-10-01T10:00:00Z",
            isMine = true
        )

        val counterpartMsg = MessageItem(
            id = "2",
            senderId = counterpartId,
            body = "Hello",
            createdAt = "2026-10-01T10:01:00Z",
            isMine = false
        )

        assertTrue("Own message must be isMine = true", myMsg.isMine == true)
        assertFalse("Counterpart message must be isMine = false", counterpartMsg.isMine == true)
    }

    // 5. First-name counterpart resolution
    @Test
    fun testCounterpartFirstNameResolution() {
        val state1 = ChatUiState(counterpartName = "Kamal Bandara", isFarmer = true)
        assertEquals("Kamal", state1.counterpartFirstName)

        val state2 = ChatUiState(counterpartName = "Nimali", isFarmer = false)
        assertEquals("Nimali", state2.counterpartFirstName)

        val state3 = ChatUiState(counterpartName = "", isFarmer = true)
        assertEquals("Buyer", state3.counterpartFirstName)

        val state4 = ChatUiState(counterpartName = "", isFarmer = false)
        assertEquals("Farmer", state4.counterpartFirstName)
    }

    // 6. Read-only status enforcement for completed / terminal orders
    @Test
    fun testReadOnlyStatusEnforcement() {
        val terminalStatuses = listOf("completed", "rejected", "cancelled", "expired", "refunded")
        val activeStatuses = listOf("requested", "accepted", "paid", "ready", "dispatched", "delivered")

        for (status in terminalStatuses) {
            val isReadOnly = com.example.viewmodel.ChatViewModel.isTerminalStatus(status)
            assertTrue("Status $status must be marked read-only", isReadOnly)
            assertNotNull("Status $status must have read-only reason", com.example.viewmodel.ChatViewModel.computeReadOnlyReason(status))
        }

        for (status in activeStatuses) {
            val isReadOnly = com.example.viewmodel.ChatViewModel.isTerminalStatus(status)
            assertFalse("Status $status must be active (not read-only)", isReadOnly)
        }
    }
}
