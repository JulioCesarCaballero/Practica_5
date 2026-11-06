package mx.ipn.escom.practica5.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.ipn.escom.practica5.AppContainer
import mx.ipn.escom.practica5.data.remote.ApiResult
import mx.ipn.escom.practica5.data.remote.userMessage
import mx.ipn.escom.practica5.domain.ReadingItem
import mx.ipn.escom.practica5.domain.ReadingStatus
import mx.ipn.escom.practica5.platform.formatIso
import mx.ipn.escom.practica5.ui.InfoLine
import mx.ipn.escom.practica5.ui.MessageBox

/** CRUD heredado de la Práctica 2: GET/POST/PUT/DELETE sobre /api/books. */
@Composable
fun ReadingScreen(container: AppContainer) {
    val readingItems by remember { container.reading.itemsFlow() }.collectAsState(emptyList())
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReadingItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun report(op: String, r: ApiResult<*>) {
        statusIsError = r !is ApiResult.Success
        status = when (r) {
            is ApiResult.Success -> "$op correcto · ${r.elapsedMs} ms"
            else -> "$op: ${r.userMessage()}"
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            val r = container.reading.refresh()
            loading = false
            if (r is ApiResult.Success) {
                statusIsError = false
                status = "GET /api/books · ${r.data.size} elemento(s) en ${r.elapsedMs} ms"
            } else {
                report("GET", r)
                status = status + " · mostrando copia local"
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { status?.let { InfoLine(it, statusIsError) } }
                TextButton(onClick = { refresh() }) { Text("Recargar") }
            }
            if (readingItems.isEmpty() && !loading) {
                MessageBox("Lista vacía", "Agrega libros a tu lista de lectura con el botón +. Requiere conexión con el servidor.")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(readingItems, key = { it.id }) { item ->
                    Column(
                        Modifier.fillMaxWidth().clickable { editing = item }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text("${item.author} · ${ReadingStatus.label(item.status)}", style = MaterialTheme.typography.bodySmall)
                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("Actualizado: ${formatIso(item.updatedAt)}", style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Agregar libro") }
    }

    if (creating) {
        ItemDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { t, a, s, n ->
                scope.launch {
                    val r = container.reading.create(t, a, s, n)
                    report("POST", r)
                    if (r is ApiResult.Success) creating = false
                }
            },
            onDelete = null,
        )
    }
    editing?.let { item ->
        ItemDialog(
            initial = item,
            onDismiss = { editing = null },
            onSave = { t, a, s, n ->
                scope.launch {
                    val r = container.reading.update(item.id, t, a, s, n)
                    report("PUT", r)
                    if (r is ApiResult.Success) editing = null
                }
            },
            onDelete = {
                scope.launch {
                    val r = container.reading.delete(item.id)
                    report("DELETE", r)
                    if (r is ApiResult.Success) editing = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDialog(
    initial: ReadingItem?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var author by remember { mutableStateOf(initial?.author ?: "") }
    var status by remember { mutableStateOf(initial?.status ?: ReadingStatus.PENDING) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo libro" else "Editar libro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text("Autor") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReadingStatus.all.forEach { s ->
                        FilterChip(selected = status == s, onClick = { status = s }, label = { Text(ReadingStatus.label(s)) })
                    }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notas") }, minLines = 2)
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(
                            if (confirmDelete) "Confirmar eliminación" else "Eliminar",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, author, status, notes) },
                enabled = title.isNotBlank() && author.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
