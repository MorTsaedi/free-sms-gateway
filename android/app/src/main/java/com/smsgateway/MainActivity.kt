package com.smsgateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val REQUEST_SMS_PERMISSION = 1001
    private val REQUEST_POST_NOTIFICATIONS = 1002

    private lateinit var tvStatus: TextView
    private lateinit var etVmUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var tvLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etVmUrl = findViewById(R.id.etVmUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        tvLogs = findViewById(R.id.tvLogs)

        loadConfig()
        checkPermissions()

        btnSave.setOnClickListener { saveConfig() }
        btnTest.setOnClickListener { testConnection() }

        updateStatus()
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
        etVmUrl.setText(prefs.getString("vm_url", ""))
        etApiKey.setText(prefs.getString("api_key", ""))
        
        val pollInterval = prefs.getInt("poll_interval", 15)
        val heartbeatInterval = prefs.getInt("heartbeat_interval", 60)
    }

    private fun saveConfig() {
        val vmUrl = etVmUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()

        if (vmUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("vm_url", vmUrl)
            .putString("api_key", apiKey)
            .putInt("poll_interval", 15)
            .putInt("heartbeat_interval", 60)
            .apply()

        ApiClient(this).saveConfig(vmUrl, apiKey)

        PollingWorker.schedule(this, 15)
        startService()

        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        updateStatus()
        logMessage("Config saved. Polling started.")
    }

    private fun testConnection() {
        lifecycleScope.launch {
            val apiClient = ApiClient(this@MainActivity)
            try {
                val response = apiClient.poll()
                logMessage("Connection test successful. Pending SMS: ${response.sms_list.size}")
                Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                logMessage("Connection test failed: $reason")
                Toast.makeText(this@MainActivity, "Connection failed: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), REQUEST_SMS_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logMessage("SMS permission granted")
            } else {
                logMessage("SMS permission denied - app cannot send SMS")
            }
        }
    }

    private fun startService() {
        val intent = Intent(this, SmsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        logMessage("Foreground service started")
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
        val configured = prefs.contains("vm_url") && prefs.contains("api_key")
        
        if (configured) {
            tvStatus.text = "Status: Configured ✓"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvStatus.text = "Status: Not configured ✗"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun logMessage(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        tvLogs.append("[$timestamp] $message\n")
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
        scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}