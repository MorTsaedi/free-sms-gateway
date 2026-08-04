package com.smsgateway

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
                // Poll for SMS AND actually send them. This worker polls as a backup to
                // SmsService, but it MUST also send the SMS it claims - otherwise the server
                // marks them CLAIMED and they never get sent.
                val response = apiClient.poll()

                for (sms in response.sms_list) {
                    sendSms(sms, apiClient.apiKey)
                }

                val heartbeatInterval = applicationContext
                    .getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
                    .getInt("heartbeat_interval", 60)

                apiClient.heartbeat(HeartbeatRequest())

                scheduleNextPoll(heartbeatInterval)
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendSms(sms: SmsItem, apiKey: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val sentIntent = android.app.PendingIntent.getBroadcast(
                applicationContext, sms.id,
                android.content.Intent("SMS_SENT").apply {
                    putExtra("sms_id", sms.id)
                    putExtra("api_key", apiKey)
                    setPackage(applicationContext.packageName)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val deliveredIntent = android.app.PendingIntent.getBroadcast(
                applicationContext, sms.id,
                android.content.Intent("SMS_DELIVERED").apply {
                    putExtra("sms_id", sms.id)
                    putExtra("api_key", apiKey)
                    setPackage(applicationContext.packageName)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            smsManager.sendTextMessage(
                sms.to_number, null, sms.message, sentIntent, deliveredIntent
            )
            Log.i("PollingWorker", "SMS send initiated to ${sms.to_number} (id=${sms.id})")
        } catch (e: Exception) {
            Log.e("PollingWorker", "Failed to send SMS to ${sms.to_number}", e)
            // Report failure back to the server so it doesn't stay claimed forever
            SmsResultSender.sendResult(applicationContext, apiKey, sms.id, false, e.message)
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