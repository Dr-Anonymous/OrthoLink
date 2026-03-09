package life.ortho.ortholink.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import androidx.annotation.RequiresApi
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.provider.CallLog
import android.provider.ContactsContract
import life.ortho.ortholink.R
import life.ortho.ortholink.model.CalendarEvent
import life.ortho.ortholink.model.CalendarEventResponse
import life.ortho.ortholink.model.Patient
import life.ortho.ortholink.model.PatientDetails
import life.ortho.ortholink.network.SupabaseClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var phoneNumber: String? = null
    private var isOutgoing: Boolean = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile
    private var shouldIgnoreOverlayUpdates: Boolean = false
    private var delayedStopRunnable: Runnable? = null
    private var patientSearchCall: Call<List<PatientDetails>>? = null
    private var calendarSearchCall: Call<CalendarEventResponse>? = null
    
    private var callerNameFromIntent: String? = null
    
    // Support for newer Android versions (API 31+)
    private var telephonyCallback: Any? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private val CHANNEL_ID = "OverlayServiceChannel"



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Caller ID Active")
            .setContentText("Identifying caller...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        // MUST call startForeground immediately, otherwise the app will crash
        // if started with startForegroundService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(1, notification)
        }

        if (intent?.action == "STOP") {
            requestServiceStop(delayMs = 0)
            return START_NOT_STICKY
        }

        if (intent?.action == "STOP_WITH_DELAY") {
            requestServiceStop(delayMs = 5000)
            return START_NOT_STICKY
        }

        shouldIgnoreOverlayUpdates = false
        clearDelayedStopRunnable()

        phoneNumber = intent?.getStringExtra("PHONE_NUMBER")?.trim()?.takeIf { it.isNotEmpty() }
        callerNameFromIntent = sanitizeCallerName(intent?.getStringExtra("CALLER_NAME"))
        isOutgoing = intent?.getBooleanExtra("IS_OUTGOING", false) ?: false
        
        if (phoneNumber != null) {
            // Fetch data FIRST, then show overlay
            fetchData(phoneNumber!!)
        } else if (!callerNameFromIntent.isNullOrEmpty()) {
            showOverlay("", null, null)
        }
        return START_NOT_STICKY
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Caller ID Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun showOverlay(phone: String, patients: List<PatientDetails>?, calendarEvents: List<CalendarEvent>?) {
        if (shouldIgnoreOverlayUpdates) return
        if (overlayView != null) return // Already showing

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        // layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // Not needed for match_parent
        // layoutParams.y = 100 // Not needed

        // Use ContextThemeWrapper to ensure theme attributes (like ?attr/selectableItemBackgroundBorderless) are resolved
        val contextThemeWrapper = android.view.ContextThemeWrapper(this, R.style.Theme_OrthoLink)
        overlayView = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.layout_caller_id, null)

        val mainContent = overlayView!!.findViewById<LinearLayout>(R.id.mainContent)
        val cardCallerInfo = overlayView!!.findViewById<androidx.cardview.widget.CardView>(R.id.cardCallerInfo)
        val scrollViewDetails = overlayView!!.findViewById<android.widget.ScrollView>(R.id.scrollViewDetails)
        
        val tvCallerName = overlayView!!.findViewById<TextView>(R.id.tvCallerName)
        val tvCallerNumber = overlayView!!.findViewById<TextView>(R.id.tvCallerNumber)
        val tvPatientInfo = overlayView!!.findViewById<TextView>(R.id.tvPatientInfo)
        
        val layoutActions = overlayView!!.findViewById<LinearLayout>(R.id.layoutActions)
        val layoutCallControls = overlayView!!.findViewById<LinearLayout>(R.id.layoutCallControls)
        val layoutWhatsAppControl = overlayView!!.findViewById<LinearLayout>(R.id.layoutWhatsAppControl)
        val layoutLocationButtons = overlayView!!.findViewById<LinearLayout>(R.id.layoutLocationButtons)
        
        val btnReceiveCall = overlayView!!.findViewById<Button>(R.id.btnReceiveCall)
        val btnWhatsApp = overlayView!!.findViewById<Button>(R.id.btnWhatsApp)

        val hsvPatients = overlayView!!.findViewById<android.widget.HorizontalScrollView>(R.id.hsvPatients)
        val layoutPatientsContainer = overlayView!!.findViewById<LinearLayout>(R.id.layoutPatientsContainer)
        val layoutCalendarEvents = overlayView!!.findViewById<LinearLayout>(R.id.layoutCalendarEvents)
        val cardCalendarEvents = overlayView!!.findViewById<androidx.cardview.widget.CardView>(R.id.cardCalendarEvents)

        val displayNumber = phone.takeIf { it.isNotBlank() } ?: "Unknown Number"
        tvCallerNumber.text = displayNumber

        // Name fallback order: contacts -> broadcast/service provided caller id -> recent call-log cache
        val finalCallerName = resolveCallerName(phone)
        
        if (!finalCallerName.isNullOrEmpty()) {
            tvCallerName.text = finalCallerName
            tvCallerName.visibility = View.VISIBLE
            // Style phone number as secondary
            tvCallerNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            tvCallerNumber.setTextColor(android.graphics.Color.parseColor("#B0BEC5"))
            tvCallerNumber.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            tvCallerName.visibility = View.GONE
            // Style phone number as primary
            tvCallerNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            tvCallerNumber.setTextColor(android.graphics.Color.WHITE)
            tvCallerNumber.setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Handle Unknown Caller (No Patient and No Calendar Event) => Strip View
        val patient = patients?.firstOrNull()
        if ((patients.isNullOrEmpty() || patient == null) && calendarEvents.isNullOrEmpty()) {
            // Adjust Window Layout Params for Strip
            // Use the layoutParams we just created above!
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.gravity = Gravity.CENTER
            
            // Transparent background
            mainContent.background = null
            mainContent.setPadding(0, 0, 0, 0)
            
            // Hide caller header in strip view as it's shown in caller screen
            cardCallerInfo.visibility = View.GONE
            scrollViewDetails.visibility = View.GONE
            
            // Show Actions but hide specific controls
            if (phone.isBlank()) {
                layoutActions.visibility = View.GONE
            } else {
                layoutActions.visibility = View.VISIBLE
                layoutCallControls.visibility = View.GONE
                layoutWhatsAppControl.visibility = View.GONE
                layoutLocationButtons.visibility = View.VISIBLE
            }
            
            // Ensure location buttons are visible (parent lin layout is visible by default)
            
            // Populate buttons listeners (same as below)
            // Unknown caller setup done in setupLocationButtons called earlier
             setupLocationButtons(overlayView!!, phone)
            
            
            // We are done for unknown caller
            try {
                if (overlayView!!.parent == null) {
                     windowManager?.addView(overlayView, layoutParams)
                } else {
                     windowManager?.updateViewLayout(overlayView, layoutParams)
                }
            } catch (e: Exception) { e.printStackTrace() }
            return
        }

        // --- Known Caller Logic (Full Overlay) ---
        // Ensure main content is visible
        cardCallerInfo.visibility = View.VISIBLE
        scrollViewDetails.visibility = View.VISIBLE
        tvPatientInfo.visibility = View.GONE // Hide "Fetching..." text

        // Display all matching patients in horizontally scrollable cards
        layoutPatientsContainer.removeAllViews()
            
            for (p in patients.orEmpty()) {
                val cardView = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.item_patient_card, layoutPatientsContainer, false)
                
                val tvCardPatientName = cardView.findViewById<TextView>(R.id.tvCardPatientName)
                val cardDisplayName = p.name ?: "${p.firstName ?: ""} ${p.lastName ?: ""}".trim()
                if (cardDisplayName.isNotEmpty()) {
                    tvCardPatientName.text = cardDisplayName
                    tvCardPatientName.visibility = View.VISIBLE
                }

                // Bind Last Visit Summary
                val layoutLastVisitSummary = cardView.findViewById<LinearLayout>(R.id.layoutLastVisitSummary)
                val tvCardLastVisitDate = cardView.findViewById<TextView>(R.id.tvCardLastVisitDate)
                val tvCardLocation = cardView.findViewById<TextView>(R.id.tvCardLocation)
                val tvCardVisitType = cardView.findViewById<TextView>(R.id.tvCardVisitType)
                val divider1 = cardView.findViewById<View>(R.id.viewCardVisitDivider1)
                val divider2 = cardView.findViewById<View>(R.id.viewCardVisitDivider2)

                var summaryPopulated = false

                // 1. Date
                if (!p.createdAt.isNullOrEmpty()) {
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val dateObj = sdf.parse(p.createdAt.split("+")[0].split(".")[0])
                        if (dateObj != null) {
                            val now = System.currentTimeMillis()
                            val diff = now - dateObj.time
                            val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff)
                            val relativeTime = when {
                                days < 7 -> android.text.format.DateUtils.getRelativeTimeSpanString(dateObj.time, now, android.text.format.DateUtils.DAY_IN_MILLIS).toString()
                                days < 30 -> "${days / 7}w ago"
                                days < 365 -> "${days / 30}m ago"
                                else -> "${days / 365}y ago"
                            }
                            tvCardLastVisitDate.text = relativeTime
                            tvCardLastVisitDate.visibility = View.VISIBLE
                            summaryPopulated = true
                        } else {
                            tvCardLastVisitDate.visibility = View.GONE
                        }
                    } catch (e: Exception) {
                        tvCardLastVisitDate.visibility = View.GONE
                    }
                } else {
                    tvCardLastVisitDate.visibility = View.GONE
                }

                // 2. Location
                if (!p.location.isNullOrEmpty()) {
                    tvCardLocation.text = p.location
                    tvCardLocation.visibility = View.VISIBLE
                    divider1.visibility = if (tvCardLastVisitDate.visibility == View.VISIBLE) View.VISIBLE else View.GONE
                    summaryPopulated = true
                } else {
                    tvCardLocation.visibility = View.GONE
                    divider1.visibility = View.GONE
                }

                // 3. Visit Type
                if (!p.visitType.isNullOrEmpty()) {
                    tvCardVisitType.text = p.visitType
                    tvCardVisitType.visibility = View.VISIBLE
                    divider2.visibility = if (tvCardLocation.visibility == View.VISIBLE || tvCardLastVisitDate.visibility == View.VISIBLE) View.VISIBLE else View.GONE
                    summaryPopulated = true
                } else {
                    tvCardVisitType.visibility = View.GONE
                    divider2.visibility = View.GONE
                }

                layoutLastVisitSummary.visibility = if (summaryPopulated) View.VISIBLE else View.GONE

                // Bind Vitals
                val vitalsList = mutableListOf<String>()
                if (!p.bp.isNullOrEmpty()) vitalsList.add("BP: ${p.bp}")
                if (!p.weight.isNullOrEmpty()) vitalsList.add("Weight: ${p.weight}")
                if (!p.temperature.isNullOrEmpty()) vitalsList.add("Temp: ${p.temperature}")
                
                val vitalsText = if (vitalsList.isNotEmpty()) vitalsList.joinToString("  |  ") else null
                bindDetail(cardView.findViewById(R.id.layoutVitals), cardView.findViewById(R.id.tvVitals), vitalsText)

                // Bind details
                bindDetail(cardView.findViewById(R.id.layoutReferredBy), cardView.findViewById(R.id.tvReferredBy), p.referredBy)
                bindDetail(cardView.findViewById(R.id.layoutPersonalNote), cardView.findViewById(R.id.tvPersonalNote), p.personalNote)
                bindDetail(cardView.findViewById(R.id.layoutComplaints), cardView.findViewById(R.id.tvComplaints), p.complaints)
                bindDetail(cardView.findViewById(R.id.layoutFindings), cardView.findViewById(R.id.tvFindings), p.findings)
                bindDetail(cardView.findViewById(R.id.layoutInvestigations), cardView.findViewById(R.id.tvInvestigations), p.investigations)
                bindDetail(cardView.findViewById(R.id.layoutDiagnosis), cardView.findViewById(R.id.tvDiagnosis), p.diagnosis)
                bindDetail(cardView.findViewById(R.id.layoutProcedure), cardView.findViewById(R.id.tvProcedure), p.procedure)
                bindDetail(cardView.findViewById(R.id.layoutAdvice), cardView.findViewById(R.id.tvAdvice), p.advice)
                bindDetail(cardView.findViewById(R.id.layoutFollowUp), cardView.findViewById(R.id.tvFollowUp), p.followup)
                bindDetail(cardView.findViewById(R.id.layoutReferredTo), cardView.findViewById(R.id.tvReferredTo), p.referredTo)

                // Parse medications - NAMES ONLY
                var medsText: String? = null
                if (p.medications != null) {
                    if (p.medications.isJsonArray) {
                        val medsArray = p.medications.asJsonArray
                        val medsList = mutableListOf<String>()
                        medsArray.forEach { 
                            if (it.isJsonObject) {
                                val obj = it.asJsonObject
                                val name = if (obj.has("name")) obj.get("name").asString else ""
                                if (name.isNotEmpty()) {
                                    medsList.add(name)
                                }
                            }
                        }
                        if (medsList.isNotEmpty()) {
                            medsText = medsList.joinToString(", ")
                        }
                    } else if (p.medications.isJsonPrimitive) {
                        medsText = p.medications.asString
                    }
                }
                bindDetail(cardView.findViewById(R.id.layoutMedications), cardView.findViewById(R.id.tvMedications), medsText)
                
                layoutPatientsContainer.addView(cardView)
            }

            hsvPatients.visibility = View.VISIBLE
            
        // Bind Calendar Events
        if (!calendarEvents.isNullOrEmpty()) {
            val event = calendarEvents[0] // Show first event for now
            val tvEventDate = overlayView!!.findViewById<TextView>(R.id.tvCalendarEventDate)
            val tvEventDesc = overlayView!!.findViewById<TextView>(R.id.tvCalendarEventDescription)
            
            try {
                tvEventDate.text = event.start.replace("T", " ").substring(0, 16)
            } catch (e: Exception) {
                tvEventDate.text = event.start
            }
            // Parse and format description
            val description = event.description
            val lines = description.split("\n")
            val formattedDescription = StringBuilder()
            
            for (line in lines) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    
                    if (key.equals("Phone", ignoreCase = true) ||
                        key.equals("SMS", ignoreCase = true) ||
                        key.equals("WhatsApp", ignoreCase = true) ||
                        key.equals("Payment", ignoreCase = true)) {
                        continue
                    }
                    
                    if (key.equals("Patient", ignoreCase = true)) {
                        // We no longer show the name in the header, 
                        // but we could use this value if we needed to populate a generic header
                        continue // Skip displaying Patient field in description
                    }

                    if (key.equals("DOB", ignoreCase = true)) {
                        try {
                            // Calculate Age
                            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                            val dobDate = sdf.parse(value)
                            if (dobDate != null) {
                                val dobCalendar = java.util.Calendar.getInstance()
                                dobCalendar.time = dobDate
                                val today = java.util.Calendar.getInstance()
                                var age = today.get(java.util.Calendar.YEAR) - dobCalendar.get(java.util.Calendar.YEAR)
                                if (today.get(java.util.Calendar.DAY_OF_YEAR) < dobCalendar.get(java.util.Calendar.DAY_OF_YEAR)) {
                                    age--
                                }
                                formattedDescription.append("Age: $age\n")
                            }
                        } catch (e: Exception) {
                            formattedDescription.append("$key: $value\n")
                        }
                    } else {
                        if (value.isNotEmpty()) {
                            formattedDescription.append("$key: $value\n")
                        }
                    }
                } else {
                     // Handle lines without ": " if necessary, or append as is if not empty
                     if (line.isNotBlank()) {
                         formattedDescription.append("$line\n")
                     }
                }
            }
            
            tvEventDesc.text = formattedDescription.toString().trim()
            
            val tvEventAttachment = overlayView!!.findViewById<TextView>(R.id.tvCalendarEventAttachment)
            val layoutAttachmentPreview = overlayView!!.findViewById<LinearLayout>(R.id.layoutAttachmentPreview)
            val wvAttachment = overlayView!!.findViewById<android.webkit.WebView>(R.id.wvAttachment)
            val btnCloseAttachment = overlayView!!.findViewById<Button>(R.id.btnCloseAttachment)

            if (!event.attachments.isNullOrEmpty()) {
                tvEventAttachment.visibility = View.VISIBLE
                
                // Configure WebView
                wvAttachment.settings.javaScriptEnabled = true
                wvAttachment.webViewClient = android.webkit.WebViewClient()
                
                tvEventAttachment.setOnClickListener {
                    layoutAttachmentPreview.visibility = View.VISIBLE
                    wvAttachment.loadUrl(event.attachments)
                }
                
                btnCloseAttachment.setOnClickListener {
                    layoutAttachmentPreview.visibility = View.GONE
                    wvAttachment.loadUrl("about:blank") // Clear content
                }
            } else {
                tvEventAttachment.visibility = View.GONE
                layoutAttachmentPreview.visibility = View.GONE
            }

            layoutCalendarEvents.visibility = View.VISIBLE
            cardCalendarEvents.visibility = View.VISIBLE
        } else {
            cardCalendarEvents.visibility = View.GONE
        }

        layoutActions.visibility = View.VISIBLE

        val btnMinimize = overlayView!!.findViewById<Button>(R.id.btnMinimize)
        val btnDecline = overlayView!!.findViewById<Button>(R.id.btnDecline)
        val btnRestore = overlayView!!.findViewById<ImageButton>(R.id.btnRestore)

        btnMinimize.setOnClickListener {
            minimizeOverlay()
        }

        btnDecline.setOnClickListener {
            endCall()
        }

        btnRestore.setOnClickListener {
            restoreOverlay()
        }

        var isCallActive = false

        if (isOutgoing) {
            // Outgoing call: Show End button (Decline button styled as End), Hide Receive
            btnReceiveCall.visibility = View.GONE
            btnDecline.visibility = View.VISIBLE
            // For outgoing calls, show minimize button
            btnMinimize.visibility = View.VISIBLE
            
            // Style btnDecline as "End Call"
            // It already has the red X icon.
        } else {
            // Incoming call
            btnReceiveCall.visibility = View.VISIBLE
            btnDecline.visibility = View.VISIBLE
            btnMinimize.visibility = View.GONE

            btnReceiveCall.setOnClickListener {
                if (!isCallActive) {
                    if (acceptCall()) {
                        isCallActive = true
                        btnReceiveCall.text = "End"
                        btnReceiveCall.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336")) // Red
                        
                        btnDecline.visibility = View.GONE
                        btnMinimize.visibility = View.VISIBLE
                    }
                } else {
                    endCall()
                }
            }
        }

        btnWhatsApp.setOnClickListener {
            openWhatsApp(phone, "/", autoSend = false)
        }

        // Known caller setup
        setupLocationButtons(overlayView!!, phone)


        try {
            if (overlayView!!.parent == null) {
                windowManager?.addView(overlayView, layoutParams)
            } else {
                windowManager?.updateViewLayout(overlayView, layoutParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }
    }



    private fun minimizeOverlay() {
        if (overlayView == null || windowManager == null) return

        val mainContent = overlayView!!.findViewById<LinearLayout>(R.id.mainContent)
        val btnRestore = overlayView!!.findViewById<ImageButton>(R.id.btnRestore)

        mainContent.visibility = View.GONE
        btnRestore.visibility = View.VISIBLE

        val layoutParams = overlayView!!.layoutParams as WindowManager.LayoutParams
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.x = 16 // 16dp from right edge
        layoutParams.y = 100 // 100dp from bottom

        try {
            windowManager!!.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreOverlay() {
        if (overlayView == null || windowManager == null) return

        val mainContent = overlayView!!.findViewById<LinearLayout>(R.id.mainContent)
        val btnRestore = overlayView!!.findViewById<ImageButton>(R.id.btnRestore)

        mainContent.visibility = View.VISIBLE
        btnRestore.visibility = View.GONE

        val layoutParams = overlayView!!.layoutParams as WindowManager.LayoutParams
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        
        try {
            windowManager!!.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acceptCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager.acceptRingingCall()
                    return true
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to answer call", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Not supported on this Android version", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun endCall() {
        android.util.Log.d("OverlayService", "endCall() called")
        
        // Try to end the call, but ALWAYS close the overlay afterwards
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    android.util.Log.d("OverlayService", "Attempting to end call via TelecomManager")
                    telecomManager.endCall()
                    android.util.Log.d("OverlayService", "telecomManager.endCall() succeeded")
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "Failed to end call", e)
                    e.printStackTrace()
                }
            } else {
                android.util.Log.w("OverlayService", "No permission to end call")
            }
        }
        
        // ALWAYS stop the service and close overlay when this button is pressed
        android.util.Log.d("OverlayService", "Stopping service and removing overlay")
        requestServiceStop(delayMs = 0)
        
        android.util.Log.d("OverlayService", "Service stop requested")
    }

    private fun handleCallStateIdle() {
        android.util.Log.d("OverlayService", "handleCallStateIdle() called")
        // Immediately hide overlay and stop to avoid stale UI after call end.
        requestServiceStop(delayMs = 0)
    }

    private fun setupLocationButtons(view: View, phone: String) {
        val btnClinic = view.findViewById<Button>(R.id.btnClinic)
        val btnLaxmi = view.findViewById<Button>(R.id.btnLaxmi)
        val btnBadam = view.findViewById<Button>(R.id.btnBadam)

        btnClinic.setOnClickListener {
            val isSunday = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY
            val msgResId = if (isSunday) R.string.msg_clinic_sunday else R.string.msg_clinic_weekday
            val message = getString(msgResId)
            openWhatsApp(phone, message, autoSend = true)
        }

        btnLaxmi.setOnClickListener {
            val message = getString(R.string.msg_laxmi)
            openWhatsApp(phone, message, autoSend = true)
        }

        btnBadam.setOnClickListener {
            val message = getString(R.string.msg_badam)
            openWhatsApp(phone, message, autoSend = true)
        }
    }

    private fun openWhatsApp(phone: String, message: String?, autoSend: Boolean = false) {
        try {
            // Set automation state
            AutomationState.shouldSend = autoSend
            AutomationState.currentPhoneNumber = phone
            AutomationState.currentMessage = message
            AutomationState.hasLink = message?.contains("http") == true
            AutomationState.retryCount = 0
            
            val url = if (message != null) {
                "https://api.whatsapp.com/send?phone=$phone&text=${java.net.URLEncoder.encode(message, "UTF-8")}"
            } else {
                "https://api.whatsapp.com/send?phone=$phone"
            }
            val i = Intent(Intent.ACTION_VIEW)
            i.data = Uri.parse(url)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
            
            // Minimize overlay instead of stopping service
            minimizeOverlay()
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            AutomationState.shouldSend = false // Reset on failure
        }
    }

    private fun fetchData(phone: String) {
        // Format phone number: remove +91 or 91 prefix if present
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        val queryPhone = if (cleanPhone.startsWith("91") && cleanPhone.length == 12) {
            cleanPhone.substring(2)
        } else {
            cleanPhone
        }

        var patientDetails: List<PatientDetails>? = null
        var calendarEvents: List<CalendarEvent>? = null
        var patientReqDone = false
        var calendarReqDone = false

        fun checkAndShow() {
            if (shouldIgnoreOverlayUpdates) return
            if (patientReqDone && calendarReqDone) {
                showOverlay(phone, patientDetails, calendarEvents)
            }
        }

        patientSearchCall = SupabaseClient.api.searchPatients(
            SupabaseClient.API_KEY,
            "Bearer ${SupabaseClient.API_KEY}",
            life.ortho.ortholink.model.SearchRequest(searchTerm = queryPhone)
        )
        patientSearchCall?.enqueue(object : Callback<List<PatientDetails>> {
            override fun onResponse(call: Call<List<PatientDetails>>, response: Response<List<PatientDetails>>) {
                if (call == patientSearchCall) patientSearchCall = null
                if (call.isCanceled || shouldIgnoreOverlayUpdates) return
                if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                    patientDetails = response.body()!!.sortedByDescending { it.createdAt ?: "" }
                }
                patientReqDone = true
                checkAndShow()
            }

            override fun onFailure(call: Call<List<PatientDetails>>, t: Throwable) {
                if (call == patientSearchCall) patientSearchCall = null
                if (call.isCanceled || shouldIgnoreOverlayUpdates) return
                patientReqDone = true
                checkAndShow()
            }
        })

        calendarSearchCall = SupabaseClient.api.searchCalendarEvents(
            SupabaseClient.API_KEY,
            "Bearer ${SupabaseClient.API_KEY}",
            mapOf("phoneNumber" to queryPhone)
        )
        calendarSearchCall?.enqueue(object : Callback<CalendarEventResponse> {
            override fun onResponse(call: Call<CalendarEventResponse>, response: Response<CalendarEventResponse>) {
                if (call == calendarSearchCall) calendarSearchCall = null
                if (call.isCanceled || shouldIgnoreOverlayUpdates) return
                if (response.isSuccessful && response.body() != null) {
                    calendarEvents = response.body()!!.calendarEvents
                }
                calendarReqDone = true
                checkAndShow()
            }

            override fun onFailure(call: Call<CalendarEventResponse>, t: Throwable) {
                if (call == calendarSearchCall) calendarSearchCall = null
                if (call.isCanceled || shouldIgnoreOverlayUpdates) return
                calendarReqDone = true
                checkAndShow()
            }
        })
    }

    private fun requestServiceStop(delayMs: Long) {
        shouldIgnoreOverlayUpdates = true
        cancelPendingRequests()
        removeOverlay()
        clearDelayedStopRunnable()

        val stopRunnable = Runnable {
            try {
                stopForeground(true)
            } catch (e: Exception) {
                android.util.Log.w("OverlayService", "stopForeground failed", e)
            }
            stopSelf()
        }

        if (delayMs > 0) {
            delayedStopRunnable = stopRunnable
            mainHandler.postDelayed(stopRunnable, delayMs)
        } else {
            stopRunnable.run()
        }
    }

    private fun clearDelayedStopRunnable() {
        delayedStopRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedStopRunnable = null
    }

    private fun cancelPendingRequests() {
        patientSearchCall?.cancel()
        patientSearchCall = null
        calendarSearchCall?.cancel()
        calendarSearchCall = null
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        if (windowManager == null || view.parent == null) return

        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error removing overlay", e)
        }
    }

    private fun getContactName(phone: String): String? {
        if (phone.isBlank()) return null
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var contactName: String? = null
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contactName = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sanitizeCallerName(contactName)
    }

    private fun resolveCallerName(phone: String): String? {
        if (phone.isBlank()) return callerNameFromIntent

        val contactName = getContactName(phone)
        if (!contactName.isNullOrBlank()) return contactName

        if (!callerNameFromIntent.isNullOrBlank()) return callerNameFromIntent

        return getCallLogCachedName(phone)
    }

    private fun getCallLogCachedName(phone: String): String? {
        if (phone.isBlank()) return null
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val targetDigits = normalizePhoneDigits(phone)
        if (targetDigits.isEmpty()) return null

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME)
        val selection = "${CallLog.Calls.CACHED_NAME} IS NOT NULL AND ${CallLog.Calls.CACHED_NAME} != ''"
        var matchedName: String? = null

        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 25"
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)

                while (cursor.moveToNext()) {
                    if (numberIndex < 0 || nameIndex < 0) break
                    val callLogNumber = cursor.getString(numberIndex)
                    val callLogName = sanitizeCallerName(cursor.getString(nameIndex))
                    if (callLogName.isNullOrBlank()) continue

                    if (phoneNumbersMatch(targetDigits, normalizePhoneDigits(callLogNumber))) {
                        matchedName = callLogName
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return matchedName
    }

    private fun normalizePhoneDigits(value: String?): String {
        return value?.filter { it.isDigit() }.orEmpty()
    }

    private fun phoneNumbersMatch(first: String, second: String): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        return first == second || first.endsWith(second) || second.endsWith(first)
    }

    private fun sanitizeCallerName(rawName: String?): String? {
        val candidate = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (candidate.lowercase(java.util.Locale.getDefault())) {
            "unknown", "unknown caller", "private number" -> null
            else -> candidate
        }
    }

    private fun bindDetail(layout: View, textView: TextView, value: String?) {
        if (!value.isNullOrEmpty() && value != "-") {
            textView.text = value
            layout.visibility = View.VISIBLE
        } else {
            layout.visibility = View.GONE
        }
    }

    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("OverlayService", "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "life.ortho.ortholink.ACTION_RETRY" -> {
                    val phone = AutomationState.currentPhoneNumber
                    val message = AutomationState.currentMessage
                    if (phone != null) {
                        android.util.Log.d("OverlayService", "Retrying WhatsApp automation...")
                        AutomationState.shouldSend = true
                        try {
                            val url = if (message != null) {
                                "https://api.whatsapp.com/send?phone=$phone&text=${java.net.URLEncoder.encode(message, "UTF-8")}"
                            } else {
                                "https://api.whatsapp.com/send?phone=$phone"
                            }
                            val i = Intent(Intent.ACTION_VIEW)
                            i.data = Uri.parse(url)
                            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(i)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    android.util.Log.d("OverlayService", "Broadcast Call state: $state")
                    if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                        handleCallStateIdle()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    android.util.Log.d("OverlayService", "Screen OFF detected")
                    // If screen goes off, double check if call is idle
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    if (tm.callState == TelephonyManager.CALL_STATE_IDLE) {
                        android.util.Log.d("OverlayService", "Call is IDLE on Screen OFF, closing")
                        handleCallStateIdle()
                    }
                }
            }
        }
    }

    private val phoneStateListener = object : android.telephony.PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            android.util.Log.d("OverlayService", "Call state changed (deprecated listener): $state")
            if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                handleCallStateIdle()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            android.util.Log.d("OverlayService", "Call state changed (TelephonyCallback): $state")
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                handleCallStateIdle()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        
        // Register listeners for call state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = CallStateCallback()
            telephonyCallback = callback
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        }
        
        // Register receiver for both retry and system events
        val filter = android.content.IntentFilter().apply {
            addAction("life.ortho.ortholink.ACTION_RETRY")
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        
        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        android.util.Log.d("OverlayService", "onDestroy() called")
        shouldIgnoreOverlayUpdates = true
        clearDelayedStopRunnable()
        cancelPendingRequests()
        super.onDestroy()
        
        // Unregister service receiver
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Unregister phone state listener/callback
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
            android.util.Log.d("OverlayService", "Phone state listeners unregistered")
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error unregistering listener", e)
        }
        
        // Remove overlay
        removeOverlay()
        android.util.Log.d("OverlayService", "Overlay removed")
        
        android.util.Log.d("OverlayService", "onDestroy() completed")
    }
}
