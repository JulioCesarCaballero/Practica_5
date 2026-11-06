package mx.ipn.escom.practica5.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import mx.ipn.escom.practica5.domain.Book

/** API pública: Open Library (https://openlibrary.org/developers/api). */
class OpenLibraryApi(private val client: HttpClient) {
    private val base = "https://openlibrary.org"
    private val searchFields = "key,title,author_name,first_publish_year,cover_i,subject"

    suspend fun search(query: String, limit: Int = 20): ApiResult<Pair<Int, List<Book>>> =
        safeCall<OlSearchResponse> {
            client.get("$base/search.json") {
                parameter("q", query)
                parameter("fields", searchFields)
                parameter("limit", limit)
            }
        }.map { resp -> resp.numFound to resp.docs.mapNotNull { it.toBook() } }

    suspend fun searchByAuthor(author: String, limit: Int = 10): ApiResult<List<Book>> =
        safeCall<OlSearchResponse> {
            client.get("$base/search.json") {
                parameter("author", author)
                parameter("fields", searchFields)
                parameter("limit", limit)
            }
        }.map { resp -> resp.docs.mapNotNull { it.toBook() } }

    suspend fun work(workId: String): ApiResult<OlWork> =
        safeCall { client.get("$base/works/$workId.json") }

    suspend fun subject(subject: String, limit: Int = 12): ApiResult<List<Book>> =
        safeCall<OlSubjectResponse> {
            client.get("$base/subjects/${subjectSlug(subject)}.json") { parameter("limit", limit) }
        }.map { resp -> resp.works.mapNotNull { it.toBook() } }

    companion object {
        private val workIdRegex = Regex("^OL\\d+W$")

        /** "/works/OL123W" -> "OL123W"; regresa null si el formato no es válido. */
        fun workIdFromKey(key: String?): String? =
            key?.substringAfterLast('/')?.takeIf { workIdRegex.matches(it) }

        fun subjectSlug(subject: String): String =
            subject.trim().lowercase()
                .replace(Regex("\\s+"), "_")
                .replace(Regex("[^a-z0-9_]"), "")

        fun descriptionText(work: OlWork): String? = when (val d = work.description) {
            null -> null
            is JsonPrimitive -> d.contentOrNull
            is JsonObject -> d["value"]?.jsonPrimitive?.contentOrNull
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
}

fun OlDoc.toBook(): Book? {
    val id = OpenLibraryApi.workIdFromKey(key) ?: return null
    return Book(
        workId = id,
        title = title?.takeIf { it.isNotBlank() } ?: return null,
        authors = authorName.take(5),
        firstYear = firstPublishYear,
        coverId = coverI,
        subjects = subject.take(25),
    )
}

fun OlSubjectWork.toBook(): Book? {
    val id = OpenLibraryApi.workIdFromKey(key) ?: return null
    return Book(
        workId = id,
        title = title?.takeIf { it.isNotBlank() } ?: return null,
        authors = authors.mapNotNull { it.name }.take(5),
        firstYear = firstPublishYear,
        coverId = coverId,
        subjects = subject.take(25),
    )
}
