package com.smsgateway

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Utility class to send SMS result to the server from broadcast receivers.
 * This is designed to work even when the main SmsService is killed.
 */
object SmsResultSender {
    private val TAG = "SmsResultSender"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun sendResult(context: Context, apiKey: String, smsId: Int, success: Boolean, error: String?) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
                val baseUrl = prefs.getString("vm_url", "http://localhost:8000")!!

                val requestBody = RequestBody.create(
                    "application/json".toMediaType(),
                    gson.toJson(mapOf("success" to success, "error" to error))
                )

                val request = Request.Builder()
                    .url("$baseUrl/api/v1/device/sms/$smsId/result")
                    .addHeader("X-Device-API-Key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "SMS result sent: id=$smsId, success=$success")
                    } else {
                        Log.e(TAG, "Failed to send SMS result: ${response.code}")
                        // Retry once after 2 seconds
                        Thread.sleep(2000)
                        retrySendResult(context, apiKey, smsId, success, error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SMS result", e)
                retrySendResult(context, apiKey, smsId, success, error)
            }
        }.start()
    }

    private fun retrySendResult(context: Context, apiKey: String, smsId: Int, success: Boolean, error: String?) {
        try {
            val prefs = context.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("vm_url", "http://localhost:8000")!!

            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                gson.toJson(mapOf("success" to success, "error" to error))
            )

            val request = Request.Builder()
                .url("$baseUrl/api/v1/device/sms/$smsId/result")
                .addHeader("X-Device-API-Key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "SMS result sent (retry success): id=$smsId")
                } else {
                    Log.e(TAG, "SMS result retry failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SMS result retry", e)
        }
    }
}