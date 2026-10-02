package com.example.data.repository

import com.example.data.local.dao.CartDao
import com.example.data.local.entities.CartItemEntity
import com.example.data.models.CartItem
import com.example.data.models.ListingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CartRepository(
    private val cartDao: CartDao,
    private val marketRepository: AgroMarketRepository
) {

    fun getCartItemsFlow(): Flow<List<CartItem>> {
        return cartDao.getAllCartItemsFlow().map { entities ->
            entities.map { it.toCartItem() }
        }
    }

    suspend fun getCartItems(): List<CartItem> = withContext(Dispatchers.IO) {
        cartDao.getAllCartItems().map { it.toCartItem() }
    }

    suspend fun addToCart(listing: ListingItem, quantityKg: Double): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUserId = marketRepository.sessionManager.getUserId() ?: ""
            if (currentUserId.isNotBlank() && listing.farmerId == currentUserId) {
                return@withContext Result.failure(IllegalStateException("You cannot purchase or add your own produce to cart."))
            }
            val existing = cartDao.getCartItemById(listing.id)
            val minOrder = minOf(listing.minOrderKg, listing.quantityAvailable)

            if (existing != null) {
                val newQty = (existing.quantityKg + quantityKg).coerceAtMost(listing.quantityAvailable)
                cartDao.updateQuantity(listing.id, newQty)
            } else {
                val clampedQty = quantityKg.coerceIn(minOrder, listing.quantityAvailable.coerceAtLeast(minOrder))
                val entity = CartItemEntity(
                    listingId = listing.id,
                    farmerId = listing.farmerId,
                    farmerName = listing.farmerFirstName ?: "Local Farmer",
                    farmerDistrict = listing.cultivationDistrictName,
                    cropName = listing.cropName,
                    pricePerKg = listing.pricePerKg,
                    quantityKg = clampedQty,
                    availableStock = listing.quantityAvailable,
                    minOrderKg = listing.minOrderKg,
                    harvestDate = listing.harvestDate,
                    photoUrl = listing.photos.firstOrNull(),
                    isActive = listing.isAvailableNow && listing.quantityAvailable > 0
                )
                cartDao.insertOrUpdate(entity)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateQuantity(listingId: String, newQuantityKg: Double): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = cartDao.getCartItemById(listingId)
            if (existing != null) {
                val clamped = newQuantityKg.coerceIn(0.5, existing.availableStock.coerceAtLeast(0.5))
                cartDao.updateQuantity(listingId, clamped)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFromCart(listingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cartDao.deleteCartItem(listingId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearCart(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cartDao.clearCart()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearFarmerItems(farmerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cartDao.deleteCartItemsForFarmer(farmerId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Re-validates cart items with the backend database.
     * Flags items where stock has changed or listing was removed/deactivated.
     */
    suspend fun syncCartWithBackend(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentItems = cartDao.getAllCartItems()
            for (item in currentItems) {
                val freshListingResult = marketRepository.getListingById(item.listingId)
                val fresh = freshListingResult.getOrNull()
                if (fresh == null || !fresh.isAvailableNow || fresh.quantityAvailable <= 0) {
                    cartDao.syncListingStatus(
                        listingId = item.listingId,
                        availableStock = 0.0,
                        isActive = false,
                        pricePerKg = item.pricePerKg
                    )
                } else {
                    cartDao.syncListingStatus(
                        listingId = item.listingId,
                        availableStock = fresh.quantityAvailable,
                        isActive = fresh.isAvailableNow,
                        pricePerKg = fresh.pricePerKg
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
