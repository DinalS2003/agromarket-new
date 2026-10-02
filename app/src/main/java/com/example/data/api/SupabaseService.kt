package com.example.data.api

import com.example.data.models.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JvmSuppressWildcards
interface SupabaseService {

    // Auth Endpoints
    @POST("auth/v1/otp")
    suspend fun sendOtp(@Body body: @JvmSuppressWildcards Map<String, String>): Response<Unit>

    @POST("auth/v1/verify")
    suspend fun verifyOtp(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("auth/v1/token?grant_type=password")
    suspend fun loginWithPassword(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/get_or_create_phone_auth")
    suspend fun getOrCreatePhoneAuth(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    // Public Lookups
    @GET("rest/v1/districts?select=*&order=id.asc")
    suspend fun getDistricts(): Response<List<District>>

    @GET("rest/v1/cities")
    suspend fun getCities(@Query("district_id") districtFilter: String = "gt.0", @Query("select") select: String = "*", @Query("order") order: String = "name.asc"): Response<List<City>>

    @GET("rest/v1/crop_suggestions?select=*")
    suspend fun getCropSuggestions(): Response<List<CropSuggestion>>

    // Profiles & Farmer
    @GET("rest/v1/profiles")
    suspend fun getProfile(@Query("id") idFilter: String, @Query("select") select: String = "*"): Response<List<UserProfile>>

    @PATCH("rest/v1/profiles")
    suspend fun updateProfile(@Query("id") idFilter: String, @Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Unit>

    @GET("rest/v1/farmers")
    suspend fun getFarmer(@Query("user_id") idFilter: String, @Query("select") select: String = "*"): Response<List<FarmerRecord>>

    @GET("rest/v1/farmer_private")
    suspend fun getFarmerPrivate(@Query("user_id") idFilter: String, @Query("select") select: String = "*"): Response<List<FarmerPrivateData>>

    @PATCH("rest/v1/farmer_private")
    suspend fun updateFarmerPrivate(@Query("user_id") userFilter: String, @Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Unit>

    @GET("rest/v1/farmer_stats")
    suspend fun getFarmerStats(@Query("farmer_id") idFilter: String, @Query("select") select: String = "*"): Response<List<FarmerStats>>

    // RPC Functions
    @POST("rest/v1/rpc/check_username_available")
    suspend fun checkUsernameAvailable(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<Boolean>

    @POST("rest/v1/rpc/lookup_login_account")
    suspend fun lookupLoginAccount(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/register_user_with_credentials")
    suspend fun registerUserWithCredentials(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/upgrade_user_credentials")
    suspend fun upgradeUserCredentials(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/reset_user_password")
    suspend fun resetUserPassword(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/change_user_password")
    suspend fun changeUserPassword(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/register_profile")
    suspend fun registerProfile(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/register_farmer")
    suspend fun registerFarmer(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/search_listings")
    suspend fun searchListings(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<List<ListingItem>>

    @POST("rest/v1/rpc/create_order_request")
    suspend fun createOrderRequest(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<String>

    @POST("rest/v1/rpc/farmer_accept_order")
    suspend fun farmerAcceptOrder(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/farmer_reject_order")
    suspend fun farmerRejectOrder(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/buyer_cancel_order")
    suspend fun buyerCancelOrder(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/farmer_cancel_order")
    suspend fun farmerCancelOrder(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/farmer_mark_ready")
    suspend fun farmerMarkReady(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/farmer_mark_dispatched")
    suspend fun farmerMarkDispatched(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/farmer_mark_delivered")
    suspend fun farmerMarkDelivered(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/buyer_confirm_delivered")
    suspend fun buyerConfirmDelivered(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("rest/v1/rpc/buyer_raise_dispute")
    suspend fun buyerRaiseDispute(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<String>

    @POST("rest/v1/rpc/buyer_submit_review")
    suspend fun buyerSubmitReview(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<String>

    @POST("rest/v1/rpc/get_order_private_details")
    suspend fun getOrderPrivateDetails(@Body body: @JvmSuppressWildcards Map<String, String>): Response<List<OrderPrivateDetails>>

    // Listings
    @POST("rest/v1/rpc/create_farmer_listing")
    suspend fun createFarmerListing(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<ListingItem>

    @GET("rest/v1/listings")
    suspend fun getFarmerListings(@Query("farmer_id") farmerFilter: String, @Query("select") select: String = "*", @Query("order") order: String = "created_at.desc"): Response<List<ListingItem>>

    @GET("rest/v1/listings")
    suspend fun getListingsById(@Query("id") idFilter: String, @Query("select") select: String = "*"): Response<List<ListingItem>>

    @POST("rest/v1/listings")
    @Headers("Prefer: return=representation")
    suspend fun createListing(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<List<ListingItem>>

    @PATCH("rest/v1/listings")
    @Headers("Prefer: return=representation")
    suspend fun updateListing(@Query("id") idFilter: String, @Body body: @JvmSuppressWildcards Map<String, Any?>): Response<List<ListingItem>>

    @DELETE("rest/v1/listings")
    suspend fun deleteListing(@Query("id") idFilter: String): Response<Unit>

    // Orders
    @POST("rest/v1/orders")
    @Headers("Prefer: return=representation")
    suspend fun insertOrderDirect(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<List<OrderItem>>

    @GET("rest/v1/orders")
    suspend fun getOrders(@Query("select") select: String = "*", @QueryMap queryMap: @JvmSuppressWildcards Map<String, String>, @Query("order") order: String = "requested_at.desc"): Response<List<OrderItem>>

    @GET("rest/v1/orders")
    suspend fun getOrderById(@Query("id") idFilter: String, @Query("select") select: String = "*"): Response<List<OrderItem>>

    @POST("rest/v1/rpc/mark_order_paid")
    suspend fun markOrderPaidRpc(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<ResponseBody>

    @POST("rest/v1/rpc/cancel_unpaid_checkout_orders")
    suspend fun cancelUnpaidCheckoutOrdersRpc(): Response<ResponseBody>

    @PATCH("rest/v1/orders")
    suspend fun updateOrderStatusDirect(@Query("id") idFilter: String, @Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Unit>

    // Messages & Rebuilt Order Chat
    @POST("rest/v1/rpc/chat_send")
    suspend fun chatSendRpc(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any?>>

    @POST("functions/v1/send-message")
    suspend fun sendChatMessageEdgeFunction(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any?>>

    @POST("rest/v1/rpc/create_chat_offer")
    suspend fun createChatOffer(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any?>>

    @POST("rest/v1/rpc/respond_to_chat_offer")
    suspend fun respondToChatOffer(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<@JvmSuppressWildcards Map<String, Any?>>

    @POST("rest/v1/rpc/block_user")
    suspend fun blockUser(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Boolean>

    @POST("rest/v1/rpc/unblock_user")
    suspend fun unblockUser(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Boolean>

    @POST("rest/v1/rpc/is_user_blocked")
    suspend fun isUserBlocked(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Boolean>

    @POST("rest/v1/rpc/report_user")
    suspend fun reportUser(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<String>

    // Storage
    @POST("storage/v1/object/{bucket}/{path}")
    suspend fun uploadStorageObject(
        @Path("bucket") bucket: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: RequestBody
    ): Response<@JvmSuppressWildcards Map<String, Any?>>

    @POST("storage/v1/object/sign/{bucket}/{path}")
    suspend fun createSignedStorageUrl(
        @Path("bucket") bucket: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: @JvmSuppressWildcards Map<String, Any>
    ): Response<@JvmSuppressWildcards Map<String, Any?>>

    @POST("rest/v1/rpc/get_or_create_conversation")
    suspend fun getOrCreateConversation(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<ResponseBody>

    @POST("rest/v1/rpc/open_order_conversation")
    suspend fun openOrderConversation(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<ResponseBody>

    @POST("rest/v1/rpc/get_inbox")
    suspend fun getInbox(@Body body: @JvmSuppressWildcards Map<String, Any?> = emptyMap()): Response<List<ConversationItem>>

    @POST("rest/v1/rpc/mark_conversation_read")
    suspend fun markConversationRead(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Unit>

    @POST("rest/v1/rpc/delete_conversation")
    suspend fun deleteConversation(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<Unit>

    @POST("rest/v1/rpc/admin_get_dispute_chat")
    suspend fun adminGetDisputeChat(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<List<MessageItem>>

    @GET("rest/v1/messages")
    suspend fun getConversationMessages(
        @Query("conversation_id") conversationFilter: String,
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.asc"
    ): Response<List<MessageItem>>

    @POST("rest/v1/messages")
    @Headers("Prefer: return=representation")
    suspend fun insertMessageDirect(@Body body: @JvmSuppressWildcards Map<String, Any?>): Response<List<MessageItem>>

    @GET("rest/v1/messages")
    suspend fun getOrderMessages(@Query("order_id") orderFilter: String, @Query("select") select: String = "*", @Query("order") order: String = "created_at.asc"): Response<List<MessageItem>>

    @DELETE("rest/v1/messages")
    suspend fun deleteOrderMessages(@Query("order_id") orderFilter: String): Response<Unit>

    // Reviews
    @GET("rest/v1/reviews")
    suspend fun getFarmerReviews(@Query("farmer_id") farmerFilter: String, @Query("select") select: String = "*", @Query("order") order: String = "created_at.desc"): Response<List<ReviewItem>>

    // Notifications
    @GET("rest/v1/notifications")
    suspend fun getNotifications(@Query("user_id") userFilter: String, @Query("select") select: String = "*", @Query("order") order: String = "created_at.desc"): Response<List<NotificationItem>>

    @PATCH("rest/v1/notifications")
    suspend fun markNotificationRead(@Query("id") idFilter: String, @Body body: @JvmSuppressWildcards Map<String, String>): Response<Unit>

    // Device Tokens
    @POST("rest/v1/device_tokens")
    @Headers("Prefer: resolution=merge-duplicates")
    suspend fun registerDeviceToken(@Body body: @JvmSuppressWildcards Map<String, String>): Response<Unit>

    // Edge Functions
    @POST("functions/v1/send-message")
    suspend fun sendMessage(@Body body: @JvmSuppressWildcards Map<String, String>): Response<@JvmSuppressWildcards Map<String, Any>>

    @POST("functions/v1/payhere-create-payment")
    suspend fun createPayHerePayment(@Body body: @JvmSuppressWildcards Map<String, String>): Response<PayHerePaymentRequest>
}
