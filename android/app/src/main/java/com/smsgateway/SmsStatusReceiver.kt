package com.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

/**
 * Statically-registered receiver for SMS_SENT and SMS_DELIVERED broadcasts.
 * Works even when SmsService has been killed by the OS. Reads the API key from
 * SharedPreferences and reports the actual send/delivery outcome to the server.
 */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val smsId = intent.getIntExtra("sms_id", -1)
        if (smsId == -1) return

        val apiKey = intent.getStringExtra("api_key") ?: run {
            // Fall back to the stored config if the key wasn't embedded in the intent.
            context.getSharedPreferences("sms_gateway", Context.MODE_PRIVATE)
                .getString("api_key", null) ?: return
        }

        when (intent.action) {
            "SMS_SENT" -> {
                // Report only once per sms_id: a multi-part SMS fires this receiver once per
                // part, and it may also fire alongside SmsService's dynamically-registered
                // receiver for the same SMS.
                if (!SmsResultSender.firstReport(context, smsId)) return
                when (resultCode) {
                    android.app.Activity.RESULT_OK -> {
                        Log.i("SmsStatusReceiver", "SMS sent id=$smsId")
                        SmsResultSender.sendResult(context, apiKey, smsId, true, null)
                    }
                    else -> {
                        val error = when (resultCode) {
                            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                            SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                            else -> "Unknown error"
                        }
                        Log.e("SmsStatusReceiver", "SMS send failed id=$smsId: $error")
                        SmsResultSender.sendResult(context, apiKey, smsId, false, error)
                    }
                }
            }
            "SMS_DELIVERED" -> {
                when (resultCode) {
                    android.app.Activity.RESULT_OK -> {
                        Log.i("SmsStatusReceiver", "SMS delivered id=$smsId")
                        DeliveryReporter.report(context, apiKey, smsId, true)
                    }
                    else -> {
                        Log.w("SmsStatusReceiver", "SMS delivery failed id=$smsId")
                        DeliveryReporter.report(context, apiKey, smsId, false)
                    }
                }
            }
        }
    }
}