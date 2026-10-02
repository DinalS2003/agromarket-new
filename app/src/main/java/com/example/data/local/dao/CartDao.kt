package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.CartItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CartDao {
    @Query("SELECT * FROM cart_items ORDER BY added_at ASC")
    fun getAllCartItemsFlow(): Flow<List<CartItemEntity>>

    @Query("SELECT * FROM cart_items ORDER BY added_at ASC")
    suspend fun getAllCartItems(): List<CartItemEntity>

    @Query("SELECT * FROM cart_items WHERE listing_id = :listingId LIMIT 1")
    suspend fun getCartItemById(listingId: String): CartItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: CartItemEntity)

    @Query("UPDATE cart_items SET quantity_kg = :quantityKg WHERE listing_id = :listingId")
    suspend fun updateQuantity(listingId: String, quantityKg: Double)

    @Query("UPDATE cart_items SET available_stock = :availableStock, is_active = :isActive, price_per_kg = :pricePerKg WHERE listing_id = :listingId")
    suspend fun syncListingStatus(listingId: String, availableStock: Double, isActive: Boolean, pricePerKg: Double)

    @Query("DELETE FROM cart_items WHERE listing_id = :listingId")
    suspend fun deleteCartItem(listingId: String)

    @Query("DELETE FROM cart_items WHERE farmer_id = :farmerId")
    suspend fun deleteCartItemsForFarmer(farmerId: String)

    @Query("DELETE FROM cart_items")
    suspend fun clearCart()
}
