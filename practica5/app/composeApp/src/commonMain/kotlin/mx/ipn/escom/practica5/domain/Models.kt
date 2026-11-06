package mx.ipn.escom.practica5.domain

/** Libro de Open Library (obra). workId tiene la forma "OL12345W". */
data class Book(
    val workId: String,
    val title: String,
    val authors: List<String>,
    val firstYear: Int? = null,
    val coverId: Long? = null,
    val subjects: List<String> = emptyList(),
    val description: String? = null,
) {
    val coverUrl: String? get() = coverId?.takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" }
    val authorsText: String get() = authors.joinToString(", ").ifBlank { "Autor desconocido" }
}

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val role: String,
    val authProvider: String,
    val shareWithAdmin: Boolean,
) {
    val isAdmin: Boolean get() = role == "admin"
    /** Identificador usado en la base local para separar los datos por cuenta. */
    val localId: String get() = id.toString()
}

data class HistoryEntry(
    val clientId: String,
    val query: String,
    val resultsCount: Int,
    val searchedAt: Long,
    val synced: Boolean,
)

data class ReadingItem(
    val id: Int,
    val title: String,
    val author: String,
    val status: String,
    val notes: String,
    val updatedAt: String,
)

data class RecommendedBook(
    val book: Book,
    val reason: String,
    val score: Int,
    val generatedAt: Long,
)

object ReadingStatus {
    const val PENDING = "pendiente"
    const val READING = "leyendo"
    const val DONE = "leido"
    val all = listOf(PENDING, READING, DONE)
    fun label(status: String) = when (status) {
        PENDING -> "Pendiente"
        READING -> "Leyendo"
        DONE -> "Leído"
        else -> status
    }
}
