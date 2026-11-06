# Matriz de pruebas – Práctica 5 (Android)

**Plataforma probada:** Android. iOS queda fuera del alcance de esta entrega.
**Dispositivo / emulador:** _p. ej. Pixel 7 · Android 14 (API 34) · imagen con Google Play_ ← completar
**Backend:** Docker Compose + túnel HTTPS (`https://….trycloudflare.com`) ← completar
**Cuentas:** `usuario@…` (rol usuario) y `admin@…` (incluida en `ADMIN_EMAILS`)

> Las columnas «Resultado obtenido» y «Captura» se llenan al ejecutar cada escenario en el emulador/dispositivo. Guarda las capturas en `docs/evidencias/` con el nombre indicado.

## A. Pruebas automatizadas (resultados reales)

| ID | Suite | Comando | Resultado |
|---|---|---|---|
| A1 | Backend: registro/login/logout, token revocado, CRUD y aislamiento entre usuarios, favoritos e historial idempotentes, permisos admin y consentimiento, login Google y vinculación, secreto obligatorio | `cd backend && pytest -q` | ✅ 8 passed |
| A2 | App (commonTest): motor de recomendaciones (pesos, temas genéricos, historial, caso sin datos, ranking determinista) | `./gradlew :composeApp:testDebugUnitTest` | ✅ 5 pruebas del motor ejecutadas en la preparación; ejecutar la suite completa en Android Studio |
| A3 | App (commonTest): parseo de Open Library, descripción texto/objeto, JSON malformado | `./gradlew :composeApp:testDebugUnitTest` | ← completar |

## B. Escenarios funcionales

| ID | Escenario | Pasos | Resultado esperado | Resultado obtenido | Captura |
|---|---|---|---|---|---|
| F1 | Consulta REST (CRUD) | Mis libros → «+» crear; tocar → editar estado; Eliminar → confirmar; Recargar | POST 201, PUT 200, DELETE 204, GET muestra «N elemento(s) en X ms» | | `f1_crud.png` |
| F2 | Búsqueda pública | Buscar «tolkien» | Lista con portadas, «20 de N resultados · X ms»; detalle con descripción y temas | | `f2_busqueda.png` |
| F3 | Estado vacío / errores | Buscar «zzqqxxww»; buscar con API_BASE_URL inválida; buscar «a» | «Sin resultados»; mensaje de error de red sin cierre; «Escribe al menos 2 caracteres» | | `f3_vacio.png` |
| F4 | Google: ingreso, restauración y cierre | «Continuar con Google» → elegir cuenta; cerrar y reabrir app; Perfil → Cerrar sesión | Entra con proveedor «Google»; al reabrir sigue la sesión; al cerrar vuelve al login y el token queda revocado (401) | | `f4_google.png` |
| F5 | Favoritos | ♥ en 3 resultados; pestaña Favoritos; desmarcar 1 | Se listan, persisten al reiniciar y aparecen en `GET /api/favorites` | | `f5_favoritos.png` |
| F6 | Recomendaciones | Con ≥2 favoritos: Para ti → Generar | ≥3 libros con motivo («Tema «fantasy»: …») y puntaje; sin datos → clásicos populares con explicación | | `f6_recs.png` |
| F7 | Reinicio de la app | Forzar detención y abrir | Sesión restaurada; favoritos, historial, recomendaciones y lista de lectura visibles | | `f7_reinicio.png` |
| F8 | Consulta offline | Modo avión → repetir búsqueda «tolkien», abrir detalle, ver favoritos/recs | Banner «Sin conexión»; resultados «guardados el …»; búsqueda nueva → mensaje claro; CRUD de escritura → error «requiere al servidor» | | `f8_offline.png` |
| F9 | Reconexión y sincronización | Offline: marcar 2 favoritos, desmarcar 1, buscar 2 veces, activar «Compartir con admin» → quitar modo avión | Pendientes > 0 offline; al reconectar se envían solos, pendientes = 0, «Última sincronización» se actualiza; en el servidor no hay duplicados | | `f9_sync.png` |
| F10 | Sin duplicados en reintentos | Offline: marcar/desmarcar/marcar el mismo libro → reconectar | Sólo 1 operación en cola y 1 favorito en el servidor | | `f10_dedupe.png` |
| F11 | Admin autorizado | Cuenta admin → Perfil → Abrir panel → usuario que autorizó | Lista de usuarios; historial y favoritos del usuario | | `f11_admin.png` |
| F12 | Acceso denegado | Cuenta normal → Perfil → «Probar acceso a /api/admin/users»; admin abre usuario sin consentimiento | «Acceso denegado (403)…»; «El usuario no autorizó compartir sus datos (403)» | | `f12_403.png` |
| F13 | Aislamiento por cuenta | Usuario A con favoritos → cerrar sesión → entrar como B | B no ve datos de A (local ni servidor) | | `f13_aislamiento.png` |
| F14 | Sesión expirada | Cambiar `JWT_SECRET` en `.env` y `docker compose up -d` (invalida todos los tokens) → Sincronizar | La app vuelve al login con «Tu sesión expiró…» | | `f14_expirada.png` |

## C. Defectos (issues)

| Issue | Descripción | Estado |
|---|---|---|
| #… | ← registrar aquí los defectos reproducibles encontrados (enlace a GitHub Issues) | |
