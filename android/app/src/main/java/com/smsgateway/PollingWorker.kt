package com.smsgateway

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val apiClient = ApiClient(applicationContext)
            
            if (!apiClient.isConfigured()) {
                Result.retry()
            } else {
                apiClient.poll()
                
                val heartbeatInterval = applicationContext.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
                    .getInt("heartbeat_interval", 60)
                
                apiClient.heartbeat(ApiClient.HeartbeatRequest())
                
                scheduleNextPoll(heartbeatInterval)
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun scheduleNextPoll(intervalSeconds: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<PollingWorker>(intervalSeconds.toLong(), TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "sms_polling",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
    }

    companion object {
        fun schedule(context: Context, intervalSeconds: Int = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<PollingWorker>(intervalSeconds.toLong(), TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "sms_polling",
                    ExistingPeriodicWorkPolicy.REPLACE,
                    workRequest
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("sms_polling")
        }
    }
}