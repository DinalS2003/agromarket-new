package com.example.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.models.NotificationItem
import com.example.data.repository.AgroMarketRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    userId: String,
    repository: AgroMarketRepository,
    onOrderClick: (String) -> Unit
) {
    var notifications by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            isLoading = true
            val res = repository.getNotifications(userId)
            res.onSuccess { notifications = it }
            isLoading = false
        }
    }

    LaunchedEffect(userId) {
        load()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(4) {
                    ShimmerListItemSkeleton()
                }
            }
        } else if (notifications.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.NotificationsNone,
                title = "No Notifications",
                message = "You're all caught up with your orders and updates.",
                modifier = Modifier.padding(padding).padding(12.dp)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("notifications_list")
            ) {
                items(notifications, key = { it.id }) { notif ->
                    val isUnread = notif.readAt == null
                    val icon = when {
                        notif.type.contains("payment") -> Icons.Filled.Payment
                        notif.type.contains("order") -> Icons.Filled.ShoppingBag
                        notif.type.contains("alert") -> Icons.Filled.Warning
                        else -> Icons.Filled.Notifications
                    }

                    AgroCard(
                        onClick = {
                            if (isUnread) {
                                scope.launch { repository.markNotificationRead(notif.id) }
                            }
                            notif.orderId?.let { onOrderClick(it) }
                        },
                        containerColor = if (isUnread) AgroGreenContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(AgroShapes.medium)
                                    .background(if (isUnread) AgroGreenContainer else MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isUnread) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(AgroSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = notif.title,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold
                                        )
                                    )
                                    if (isUnread) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(AgroGreenPrimary)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(AgroSpacing.xxs))
                                Text(
                                    text = notif.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(AgroSpacing.xs))
                                Text(
                                    text = notif.createdAt.take(16).replace("T", " "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

