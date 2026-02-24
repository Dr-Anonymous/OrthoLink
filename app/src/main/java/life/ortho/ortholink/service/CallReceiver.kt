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
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                if (incomingNumber != null) {
                    startOverlayService(context, incomingNumber)
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
        }
    }

    private fun startOverlayService(context: Context, number: String) {
        if (Settings.canDrawOverlays(context)) {
            val serviceIntent = Intent(context, OverlayService::class.java)
            serviceIntent.putExtra("PHONE_NUMBER", number)
            serviceIntent.putExtra("IS_OUTGOING", false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
