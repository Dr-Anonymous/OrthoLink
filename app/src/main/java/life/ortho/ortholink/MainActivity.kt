package life.ortho.ortholink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.CallLog
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.content.Context
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val baseUrl = "https://ortho.life/wa"

    // JavaScript interface for clipboard access
    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun getClipboardText(): String {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                return clip.getItemAt(0).coerceToText(this@MainActivity).toString()
            }
            return ""
        }

        @android.webkit.JavascriptInterface
        fun isWhatsAppInstalled(): Boolean {
            return isAppInstalled("com.whatsapp")
        }

        @android.webkit.JavascriptInterface
        fun isWhatsAppBusinessInstalled(): Boolean {
            return isAppInstalled("com.whatsapp.w4b")
        }

        private fun isAppInstalled(packageName: String): Boolean {
            return try {
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        setupWebView()
        // Add the JavaScript interface for clipboard and general Android features
        val webInterface = WebAppInterface()
        webView.addJavascriptInterface(webInterface, "AndroidClipboard")
        webView.addJavascriptInterface(webInterface, "Android")

        val fabRefresh: FloatingActionButton = findViewById(R.id.fab_refresh)
        fabRefresh.setOnClickListener {
            webView.reload()
        }

        checkAndRequestPermissions()
        
        // Check for updates
        life.ortho.ortholink.manager.UpdateManager.checkForUpdate(this)
    }

    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let { urlString ->
                    when {
                        urlString.startsWith("tel:") -> {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(urlString))
                            startActivity(intent)
                            return true
                        }
                        urlString.startsWith("sms:") -> {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(urlString))
                            startActivity(intent)
                            return true
                        }
                        urlString.startsWith("whatsapp:") -> {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlString))
                                startActivity(intent)
                                return true
                            } catch (e: Exception) {
                                // If WhatsApp is not installed, do nothing, as we can't open the link.
                                return true // Prevent WebView from trying to load it.
                            }
                        }
                        urlString.startsWith("http://") || urlString.startsWith("https://") -> {
                            val uri = Uri.parse(urlString)
                            if (uri.host == "ortho.life") {
                                return false // Load in WebView
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                startActivity(intent)
                                return true // Handled by browser
                            }
                        }
                        else -> {
                            // Let WebView handle it
                            return false
                        }
                    }
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val newWebView = WebView(this@MainActivity).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            url?.let {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                                startActivity(intent)
                            }
                            return true // The URL is handled.
                        }
                    }
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        val settings = webView.settings
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            // Enable clipboard access
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true

            // Caching
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        // Enable long press for context menu (paste, etc.)
        webView.isLongClickable = true
        webView.setOnLongClickListener { false } // Return false to allow default context menu
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        permissions.add(Manifest.permission.PROCESS_OUTGOING_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkOverlayPermission()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check if all permissions are granted
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkOverlayPermission()
        } else {
            // Some permissions denied, maybe show rationale or proceed with limited functionality
            // For now, try to proceed to overlay check anyway, or just load webview
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            loadWebViewWithNumber()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Permission denied
            loadWebViewWithNumber()
        } else {
            // Permission granted
            loadWebViewWithNumber()
        }
    }

    private fun loadWebViewWithNumber() {
        val callDetails = getRecentCallDetails()
        if (!callDetails.isNullOrEmpty()) {
            val urlBuilder = StringBuilder("$baseUrl?")
            callDetails.forEach { (number, name, date) ->
                urlBuilder.append("numbers[]=${Uri.encode(number)}&")
                urlBuilder.append("names[]=${Uri.encode(name ?: "")}&")
                urlBuilder.append("timestamps[]=${date}&")
            }
            // Remove the last '&'
            val url = urlBuilder.toString().dropLast(1)
            webView.loadUrl(url)
        } else {
            webView.loadUrl(baseUrl)
        }
    }

    private fun getRecentCallDetails(): List<Triple<String, String?, Long>>? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE)
        // Fetch more calls to have a buffer for finding unique ones
        val limitedUri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "25")
            .build()
        val cursor = contentResolver.query(
            limitedUri,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            val numberColumn = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameColumn = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val dateColumn = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val callDetails = mutableListOf<Triple<String, String?, Long>>()
            val seenNumbers = HashSet<String>()
            while (it.moveToNext() && callDetails.size < 5) {
                val number = it.getString(numberColumn)
                if (seenNumbers.add(number)) {
                    val name = it.getString(nameColumn)
                    val date = it.getLong(dateColumn)
                    callDetails.add(Triple(number, name, date))
                }
            }
            return callDetails
        }
        return null
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
