package life.ortho.ortholink.model

import com.google.gson.annotations.SerializedName

data class Consultation(
    val id: String,
    val status: String, // 'pending', 'under_evaluation', 'completed'
    val location: String?,
    @SerializedName("created_at") val createdAt: String
)

data class ConsultationResponse(
    val consultations: List<Consultation>
)

data class ConsultationRequest(
    val date: String
)
