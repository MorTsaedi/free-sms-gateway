package com.smsgateway

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SmsService : Service() {
    private val TAG = "SmsService"
    private var job: Job? = null
    private val apiClient by lazy { ApiClient(this) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Never leave a previous polling loop running: startService()/START_STICKY can call
        // this repeatedly, and each orphaned loop would otherwise keep polling in parallel.
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    pollAndSend()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop", e)
                }
                delay(getPollInterval().toLong() * 1000)
            }
        }
        return START_STICKY
    }

    private suspend fun pollAndSend() {
        if (!apiClient.isConfigured()) {
            Log.w(TAG, "Not configured, skipping poll")
            return
        }

        val response = apiClient.poll()
        
        for (sms in response.sms_list) {
            sendSms(sms)
        }
    }

    private suspend fun sendSms(sms: SmsItem) {
        Log.i(TAG, "Sending SMS to ${sms.to_number}")
        
        try {
            val smsManager = SmsManager.getDefault()
            
            smsManager.sendTextMessage(
                sms.to_number,
                null,
                sms.message,
                null,
                null
            )

            Log.i(TAG, "SMS sent successfully to ${sms.to_number}")
            apiClient.sendSmsResult(sms.id, true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to ${sms.to_number}", e)
            apiClient.sendSmsResult(sms.id, false, e.message)
        }
    }

    private fun getPollInterval(): Int {
        return getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
            .getInt("poll_interval", 15)
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "sms_gateway_channel"
        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "SMS Gateway",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SMS Gateway")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext.cancelChildren()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}