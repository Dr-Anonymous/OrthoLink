package life.ortho.ortholink.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object SupabaseClient {
    private const val BASE_URL = "https://vqskeanwpnvuyxorymib.supabase.co/"
    // Note: In a real production app, keys should be secured (e.g., BuildConfig or NDK).
    // Using the anon key found in the web app for now.
    const val API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZxc2tlYW53cG52dXl4b3J5bWliIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDg0MDMwMDcsImV4cCI6MjA2Mzk3OTAwN30.ICS_onpmI7Zdyqzx3ZP9_H_L8O4h7HRDhqbjWC3tBLk"

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    val api: SupabaseApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SupabaseApi::class.java)
    }
}
