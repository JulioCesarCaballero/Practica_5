package mx.ipn.escom.practica5.platform

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings

/**
 * Guarda la sesión (JWT + datos básicos) en el Keychain de iOS (equivalente a
 * EncryptedSharedPreferences + Keystore en Android). Nunca se guarda la contraseña
 * ni el ID token de Google.
 */
@OptIn(ExperimentalSettingsImplementation::class)
class IosSessionStorage : SessionStorage {
    private val keychain = KeychainSettings(service = "mx.ipn.escom.practica5.session")

    override fun save(json: String) = keychain.putString(KEY, json)

    override fun load(): String? = keychain.getStringOrNull(KEY)

    override fun clear() = keychain.remove(KEY)

    private companion object {
        const val KEY = "session"
    }
}
