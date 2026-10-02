package com.example.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.models.ConversationItem
import com.example.ui.components.EmptyStateView
import com.example.ui.components.OrderStatusChip
import com.example.ui.components.formatMessageTimestamp
import com.example.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    userId: String,
    isFarmer: Boolean,
    chatViewModel: ChatViewModel,
    onConversationClick: (ConversationItem) -> Unit,
    onBackClick: () -> Unit
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val isLoading by chatViewModel.isLoadingConversations.collectAsState()

    var selectedFilter by remember { mutableStateOf("All") }
    var conversationToDelete by remember { mutableStateOf<ConversationItem?>(null) }

    LaunchedEffect(Unit) {
        chatViewModel.loadConversations(userId, isFarmer)
    }

    LaunchedEffect(userId, isFarmer) {
        chatViewModel.loadConversations(userId, isFarmer)
    }

    // Filter and strictly sort with most recent messages at the top
    val filteredList = remember(conversations, selectedFilter) {
        conversations
            .filter { conv ->
                when (selectedFilter) {
                    "Unread" -> conv.unreadCount > 0
                    "With Orders" -> conv.hasLinkedOrder
                    else -> true
                }
            }
            .sortedWith(compareByDescending<ConversationItem> { it.computedTimestamp }.thenByDescending { it.lastMessageAt })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val unreadCount = remember(conversations) { conversations.count { it.unreadCount > 0 } }
            val withOrdersCount = remember(conversations) { conversations.count { it.hasLinkedOrder } }

            // Filter Tabs
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == "All",
                        onClick = { selectedFilter = "All" },
                        leadingIcon = {
                            Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("All (${conversations.size})") },
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("filter_all_chats")
                    )
                }
                if (unreadCount > 0) {
                    item {
                        FilterChip(
                            selected = selectedFilter == "Unread",
                            onClick = { selectedFilter = "Unread" },
                            leadingIcon = {
                                Icon(Icons.Filled.MarkEmailUnread, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text("Unread ($unreadCount)") },
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("filter_unread_chats")
                        )
                    }
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "With Orders",
                        onClick = { selectedFilter = "With Orders" },
                        leadingIcon = {
                            Icon(Icons.Filled.ShoppingBag, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("With Orders ($withOrdersCount)") },
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("filter_with_orders")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isLoading && conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredList.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = if (selectedFilter == "Unread") "No Unread Messages" else "No Conversations Yet",
                    message = if (isFarmer) {
                        "When buyers start a conversation on your crop listings, messages will appear here."
                    } else {
                        "Explore the marketplace and start a chat on any crop listing to ask about harvest readiness or delivery."
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("conversations_list")
                ) {
                    items(filteredList, key = { it.conversationId }) { conv ->
                        ConversationCard(
                            conversation = conv,
                            isFarmer = isFarmer,
                            onClick = { onConversationClick(conv) },
                            onLongClick = { conversationToDelete = conv }
                        )
                    }
                }
            }
        }

        if (conversationToDelete != null) {
            val conv = conversationToDelete!!
            val counterpartDisplayName = conv.counterpartName.ifBlank { if (isFarmer) "Buyer" else "Farmer" }
            AlertDialog(
                onDismissRequest = { conversationToDelete = null },
                title = { Text("Delete Conversation", fontWeight = FontWeight.Bold) },
                text = {
                    Text("Delete conversation with $counterpartDisplayName? This will hide the chat from your inbox.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = conversationToDelete
                            conversationToDelete = null
                            if (target != null) {
                                chatViewModel.deleteConversation(target.conversationId, userId)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("confirm_delete_conversation_btn")
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { conversationToDelete = null },
                        modifier = Modifier.testTag("cancel_delete_conversation_btn")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationCard(
    conversation: ConversationItem,
    isFarmer: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("conversation_card_${conversation.conversationId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Thumbnail / Avatar
            val counterpartDisplayName = conversation.counterpartName.ifBlank { if (isFarmer) "Buyer" else "Farmer" }
            val thumbnail = conversation.listingThumbnail ?: conversation.counterpartAvatar
            if (!thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = com.example.ui.components.resolveImageModel(thumbnail),
                    contentDescription = conversation.cropName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = counterpartDisplayName.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. Info Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Line 1: Counterpart Name + Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = counterpartDisplayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    val timeFormatted = formatMessageTimestamp(conversation.lastMessageAt)
                    if (timeFormatted.isNotBlank()) {
                        Text(
                            text = timeFormatted,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Line 2: Crop pill & Order status chip if linked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (conversation.cropName.isNotBlank() && conversation.cropName != "Produce") {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = conversation.cropName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (conversation.orderStatus != null) {
                        OrderStatusChip(status = conversation.orderStatus)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Line 3: Message preview + Unread Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val isUnread = conversation.unreadCount > 0
                    Text(
                        text = conversation.lastMessagePreview.ifBlank { "No messages yet" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isUnread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(
                                text = "${conversation.unreadCount}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
