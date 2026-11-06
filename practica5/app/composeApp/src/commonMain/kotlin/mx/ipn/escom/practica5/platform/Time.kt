package mx.ipn.escom.practica5.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun Int.pad() = toString().padStart(2, '0')

/** Formato dd/MM/yyyy HH:mm en la zona horaria del dispositivo. */
fun formatDateTime(epochMillis: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.dayOfMonth.pad()}/${t.monthNumber.pad()}/${t.year} ${t.hour.pad()}:${t.minute.pad()}"
}

/** Convierte un ISO-8601 del servidor (UTC) a texto local; si falla, regresa el texto original. */
fun formatIso(iso: String): String = try {
    formatDateTime(Instant.parse(iso).toEpochMilliseconds())
} catch (e: Exception) {
    iso
}
