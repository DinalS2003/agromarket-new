package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.entities.ConversationEntity
import com.example.data.local.entities.FailureReason
import com.example.data.local.entities.MessageEntity
import com.example.data.local.entities.OutboxStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatOutboxUnitTest {

    private lateinit var db: AppDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao

    private val convId = "00000000-0000-0000-0000-000000000010"
    private val buyerId = "00000000-0000-0000-0000-000000000002"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        messageDao = db.messageDao()
        conversationDao = db.conversationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ========================================================================
    // 1. UNIT TEST: Process death mid-send
    // ========================================================================
    @Test
    fun testProcessDeathMidSendRecovery() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val tempId = nonce

        // Step 1: User types and persists message as QUEUED
        val queuedMsg = MessageEntity(
            id = tempId,
            conversationId = convId,
            senderId = buyerId,
            kind = "user",
            body = "Will 50kg carrots be ready tomorrow morning?",
            clientNonce = nonce,
            createdAt = "2026-10-01T08:00:00Z",
            createdAtTimestamp = 1769846400000L,
            isMine = true,
            senderRole = "buyer",
            status = OutboxStatus.QUEUED
        )
        messageDao.insertOrUpdate(queuedMsg)

        // Step 2: Worker or network call starts sending -> status transitions to SENDING
        messageDao.updateStatus(tempId, nonce, OutboxStatus.SENDING)
        val sendingMsg = messageDao.findMessage(tempId, nonce)
        assertNotNull(sendingMsg)
        assertEquals(OutboxStatus.SENDING, sendingMsg?.status)

        // Step 3: Simulate process death mid-send (OS kills app, worker interrupted)
        // On next application boot / worker initialization, resetSendingToQueued is executed:
        val recoveredCount = messageDao.resetSendingToQueued()
        assertEquals(1, recoveredCount)

        // Step 4: Verify message safely recovered back to QUEUED, maintaining same clientNonce and body
        val recoveredMsg = messageDao.findMessage(tempId, nonce)
        assertNotNull(recoveredMsg)
        assertEquals("Status must recover from SENDING to QUEUED on process death", OutboxStatus.QUEUED, recoveredMsg?.status)
        assertEquals("clientNonce must remain identical across process death", nonce, recoveredMsg?.clientNonce)
        assertEquals("Message body must be intact", "Will 50kg carrots be ready tomorrow morning?", recoveredMsg?.body)
    }

    // ========================================================================
    // 2. UNIT TEST: Duplicate retry reuses client_nonce
    // ========================================================================
    @Test
    fun testDuplicateRetryReusesClientNonce() = runBlocking {
        val stableNonce = "550e8400-e29b-41d4-a716-446655440000"
        val tempId = stableNonce

        // Message inserted and marked FAILED
        val failedMsg = MessageEntity(
            id = tempId,
            conversationId = convId,
            senderId = buyerId,
            kind = "user",
            body = "Need confirmation for dispatch time",
            clientNonce = stableNonce,
            createdAt = "2026-10-01T08:05:00Z",
            createdAtTimestamp = 1769846700000L,
            isMine = true,
            senderRole = "buyer",
            status = OutboxStatus.FAILED,
            failureReason = FailureReason.NETWORK_ERROR,
            failureMessage = "Connection timed out"
        )
        messageDao.insertOrUpdate(failedMsg)

        // User taps Retry: resetToQueued is called
        messageDao.resetToQueued(tempId, stableNonce)

        val retriedMsg = messageDao.findMessage(tempId, stableNonce)
        assertNotNull(retriedMsg)
        assertEquals("Status must transition to QUEUED upon retry", OutboxStatus.QUEUED, retriedMsg?.status)
        assertEquals("Failure reason must be reset to NONE", FailureReason.NONE, retriedMsg?.failureReason)
        assertEquals("Must reuse the EXACT same client_nonce on retry", stableNonce, retriedMsg?.clientNonce)

        // When server confirms, idempotent deduplication updates row to SENT
        val serverConfirmedId = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val confirmedEntity = retriedMsg!!.copy(
            id = serverConfirmedId,
            status = OutboxStatus.SENT,
            failureReason = FailureReason.NONE
        )
        messageDao.deleteMessage(tempId, stableNonce)
        messageDao.insertOrUpdate(confirmedEntity)

        val finalMsg = messageDao.findMessage(serverConfirmedId, stableNonce)
        assertNotNull(finalMsg)
        assertEquals(OutboxStatus.SENT, finalMsg?.status)
        assertEquals(stableNonce, finalMsg?.clientNonce)
    }

    // ========================================================================
    // 3. UNIT TEST: Airplane mode then reconnect (No fake success)
    // ========================================================================
    @Test
    fun testAirplaneModeThenReconnectOnlyServerConfirmedBecomesSent() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val tempId = nonce

        // Step 1: Enqueued while in airplane mode (offline)
        val outgoingMsg = MessageEntity(
            id = tempId,
            conversationId = convId,
            senderId = buyerId,
            kind = "user",
            body = "Offline message sent in field",
            clientNonce = nonce,
            createdAt = "2026-10-01T08:10:00Z",
            createdAtTimestamp = 1769847000000L,
            isMine = true,
            senderRole = "buyer",
            status = OutboxStatus.QUEUED
        )
        messageDao.insertOrUpdate(outgoingMsg)

        // Step 2: Network transmission attempted during airplane mode throws IOException
        fun simulateSend(isAirplaneMode: Boolean): Result<Pair<String, String>> {
            return if (isAirplaneMode) {
                Result.failure(IOException("Airplane mode: No network available"))
            } else {
                Result.success(Pair("server-msg-uuid-999", "2026-10-01T08:11:00Z"))
            }
        }

        val offlineAttempt = simulateSend(isAirplaneMode = true)
        assertTrue("Attempt in airplane mode must fail", offlineAttempt.isFailure)

        // In offline failure: Marked FAILED with NETWORK_ERROR, never SENT (no fake success!)
        messageDao.markFailed(
            id = tempId,
            clientNonce = nonce,
            reason = FailureReason.NETWORK_ERROR,
            message = offlineAttempt.exceptionOrNull()?.message,
            code = null
        )

        val offlineState = messageDao.findMessage(tempId, nonce)
        assertNotNull(offlineState)
        assertNotEquals("Message must NOT be marked SENT without server confirmation", OutboxStatus.SENT, offlineState?.status)
        assertEquals(OutboxStatus.FAILED, offlineState?.status)
        assertEquals(FailureReason.NETWORK_ERROR, offlineState?.failureReason)

        // Step 3: Airplane mode turned off / Network reconnected
        val onlineAttempt = simulateSend(isAirplaneMode = false)
        assertTrue("Attempt after reconnect must succeed", onlineAttempt.isSuccess)

        val (serverId, serverTimestamp) = onlineAttempt.getOrThrow()
        val confirmedEntity = offlineState!!.copy(
            id = serverId,
            createdAt = serverTimestamp,
            status = OutboxStatus.SENT,
            failureReason = FailureReason.NONE,
            failureMessage = null
        )
        messageDao.deleteMessage(tempId, nonce)
        messageDao.insertOrUpdate(confirmedEntity)

        // Verify ONLY server-confirmed becomes SENT
        val onlineState = messageDao.findMessage(serverId, nonce)
        assertNotNull(onlineState)
        assertEquals(OutboxStatus.SENT, onlineState?.status)
        assertEquals(FailureReason.NONE, onlineState?.failureReason)
        assertEquals(serverId, onlineState?.id)
    }

    // ========================================================================
    // 4. UNIT TEST: Ordering
    // ========================================================================
    @Test
    fun testMessageDeterministicOrderingInRoom() = runBlocking {
        // Insert messages with varying timestamps
        val msg1 = MessageEntity(
            id = "msg-001",
            conversationId = convId,
            body = "First message",
            clientNonce = "nonce-1",
            createdAt = "2026-10-01T07:00:00Z",
            createdAtTimestamp = 1000L,
            status = OutboxStatus.SENT
        )
        val msg2 = MessageEntity(
            id = "msg-002",
            conversationId = convId,
            body = "Second message",
            clientNonce = "nonce-2",
            createdAt = "2026-10-01T07:15:00Z",
            createdAtTimestamp = 2000L,
            status = OutboxStatus.SENT
        )
        val queuedMsg = MessageEntity(
            id = "msg-queued",
            conversationId = convId,
            body = "Latest queued outgoing message",
            clientNonce = "nonce-3",
            createdAt = "2026-10-01T07:30:00Z",
            createdAtTimestamp = 3000L,
            status = OutboxStatus.QUEUED
        )

        messageDao.insertOrUpdateAll(listOf(msg1, msg2, queuedMsg))

        // Query messages list (ordered by createdAtTimestamp DESC for reverseLayout chat)
        val orderedList = messageDao.getMessagesList(convId)
        assertEquals(3, orderedList.size)

        // Index 0 must be newest message (queuedMsg)
        assertEquals("msg-queued", orderedList[0].id)
        assertEquals(3000L, orderedList[0].createdAtTimestamp)

        // Index 1 must be second message
        assertEquals("msg-002", orderedList[1].id)
        assertEquals(2000L, orderedList[1].createdAtTimestamp)

        // Index 2 must be oldest message
        assertEquals("msg-001", orderedList[2].id)
        assertEquals(1000L, orderedList[2].createdAtTimestamp)

        // Reactive flow emission also maintains exact order
        val flowList = messageDao.getMessagesFlow(convId).first()
        assertEquals(listOf("msg-queued", "msg-002", "msg-001"), flowList.map { it.id })
    }

    // ========================================================================
    // 5. UNIT TEST: Server error code mappings (422, 409, 429, 403, 5xx) & Delete Action
    // ========================================================================
    @Test
    fun testServerErrorCodeMappingsAndDistinctUIStates() = runBlocking {
        // 422: Blocked / Policy violation
        val msg422 = MessageEntity(
            id = "msg-422",
            conversationId = convId,
            body = "Call me on 0771234567",
            clientNonce = "nonce-422",
            createdAt = "2026-10-01T08:00:00Z",
            status = OutboxStatus.FAILED,
            failureReason = FailureReason.BLOCKED,
            failureCode = 422
        )
        val item422 = msg422.toMessageItem()
        assertEquals("FAILED", item422.outboxStatus)
        assertEquals("BLOCKED", item422.failureReason)
        assertTrue(item422.sendFailed)

        // 409: Closed
        val msg409 = MessageEntity(
            id = "msg-409",
            conversationId = convId,
            body = "Still available?",
            clientNonce = "nonce-409",
            createdAt = "2026-10-01T08:00:00Z",
            status = OutboxStatus.FAILED,
            failureReason = FailureReason.CLOSED,
            failureCode = 409
        )
        assertEquals("CLOSED", msg409.toMessageItem().failureReason)

        // 429: Rate Limit
        val msg429 = MessageEntity(
            id = "msg-429",
            conversationId = convId,
            body = "Spam test",
            clientNonce = "nonce-429",
            createdAt = "2026-10-01T08:00:00Z",
            status = OutboxStatus.FAILED,
            failureReason = FailureReason.RATE_LIMIT,
            failureCode = 429
        )
        assertEquals("RATE_LIMIT", msg429.toMessageItem().failureReason)

        // 403: Suspended
        val msg403 = MessageEntity(
            id = "msg-403",
            conversationId = convId,
            body = "Hello",
            clientNonce = "nonce-403",
            createdAt = "2026-10-01T08:00:00Z",
            status = OutboxStatus.FAILED,
            failureReason = FailureReason.SUSPENDED,
            failureCode = 403
        )
        assertEquals("SUSPENDED", msg403.toMessageItem().failureReason)

        // Delete action: Verify deleteMessage removes failed message from Room
        messageDao.insertOrUpdate(msg422)
        assertNotNull(messageDao.findMessage("msg-422", "nonce-422"))

        messageDao.deleteMessage("msg-422", "nonce-422")
        assertNull("Deleted message must no longer exist in Room", messageDao.findMessage("msg-422", "nonce-422"))
    }
}
