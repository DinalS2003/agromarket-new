package com.example.ui.screens.orders

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.District
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.OrderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceOrderScreen(
    viewModel: OrderViewModel,
    districts: List<District>,
    onBackClick: () -> Unit,
    onOrderPlaced: (String) -> Unit,
    currentUserId: String? = null
) {
    val state by viewModel.placeOrderState.collectAsState()

    LaunchedEffect(state.orderCreatedId) {
        state.orderCreatedId?.let { id ->
            onOrderPlaced(id)
        }
    }

    val listing = state.listing ?: return
    val isOwner = !currentUserId.isNullOrBlank() && listing.farmerId == currentUserId
    var districtExpanded by remember { mutableStateOf(false) }
    var cityExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Request Order", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Estimated Subtotal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatLkr(state.subtotal),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = AgroGreenPrimary
                            ),
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = viewModel::submitOrderRequest,
                        enabled = !isOwner && !state.isSubmitting && state.quantityError == null,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("submit_order_button"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp)
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.ShoppingBag,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isOwner) "Cannot Order Own Crop" else "Request Order",
                                fontWeight = FontWeight.Bold
                            )
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
                .padding(horizontal = AgroSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgroSpacing.md)
        ) {
            if (isOwner) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AgroShapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "You cannot place an order for your own harvest listing.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Harvest snapshot card
            item {
                AgroCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = AgroShapes.medium,
                            color = AgroGreenContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Eco, contentDescription = null, tint = AgroGreenPrimary, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(AgroSpacing.md))
                        Column {
                            Text(listing.cropName, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                "${formatLkr(listing.pricePerKg)}/kg • ${listing.quantityAvailable} kg in stock",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Quantity Input with Stepper & Quick Chips
            item {
                AgroCard {
                    Text(
                        text = "Order Quantity (kg)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(AgroSpacing.sm))

                    val currentQty = state.quantityKg.toDoubleOrNull() ?: listing.minOrderKg
                    val minStep = 1.0
                    val minOrder = maxOf(1.0, minOf(listing.minOrderKg, listing.quantityAvailable))
                    val maxStock = listing.quantityAvailable

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val next = (currentQty - minStep).coerceAtLeast(1.0)
                                viewModel.onQuantityChange(if (next % 1.0 == 0.0) next.toInt().toString() else next.toString())
                            },
                            enabled = currentQty > 1.0,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("quantity_minus_button")
                        ) {
                            Icon(
                                Icons.Filled.Remove,
                                contentDescription = "Decrease Quantity",
                                tint = if (currentQty > 1.0) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }

                        OutlinedTextField(
                            value = state.quantityKg,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() || it == '.' }
                                viewModel.onQuantityChange(filtered)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            ),
                            trailingIcon = {
                                Text(
                                    text = "kg",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quantity_input_field")
                        )

                        IconButton(
                            onClick = {
                                val next = (currentQty + minStep).coerceAtMost(maxStock)
                                viewModel.onQuantityChange(if (next % 1.0 == 0.0) next.toInt().toString() else next.toString())
                            },
                            enabled = currentQty < maxStock,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("quantity_plus_button")
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Increase Quantity",
                                tint = if (currentQty < maxStock) AgroGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }

                    if (state.quantityError != null) {
                        Spacer(modifier = Modifier.height(AgroSpacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = state.quantityError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.testTag("quantity_inline_error")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.sm))

                    // Quick Quantity Presets (Optional shortcuts)
                    val presetValues = listOfNotNull(
                        if (minOrder > 1.0) minOrder else null,
                        10.0,
                        25.0,
                        50.0,
                        100.0,
                        maxStock
                    ).filter { it <= maxStock && it >= 1.0 }.distinct()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgroSpacing.xs)
                    ) {
                        presetValues.forEach { preset ->
                            val isSelected = (currentQty == preset && state.quantityError == null)
                            Surface(
                                shape = AgroShapes.pill,
                                color = if (isSelected) AgroGreenPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clickable {
                                        viewModel.onQuantityChange(if (preset % 1.0 == 0.0) preset.toInt().toString() else preset.toString())
                                    }
                            ) {
                                Text(
                                    text = if (preset == maxStock) "Max (${if (preset % 1.0 == 0.0) preset.toInt() else preset}kg)"
                                           else "${if (preset % 1.0 == 0.0) preset.toInt() else preset} kg",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AgroGreenOnPrimary else MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Delivery Method
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Delivery Method",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        // Option 1: Buyer Arranged (Recommended)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (state.deliveryMethod == "buyer_arranged") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onDeliveryMethodChange("buyer_arranged") }
                                .testTag("radio_buyer_arranged")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.deliveryMethod == "buyer_arranged",
                                    onClick = { viewModel.onDeliveryMethodChange("buyer_arranged") }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "I will arrange delivery / pickup",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.height(20.dp)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            ) {
                                                Text(
                                                    text = "Recommended",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 11.sp,
                                                        lineHeight = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Arrange your own vehicle or courier pickup from the farmer landmark.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Option 2: Farmer Delivery
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (state.deliveryMethod == "farmer_delivery") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onDeliveryMethodChange("farmer_delivery") }
                                .testTag("radio_farmer_delivery")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.deliveryMethod == "farmer_delivery",
                                    onClick = { viewModel.onDeliveryMethodChange("farmer_delivery") }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Request delivery from farmer",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        "Farmer will review and quote their delivery fee before you pay.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Farmer Delivery Address fields
                        AnimatedVisibility(visible = state.deliveryMethod == "farmer_delivery") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Text(
                                    "Delivery Destination",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                ExposedDropdownMenuBox(
                                    expanded = districtExpanded,
                                    onExpandedChange = { districtExpanded = !districtExpanded }
                                ) {
                                    val distName = districts.find { it.id == state.deliveryDistrictId }?.name ?: "Select District"
                                    OutlinedTextField(
                                        value = distName,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Delivery District") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = districtExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = districtExpanded,
                                        onDismissRequest = { districtExpanded = false }
                                    ) {
                                        districts.forEach { dist ->
                                            DropdownMenuItem(
                                                text = { Text(dist.name) },
                                                onClick = {
                                                    viewModel.onDeliveryDistrictChange(dist.id)
                                                    districtExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                ExposedDropdownMenuBox(
                                    expanded = cityExpanded,
                                    onExpandedChange = { cityExpanded = !cityExpanded }
                                ) {
                                    val cityName = state.deliveryCities.find { it.id == state.deliveryCityId }?.name ?: "Select Town"
                                    OutlinedTextField(
                                        value = cityName,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Delivery Town") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = cityExpanded,
                                        onDismissRequest = { cityExpanded = false }
                                    ) {
                                        state.deliveryCities.forEach { city ->
                                            DropdownMenuItem(
                                                text = { Text(city.name) },
                                                onClick = {
                                                    viewModel.onDeliveryCityChange(city.id)
                                                    cityExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = state.deliveryAddress,
                                    onValueChange = viewModel::onDeliveryAddressChange,
                                    label = { Text("Full Street Delivery Address") },
                                    placeholder = { Text("House/Shop No, Street Name") },
                                    supportingText = {
                                        Text("Visible ONLY to this farmer, and only while order is paid/dispatched.")
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("delivery_address_input")
                                )
                            }
                        }
                    }
                }
            }

            // Requested Date Picker
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Required Delivery / Pickup Date",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = state.requestedDate,
                            onValueChange = viewModel::onRequestedDateChange,
                            label = { Text("Date (YYYY-MM-DD)") },
                            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                            supportingText = {
                                Text("Must be on or after harvest date: ${listing.harvestDate}")
                            },
                            modifier = Modifier.fillMaxWidth().testTag("requested_date_input")
                        )
                    }
                }
            }

            // One-line note with info icon
            item {
                Surface(
                    color = AgroGreenContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = AgroGreenPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "12h farmer review • Pay after farmer accepts • Secure PayHere checkout",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = AgroGreenOnContainer
                        )
                    }
                }
            }

            if (!state.errorMessage.isNullOrBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().testTag("order_error_banner")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Unable to Complete Request",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.errorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = viewModel::clearErrorMessage,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                                ) {
                                    Text("Dismiss")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = viewModel::submitOrderRequest,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}
