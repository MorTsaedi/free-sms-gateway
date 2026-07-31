package com.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting SMS service")
            
            val apiClient = ApiClient(context)
            if (apiClient.isConfigured()) {
                PollingWorker.schedule(context, 15)
                
                val serviceIntent = Intent(context, SmsService::class.java)
                context.startForegroundService(serviceIntent)
                
                Log.i(TAG, "Service started on boot")
            }
        }
    }
}