package com.example.ui.screens.farmer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.models.ListingItem
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FarmerProfileScreen(
    farmerId: String,
    farmerName: String,
    districtName: String,
    cityName: String? = null,
    ratingAvg: Double = 0.0,
    ratingCount: Int = 0,
    reliabilityScore: Int = 100,
    completedOrders: Int = 0,
    isTopFarmer: Boolean = false,
    mainCrops: List<String> = emptyList(),
    listings: List<ListingItem> = emptyList(),
    onBackClick: () -> Unit,
    onListingClick: (ListingItem) -> Unit,
    onChatClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Farmer Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            StickyBottomCTA {
                PrimaryButton(
                    text = "Message Farmer",
                    onClick = onChatClick,
                    leadingIcon = Icons.AutoMirrored.Filled.Chat,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("farmer_profile_screen"),
            contentPadding = PaddingValues(AgroSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgroSpacing.lg)
        ) {
            // Profile Header Card
            item {
                AgroCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(AgroGreenContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = AgroGreenPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(AgroSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AgroSpacing.xs)
                            ) {
                                Text(
                                    text = farmerName,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                if (isTopFarmer) {
                                    TopFarmerBadge()
                                }
                            }
                            Spacer(modifier = Modifier.height(AgroSpacing.xxs))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(AgroSpacing.xxs))
                                Text(
                                    text = if (!cityName.isNullOrBlank()) "$cityName, $districtName" else districtName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AgroSpacing.lg))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(modifier = Modifier.height(AgroSpacing.md))

                    // Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Reliability",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.xxs))
                            Text(
                                text = "$reliabilityScore%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AgroGreenPrimary
                                )
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Rating",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.xxs))
                            RatingStars(rating = ratingAvg, reviewCount = ratingCount)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Completed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(AgroSpacing.xxs))
                            Text(
                                text = "$completedOrders orders",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            // Main Crops section
            if (mainCrops.isNotEmpty()) {
                item {
                    SectionHeader(title = "Cultivated Produce")
                    AgroCard {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AgroSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(AgroSpacing.sm)
                        ) {
                            mainCrops.forEach { crop ->
                                Surface(
                                    color = AgroGreenContainer.copy(alpha = 0.6f),
                                    shape = AgroShapes.pill
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Spa,
                                            contentDescription = null,
                                            tint = AgroGreenPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = crop,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = AgroGreenOnContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Active Listings section
            item {
                SectionHeader(
                    title = "Available Harvests",
                    badgeText = "${listings.size} Listed"
                )
            }

            if (listings.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Storefront,
                        title = "No Active Listings",
                        message = "This farmer currently has no active listings on the marketplace."
                    )
                }
            } else {
                items(listings, key = { it.id }) { listing ->
                    AgroCard(
                        onClick = { onListingClick(listing) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = listing.cropName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(AgroSpacing.xxs))
                                Text(
                                    text = "Available: ${listing.quantityAvailable} kg • Min: ${listing.minOrderKg} kg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${formatLkr(listing.pricePerKg)}/kg",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = AgroGreenPrimary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
