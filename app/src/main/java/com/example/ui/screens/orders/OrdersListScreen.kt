package com.example.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.models.OrderItem
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersListScreen(
    orders: List<OrderItem>,
    isFarmer: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOrderClick: (OrderItem) -> Unit,
    onPayNowClick: ((OrderItem) -> Unit)? = null,
    onCancelClick: ((OrderItem) -> Unit)? = null
) {
    var selectedFilter by remember { mutableStateOf("All") }
    var orderToCancel by remember { mutableStateOf<OrderItem?>(null) }

    val activeStatuses = setOf("requested", "accepted", "paid", "ready", "dispatched")
    val completedStatuses = setOf("delivered", "completed")
    val terminalStatuses = setOf("cancelled", "rejected", "expired", "refunded", "disputed")

    val activeCount = remember(orders) { orders.count { it.status.lowercase() in activeStatuses } }
    val completedCount = remember(orders) { orders.count { it.status.lowercase() in completedStatuses } }

    val filteredOrders = remember(orders, selectedFilter) {
        when (selectedFilter) {
            "Active" -> orders.filter { it.status.lowercase() in activeStatuses }
            "Completed" -> orders.filter { it.status.lowercase() in completedStatuses }
            "Cancelled" -> orders.filter { it.status.lowercase() in terminalStatuses }
            else -> orders
        }
    }

    if (orderToCancel != null) {
        AlertDialog(
            onDismissRequest = { orderToCancel = null },
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Cancel Order ${orderToCancel!!.orderNumber}?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isFarmer) "Are you sure you want to cancel this order? This will mark the order cancelled and notify the buyer."
                    else "Are you sure you want to cancel this order? If you have not paid, no payment will be taken."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ord = orderToCancel
                        orderToCancel = null
                        if (ord != null) {
                            onCancelClick?.invoke(ord)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_cancel_order_dialog_button")
                ) {
                    Text("Yes, Cancel Order")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { orderToCancel = null }) {
                    Text("Go Back")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter Tabs (All / Active / Completed / Cancelled)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == "All",
                        onClick = { selectedFilter = "All" },
                        leadingIcon = {
                            Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("All (${orders.size})") },
                        shape = AgroShapes.pill,
                        modifier = Modifier.testTag("filter_orders_all")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "Active",
                        onClick = { selectedFilter = "Active" },
                        leadingIcon = {
                            Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Active ($activeCount)") },
                        shape = AgroShapes.pill,
                        modifier = Modifier.testTag("filter_orders_active")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "Completed",
                        onClick = { selectedFilter = "Completed" },
                        leadingIcon = {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Completed ($completedCount)") },
                        shape = AgroShapes.pill,
                        modifier = Modifier.testTag("filter_orders_completed")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "Cancelled",
                        onClick = { selectedFilter = "Cancelled" },
                        leadingIcon = {
                            Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Cancelled") },
                        shape = AgroShapes.pill,
                        modifier = Modifier.testTag("filter_orders_cancelled")
                    )
                }
            }

            if (isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(4) {
                        ShimmerListItemSkeleton()
                    }
                }
            } else if (filteredOrders.isEmpty()) {
                EmptyState(
                    icon = if (isFarmer) Icons.Filled.LocalShipping else Icons.Filled.ShoppingBag,
                    title = if (isFarmer) "No Orders Received" else "No Orders Placed",
                    message = if (isFarmer) {
                        "Incoming purchase orders from buyers will appear here for your review and fulfillment."
                    } else {
                        "Browse fresh harvest from Sri Lankan farmers in the Marketplace and place an order."
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("orders_list")
                ) {
                    items(filteredOrders, key = { it.id }) { order ->
                        OrderSummaryCard(
                            order = order,
                            isFarmer = isFarmer,
                            onClick = { onOrderClick(order) },
                            onPayNowClick = if (!isFarmer && (order.status == "requested" || order.status == "accepted")) {
                                { onPayNowClick?.invoke(order) }
                            } else null,
                            onCancelOrderClick = {
                                orderToCancel = order
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OrderSummaryCard(
    order: OrderItem,
    isFarmer: Boolean,
    onClick: () -> Unit,
    onPayNowClick: (() -> Unit)? = null,
    onCancelOrderClick: (() -> Unit)? = null
) {
    val otherPartyName = if (isFarmer) {
        order.buyerName?.ifBlank { "Buyer" } ?: "Buyer"
    } else {
        order.farmerName?.ifBlank { "Farmer" } ?: "Farmer"
    }

    val placedTime = formatOrderColomboDateTime(order.requestedAt ?: order.requestedDate)
    val acceptedTime = formatOrderColomboDateTime(order.acceptedAt)
    val paidTime = formatOrderColomboDateTime(order.paidAt)
    val isCancelled = order.status in listOf("cancelled", "rejected", "expired")
    val cancelledByWho = when (order.endedBy?.lowercase()?.trim()) {
        "buyer" -> "Buyer"
        "farmer" -> "Farmer"
        "system" -> "Auto-expired"
        "admin" -> "Admin"
        else -> if (order.status == "rejected") "Farmer" else "Auto-expired"
    }
    val endedTime = formatOrderColomboDateTime(order.endedAt)
    val hasRefund = order.refundStatus in listOf("required", "pending") || order.refundAmount > 0

    AgroCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("order_card_${order.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top Row: Crop image + Crop name + Order # + Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Photo
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (!order.photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = resolveImageModel(order.photoUrl),
                            contentDescription = order.cropName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Eco,
                                contentDescription = null,
                                tint = AgroGreenPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = order.cropName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        StatusChip(status = order.status)
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (isFarmer) "Buyer: $otherPartyName" else "Farmer: $otherPartyName",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Order: ${order.orderNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(8.dp))

            // Pricing & Quantity & Harvest date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${order.quantityKg} kg @ ${formatLkr(order.pricePerKg)}/kg",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (!order.harvestDate.isNullOrBlank()) {
                        Text(
                            text = "Harvest: ${order.harvestDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isFarmer) "Farmer Payout" else "Total Amount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatLkr(if (isFarmer) order.farmerPayoutAmount else order.totalAmount),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = AgroGreenPrimary
                        )
                    )
                }
            }

            // Timestamps: Placed date/time, accepted, paid, cancelled
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCancelled) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (placedTime != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AccessTime,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Placed: $placedTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (acceptedTime != null && !isCancelled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = AgroGreenPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Accepted: $acceptedTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (paidTime != null && !isCancelled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Payment,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = AgroGreenPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Paid: $paidTime",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AgroGreenPrimary
                        )
                    }
                }

                // First-class Cancelled status details
                if (isCancelled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Cancelled by $cancelledByWho${if (endedTime != null) " at $endedTime" else ""}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!order.endReason.isNullOrBlank()) {
                        Text(
                            text = "Reason: ${order.endReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (hasRefund) {
                        Surface(
                            color = AgroGreenContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Refund in progress (${formatLkr(if (order.refundAmount > 0) order.refundAmount else order.totalAmount)})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = AgroGreenPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Quick actions for awaiting payment orders
            if (!isCancelled && !isFarmer) {
                if (order.status == "accepted") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onCancelOrderClick?.invoke() },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("list_cancel_button_${order.id}"),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Cancel order", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { onPayNowClick?.invoke() },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(40.dp)
                                .testTag("list_pay_now_button_${order.id}"),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Filled.Payment, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pay now", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else if (order.status == "requested") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Awaiting farmer review (12h)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AgroGreenPrimary
                        )

                        OutlinedButton(
                            onClick = { onCancelOrderClick?.invoke() },
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("list_cancel_button_${order.id}"),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("Cancel request", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
