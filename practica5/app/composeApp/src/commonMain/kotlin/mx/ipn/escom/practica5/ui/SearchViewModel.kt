package mx.ipn.escom.practica5.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.data.repo.LibraryRepository
import mx.ipn.escom.practica5.data.repo.SearchOutcome

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data class Loading(val query: String) : SearchUiState
    data class Done(val outcome: SearchOutcome) : SearchUiState
}

/** Mantiene el estado de la búsqueda al cambiar de pestaña. */
class SearchViewModel(private val scope: CoroutineScope, private val library: LibraryRepository) {
    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    val query = MutableStateFlow("")
    private var job: Job? = null

    fun search(text: String = query.value) {
        query.value = text
        job?.cancel()
        job = scope.launch {
            _state.value = SearchUiState.Loading(text)
            _state.value = SearchUiState.Done(library.search(text))
        }
    }

    fun reset() {
        job?.cancel()
        query.value = ""
        _state.value = SearchUiState.Idle
    }
}
