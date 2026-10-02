package com.example.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.example.data.models.ListingItem
import com.example.data.models.MessageItem
import com.example.data.realtime.RealtimeConnectionState
import com.example.ui.components.AgroDateTime
import com.example.ui.components.formatMessageTimestamp
import com.example.ui.components.friendlyMessageFromString
import com.example.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    orderId: String,
    orderStatus: String,
    currentUserId: String,
    isFarmerView: Boolean,
    listing: ListingItem? = null,
    viewModel: ChatViewModel,
    counterpartName: String? = null,
    onRequestOrderFromChat: ((ListingItem) -> Unit)? = null,
    onBackClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val connState by viewModel.connectionState.collectAsState()

    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle observer for resume/pause events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResumeScreen()
                Lifecycle.Event.ON_PAUSE -> viewModel.onPauseScreen()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize chat session
    LaunchedEffect(orderId, orderStatus, listing, currentUserId, isFarmerView, counterpartName) {
        viewModel.initChat(
            orderId = orderId,
            orderStatus = orderStatus,
            listing = listing,
            currentUserId = currentUserId,
            isFarmerView = isFarmerView,
            counterpartName = counterpartName
        )
    }

    val activeOrder = state.order
    val activeListing = state.listing ?: listing
    val isReadOnly = state.isReadOnly

    val orderCropName = activeOrder?.cropName ?: activeListing?.cropName ?: state.cropName.ifBlank { "Produce" }
    val effectiveStatus = activeOrder?.status ?: state.orderStatus?.ifBlank { null } ?: orderStatus
    val displayCounterpartName = state.counterpartName.ifBlank { if (state.isFarmer) "Buyer" else "Farmer" }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("order_chat_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Counterpart Avatar with Monogram Fallback
                        Box(modifier = Modifier.size(38.dp)) {
                            if (!state.counterpartAvatar.isNullOrBlank()) {
                                AsyncImage(
                                    model = com.example.ui.components.resolveImageModel(state.counterpartAvatar),
                                    contentDescription = displayCounterpartName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Surface(
                                    color = if (state.isFarmer) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = displayCounterpartName.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (state.isFarmer) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = displayCounterpartName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (state.isFarmer) "Buyer" else "Farmer",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = orderCropName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isReadOnly) "Read-Only" else "Active",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isReadOnly) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isReadOnly) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                    )
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Realtime Connection chip
                    val (dotColor, connLabel) = when (connState) {
                        RealtimeConnectionState.CONNECTED -> Pair(Color(0xFF2E7D32), "Live")
                        RealtimeConnectionState.CONNECTING -> Pair(Color(0xFFFFA000), "Connecting")
                        RealtimeConnectionState.RESYNCING -> Pair(Color(0xFF1976D2), "Syncing")
                        RealtimeConnectionState.DISCONNECTED -> Pair(Color(0xFFD32F2F), "Offline")
                    }
                    Surface(
                        color = dotColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("connection_state_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = connLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = dotColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (isReadOnly) {
                // Read-Only Status Enforcement Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .testTag("chat_read_only_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when (effectiveStatus.lowercase()) {
                                    "completed" -> Icons.Filled.CheckCircle
                                    "refunded" -> Icons.Filled.CurrencyExchange
                                    "rejected" -> Icons.Filled.Cancel
                                    "cancelled" -> Icons.Filled.Block
                                    "expired" -> Icons.Filled.TimerOff
                                    else -> Icons.Filled.Lock
                                },
                                contentDescription = null,
                                tint = if (effectiveStatus.lowercase() == "completed") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Chat is read-only (${effectiveStatus.replace('_', ' ').uppercase()})",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = state.readOnlyReason ?: "This conversation is closed. Messaging is preserved for reference.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Active Message Input Bar with Quick Replies
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .testTag("chat_active_input_bar")
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        // Quick Reply Chips
                        QuickReplyChipsRow(
                            isFarmer = state.isFarmer,
                            onQuickReplySelected = { viewModel.sendQuickReply(it) }
                        )

                        // General Error Notice
                        AnimatedVisibility(
                            visible = !state.errorMessage.isNullOrBlank(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = friendlyMessageFromString(state.errorMessage ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.clearError() },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        // Text Input Field & Send Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = state.inputText,
                                onValueChange = viewModel::onInputTextChange,
                                placeholder = {
                                    Text("Type a message...")
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chat_input_field")
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { viewModel.sendMessage() },
                                enabled = !state.isSending && state.inputText.isNotBlank(),
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(
                                        if (state.inputText.isNotBlank() && !state.isSending) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape
                                    )
                                    .testTag("chat_send_button")
                            ) {
                                if (state.isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = if (state.inputText.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isLoadingInitial) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Text(
                            text = "No messages yet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (state.isFarmer) "Direct conversation with buyer for $orderCropName." else "Ask farmer about crop readiness, harvesting or pickup details.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat_messages_list")
                ) {
                    itemsIndexed(state.messages, key = { _, item -> item.id }) { index, message ->
                        val isSystem = message.isSystem || message.senderId == null

                        // Date header separator (detect day boundary)
                        val showDateSeparator = shouldShowDateHeader(index, state.messages)
                        if (showDateSeparator) {
                            ChatDateHeader(dateIso = message.createdAt)
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        if (isSystem) {
                            // Centered line system message
                            CenteredSystemMessage(message = message)
                        } else {
                            // Plain text speech bubble
                            SimpleMessageBubble(
                                message = message,
                                isMine = message.isMine == true,
                                senderDisplayName = displayCounterpartName
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Centered line order-status system message
 */
@Composable
fun CenteredSystemMessage(message: MessageItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp)
            .testTag("system_message_${message.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * Simple text message bubble (no images, no ticks, no offers)
 */
@Composable
fun SimpleMessageBubble(
    message: MessageItem,
    isMine: Boolean,
    senderDisplayName: String = ""
) {
    val bubbleColor = if (isMine) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = if (isMine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)

    val shape = if (isMine) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isMine) 48.dp else 0.dp,
                end = if (isMine) 0.dp else 48.dp
            ),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            tonalElevation = 1.dp,
            modifier = Modifier.testTag("message_bubble_${message.id}")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (!isMine && senderDisplayName.isNotBlank()) {
                    Text(
                        text = senderDisplayName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = textColor
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = AgroDateTime.formatChatBubbleTimestamp(message.createdAt),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = timeColor
                    )
                }
            }
        }
    }
}

/**
 * Date Header separator (e.g. "Today", "Yesterday", or "2 October 2026")
 */
@Composable
fun ChatDateHeader(dateIso: String) {
    val label = AgroDateTime.formatDateHeader(dateIso)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

private fun shouldShowDateHeader(index: Int, messages: List<MessageItem>): Boolean {
    if (index == messages.lastIndex) return true
    val current = messages.getOrNull(index) ?: return false
    val next = messages.getOrNull(index + 1) ?: return false
    return !AgroDateTime.isSameDay(current.createdAt, next.createdAt)
}

@Composable
fun QuickReplyChipsRow(
    isFarmer: Boolean,
    onQuickReplySelected: (String) -> Unit
) {
    val replies = if (isFarmer) {
        listOf(
            "Harvest is fresh and ready!",
            "Pickup is available today.",
            "Can fulfill your quantity.",
            "What time will you arrive?"
        )
    } else {
        listOf(
            "Is this still available?",
            "When will this be harvested?",
            "Can I pick up today?",
            "What is the exact landmark?"
        )
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(replies) { reply ->
            SuggestionChip(
                onClick = { onQuickReplySelected(reply) },
                label = { Text(reply, style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(16.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = null
            )
        }
    }
}
