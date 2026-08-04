package com.smsgateway

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Utility class to report SMS delivery status to the server from broadcast receivers.
 * This is designed to work even when the main SmsService is killed.
 */
object DeliveryReporter {
    private val TAG = "DeliveryReporter"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun report(context: Context, apiKey: String, smsId: Int, success: Boolean) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
                val baseUrl = prefs.getString("vm_url", "http://localhost:8000")!!

                val requestBody = RequestBody.create(
                    "application/json".toMediaType(),
                    gson.toJson(mapOf("success" to success))
                )

                val request = Request.Builder()
                    .url("$baseUrl/api/v1/device/sms/$smsId/delivery")
                    .addHeader("X-Device-API-Key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Delivery report sent: id=$smsId, success=$success")
                    } else {
                        Log.e(TAG, "Failed to send delivery report: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending delivery report", e)
            }
        }.start()
    }
}