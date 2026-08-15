package com.smsgateway

import android.app.Service
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val sentIntents = mutableMapOf<Int, PendingIntent>()
    private val deliveredIntents = mutableMapOf<Int, PendingIntent>()

    override fun onCreate() {
        super.onCreate()
        // Register broadcast receivers
        val sentFilter = IntentFilter("SMS_SENT")
        val deliveredFilter = IntentFilter("SMS_DELIVERED")
        registerReceiver(sentReceiver, sentFilter)
        registerReceiver(deliveredReceiver, deliveredFilter)
        startForeground(1, createNotification())
    }

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val smsId = intent?.getIntExtra("sms_id", -1) ?: return
            val apiKey = intent.getStringExtra("api_key") ?: return
            when (resultCode) {
                android.app.Activity.RESULT_OK -> {
                    Log.i(TAG, "SMS sent confirmation received for id $smsId")
                    // Report once: a multi-part SMS fires this receiver once per part.
                    if (SmsResultSender.firstReport(context!!, smsId)) {
                        SmsResultSender.sendResult(context, apiKey, smsId, true, null)
                    }
                }
                else -> {
                    val error = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                        else -> "Unknown error"
                    }
                    Log.e(TAG, "SMS sent failed for id $smsId: $error")
                    if (SmsResultSender.firstReport(context!!, smsId)) {
                        SmsResultSender.sendResult(context, apiKey, smsId, false, error)
                    }
                }
            }
            // Clean up pending intents
            val prefs = context?.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
            prefs?.edit()?.remove("sent_$smsId")?.apply()
            prefs?.edit()?.remove("delivered_$smsId")?.apply()
        }
    }

    private val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val smsId = intent?.getIntExtra("sms_id", -1) ?: return
            val apiKey = intent.getStringExtra("api_key") ?: return
            when (resultCode) {
                android.app.Activity.RESULT_OK -> {
                    Log.i(TAG, "SMS delivered for id $smsId")
                    DeliveryReporter.report(context!!, apiKey, smsId, true)
                }
                else -> {
                    Log.w(TAG, "SMS delivery failed for id $smsId")
                    DeliveryReporter.report(context!!, apiKey, smsId, false)
                }
            }
        }
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

        // Create PendingIntents for sent and delivery reports with API key embedded
        val apiKey = apiClient.apiKey
        val smsManager = SmsManager.getDefault()

        // Split long messages into parts (160-char single-SMS limit) so SMS longer than
        // one part send reliably on all devices. divideMessage returns a single-element
        // list for short messages, so this also covers the normal single-part path.
        val parts = smsManager.divideMessage(sms.message)

        val sentPendingIntents = parts.indices.mapTo(ArrayList(parts.size)) { i ->
            PendingIntent.getBroadcast(
                this, sms.id * 10 + i,
                Intent("SMS_SENT").apply {
                    putExtra("sms_id", sms.id)
                    putExtra("part_index", i)
                    putExtra("part_count", parts.size)
                    putExtra("api_key", apiKey as java.io.Serializable)
                    setPackage(packageName)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val deliveredPendingIntents = parts.indices.mapTo(ArrayList(parts.size)) { i ->
            PendingIntent.getBroadcast(
                this, sms.id * 10 + i,
                Intent("SMS_DELIVERED").apply {
                    putExtra("sms_id", sms.id)
                    putExtra("part_index", i)
                    putExtra("part_count", parts.size)
                    putExtra("api_key", apiKey as java.io.Serializable)
                    setPackage(packageName)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        sentIntents[sms.id] = sentPendingIntents[0]
        deliveredIntents[sms.id] = deliveredPendingIntents[0]

        try {
            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    sms.to_number, null, parts[0], sentPendingIntents[0], deliveredPendingIntents[0]
                )
            } else {
                @Suppress("DEPRECATION") // sendMultipartTextMessage still required below API 33
                smsManager.sendMultipartTextMessage(
                    sms.to_number, null, parts, sentPendingIntents, deliveredPendingIntents
                )
            }

            // Result will be reported via broadcast receivers
            Log.i(TAG, "SMS send initiated to ${sms.to_number} (id=${sms.id}, parts=${parts.size})")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to ${sms.to_number}", e)
            sentIntents.remove(sms.id)
            deliveredIntents.remove(sms.id)
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