package life.ortho.ortholink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.os.Build
import android.provider.Settings

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.trim()
                val incomingName = extractIncomingName(intent)

                if (!incomingNumber.isNullOrBlank() || !incomingName.isNullOrBlank()) {
                    startOverlayService(context, incomingNumber.orEmpty(), false, incomingName)
                }
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                val serviceIntent = Intent(context, OverlayService::class.java)
                serviceIntent.action = "STOP"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } else if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)?.trim()
            if (!outgoingNumber.isNullOrBlank()) {
                startOverlayService(context, outgoingNumber, true)
            }
        }
    }

    private fun startOverlayService(context: Context, number: String, isOutgoing: Boolean, name: String? = null) {
        if (number.isBlank() && name.isNullOrBlank()) return
        if (Settings.canDrawOverlays(context)) {
            val serviceIntent = Intent(context, OverlayService::class.java)
            if (number.isNotBlank()) {
                serviceIntent.putExtra("PHONE_NUMBER", number)
            }
            serviceIntent.putExtra("IS_OUTGOING", isOutgoing)
            if (!name.isNullOrBlank()) {
                serviceIntent.putExtra("CALLER_NAME", name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private fun extractIncomingName(intent: Intent): String? {
        val knownKeys = listOf(
            "android.telephony.extra.INCOMING_NAME",
            "incoming_name",
            "caller_name",
            "com.android.phone.extra.CALLER_NAME"
        )

        for (key in knownKeys) {
            val value = sanitizeCallerName(intent.getStringExtra(key))
            if (value != null) return value
        }

        val extras = intent.extras ?: return null
        for (key in extras.keySet()) {
            if (key.contains("name", ignoreCase = true)) {
                val value = try {
                    sanitizeCallerName(extras.getString(key))
                } catch (_: ClassCastException) {
                    null
                }
                if (value != null) return value
            }
        }

        return null
    }

    private fun sanitizeCallerName(rawName: String?): String? {
        val candidate = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (candidate.lowercase(java.util.Locale.getDefault())) {
            "unknown", "unknown caller", "private number" -> null
            else -> candidate
        }
    }
}
