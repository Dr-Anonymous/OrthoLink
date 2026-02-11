package life.ortho.ortholink.network

import life.ortho.ortholink.model.CalendarEventResponse
import life.ortho.ortholink.model.PatientDetails
import life.ortho.ortholink.model.SearchRequest
import life.ortho.ortholink.model.ConsultationRequest
import life.ortho.ortholink.model.ConsultationResponse
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface SupabaseApi {

    @POST("functions/v1/search-patients")
    fun searchPatients(
        @Header("apikey") apiKey: String,
        @Header("Authorization") authorization: String,
        @Body request: SearchRequest
    ): Call<List<PatientDetails>>

    @POST("functions/v1/search-calendar-events")
    fun searchCalendarEvents(
        @Header("apikey") apiKey: String,
        @Header("Authorization") authorization: String,
        @Body request: Map<String, String>
    ): Call<CalendarEventResponse>

    @POST("functions/v1/get-consultations")
    fun getConsultations(
        @Header("apikey") apiKey: String,
        @Header("Authorization") authorization: String,
        @Body request: ConsultationRequest
    ): Call<ConsultationResponse>
}
