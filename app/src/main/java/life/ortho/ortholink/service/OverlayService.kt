package life.ortho.ortholink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
        startForeground(1, notification)

        if (intent?.action == "STOP") {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "STOP_WITH_DELAY") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopForeground(true)
                stopSelf()
            }, 5000) // 5 seconds delay
            return START_NOT_STICKY
        }

        phoneNumber = intent?.getStringExtra("PHONE_NUMBER")
        isOutgoing = intent?.getBooleanExtra("IS_OUTGOING", false) ?: false
        
        if (phoneNumber != null) {
            // Check if number is in contacts
            if (contactExists(this, phoneNumber!!)) {
                // It's a saved contact, do not show overlay
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            // Fetch data FIRST, then show overlay
            fetchData(phoneNumber!!)
        }
        return START_NOT_STICKY
    }

    private fun contactExists(context: Context, number: String): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false // Permission not granted, assume unknown
        }
        
        val lookupUri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup._ID)
        var cur: android.database.Cursor? = null
        try {
            cur = context.contentResolver.query(lookupUri, projection, null, null, null)
            if (cur != null && cur.moveToFirst()) {
                return true // Contact found
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cur?.close()
        }
        return false
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
        
        val tvCallerNumber = overlayView!!.findViewById<TextView>(R.id.tvCallerNumber)
        val tvCallerName = overlayView!!.findViewById<TextView>(R.id.tvCallerName)
        val layoutLocation = overlayView!!.findViewById<LinearLayout>(R.id.layoutLocation)
        val tvLocation = overlayView!!.findViewById<TextView>(R.id.tvLocation)
        val tvDate = overlayView!!.findViewById<TextView>(R.id.tvDate)
        
        val layoutActions = overlayView!!.findViewById<LinearLayout>(R.id.layoutActions)
        val layoutCallControls = overlayView!!.findViewById<LinearLayout>(R.id.layoutCallControls)
        val layoutWhatsAppControl = overlayView!!.findViewById<LinearLayout>(R.id.layoutWhatsAppControl)
        val layoutLocationButtons = overlayView!!.findViewById<LinearLayout>(R.id.layoutLocationButtons)
        
        val btnReceiveCall = overlayView!!.findViewById<Button>(R.id.btnReceiveCall)
        val btnWhatsApp = overlayView!!.findViewById<Button>(R.id.btnWhatsApp)
        val btnClinic = overlayView!!.findViewById<Button>(R.id.btnClinic)
        val btnLaxmi = overlayView!!.findViewById<Button>(R.id.btnLaxmi)
        val btnBadam = overlayView!!.findViewById<Button>(R.id.btnBadam)

        val hsvPatients = overlayView!!.findViewById<android.widget.HorizontalScrollView>(R.id.hsvPatients)
        val layoutPatientsContainer = overlayView!!.findViewById<LinearLayout>(R.id.layoutPatientsContainer)
        val layoutCalendarEvents = overlayView!!.findViewById<LinearLayout>(R.id.layoutCalendarEvents)
        val cardCalendarEvents = overlayView!!.findViewById<androidx.cardview.widget.CardView>(R.id.cardCalendarEvents)

        tvCallerNumber.text = phone
        
        // Handle Unknown Caller (Patient not found) => Strip View
        val patient = patients?.firstOrNull()
        if (patients.isNullOrEmpty() || patient == null) {
            // Adjust Window Layout Params for Strip
            // Use the layoutParams we just created above!
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.gravity = Gravity.CENTER
            
            // Transparent background
            mainContent.background = null
            mainContent.setPadding(0, 0, 0, 0)
            
            // Hide full screen elements
            cardCallerInfo.visibility = View.GONE
            scrollViewDetails.visibility = View.GONE
            
            // Show Actions but hide specific controls
            layoutActions.visibility = View.VISIBLE
            layoutCallControls.visibility = View.GONE
            layoutWhatsAppControl.visibility = View.GONE
            layoutLocationButtons.visibility = View.VISIBLE
            
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
        // Use name from API if available, otherwise construct it
        val displayName = patient.name ?: "${patient.firstName ?: ""} ${patient.lastName ?: ""}".trim()
        tvCallerName.text = if (displayName.isNotEmpty()) displayName else "Unknown Caller"
        
        // Location
        if (!patient.location.isNullOrEmpty()) {
            tvLocation.text = patient.location
            layoutLocation.visibility = View.VISIBLE
        }

        // Date
        if (!patient.createdAt.isNullOrEmpty()) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                // Handle timezone if needed, usually ISO is UTC but we might need lenient parsing
                // or just parse the first part if it has milliseconds/timezone
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC") // Assuming DB is UTC
                    val dateObj = sdf.parse(patient.createdAt.split("+")[0].split(".")[0]) // clean up ISO string
                    
                    if (dateObj != null) {
                        val now = System.currentTimeMillis()
                        val diff = now - dateObj.time
                        val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff)
                        
                        val relativeTime = when {
                            days < 7 -> android.text.format.DateUtils.getRelativeTimeSpanString(
                                dateObj.time,
                                now,
                                android.text.format.DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                            days < 30 -> "${days / 7} weeks ago"
                            days < 365 -> "${days / 30} months ago"
                            else -> "${days / 365} years ago"
                        }
                        
                        val displayFormat = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
                        displayFormat.timeZone = java.util.TimeZone.getDefault() // Show in local time
                        val formattedDate = displayFormat.format(dateObj)
                        
                        tvDate.text = "$relativeTime ($formattedDate)"
                    } else {
                         tvDate.text = patient.createdAt.substring(0, 10)
                    }
                    tvDate.visibility = View.VISIBLE
                } catch (e: Exception) {
                    // Fallback
                     try {
                        tvDate.text = patient.createdAt.substring(0, 10)
                        tvDate.visibility = View.VISIBLE
                     } catch(e2: Exception) {}
                }
            }
            
            // Display all matching patients in horizontally scrollable cards
            layoutPatientsContainer.removeAllViews()
            
            for (p in patients) {
                val cardView = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.item_patient_card, layoutPatientsContainer, false)
                
                val tvCardPatientName = cardView.findViewById<TextView>(R.id.tvCardPatientName)
                val cardDisplayName = p.name ?: "${p.firstName ?: ""} ${p.lastName ?: ""}".trim()
                if (cardDisplayName.isNotEmpty()) {
                    tvCardPatientName.text = cardDisplayName
                    tvCardPatientName.visibility = View.VISIBLE
                }

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
                        // If we don't have a patient name from the DB, use this one
                        if (tvCallerName.text == "Unknown Caller") {
                            tvCallerName.text = value
                        }
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
            windowManager?.addView(overlayView, layoutParams)
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
        
        // Remove overlay first
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error removing overlay", e)
        }
        
        stopForeground(true)
        stopSelf()
        
        android.util.Log.d("OverlayService", "Service stop requested")
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
            if (patientReqDone && calendarReqDone) {
                showOverlay(phone, patientDetails, calendarEvents)
            }
        }

        SupabaseClient.api.searchPatients(
            SupabaseClient.API_KEY,
            "Bearer ${SupabaseClient.API_KEY}",
            life.ortho.ortholink.model.SearchRequest(searchTerm = queryPhone)
        ).enqueue(object : Callback<List<PatientDetails>> {
            override fun onResponse(call: Call<List<PatientDetails>>, response: Response<List<PatientDetails>>) {
                if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                    patientDetails = response.body()!!.sortedByDescending { it.createdAt ?: "" }
                }
                patientReqDone = true
                checkAndShow()
            }

            override fun onFailure(call: Call<List<PatientDetails>>, t: Throwable) {
                patientReqDone = true
                checkAndShow()
            }
        })

        SupabaseClient.api.searchCalendarEvents(
            SupabaseClient.API_KEY,
            "Bearer ${SupabaseClient.API_KEY}",
            mapOf("phoneNumber" to queryPhone)
        ).enqueue(object : Callback<CalendarEventResponse> {
            override fun onResponse(call: Call<CalendarEventResponse>, response: Response<CalendarEventResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    calendarEvents = response.body()!!.calendarEvents
                }
                calendarReqDone = true
                checkAndShow()
            }

            override fun onFailure(call: Call<CalendarEventResponse>, t: Throwable) {
                calendarReqDone = true
                checkAndShow()
            }
        })
    }

    private fun bindDetail(layout: View, textView: TextView, value: String?) {
        if (!value.isNullOrEmpty() && value != "-") {
            textView.text = value
            layout.visibility = View.VISIBLE
        } else {
            layout.visibility = View.GONE
        }
    }

    private val retryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "life.ortho.ortholink.ACTION_RETRY") {
                val phone = AutomationState.currentPhoneNumber
                val message = AutomationState.currentMessage
                if (phone != null) {
                    android.util.Log.d("OverlayService", "Retrying WhatsApp automation...")
                    // Reset shouldSend to true, but keep retryCount (it's managed by AutomationService)
                    AutomationState.shouldSend = true
                    
                    // Re-launch WhatsApp
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
        }
    }

    private val phoneStateListener = object : android.telephony.PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            android.util.Log.d("OverlayService", "Call state changed: $state (IDLE=${android.telephony.TelephonyManager.CALL_STATE_IDLE})")
            if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                android.util.Log.d("OverlayService", "Call ended, stopping service")
                // Add a small delay to ensure call is fully ended
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stopForeground(true)
                    stopSelf()
                }, 500)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        
        // Register retry receiver
        val filter = android.content.IntentFilter("life.ortho.ortholink.ACTION_RETRY")
        ContextCompat.registerReceiver(
            this,
            retryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        android.util.Log.d("OverlayService", "onDestroy() called")
        super.onDestroy()
        
        // Unregister retry receiver
        try {
            unregisterReceiver(retryReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Unregister phone state listener
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_NONE)
            android.util.Log.d("OverlayService", "PhoneStateListener unregistered")
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Error unregistering listener", e)
        }
        
        // Remove overlay
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
                android.util.Log.d("OverlayService", "Overlay removed")
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "Error removing overlay in onDestroy", e)
                e.printStackTrace()
            }
            overlayView = null
        }
        
        android.util.Log.d("OverlayService", "onDestroy() completed")
    }
}
