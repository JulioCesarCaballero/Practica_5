package mx.ipn.escom.practica5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mx.ipn.escom.practica5.data.repo.RecommendationEngine
import mx.ipn.escom.practica5.data.repo.RecommendationEngine.Kind
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.domain.HistoryEntry

class RecommendationEngineTest {
    private fun book(id: Int, subjects: List<String>, authors: List<String> = listOf("A$id")) =
        Book("OL${id}W", "Libro $id", authors, subjects = subjects)

    @Test
    fun favoritesWeighMoreThanViewed() {
        val favs = listOf(
            book(1, listOf("Fantasy", "Magic"), listOf("Tolkien")),
            book(2, listOf("Fantasy"), listOf("Tolkien")),
        )
        val viewed = listOf(book(3, listOf("Science fiction")), book(4, listOf("Science fiction")))
        val plan = RecommendationEngine.buildPlan(favs, viewed, emptyList())
        val subjects = plan.seeds.filter { it.kind == Kind.SUBJECT }
        assertEquals("fantasy", subjects[0].value)
        assertEquals(6, subjects[0].score) // 2 favoritos × 3
        assertEquals("magic", subjects[1].value) // 3 pts > ciencia ficción 2 pts
        assertEquals("Tolkien", plan.seeds.first { it.kind == Kind.AUTHOR }.value)
    }

    @Test
    fun genericSubjectsAreIgnored() {
        assertNull(RecommendationEngine.normalizeSubject("Accessible book"))
        assertNull(RecommendationEngine.normalizeSubject("nyt:hardcover-fiction=2008-01-01"))
        assertNull(RecommendationEngine.normalizeSubject("Fiction"))
        assertEquals("fantasy", RecommendationEngine.normalizeSubject("  Fantasy "))
    }

    @Test
    fun usesHistoryWhenNoFavorites() {
        val history = listOf(
            HistoryEntry("1", "Dune", 10, 1, true),
            HistoryEntry("2", "dune", 10, 2, true),
            HistoryEntry("3", "Borges", 10, 3, true),
        )
        val plan = RecommendationEngine.buildPlan(emptyList(), emptyList(), history)
        assertEquals(Kind.QUERY, plan.seeds.single().kind)
        assertEquals("dune", plan.seeds.single().value)
    }

    @Test
    fun fallsBackToPopularWithoutData() {
        val plan = RecommendationEngine.buildPlan(emptyList(), emptyList(), emptyList())
        assertEquals(Kind.POPULAR, plan.seeds.single().kind)
    }

    @Test
    fun rankingIsDeterministicAndExcludesFavorites() {
        val s1 = RecommendationEngine.Seed(Kind.SUBJECT, "fantasy", 6, "tema")
        val s2 = RecommendationEngine.Seed(Kind.AUTHOR, "Tolkien", 6, "autor")
        val shared = book(10, emptyList())
        val recs = RecommendationEngine.rank(
            listOf(
                s1 to listOf(book(11, emptyList()), shared, book(1, emptyList())),
                s2 to listOf(shared, book(12, emptyList())),
            ),
            exclude = setOf("OL1W"),
            generatedAt = 0,
        )
        assertEquals("OL10W", recs.first().book.workId) // coincide con 2 criterios
        assertEquals(12, recs.first().score)
        assertTrue(recs.none { it.book.workId == "OL1W" })
        assertEquals(3, recs.size)
    }
}
