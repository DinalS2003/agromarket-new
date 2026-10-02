package com.example.ui.screens.orders

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.data.models.OrderItem
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.OrderViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    currentUserId: String,
    viewModel: OrderViewModel,
    onBackClick: () -> Unit,
    onChatClick: (String, String) -> Unit,
    onLaunchPayHere: (com.example.data.models.PayHerePaymentRequest) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.detailState.collectAsState()

    LaunchedEffect(orderId) {
        if (orderId.isNotBlank()) {
            viewModel.loadOrderDetail(orderId)
        }
    }

    LaunchedEffect(state.payHereRequest) {
        state.payHereRequest?.let { req ->
            onLaunchPayHere(req)
            viewModel.onPayHereProcessed()
        }
    }

    val order = state.order
    if (order == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Order Details", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (state.isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AgroSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AgroSpacing.md)
                    ) {
                        ShimmerCardSkeleton()
                        ShimmerListItemSkeleton()
                    }
                } else {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "Order Not Found",
                        message = "Could not load order details.",
                        actionLabel = "Retry",
                        onActionClick = { viewModel.loadOrderDetail(orderId) }
                    )
                }
            }
        }
        return
    }

    val isFarmer = (currentUserId == order.farmerId)
    val isBuyer = (currentUserId == order.buyerId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(order.orderNumber, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val isReadOnly = order.status in listOf("rejected", "cancelled", "expired", "completed", "refunded")
                    IconButton(
                        onClick = { onChatClick(order.id, order.status) },
                        modifier = Modifier.testTag("open_chat_action")
                    ) {
                        Icon(
                            imageVector = if (isReadOnly) Icons.Filled.Lock else Icons.AutoMirrored.Filled.Chat,
                            contentDescription = if (isReadOnly) "Order Chat (Read-Only)" else "Order Chat",
                            tint = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Action Bars for Farmer and Buyer
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (isFarmer) {
                        // Farmer Actions
                        when (order.status) {
                            "requested" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = viewModel::openRejectDialog,
                                        modifier = Modifier.weight(1f).testTag("farmer_reject_button")
                                    ) {
                                        Text("Reject")
                                    }
                                    Button(
                                        onClick = {
                                            val suggestedLandmark: String? = state.listingItem?.cultivationCityName?.let { "Near $it town center" }
                                                ?: state.deliveryCityName?.let { "Near $it town center" }
                                            viewModel.openAcceptSheet(suggestedLandmark)
                                        },
                                        modifier = Modifier.weight(1.5f).testTag("farmer_accept_button")
                                    ) {
                                        Text("Accept Order", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            "paid" -> {
                                Button(
                                    onClick = { viewModel.markReady(order.id) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("farmer_ready_button")
                                ) {
                                    Icon(Icons.Filled.Inventory, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Mark Ready", fontWeight = FontWeight.Bold)
                                }
                            }
                            "ready" -> {
                                if (order.deliveryMethod == "farmer_delivery") {
                                    Button(
                                        onClick = { viewModel.markDispatched(order.id) },
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("farmer_dispatch_button")
                                    ) {
                                        Icon(Icons.Filled.LocalShipping, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Mark Dispatched", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.markDelivered(order.id) },
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("farmer_delivered_button")
                                    ) {
                                        Icon(Icons.Filled.DoneAll, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Mark Handed Over to Transport", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            "dispatched" -> {
                                Button(
                                    onClick = { viewModel.markDelivered(order.id) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("farmer_delivered_button")
                                ) {
                                    Icon(Icons.Filled.DoneAll, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Confirm Delivered to Buyer", fontWeight = FontWeight.Bold)
                                }
                            }
                            else -> {}
                        }

                        if (order.status in listOf("requested", "accepted", "paid", "ready")) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = viewModel::openCancelDialog,
                                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("farmer_cancel_button")
                            ) {
                                Text("Cancel Order", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else if (isBuyer) {
                        // Buyer Actions
                        when (order.status) {
                            "accepted" -> {
                                val isPaymentExpired = remember(order.expiresAt, order.acceptedAt) {
                                    val expiryInstant = order.expiresAt?.let {
                                        try { java.time.Instant.parse(it) } catch (_: Exception) { null }
                                    } ?: order.acceptedAt?.let {
                                        try { java.time.Instant.parse(it).plus(java.time.Duration.ofMinutes(30)) } catch (_: Exception) { null }
                                    }
                                    expiryInstant != null && java.time.Instant.now().isAfter(expiryInstant)
                                }

                                if (isPaymentExpired) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("payment_expired_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.TimerOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Payment window expired (30 min). Order cancelled.",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = viewModel::openCancelDialog,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("buyer_cancel_button")
                                        ) {
                                            Text("Cancel order")
                                        }

                                        Button(
                                            onClick = { viewModel.initiatePayment(order.id) },
                                            enabled = !state.actionLoading && state.payHereRequest == null,
                                            modifier = Modifier
                                                .weight(1.5f)
                                                .height(48.dp)
                                                .testTag("buyer_pay_now_button")
                                        ) {
                                            Icon(Icons.Filled.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (state.actionLoading) "Preparing..." else "Pay ${formatLkr(order.totalAmount)}",
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                            "requested" -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        color = AgroGreenContainer.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("awaiting_farmer_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.Schedule, contentDescription = null, tint = AgroGreenPrimary, modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Awaiting Farmer Acceptance",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = AgroGreenOnContainer
                                                )
                                                Text(
                                                    text = "The farmer has 12 hours to review and accept your order request. Once accepted, you can complete payment with PayHere.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = viewModel::openCancelDialog,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("buyer_cancel_button")
                                    ) {
                                        Text("Cancel order")
                                    }
                                }
                            }
                            "cancelled", "rejected", "expired" -> {
                                val cancelledByWho = when (order.endedBy?.lowercase()?.trim()) {
                                    "buyer" -> "Buyer"
                                    "farmer" -> "Farmer"
                                    "system" -> "Auto-expired"
                                    "admin" -> "Admin"
                                    else -> if (order.status == "rejected") "Farmer" else "Auto-expired"
                                }
                                val endedTimeStr = formatOrderColomboDateTime(order.endedAt)
                                val hasRefund = order.refundStatus in listOf("required", "pending") || order.refundAmount > 0

                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("order_cancelled_footer_banner")
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Order Cancelled by $cancelledByWho",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        if (!order.endReason.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Reason: ${order.endReason}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (endedTimeStr != null) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Cancelled at: $endedTimeStr",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (hasRefund) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Surface(
                                                color = AgroGreenContainer,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "Refund in progress (${formatLkr(if (order.refundAmount > 0) order.refundAmount else order.totalAmount)})",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = AgroGreenPrimary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            "delivered" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = viewModel::openDisputeDialog,
                                        modifier = Modifier.weight(1f).testTag("buyer_dispute_button")
                                    ) {
                                        Text("Raise Dispute")
                                    }
                                    Button(
                                        onClick = { viewModel.confirmReceipt(order.id) },
                                        modifier = Modifier.weight(1.5f).testTag("buyer_confirm_receipt_button")
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Confirm Receipt", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            "completed" -> {
                                Button(
                                    onClick = viewModel::openReviewDialog,
                                    modifier = Modifier.fillMaxWidth().testTag("buyer_review_button")
                                ) {
                                    Icon(Icons.Filled.Star, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Rate & Review Farmer", fontWeight = FontWeight.Bold)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .imeNestedScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success & Error Toasts
            if (state.actionSuccessMessage != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.actionSuccessMessage!!,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            )
                            if (state.actionSuccessMessage!!.contains("update shortly", ignoreCase = true) || state.actionSuccessMessage!!.contains("Confirming", ignoreCase = true)) {
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.loadOrderDetail(order.id) },
                                    modifier = Modifier.size(32.dp).testTag("refresh_payment_status_btn")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh Order Status", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }

            if (!state.actionError.isNullOrBlank() && !state.actionError!!.contains("jwt", ignoreCase = true)) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = state.actionError!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Status & Countdown Timer
            item {
                AgroCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Order Status",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StatusChip(status = order.status)
                    }

                    if (order.status in listOf("requested", "accepted") && state.remainingSeconds > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val mins = state.remainingSeconds / 60
                        val secs = state.remainingSeconds % 60
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = AgroShapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Timer,
                                    contentDescription = "Timer",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (order.status == "requested") {
                                        "Farmer response deadline: ${mins}m ${secs}s"
                                    } else {
                                        "Payment window expires in: ${mins}m ${secs}s"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Order Produce & Counterparty Details Card
            item {
                val otherPartyName = if (isFarmer) {
                    order.buyerName?.ifBlank { "Buyer" } ?: "Buyer"
                } else {
                    order.farmerName?.ifBlank { "Farmer" } ?: "Farmer"
                }
                val placedTime = formatOrderColomboDateTime(order.requestedAt ?: order.requestedDate)
                val acceptedTime = formatOrderColomboDateTime(order.acceptedAt)
                val paidTime = formatOrderColomboDateTime(order.paidAt)

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("order_crop_details_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(12.dp))
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
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = order.cropName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${order.quantityKg} kg @ ${formatLkr(order.pricePerKg)}/kg",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = AgroGreenPrimary)
                                )
                                if (!order.harvestDate.isNullOrBlank()) {
                                    Text(
                                        text = "Harvest Date: ${order.harvestDate}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = if (isFarmer) "Buyer: $otherPartyName" else "Farmer: $otherPartyName",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Order Timeline",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (placedTime != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Filled.AccessTime, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Placed: $placedTime",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (acceptedTime != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = AgroGreenPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Accepted: $acceptedTime",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (paidTime != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Filled.Payment, contentDescription = null, modifier = Modifier.size(14.dp), tint = AgroGreenPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Paid: $paidTime",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = AgroGreenPrimary)
                                )
                            }
                        }

                        if (order.status in listOf("cancelled", "rejected", "expired")) {
                            val cancelledByWho = when (order.endedBy?.lowercase()?.trim()) {
                                "buyer" -> "Buyer"
                                "farmer" -> "Farmer"
                                "system" -> "Auto-expired"
                                "admin" -> "Admin"
                                else -> if (order.status == "rejected") "Farmer" else "Auto-expired"
                            }
                            val endedTime = formatOrderColomboDateTime(order.endedAt)
                            val hasRefund = order.refundStatus in listOf("required", "pending") || order.refundAmount > 0

                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Cancelled: by $cancelledByWho${if (endedTime != null) " at $endedTime" else ""}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                    )
                                    if (!order.endReason.isNullOrBlank()) {
                                        Text(
                                            text = "Reason: ${order.endReason}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (hasRefund) {
                                        Text(
                                            text = "Refund status: In progress (${formatLkr(if (order.refundAmount > 0) order.refundAmount else order.totalAmount)})",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = AgroGreenPrimary)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.privateDetails?.deliveryAddress != null || state.privateDetails?.pickupLandmark != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        modifier = Modifier.testTag("private_details_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Private Coordination Details",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            // Privacy Rules:
                            // The farmer sees the buyer's delivery address only after payment and until the order is delivered.
                            // The buyer sees the farmer's pickup landmark only after payment and until delivery. After that, both are hidden.
                            val isPaidAndPendingDelivery = order.status in listOf("paid", "ready", "dispatched")
                            val canFarmerSeeAddress = isFarmer && isPaidAndPendingDelivery
                            val canBuyerSeeLandmark = isBuyer && isPaidAndPendingDelivery

                            val destinationCityDistrict = listOfNotNull(state.deliveryCityName, state.deliveryDistrictName)
                                .joinToString(", ")
                                .ifBlank { "District / City recorded" }

                            if (order.deliveryMethod == "farmer_delivery") {
                                // Buyer Destination (District & City) - visible to farmer for routing
                                if (isFarmer) {
                                    Text("Buyer Destination (District & City):", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        text = destinationCityDistrict,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (canFarmerSeeAddress) {
                                    // Full address unlocked only after payment and until delivered
                                    Text("Full Delivery Address:", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        text = state.privateDetails?.deliveryAddress ?: "Address provided",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )

                                    if (state.privateDetails?.deliveryAddress != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Address", state.privateDetails!!.deliveryAddress))
                                                Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Copy Address")
                                        }
                                    }

                                    // PickMe Action if PickMe delivery mode chosen
                                    if (order.farmerDeliveryMode == "pickme") {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                val pkg = BuildConfig.PICKME_PACKAGE_ID
                                                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                                                if (intent != null) {
                                                    context.startActivity(intent)
                                                } else {
                                                    val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PICKME_STORE_URL))
                                                    context.startActivity(storeIntent)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                                            modifier = Modifier.fillMaxWidth().height(44.dp).testTag("open_pickme_button")
                                        ) {
                                            Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Open PickMe", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else if (isFarmer) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (order.status in listOf("delivered", "completed")) {
                                                    "Buyer's delivery address is hidden now that the order has been delivered."
                                                } else {
                                                    "Buyer's full street address unlocks once payment is completed."
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (order.deliveryMethod == "buyer_arranged") {
                                if (canBuyerSeeLandmark && state.privateDetails?.pickupLandmark != null) {
                                    Text("Farmer Pickup Landmark:", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        text = state.privateDetails!!.pickupLandmark!!,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Hand over your order number to the farmer at this landmark.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (isBuyer) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (order.status in listOf("delivered", "completed")) {
                                                    "Pickup landmark is hidden now that the order has been delivered."
                                                } else {
                                                    "Farmer's pickup landmark unlocks once payment is confirmed."
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // In-App Order Chat Card (per order, buyer & farmer only, read-only when finalized)
            item {
                val isReadOnly = order.status in listOf("rejected", "cancelled", "expired", "completed", "refunded")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("order_chat_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = if (isReadOnly) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isReadOnly) Icons.Filled.Lock else Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                        tint = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isFarmer) "Order Chat with Buyer" else "Order Chat with Farmer",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (isReadOnly) "Order is ${order.status.replace('_', ' ')}. Chat is read-only."
                                    else "Coordinate harvest, pickup location & readiness",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onChatClick(order.id, order.status) },
                            modifier = Modifier.fillMaxWidth().testTag("open_order_chat_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isReadOnly) "View Chat History (Read-Only)" else "Open Order Chat")
                        }
                    }
                }
            }

            // Order Financials Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Financial Breakdown", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Harvest (${order.quantityKg} kg x ${formatLkr(order.pricePerKg)})", style = MaterialTheme.typography.bodyMedium)
                            Text(formatLkr(order.subtotal), style = MaterialTheme.typography.bodyMedium)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Delivery Fee (${order.deliveryMethod})", style = MaterialTheme.typography.bodyMedium)
                            Text(formatLkr(order.deliveryFee), style = MaterialTheme.typography.bodyMedium)
                        }

                        val discount = (order.subtotal + order.deliveryFee) - order.totalAmount
                        if (discount > 0.01) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "Order Discount",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = AgroGreenPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    "-${formatLkr(discount)}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = AgroGreenPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Divider()

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Order Amount", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                formatLkr(order.totalAmount),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            )
                        }

                        if (isFarmer) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Platform Commission (3% of subtotal)", style = MaterialTheme.typography.bodySmall)
                                        Text("-${formatLkr(order.commissionAmount)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Farmer Payout Amount", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text(formatLkr(order.farmerPayoutAmount), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                                    }
                                    Text(
                                        text = "Payout status: ${order.payoutStatus.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    // Farmer Accept Order BottomSheet
    if (state.showAcceptSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeAcceptSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Accept Order Request", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                if (order.deliveryMethod == "farmer_delivery") {
                    Text("Select your transport arrangement:", style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = state.deliveryMode == "own_transport",
                            onClick = { viewModel.onDeliveryModeChange("own_transport") },
                            label = { Text("My own transport") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = state.deliveryMode == "pickme",
                            onClick = { viewModel.onDeliveryModeChange("pickme") },
                            label = { Text("PickMe") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (state.deliveryMode == "pickme") {
                        OutlinedButton(
                            onClick = {
                                val pkg = BuildConfig.PICKME_PACKAGE_ID
                                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                                    ?: Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PICKME_STORE_URL))
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.pickme.passenger"))
                                    context.startActivity(storeIntent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("open_pickme_in_accept_dialog")
                        ) {
                            Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open PickMe App")
                        }
                    }

                    OutlinedTextField(
                        value = state.deliveryFeeInput,
                        onValueChange = viewModel::onDeliveryFeeChange,
                        label = { Text("Delivery Fee to Buyer (Rs.)") },
                        supportingText = { Text("Added to buyer total. Passed 100% to you without commission.") },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_fee_input")
                    )
                } else {
                    val isBlankLandmark = state.pickupLandmarkInput.trim().isBlank()
                    OutlinedTextField(
                        value = state.pickupLandmarkInput,
                        onValueChange = viewModel::onPickupLandmarkChange,
                        label = { Text("Pickup Landmark * (Required)") },
                        placeholder = { Text("e.g. Near clock tower junction, Kandy Road") },
                        supportingText = {
                            if (isBlankLandmark) {
                                Text(
                                    "Required: Enter a recognizable spot where buyer can collect order.",
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("Shown to the buyer only after payment.")
                            }
                        },
                        isError = isBlankLandmark,
                        modifier = Modifier.fillMaxWidth().testTag("pickup_landmark_input")
                    )

                    val suggested: String? = state.listingItem?.cultivationCityName?.let { "Near $it town center" }
                        ?: state.deliveryCityName?.let { "Near $it town center" }
                    if (!suggested.isNullOrBlank() && state.pickupLandmarkInput.trim() != suggested) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onPickupLandmarkChange(suggested) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Tap to use: $suggested",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Summary for farmer: subtotal, 3% platform commission (on crop price only), delivery fee, farmer receives
                val subtotal = order.subtotal
                val commission = subtotal * 0.03
                val deliveryFee = if (order.deliveryMethod == "farmer_delivery") (state.deliveryFeeInput.toDoubleOrNull() ?: 0.0) else 0.0
                val farmerReceives = (subtotal - commission) + deliveryFee

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().testTag("farmer_earnings_summary")
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Payout Summary", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal (${order.quantityKg.toInt()} kg):", style = MaterialTheme.typography.bodySmall)
                            Text(formatLkr(subtotal), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Platform Commission (3% crop only):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Text("-${formatLkr(commission)}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error))
                        }
                        if (order.deliveryMethod == "farmer_delivery") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Delivery Fee (100% to you):", style = MaterialTheme.typography.bodySmall)
                                Text("+${formatLkr(deliveryFee)}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = AgroGreenPrimary))
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("What You Receive:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                formatLkr(farmerReceives),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = AgroGreenPrimary)
                            )
                        }
                    }
                }

                if (state.actionError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.actionError!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                val canAccept = !state.actionLoading &&
                    (order.deliveryMethod != "buyer_arranged" || state.pickupLandmarkInput.trim().isNotBlank()) &&
                    (order.deliveryMethod != "farmer_delivery" || state.deliveryMode in listOf("own_transport", "pickme"))

                Button(
                    onClick = { viewModel.confirmAcceptOrder(order.id) },
                    enabled = canAccept,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("confirm_accept_button")
                ) {
                    if (state.actionLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Accept & Confirm", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Cancel Order Dialog
    if (state.showCancelDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeCancelDialog,
            title = { Text("Cancel Order") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (isFarmer) "Warning: Cancelling orders lowers your reliability score and Top Farmer eligibility."
                        else "Are you sure you want to cancel this order request?"
                    )
                    OutlinedTextField(
                        value = state.cancelReasonInput,
                        onValueChange = viewModel::onCancelReasonChange,
                        label = { Text("Cancellation Reason") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmCancelOrder(order.id, isFarmer) },
                    enabled = !state.actionLoading && (!isFarmer || state.cancelReasonInput.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_cancel_order_button")
                ) {
                    Text("Confirm Cancellation")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeCancelDialog) {
                    Text("Go Back")
                }
            }
        )
    }

    // Reject Order Dialog
    if (state.showRejectDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeRejectDialog,
            title = { Text("Reject Order Request") },
            text = {
                OutlinedTextField(
                    value = state.rejectReasonInput,
                    onValueChange = viewModel::onRejectReasonChange,
                    label = { Text("Reason (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmRejectOrder(order.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reject Order")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeRejectDialog) { Text("Back") }
            }
        )
    }

    // Dispute Dialog
    if (state.showDisputeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeDisputeDialog,
            title = { Text("Raise Order Dispute") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "An administrator will review the order details and chat transcript to resolve the dispute with refund or release.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = state.disputeReasonInput,
                        onValueChange = viewModel::onDisputeReasonChange,
                        label = { Text("Dispute Details (min 10 characters)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitDispute(order.id) },
                    enabled = state.disputeReasonInput.trim().length >= 10
                ) {
                    Text("Submit Dispute")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeDisputeDialog) { Text("Cancel") }
            }
        )
    }

    // Review Dialog
    if (state.showReviewDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeReviewDialog,
            title = { Text("Review Your Experience") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Rate harvest quality and farmer communication:")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            IconButton(onClick = { viewModel.onRatingChange(star) }) {
                                Icon(
                                    imageVector = if (star <= state.ratingInput) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = "$star stars",
                                    tint = com.example.ui.theme.AgroGoldStar
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.reviewCommentInput,
                        onValueChange = viewModel::onReviewCommentChange,
                        label = { Text("Comment (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.submitReview(order.id) }) {
                    Text("Submit Review")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeReviewDialog) { Text("Later") }
            }
        )
    }
}
