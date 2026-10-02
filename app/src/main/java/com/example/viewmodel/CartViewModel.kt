package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.models.CartItem
import com.example.data.models.ListingItem
import com.example.data.repository.CartRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FarmerCartGroup(
    val farmerId: String,
    val farmerName: String,
    val farmerDistrict: String?,
    val items: List<CartItem>,
    val subtotal: Double
)

data class CartUiState(
    val items: List<CartItem> = emptyList(),
    val farmerGroups: List<FarmerCartGroup> = emptyList(),
    val totalItemCount: Int = 0,
    val totalAmount: Double = 0.0,
    val hasUnavailableItems: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val successNotice: String? = null
)

class CartViewModel(
    private val cartRepository: CartRepository
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _successNotice = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CartUiState> = combine(
        cartRepository.getCartItemsFlow(),
        _syncing,
        _errorMessage,
        _successNotice
    ) { items, syncing, error, notice ->
        val groups = items.groupBy { it.farmerId }.map { (farmerId, farmerItems) ->
            val first = farmerItems.first()
            FarmerCartGroup(
                farmerId = farmerId,
                farmerName = first.farmerName,
                farmerDistrict = first.farmerDistrict,
                items = farmerItems,
                subtotal = farmerItems.sumOf { it.subtotal }
            )
        }

        val totalAmount = items.sumOf { it.subtotal }
        val hasUnavailable = items.any { it.isOutOfStock || it.exceedsStock }

        CartUiState(
            items = items,
            farmerGroups = groups,
            totalItemCount = items.size,
            totalAmount = totalAmount,
            hasUnavailableItems = hasUnavailable,
            isSyncing = syncing,
            errorMessage = error,
            successNotice = notice
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CartUiState()
    )

    init {
        syncStock()
    }

    fun syncStock() {
        viewModelScope.launch {
            _syncing.value = true
            cartRepository.syncCartWithBackend()
            _syncing.value = false
        }
    }

    fun addToCart(listing: ListingItem, quantityKg: Double) {
        viewModelScope.launch {
            val res = cartRepository.addToCart(listing, quantityKg)
            if (res.isSuccess) {
                _successNotice.value = "Added ${quantityKg.toInt()} kg of ${listing.cropName} to cart"
            } else {
                val msg = res.exceptionOrNull()?.message ?: "Failed to add to cart"
                _errorMessage.value = msg
            }
        }
    }

    fun updateQuantity(listingId: String, newQuantityKg: Double) {
        viewModelScope.launch {
            cartRepository.updateQuantity(listingId, newQuantityKg)
        }
    }

    fun removeItem(listingId: String) {
        viewModelScope.launch {
            cartRepository.removeFromCart(listingId)
        }
    }

    fun clearFarmerGroup(farmerId: String) {
        viewModelScope.launch {
            cartRepository.clearFarmerItems(farmerId)
        }
    }

    fun clearCart() {
        viewModelScope.launch {
            cartRepository.clearCart()
        }
    }

    fun dismissNotice() {
        _successNotice.value = null
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
