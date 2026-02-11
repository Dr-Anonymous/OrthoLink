package life.ortho.ortholink.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import life.ortho.ortholink.R
import life.ortho.ortholink.model.ConsultationRequest
import life.ortho.ortholink.model.ConsultationResponse
import life.ortho.ortholink.model.Consultation
import life.ortho.ortholink.network.SupabaseClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return WidgetRemoteViewsFactory(this.applicationContext)
    }
}

class WidgetRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    
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
        data.clear()

        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            
            // Execute synchronous call
            val call = SupabaseClient.api.getConsultations(
                apiKey = SupabaseClient.API_KEY,
                authorization = "Bearer ${SupabaseClient.API_KEY}",
                request = ConsultationRequest(date = today)
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

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
