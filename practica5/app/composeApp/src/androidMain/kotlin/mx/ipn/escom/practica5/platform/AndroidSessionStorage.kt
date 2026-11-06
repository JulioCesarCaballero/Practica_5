package mx.ipn.escom.practica5.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda la sesión (JWT + datos básicos del usuario) cifrada con AES-256 usando una llave
 * del Android Keystore. Nunca se guarda la contraseña ni el ID token de Google.
 */
class AndroidSessionStorage(context: Context) : SessionStorage {
    private val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        fun build(): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            build()
        } catch (e: Exception) {
            // Llave corrupta (p. ej. tras restaurar el dispositivo): se descarta la sesión.
            context.deleteSharedPreferences(FILE)
            build()
        }
    }

    override fun save(json: String) {
        prefs.edit().putString(KEY, json).apply()
    }

    override fun load(): String? = prefs.getString(KEY, null)

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val FILE = "secure_session"
        const val KEY = "session"
    }
}
