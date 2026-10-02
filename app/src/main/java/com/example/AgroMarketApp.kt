package com.example

import android.app.Application
import com.example.data.api.ApiClient
import com.example.data.local.AppDatabase
import com.example.data.local.SessionManager
import com.example.data.repository.AgroMarketRepository
import com.example.data.repository.CartRepository

class AgroMarketApp : Application() {

    lateinit var sessionManager: SessionManager
        private set

    lateinit var repository: AgroMarketRepository
        private set

    val chatRepository get() = repository.chatRepository

    val cartRepository by lazy {
        CartRepository(
            AppDatabase.getInstance(this).cartDao(),
            repository
        )
    }

    val paymentService by lazy {
        com.example.data.payment.PayHerePaymentService(
            repository,
            sessionManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        sessionManager = SessionManager(applicationContext)
        ApiClient.init(sessionManager)
        repository = AgroMarketRepository(sessionManager)

        try {
            com.example.data.worker.ChatOutboxWorker.enqueueDrain(this)
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: AgroMarketApp
            private set
    }
}
