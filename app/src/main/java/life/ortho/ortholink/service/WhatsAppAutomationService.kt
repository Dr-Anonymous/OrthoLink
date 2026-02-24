package life.ortho.ortholink.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat

class WhatsAppAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppAutoService"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val SEND_BUTTON_ID = "com.whatsapp:id/send" // This ID might change!
        private const val SEND_BUTTON_DESC = "Send"
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var actionSequenceScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "WhatsApp Automation Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != WHATSAPP_PACKAGE && event.packageName != WHATSAPP_BUSINESS_PACKAGE) {
            return
        }

        // CRITICAL FIX: Only proceed if we initiated the action
        if (!AutomationState.shouldSend || actionSequenceScheduled) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val rootNode = rootInActiveWindow ?: return
            
            // Check for "Not on WhatsApp" dialog
            val errorNode = findNodeByText(rootNode, "on WhatsApp")
            if (errorNode != null) {
                actionSequenceScheduled = true
                handleErrorAndFallback(rootNode, "Not on WhatsApp")
                return
            }

            // Check for "Couldn't connect" dialog
            val connectionErrorNode = findNodeByText(rootNode, "Couldn't connect")
            if (connectionErrorNode != null) {
                Log.d(TAG, "Detected Connection Error")
                
                if (AutomationState.retryCount < 1) {
                    Log.d(TAG, "Retrying... (Count: ${AutomationState.retryCount})")
                    AutomationState.retryCount++
                    
                    // Click OK to dismiss
                    val okButton = findNodeByText(rootNode, "OK")
                    okButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    
                    actionSequenceScheduled = true
                    scheduleBackTwice {
                        val intent = Intent("life.ortho.ortholink.ACTION_RETRY")
                        intent.setPackage(packageName)
                        sendBroadcast(intent)

                        AutomationState.shouldSend = false
                        actionSequenceScheduled = false
                    }
                    return
                } else {
                    Log.d(TAG, "Retry limit reached. Falling back to SMS.")
                    actionSequenceScheduled = true
                    handleErrorAndFallback(rootNode, "Couldn't connect")
                    return
                }
            }

            // Check for "No Internet" dialog/message
            val internetErrorNode = findNodeByText(rootNode, "internet")
            if (internetErrorNode != null) {
                Log.d(TAG, "Detected Internet Error")
                
                if (AutomationState.retryCount < 1) {
                    Log.d(TAG, "Retrying... (Count: ${AutomationState.retryCount})")
                    AutomationState.retryCount++
                    
                    actionSequenceScheduled = true
                    scheduleBackTwice {
                        AutomationState.shouldSend = false
                        actionSequenceScheduled = false
                    }
                    return
                } else {
                    Log.d(TAG, "Retry limit reached. Falling back to SMS.")
                    actionSequenceScheduled = true
                    handleErrorAndFallback(rootNode, "No Internet")
                    return
                }
            }

            // Attempt to find and click the send button
            val sendButton = findSendButton(rootNode)
            if (sendButton != null) {
                Log.d(TAG, "Send button found...")
                actionSequenceScheduled = true
                val delay = if (AutomationState.hasLink) {
                    Log.d(TAG, "Link detected, waiting 2.5s for preview...")
                    2500L
                } else {
                    500L
                }
                scheduleSendSequence(delay)
            }
        }
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = rootNode.findAccessibilityNodeInfosByText(text)
        if (list != null && list.isNotEmpty()) {
            return list[0]
        }
        return null
    }

    private fun handleErrorAndFallback(rootNode: AccessibilityNodeInfo, reason: String) {
        Log.d(TAG, "Handling Error: $reason")
        
        // Click OK if present (to dismiss dialogs)
        val okButton = findNodeByText(rootNode, "OK")
        okButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        scheduleBackTwice {
            val phone = AutomationState.currentPhoneNumber
            val msg = AutomationState.currentMessage
            if (phone != null && msg != null) {
                sendSMS(phone, msg)
            } else {
                Log.e(TAG, "Cannot send SMS: Phone or Message is null")
            }

            AutomationState.shouldSend = false
            AutomationState.retryCount = 0
            actionSequenceScheduled = false
        }
    }
    
    private fun sendSMS(phoneNumber: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS permission not granted. Opening SMS composer.")
            openSmsComposer(phoneNumber, message)
            return
        }

        try {
            Log.d(TAG, "Attempting to send SMS to $phoneNumber")
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.d(TAG, "SMS sent successfully")
            Toast.makeText(this, "Sent via SMS", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
            e.printStackTrace()
            openSmsComposer(phoneNumber, message)
        }
    }

    private fun openSmsComposer(phoneNumber: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Opened SMS app to send message", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SMS composer", e)
        }
    }

    private fun scheduleBackTwice(onComplete: (() -> Unit)? = null) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            onComplete?.invoke()
        }, 500L)
    }

    private fun scheduleSendSequence(initialDelayMs: Long) {
        mainHandler.postDelayed({
            val rootNode = rootInActiveWindow
            val sendButton = rootNode?.let { findSendButton(it) }
            if (sendButton == null) {
                Log.e(TAG, "Send button unavailable when send sequence executed")
                actionSequenceScheduled = false
                return@postDelayed
            }

            Log.d(TAG, "Clicking send button now")
            val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                Log.e(TAG, "Failed to click send button")
                actionSequenceScheduled = false
                return@postDelayed
            }

            Log.d(TAG, "Send button clicked successfully")
            AutomationState.shouldSend = false

            mainHandler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "Performed first BACK action")
            }, 1000L)

            mainHandler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "Performed second BACK action")
                actionSequenceScheduled = false
            }, 1500L)
        }, initialDelayMs)
    }

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Try by Resource ID
        val nodesById = rootNode.findAccessibilityNodeInfosByViewId(SEND_BUTTON_ID)
        if (nodesById != null && nodesById.isNotEmpty()) {
            return nodesById[0]
        }

        // 2. Try by Content Description (Fallback)
        val nodesByDesc = rootNode.findAccessibilityNodeInfosByText(SEND_BUTTON_DESC)
        if (nodesByDesc != null && nodesByDesc.isNotEmpty()) {
            for (node in nodesByDesc) {
                if (node.className == "android.widget.ImageButton" || node.className == "android.widget.ImageView") {
                     return node
                }
            }
        }
        
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        actionSequenceScheduled = false
    }
}
