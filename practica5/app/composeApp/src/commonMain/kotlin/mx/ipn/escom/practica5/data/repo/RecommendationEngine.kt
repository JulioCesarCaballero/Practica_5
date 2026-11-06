package mx.ipn.escom.practica5.data.repo

import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.domain.HistoryEntry
import mx.ipn.escom.practica5.domain.RecommendedBook

/**
 * Motor de recomendaciones basado en reglas reproducibles (sin IA):
 *
 *  - Cada tema (subject) suma 3 puntos por cada favorito que lo contiene y 1 punto por cada
 *    libro consultado (vista de detalle) que lo contiene. Lo mismo para autores.
 *  - Se eligen los 2 temas y el autor con mayor puntaje (empate: orden alfabético).
 *  - Sin favoritos ni libros vistos, se usa la búsqueda más frecuente del historial.
 *  - Sin ningún dato, se muestran clásicos populares (tema "classic_literature").
 *  - Un candidato acumula el puntaje de cada criterio que lo produjo; se excluyen los
 *    favoritos. Se ordena por puntaje y luego por título, y se toman 10.
 */
object RecommendationEngine {
    enum class Kind { SUBJECT, AUTHOR, QUERY, POPULAR }

    data class Seed(val kind: Kind, val value: String, val score: Int, val reason: String)

    data class Plan(val seeds: List<Seed>, val explanation: String)

    const val FAVORITE_WEIGHT = 3
    const val VIEWED_WEIGHT = 1
    const val POPULAR_SUBJECT = "classic_literature"

    private val genericSubjects = setOf(
        "fiction", "general", "accessible book", "protected daisy", "in library", "lending library",
        "large type books", "open library staff picks", "internet archive wishlist", "overdrive",
        "english language", "literature", "novela", "ficción", "fiction, general", "nyt bestseller",
        "long now manual for civilization", "textbooks", "juvenile literature",
    )

    /** Normaliza un tema y descarta los genéricos o técnicos (etiquetas de bibliotecas). */
    fun normalizeSubject(raw: String): String? {
        val s = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (s.length < 3 || s.length > 40) return null
        if (s in genericSubjects) return null
        if (s.contains(':') || s.contains('=') || s.startsWith("reading level")) return null
        if (s.any { it.isDigit() }) return null
        return s
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) "$n $one" else "$n $many"

    fun buildPlan(favorites: List<Book>, viewed: List<Book>, history: List<HistoryEntry>): Plan {
        val favIds = favorites.map { it.workId }.toSet()
        val viewedOnly = viewed.filter { it.workId !in favIds }

        class Tally {
            var fav: Int = 0
            var seen: Int = 0
            val score: Int get() = this.fav * FAVORITE_WEIGHT + this.seen * VIEWED_WEIGHT
        }

        val subjects = linkedMapOf<String, Tally>()
        val authors = linkedMapOf<String, Tally>()
        favorites.forEach { b ->
            b.subjects.mapNotNull(::normalizeSubject).toSet().forEach { subjects.getOrPut(it) { Tally() }.fav++ }
            b.authors.map { it.trim() }.filter { it.isNotEmpty() }.toSet().forEach { authors.getOrPut(it) { Tally() }.fav++ }
        }
        viewedOnly.forEach { b ->
            b.subjects.mapNotNull(::normalizeSubject).toSet().forEach { subjects.getOrPut(it) { Tally() }.seen++ }
            b.authors.map { it.trim() }.filter { it.isNotEmpty() }.toSet().forEach { authors.getOrPut(it) { Tally() }.seen++ }
        }

        val order = compareByDescending<Map.Entry<String, Tally>> { it.value.score }.thenBy { it.key }
        val topSubjects = subjects.entries.sortedWith(order).take(2)
        val topAuthor = authors.entries.sortedWith(order).firstOrNull()

        val seeds = mutableListOf<Seed>()
        topSubjects.forEach { (name, t) ->
            seeds += Seed(
                Kind.SUBJECT, name, t.score,
                "Tema «$name»: está en ${plural(t.fav, "favorito", "favoritos")} y ${plural(t.seen, "libro consultado", "libros consultados")}",
            )
        }
        topAuthor?.let { (name, t) ->
            seeds += Seed(
                Kind.AUTHOR, name, t.score,
                "Autor «$name»: está en ${plural(t.fav, "favorito", "favoritos")} y ${plural(t.seen, "libro consultado", "libros consultados")}",
            )
        }
        if (seeds.isNotEmpty()) {
            return Plan(
                seeds,
                "Puntaje = $FAVORITE_WEIGHT × favoritos + $VIEWED_WEIGHT × libros consultados. " +
                    "Criterios usados: " + seeds.joinToString("; ") { "${it.kind.label()} «${it.value}» (${it.score} pts)" } + ".",
            )
        }

        val topQuery = history
            .groupBy { it.query.trim().lowercase() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<HistoryEntry>>> { it.value.size }.thenBy { it.key })
            .firstOrNull()
        if (topQuery != null) {
            val n = topQuery.value.size
            return Plan(
                listOf(Seed(Kind.QUERY, topQuery.key, n, "Relacionado con tu búsqueda «${topQuery.key}» (${plural(n, "vez", "veces")})")),
                "Aún no tienes favoritos ni libros consultados: se usa tu búsqueda más frecuente del historial.",
            )
        }
        return Plan(
            listOf(Seed(Kind.POPULAR, POPULAR_SUBJECT, 0, "Clásico popular (aún no hay historial ni favoritos)")),
            "Sin historial ni favoritos todavía: se muestran clásicos populares. Busca libros y marca favoritos para personalizar.",
        )
    }

    private fun Kind.label() = when (this) {
        Kind.SUBJECT -> "tema"
        Kind.AUTHOR -> "autor"
        Kind.QUERY -> "búsqueda"
        Kind.POPULAR -> "populares"
    }

    /** Combina los candidatos de cada criterio de forma determinista. */
    fun rank(
        results: List<Pair<Seed, List<Book>>>,
        exclude: Set<String>,
        generatedAt: Long,
        limit: Int = 10,
    ): List<RecommendedBook> {
        data class Acc(val book: Book, var score: Int, val seeds: MutableList<Seed>)

        val acc = linkedMapOf<String, Acc>()
        results.forEach { (seed, books) ->
            books.distinctBy { it.workId }.forEach { b ->
                if (b.workId in exclude) return@forEach
                val a = acc.getOrPut(b.workId) { Acc(b, 0, mutableListOf()) }
                a.score += maxOf(seed.score, 1)
                a.seeds += seed
            }
        }
        return acc.values
            .sortedWith(compareByDescending<Acc> { it.score }.thenBy { it.book.title.lowercase() }.thenBy { it.book.workId })
            .take(limit)
            .map { a ->
                val main = a.seeds.maxBy { it.score }
                val extra = a.seeds.size - 1
                val reason = if (extra > 0) "${main.reason} (+ coincide con $extra criterio${if (extra > 1) "s" else ""} más)" else main.reason
                RecommendedBook(a.book, reason, a.score, generatedAt)
            }
    }
}
