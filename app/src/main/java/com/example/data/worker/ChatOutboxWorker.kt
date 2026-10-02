package com.example.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Legacy outbox worker. Simple chat uses direct HTTP transmission.
 */
class ChatOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return Result.success()
    }

    companion object {
        fun enqueueDrain(context: Context) {
            // No-op in simple chat architecture
        }
    }
}
