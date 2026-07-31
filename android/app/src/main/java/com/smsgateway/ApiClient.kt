package com.smsgateway

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

data class PollResponse(
    val sms_list: List<SmsItem>
)

data class SmsItem(
    val id: Int,
    val to_number: String,
    val message: String
)

data class HeartbeatRequest(
    val status: String = "online",
    val battery_level: Int? = null,
    val signal_strength: Int? = null
)

data class SmsResultRequest(
    val success: Boolean,
    val error: String? = null
)

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)

    private val baseUrl: String
        get() = prefs.getString("vm_url", "http://localhost:8000")!!

    private val apiKey: String
        get() = prefs.getString("api_key", "")!!

    private fun authHeaders() = Headers.Builder()
        .add("X-Device-API-Key", apiKey)
        .add("Content-Type", "application/json")
        .build()

    suspend fun poll(): PollResponse {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/device/poll")
            .headers(authHeaders())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Poll failed: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            return gson.fromJson(body, PollResponse::class.java)
        }
    }

    suspend fun heartbeat(request: HeartbeatRequest) {
        val json = gson.toJson(request)
        val requestBody = RequestBody.create(json, MediaType.get("application/json"))

        val request = Request.Builder()
            .url("$baseUrl/api/v1/device/heartbeat")
            .headers(authHeaders())
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Heartbeat failed: ${response.code}")
            }
        }
    }

    suspend fun sendSmsResult(smsId: Int, success: Boolean, error: String? = null) {
        val request = SmsResultRequest(success, error)
        val json = gson.toJson(request)
        val requestBody = RequestBody.create(json, MediaType.get("application/json"))

        val httpRequest = Request.Builder()
            .url("$baseUrl/api/v1/device/sms/$smsId/result")
            .headers(authHeaders())
            .post(requestBody)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SMS result failed: ${response.code}")
            }
        }
    }

    fun saveConfig(vmUrl: String, apiKey: String) {
        prefs.edit()
            .putString("vm_url", vmUrl)
            .putString("api_key", apiKey)
            .apply()
    }

    fun isConfigured(): Boolean {
        return prefs.contains("vm_url") && prefs.contains("api_key")
    }
}