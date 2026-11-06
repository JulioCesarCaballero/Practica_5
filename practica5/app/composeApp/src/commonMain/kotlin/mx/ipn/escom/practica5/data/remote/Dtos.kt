package mx.ipn.escom.practica5.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ------------------------- Backend propio -------------------------

@Serializable
data class UserDto(
    val id: Int,
    val name: String,
    val email: String,
    val role: String = "user",
    @SerialName("auth_provider") val authProvider: String = "local",
    @SerialName("share_with_admin") val shareWithAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

@Serializable
data class GoogleLoginRequest(@SerialName("id_token") val idToken: String)

@Serializable
data class PreferencesRequest(@SerialName("share_with_admin") val shareWithAdmin: Boolean)

@Serializable
data class ReadingItemDto(
    val id: Int,
    val title: String,
    val author: String,
    val status: String,
    val notes: String = "",
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ReadingItemRequest(
    val title: String,
    val author: String,
    val status: String,
    val notes: String,
)

@Serializable
data class FavoriteDto(
    @SerialName("work_id") val workId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    @SerialName("cover_id") val coverId: Long? = null,
    @SerialName("first_year") val firstYear: Int? = null,
    val subjects: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class FavoriteRequest(
    val title: String,
    val authors: List<String>,
    @SerialName("cover_id") val coverId: Long? = null,
    @SerialName("first_year") val firstYear: Int? = null,
    val subjects: List<String> = emptyList(),
)

@Serializable
data class HistoryDto(
    @SerialName("client_id") val clientId: String,
    val query: String,
    @SerialName("results_count") val resultsCount: Int,
    @SerialName("searched_at") val searchedAt: Long,
)

@Serializable
data class AdminUserDto(
    val id: Int,
    val name: String,
    val email: String,
    val role: String,
    @SerialName("auth_provider") val authProvider: String = "local",
    @SerialName("share_with_admin") val shareWithAdmin: Boolean = false,
    @SerialName("favorites_count") val favoritesCount: Int? = null,
    @SerialName("history_count") val historyCount: Int? = null,
)

// ------------------------- Open Library -------------------------

@Serializable
data class OlSearchResponse(
    val numFound: Int = 0,
    val docs: List<OlDoc> = emptyList(),
)

@Serializable
data class OlDoc(
    val key: String? = null,
    val title: String? = null,
    @SerialName("author_name") val authorName: List<String> = emptyList(),
    @SerialName("first_publish_year") val firstPublishYear: Int? = null,
    @SerialName("cover_i") val coverI: Long? = null,
    val subject: List<String> = emptyList(),
)

@Serializable
data class OlWork(
    val key: String? = null,
    val title: String? = null,
    /** Puede venir como texto o como objeto {"type": ..., "value": ...}. */
    val description: JsonElement? = null,
    val subjects: List<String> = emptyList(),
    val covers: List<Long> = emptyList(),
)

@Serializable
data class OlSubjectResponse(
    val name: String? = null,
    val works: List<OlSubjectWork> = emptyList(),
)

@Serializable
data class OlSubjectWork(
    val key: String? = null,
    val title: String? = null,
    @SerialName("cover_id") val coverId: Long? = null,
    val authors: List<OlAuthorRef> = emptyList(),
    @SerialName("first_publish_year") val firstPublishYear: Int? = null,
    val subject: List<String> = emptyList(),
)

@Serializable
data class OlAuthorRef(val name: String? = null)
