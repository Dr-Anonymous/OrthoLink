package life.ortho.ortholink.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import life.ortho.ortholink.R
import life.ortho.ortholink.model.ConsultationRequest
import life.ortho.ortholink.model.ConsultationResponse
import life.ortho.ortholink.model.Consultation
import life.ortho.ortholink.network.SupabaseClient
import life.ortho.ortholink.widget.PendingConsultationsWidget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return WidgetRemoteViewsFactory(this.applicationContext)
    }
}

class WidgetRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private val prefs = context.getSharedPreferences("pending_consultations_widget", Context.MODE_PRIVATE)

    companion object {
        private const val MIN_FETCH_INTERVAL_MS = 15_000L
        private const val PREF_LAST_FETCH_MS = "last_fetch_ms"
    }
    
    data class LocationStats(
        val locationName: String,
        val pendingCount: Int,
        val completedCount: Int,
        val totalCount: Int
    )

    private val data = mutableListOf<LocationStats>()

    override fun onCreate() {
        // No-op
    }

    override fun onDataSetChanged() {
        // This is called when we call notifyAppWidgetViewDataChanged
        // It runs on a background thread provided by the system
        
        var activeRefreshId = 0L
        try {
            // Check if this is an explicit user refresh
            val refreshPrefs = context.getSharedPreferences("widget_refresh_state", Context.MODE_PRIVATE)
            activeRefreshId = refreshPrefs.getLong("active_refresh_id", 0L)
            val isManual = activeRefreshId != 0L

            if (!isManual) {
                Log.d("WidgetLoopFix", "onDataSetChanged: Skipping fetch (Auto/System update). active_refresh_id is 0.")
                return
            }

            val now = System.currentTimeMillis()
            val lastFetch = prefs.getLong(PREF_LAST_FETCH_MS, 0L)
            
            Log.d("WidgetLoopFix", "onDataSetChanged: MANUAL FETCH starting (ID: $activeRefreshId). Time since last: ${now - lastFetch}ms")
            
            // 1 second throttle for manual to prevent UI spam/double-clicks
            if (now - lastFetch < 1000L) {
                Log.d("WidgetLoopFix", "onDataSetChanged: Skipping fetch (Throttled <1s)")
                return
            }

            // Only clear data if we are actually going to fetch new data
            data.clear()
            
            // Record at start to prevent concurrent refresh storms
            prefs.edit().putLong(PREF_LAST_FETCH_MS, now).apply()

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            
            // Execute synchronous call with fingerprinting
            Log.d("WidgetLoopFix", "onDataSetChanged: Calling API for date $today")
            val call = SupabaseClient.api.getConsultations(
                apiKey = SupabaseClient.API_KEY,
                authorization = "Bearer ${SupabaseClient.API_KEY}",
                request = ConsultationRequest(
                    date = today,
                    refresh_id = activeRefreshId
                )
            )
            
            val response = call.execute()

            if (response.isSuccessful) {
                val responseBody = response.body()
                val consultations = responseBody?.consultations ?: emptyList()
                
                if (consultations.isNotEmpty()) {
                    val grouped = consultations.groupBy { it.location ?: "Unknown" }
                    
                    grouped.forEach { (loc, list) ->
                        // pending and under_evaluation count towards "Pending" work
                        val pending = list.count { it.status == "pending" || it.status == "under_evaluation" }
                        val completed = list.count { it.status == "completed" }
                        val total = list.size
                        
                        data.add(LocationStats(loc, pending, completed, total))
                    }
                    // Sort descending by total count
                    data.sortByDescending { it.totalCount }
                }
            } else {
                // Log error or handle empty
                System.err.println("Widget fetch failed: ${response.code()} ${response.errorBody()?.string()}")
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
             // Only send broadcast to hide loader if we explicitly requested a refresh
             val refreshPrefs = context.getSharedPreferences("widget_refresh_state", Context.MODE_PRIVATE)
             val currentActiveId = refreshPrefs.getLong("active_refresh_id", 0L)
             
             if (currentActiveId != 0L && currentActiveId == activeRefreshId) {
                 Log.d("WidgetLoopFix", "onDataSetChanged: Success/Done. Clearing $activeRefreshId and hiding loader.")
                 // Use commit() to ensure this is written before we send the REFRESH_COMPLETE broadcast
                 refreshPrefs.edit().putLong("active_refresh_id", 0L).commit()
                 
                 val intent = Intent(context, PendingConsultationsWidget::class.java).apply {
                     action = PendingConsultationsWidget.ACTION_REFRESH_COMPLETE
                 }
                 context.sendBroadcast(intent)
             } else if (activeRefreshId != 0L) {
                 Log.d("WidgetLoopFix", "onDataSetChanged: Finished but skipped clearing. activeRefreshId=$activeRefreshId, currentActiveId=$currentActiveId")
             }
        }
    }

    override fun onDestroy() {
        data.clear()
    }

    override fun getCount(): Int = data.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= data.size) return RemoteViews(context.packageName, R.layout.widget_list_item)

        val item = data[position]
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        
        views.setTextViewText(R.id.widget_location_name, item.locationName)
        views.setTextViewText(R.id.widget_pending_count, "Pending: ${item.pendingCount}")
        // Removed Done count as requested
        views.setTextViewText(R.id.widget_total_count, "Total: ${item.totalCount}")

        // Allow click to trigger the template intent (refresh)
        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
