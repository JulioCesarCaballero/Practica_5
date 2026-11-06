package mx.ipn.escom.practica5.data.remote

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Resultado de una llamada remota: nunca lanza excepciones hacia la UI. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val elapsedMs: Long = 0) : ApiResult<T>
    /** El servidor respondió con un código HTTP de error (4xx/5xx). */
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>
    /** Sin conexión, DNS, timeout o TLS. */
    data class NetworkError(val message: String) : ApiResult<Nothing>
    /** La respuesta llegó pero el JSON no tiene el formato esperado. */
    data class Malformed(val message: String) : ApiResult<Nothing>
}

/** Error transitorio: conviene reintentar (red, timeout, 5xx, 429, 408). */
val ApiResult<*>.isRetryable: Boolean
    get() = when (this) {
        is ApiResult.NetworkError -> true
        is ApiResult.HttpError -> code >= 500 || code == 429 || code == 408
        else -> false
    }

fun ApiResult<*>.userMessage(): String = when (this) {
    is ApiResult.Success -> ""
    is ApiResult.HttpError -> when (code) {
        401 -> "Sesión no válida o credenciales incorrectas ($code): $message"
        403 -> "Acceso denegado ($code): $message"
        404 -> "No encontrado ($code): $message"
        429 -> "Se alcanzó el límite de consultas (429). Intenta de nuevo en unos segundos."
        in 500..599 -> "Error del servidor ($code). Intenta más tarde."
        else -> "Error HTTP $code: $message"
    }
    is ApiResult.NetworkError -> "Error de red: $message"
    is ApiResult.Malformed -> "Datos con formato inesperado: $message"
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data), elapsedMs)
    is ApiResult.HttpError -> this
    is ApiResult.NetworkError -> this
    is ApiResult.Malformed -> this
}

private val errorJson = Json { ignoreUnknownKeys = true }

suspend fun HttpResponse.errorMessage(): String = try {
    val text = bodyAsText()
    runCatching {
        errorJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull() ?: text.take(160).ifBlank { status.description }
} catch (e: Exception) {
    status.description
}

/**
 * Ejecuta una petición y convierte cualquier falla en ApiResult.
 * Mide el tiempo de respuesta para mostrarlo en la interfaz.
 */
suspend inline fun <reified T> safeCall(
    noinline onUnauthorized: (() -> Unit)? = null,
    crossinline request: suspend () -> HttpResponse,
): ApiResult<T> {
    val start = mx.ipn.escom.practica5.platform.nowMillis()
    return try {
        val response = request()
        val elapsed = mx.ipn.escom.practica5.platform.nowMillis() - start
        if (response.status.isSuccess()) {
            @Suppress("UNCHECKED_CAST")
            val data: T = if (T::class == Unit::class) Unit as T else response.body()
            ApiResult.Success(data, elapsed)
        } else {
            if (response.status.value == 401) onUnauthorized?.invoke()
            ApiResult.HttpError(response.status.value, response.errorMessage())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ContentConvertException) {
        ApiResult.Malformed(e.message ?: "JSON inválido")
    } catch (e: SerializationException) {
        ApiResult.Malformed(e.message ?: "JSON inválido")
    } catch (e: IllegalArgumentException) {
        ApiResult.Malformed(e.message ?: "Dato inválido")
    } catch (e: Exception) {
        ApiResult.NetworkError(e.message ?: e::class.simpleName ?: "desconocido")
    }
}
