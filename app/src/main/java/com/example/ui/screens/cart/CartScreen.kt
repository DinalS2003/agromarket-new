package com.example.ui.screens.cart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.CartViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    viewModel: CartViewModel,
    onBackClick: () -> Unit,
    onCheckoutClick: () -> Unit,
    onBrowseMarketClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.syncStock()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("Clear Shopping Cart?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to remove all items from your cart?") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearCart()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("My Cart", fontWeight = FontWeight.Bold)
                        if (state.totalItemCount > 0) {
                            Spacer(modifier = Modifier.width(AgroSpacing.sm))
                            Surface(
                                color = AgroGreenContainer,
                                shape = AgroShapes.pill
                            ) {
                                Text(
                                    text = "${state.totalItemCount}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = AgroGreenOnContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("cart_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.testTag("clear_cart_button")
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear Cart")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                StickyBottomCTA {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Total (${state.items.size} ${if (state.items.size == 1) "crop" else "crops"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatLkr(state.totalAmount),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = AgroGreenPrimary
                            )
                        )
                    }

                    PrimaryButton(
                        text = "Checkout",
                        onClick = onCheckoutClick,
                        enabled = !state.hasUnavailableItems,
                        leadingIcon = Icons.Filled.ShoppingCartCheckout,
                        minHeight = 50.dp,
                        modifier = Modifier.testTag("checkout_cta_button")
                    )
                }
            }
        }
    ) { padding ->
        if (state.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Your Cart is Empty",
                    message = "Browse fresh harvest directly from Sri Lankan farmers and add items to your cart.",
                    actionLabel = "Explore Marketplace",
                    onActionClick = onBrowseMarketClick
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = AgroSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AgroSpacing.md),
                contentPadding = PaddingValues(top = AgroSpacing.md, bottom = AgroSpacing.xl)
            ) {
                // Stock Warning Notice Banner
                if (state.hasUnavailableItems) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = AgroShapes.card,
                            modifier = Modifier.fillMaxWidth().testTag("cart_stock_warning_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(AgroSpacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(AgroSpacing.md))
                                Column {
                                    Text(
                                        text = "Unavailable Items in Cart",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "Please remove or adjust quantity for out-of-stock items before checkout.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Farmer Groups
                state.farmerGroups.forEach { group ->
                    item(key = "header_${group.farmerId}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AgroSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = AgroGreenPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(AgroSpacing.xs))
                                Text(
                                    text = group.farmerName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!group.farmerDistrict.isNullOrBlank()) {
                                    Text(
                                        text = " (${group.farmerDistrict})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "Subtotal: ${formatLkr(group.subtotal)}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = AgroGreenPrimary
                            )
                        }
                    }

                    items(group.items, key = { it.listingId }) { item ->
                        CartItemCard(
                            item = item,
                            onQuantityChange = { newQty ->
                                viewModel.updateQuantity(item.listingId, newQty)
                            },
                            onRemoveClick = {
                                viewModel.removeItem(item.listingId)
                            }
                        )
                    }

                    item(key = "divider_${group.farmerId}") {
                        Spacer(modifier = Modifier.height(AgroSpacing.xs))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }
}
