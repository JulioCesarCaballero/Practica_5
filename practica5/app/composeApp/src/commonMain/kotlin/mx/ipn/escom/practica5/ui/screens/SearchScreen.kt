package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.repo.SearchOutcome
import mx.ipn.escom.practica5.domain.Book
import mx.ipn.escom.practica5.platform.formatDateTime
import mx.ipn.escom.practica5.ui.BookRow
import mx.ipn.escom.practica5.ui.CenteredLoading
import mx.ipn.escom.practica5.ui.InfoLine
import mx.ipn.escom.practica5.ui.MessageBox
import mx.ipn.escom.practica5.ui.SearchUiState

@Composable
fun SearchScreen(container: AppContainer, onOpen: (Book) -> Unit) {
    val vm = container.searchViewModel
    val query by vm.query.collectAsState()
    val state by vm.state.collectAsState()
    val favIds by remember { container.library.favoriteIdsFlow() }.collectAsState(emptySet())
    val history by remember { container.library.historyFlow() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val toggle: (Book) -> Unit = { b -> scope.launch { container.library.toggleFavorite(b) } }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            label = { Text("Buscar libros o autores (Open Library)") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.reset() }) { Icon(Icons.Filled.Clear, "Limpiar") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val recent = remember(history) { history.map { it.query }.distinctBy { it.lowercase() }.take(8) }
        if (recent.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                items(recent) { q -> AssistChip(onClick = { vm.search(q) }, label = { Text(q) }) }
            }
        }

        when (val s = state) {
            SearchUiState.Idle -> MessageBox(
                "Busca un libro",
                "Escribe un título, autor o tema y presiona buscar. Tus búsquedas se guardan en el historial y " +
                    "los resultados quedan disponibles sin conexión.",
            )
            is SearchUiState.Loading -> CenteredLoading("Buscando «${s.query}»…")
            is SearchUiState.Done -> when (val o = s.outcome) {
                is SearchOutcome.Empty -> MessageBox("Sin resultados", "No se encontraron libros para «${o.query}».")
                is SearchOutcome.Failure -> MessageBox(
                    "No se pudo buscar", o.message, isError = true, actionLabel = "Reintentar", onAction = { vm.search(o.query) },
                )
                is SearchOutcome.Results -> {
                    val info = buildString {
                        append("${o.books.size} de ${o.total} resultados")
                        if (o.elapsedMs != null) append(" · ${o.elapsedMs} ms")
                        if (o.fromCache) append(" · guardados el ${formatDateTime(o.updatedAt)}")
                    }
                    InfoLine(info)
                    o.warning?.let { InfoLine(it, isError = true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(o.books, key = { it.workId }) { b ->
                            BookRow(b, b.workId in favIds, onClick = { onOpen(b) }, onToggleFavorite = { toggle(b) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
