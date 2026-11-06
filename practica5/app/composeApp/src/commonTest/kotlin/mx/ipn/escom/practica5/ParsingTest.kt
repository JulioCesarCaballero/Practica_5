package mx.ipn.escom.practica5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import mx.ipn.escom.practica5.data.remote.OlSearchResponse
import mx.ipn.escom.practica5.data.remote.OlWork
import mx.ipn.escom.practica5.data.remote.OpenLibraryApi
import mx.ipn.escom.practica5.data.remote.appJson
import mx.ipn.escom.practica5.data.remote.toBook

class ParsingTest {
    @Test
    fun parsesSearchAndSkipsInvalidDocs() {
        val json = """
            {"numFound": 2, "extra": true, "docs": [
              {"key": "/works/OL27448W", "title": "The Lord of the Rings", "author_name": ["J.R.R. Tolkien"],
               "first_publish_year": 1954, "cover_i": 14625765, "subject": ["Fantasy"]},
              {"key": "/books/OL1M", "title": "Edición, no obra"},
              {"title": "Sin key"}
            ]}
        """.trimIndent()
        val resp = appJson.decodeFromString(OlSearchResponse.serializer(), json)
        val books = resp.docs.mapNotNull { it.toBook() }
        assertEquals(1, books.size)
        assertEquals("OL27448W", books[0].workId)
        assertEquals("https://covers.openlibrary.org/b/id/14625765-M.jpg", books[0].coverUrl)
    }

    @Test
    fun descriptionAsStringOrObject() {
        val a = appJson.decodeFromString(OlWork.serializer(), """{"description": "Texto"}""")
        val b = appJson.decodeFromString(OlWork.serializer(), """{"description": {"type": "/type/text", "value": "Otro"}}""")
        val c = appJson.decodeFromString(OlWork.serializer(), """{"description": null}""")
        assertEquals("Texto", OpenLibraryApi.descriptionText(a))
        assertEquals("Otro", OpenLibraryApi.descriptionText(b))
        assertNull(OpenLibraryApi.descriptionText(c))
    }

    @Test
    fun malformedJsonThrowsSerializationException() {
        assertFailsWith<SerializationException> {
            appJson.decodeFromString(OlSearchResponse.serializer(), """{"docs": "no-es-lista"}""")
        }
    }

    @Test
    fun subjectSlug() {
        assertEquals("science_fiction", OpenLibraryApi.subjectSlug("Science  Fiction"))
    }
}
