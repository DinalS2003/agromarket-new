package com.example.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.models.CartItem

@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "listing_id")
    val listingId: String,

    @ColumnInfo(name = "farmer_id")
    val farmerId: String,

    @ColumnInfo(name = "farmer_name")
    val farmerName: String,

    @ColumnInfo(name = "farmer_district")
    val farmerDistrict: String? = null,

    @ColumnInfo(name = "crop_name")
    val cropName: String,

    @ColumnInfo(name = "price_per_kg")
    val pricePerKg: Double,

    @ColumnInfo(name = "quantity_kg")
    val quantityKg: Double,

    @ColumnInfo(name = "available_stock")
    val availableStock: Double,

    @ColumnInfo(name = "min_order_kg")
    val minOrderKg: Double = 1.0,

    @ColumnInfo(name = "harvest_date")
    val harvestDate: String? = null,

    @ColumnInfo(name = "photo_url")
    val photoUrl: String? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toCartItem(): CartItem = CartItem(
        listingId = listingId,
        farmerId = farmerId,
        farmerName = farmerName,
        farmerDistrict = farmerDistrict,
        cropName = cropName,
        pricePerKg = pricePerKg,
        quantityKg = quantityKg,
        availableStock = availableStock,
        minOrderKg = minOrderKg,
        harvestDate = harvestDate,
        photoUrl = photoUrl,
        isActive = isActive
    )

    companion object {
        fun fromCartItem(item: CartItem): CartItemEntity = CartItemEntity(
            listingId = item.listingId,
            farmerId = item.farmerId,
            farmerName = item.farmerName,
            farmerDistrict = item.farmerDistrict,
            cropName = item.cropName,
            pricePerKg = item.pricePerKg,
            quantityKg = item.quantityKg,
            availableStock = item.availableStock,
            minOrderKg = item.minOrderKg,
            harvestDate = item.harvestDate,
            photoUrl = item.photoUrl,
            isActive = item.isActive
        )
    }
}
