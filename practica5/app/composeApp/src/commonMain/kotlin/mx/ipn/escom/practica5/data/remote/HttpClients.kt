package mx.ipn.escom.practica5.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import mx.ipn.escom.practica5.platform.platformHttpClient

val appJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = false
}

/** Cliente HTTP compartido (Ktor). La plataforma sólo aporta el motor. */
fun createHttpClient(): HttpClient = platformHttpClient {
    expectSuccess = false
    install(ContentNegotiation) { json(appJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
    install(Logging) {
        level = LogLevel.INFO // INFO no imprime encabezados: el token no aparece en los logs
        logger = object : Logger {
            override fun log(message: String) = println("HTTP: $message")
        }
    }
    defaultRequest {
        headers.append(HttpHeaders.Accept, "application/json")
        // Open Library pide identificar a los clientes; no se incluyen datos personales.
        headers.append(HttpHeaders.UserAgent, "Practica5-ESCOM-Libros/1.0 (app academica)")
    }
}
