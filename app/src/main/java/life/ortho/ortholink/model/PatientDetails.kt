package life.ortho.ortholink.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class PatientDetails(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    @SerializedName("first_name") val firstName: String?,
    @SerializedName("last_name") val lastName: String?,
    @SerializedName("personalNote") val personalNote: String?,
    @SerializedName("complaints") val complaints: String?,
    @SerializedName("findings") val findings: String?,
    @SerializedName("investigations") val investigations: String?,
    @SerializedName("diagnosis") val diagnosis: String?,
    @SerializedName("medications") val medications: JsonElement?,
    @SerializedName("advice") val advice: String?,
    @SerializedName("followup") val followup: String?,
    @SerializedName("procedure") val procedure: String?,
    @SerializedName("referred_to") val referredTo: String?,
    @SerializedName("location") val location: String?,
    @SerializedName("referred_by") val referredBy: String?,
    @SerializedName("visit_type") val visitType: String?,
    @SerializedName("bp") val bp: String?,
    @SerializedName("weight") val weight: String?,
    @SerializedName("temperature") val temperature: String?,
    @SerializedName("created_at") val createdAt: String?
)

data class SearchRequest(
    val searchTerm: String,
    val searchType: String = "phone"
)
