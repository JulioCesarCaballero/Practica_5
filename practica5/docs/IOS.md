# iOS (Kotlin Multiplatform + Compose Multiplatform)

Misma app y mismo código compartido (`commonMain`) que Android. Lo específico de iOS está en
`app/composeApp/src/iosMain` (Kotlin) y `iosApp/` (SwiftUI + Xcode).

| Pieza | Android | iOS |
|---|---|---|
| Motor HTTP (Ktor) | OkHttp | Darwin (NSURLSession) |
| Base local (SQLDelight) | `AndroidSqliteDriver` | `NativeSqliteDriver` |
| Sesión segura | EncryptedSharedPreferences + Keystore | **Keychain** (`KeychainSettings`) |
| Monitor de red | `ConnectivityManager.NetworkCallback` | **NWPathMonitor** |
| Google Sign-In | Credential Manager | **GoogleSignIn-iOS** (Swift) → callback a Kotlin |
| Punto de entrada | `MainActivity` / `Practica5App` | `iOSApp.swift` → `MainViewController()` |
| Botón "atrás" | `BackHandler` | no aplica (flecha en la barra superior) |

Archivos nuevos: `iosMain/.../platform/{Platform.ios,IosSessionStorage,IosConnectivityMonitor,IosGoogleAuthClient}.kt`,
`iosMain/.../MainViewController.kt`, `iosApp/{project.yml,Config.xcconfig.example}` y `iosApp/iosApp/{iOSApp,ContentView,GoogleSignInHandler}.swift` + `Info.plist`.
Cambios en Gradle: destinos `iosX64/iosArm64/iosSimulatorArm64` y dependencias de `iosMain` (`libs.versions.toml`).

> El destino **iosX64** es necesario si el simulador corre en un Mac Intel o en un macOS virtualizado x86_64
> (p. ej. macOS en Docker). En Apple Silicon se usa `iosSimulatorArm64`.

## 1. Requisitos (macOS con Xcode 15+, iOS 15+)
- Xcode (con un simulador iOS 15+ instalado) y Command Line Tools.
- **JDK 17** (Temurin) y `JAVA_HOME` configurado.
- **XcodeGen**: `brew install xcodegen` (o descargar el release de GitHub y poner el binario en el PATH).
- **Android SDK mínimo**: el proyecto Gradle también declara el módulo Android, así que Gradle exige un SDK
  aunque sólo se compile iOS. Sin instalar Android Studio:
  ```bash
  mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
  # descarga "Command line tools" para Mac de https://developer.android.com/studio#command-tools y descomprime como "latest"
  yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/android-sdk \
        "platforms;android-35" "build-tools;35.0.0"
  echo "sdk.dir=$HOME/android-sdk" >> app/local.properties
  ```
- Backend con HTTPS en marcha (`docker compose --profile https up -d --build`) y su URL `https://xxxx.trycloudflare.com`.

## 2. Configuración sin secretos
```bash
cd iosApp
cp Config.xcconfig.example Config.xcconfig   # ignorado por git
# edita: API_BASE_URL, WEB_CLIENT_ID, IOS_CLIENT_ID, IOS_REVERSED_CLIENT_ID
```
Google Cloud Console → Credenciales → **ID de cliente OAuth tipo iOS** con *Bundle ID* `mx.ipn.escom.practica5`.
`WEB_CLIENT_ID` es el mismo ID web que Android y que `GOOGLE_CLIENT_IDS` del backend (el backend no cambia).

## 3. Compilar el framework Kotlin (verificación previa, sin Xcode)
```bash
cd app
./gradlew :composeApp:linkDebugFrameworkIosX64          # simulador Intel / macOS virtualizado
# ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64   # Apple Silicon
```
La primera vez descarga Kotlin/Native (varios cientos de MB) y tarda varios minutos.

## 4. Generar el proyecto y ejecutar en el simulador
```bash
cd iosApp
xcodegen generate                      # crea iosApp.xcodeproj (ignorado por git)
open iosApp.xcodeproj                  # Run (⌘R) con un iPhone simulado iOS 15+
```
Por línea de comandos:
```bash
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 15' -derivedDataPath build build
xcrun simctl boot "iPhone 15" 2>/dev/null; open -a Simulator
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/LibrosP5.app
xcrun simctl launch booted mx.ipn.escom.practica5
xcrun simctl io booted screenshot captura.png        # capturas para la evidencia
xcrun simctl io booted recordVideo demo.mp4          # video para la evidencia
```
Anota para el informe: `xcrun simctl list runtimes` (versión de iOS), `sw_vers` y `xcodebuild -version`.

## 5. Pruebas en iOS (mismos pasos que Android; sección «Pruebas manuales» del README)
Registro/login, búsqueda Open Library, estado vacío/error, Google (ingreso → reinicio → cierre), favoritos,
recomendaciones, reinicio de la app, offline, reconexión + sincronización, admin 200 y usuario normal 403.
- **Offline en simulador:** el simulador usa la red del Mac. Desconecta la red del Mac (Wi-Fi/Ethernet; en un macOS
  virtualizado: `sudo ifconfig $(route get default | awk '/interface/{print $2}') down`, y `up` para reconectar).
  NWPathMonitor cambiará a *offline* en tiempo real.
- Prueba de sesión persistente: cierra la app (swipe) y ábrela; la sesión se restaura desde el Keychain.
  Nota: el Keychain de iOS puede sobrevivir a una desinstalación; para empezar de cero usa
  `xcrun simctl uninstall booted mx.ipn.escom.practica5` y *Device → Erase All Content and Settings*.

## 6. Problemas frecuentes
| Síntoma | Causa / solución |
|---|---|
| `SDK location not found` | Falta `sdk.dir` en `app/local.properties` (ver §1). |
| `Command PhaseScriptExecution failed` | Revisa `JAVA_HOME` (JDK 17) y que `./gradlew` tenga permiso de ejecución (`chmod +x app/gradlew`). |
| `Sandbox: ... deny file-write` | Debe estar `ENABLE_USER_SCRIPT_SANDBOXING = NO` (ya está en `project.yml`; regenera con `xcodegen generate`). |
| `framework 'ComposeApp' not found` | Compila primero desde Xcode (la fase de script lo genera) o ejecuta el paso 3; revisa `FRAMEWORK_SEARCH_PATHS`. |
| Google: `Falta IOS_CLIENT_ID` / pantalla en blanco al volver | `Config.xcconfig` incompleto o `IOS_REVERSED_CLIENT_ID` incorrecto (debe coincidir con el esquema URL). |
| Google: `id_token` rechazado por el backend (401) | `WEB_CLIENT_ID` debe estar en `GOOGLE_CLIENT_IDS` del `.env` del backend. |
| Error de Kotlin/Native con una versión de Xcode antigua | Cada versión de Kotlin está probada contra una versión de Xcode concreta; anota el mensaje completo en un issue. |
| App muy lenta o pantalla negra en un macOS virtualizado | Compose dibuja con Metal; sin GPU el simulador usa render por software. Prueba en un Mac físico o un simulador distinto. |
