package mx.ipn.escom.practica5.platform

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSUUID

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun randomUuid(): String = NSUUID().UUIDString()

/** Motor Darwin = NSURLSession. App Transport Security de iOS ya bloquea el tráfico sin HTTPS. */
actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    config(this)
}

/**
 * En iOS no existe un botón "atrás" del sistema. La navegación de la app ya incluye la flecha
 * de regreso en la barra superior, así que aquí no se intercepta ningún gesto.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Intencionalmente vacío.
}
