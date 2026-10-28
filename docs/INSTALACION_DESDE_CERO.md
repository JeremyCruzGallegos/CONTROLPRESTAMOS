# Instalación desde cero

Esta guía supone que no existe ninguna instalación, libro ni proyecto Cloud previo. No utiliza ni copia recursos de ningún negocio existente. Los APK que se generen aquí son para esa instalación nueva; no deben instalarse sobre una app de otro negocio con una configuración distinta.

## 1. Herramientas

- Git, Android Studio compatible con AGP 9.4.1, JDK 25 para el daemon de Gradle.
- SDK Android API 37. El wrapper descarga Gradle 9.6.0; las dependencias están en `gradle/libs.versions.toml`.
- Python 3 y Node.js 22 o superior para las herramientas y pruebas del servidor.
- Cuenta Google propietaria del nuevo libro y cuenta Cloudflare para Workers.
- Un dominio HTTPS para las páginas de inicio, privacidad y condiciones si tu publicación OAuth lo requiere.

```bash
git clone https://github.com/JeremyCruzGallegos/CONTROLPRESTAMOS.git
cd CONTROLPRESTAMOS
```

Abre esta carpeta en Android Studio. Configura la ruta local del SDK desde el IDE o con `local.properties` (no se sube a Git). El wrapper solicita JDK 25; si no puede descargarlo, instala esa versión y selecciona su ruta como Gradle JDK.

## 2. Crear el libro vacío

1. Con la cuenta del futuro prestamista, crea una hoja de cálculo **nueva y vacía** en Google Sheets. Ponle el nombre de tu instalación.
2. Dentro de ese libro abre **Extensiones → Apps Script**.
3. Sustituye el contenido inicial del editor por `tools/InicializarLibro.gs`.
4. Guarda y ejecuta `inicializarLibroVacio`. Autoriza tu script desde tu cuenta propietaria.
5. Regresa al libro. Deben existir `NUEVO`, `PRESTAMOS` y `CLIENTES`.
6. Comprueba capital y saldos `S/ 0.00`, sin préstamos, pagos ni clientes.
7. Copia el ID de la URL: es el texto entre `/d/` y `/edit`. Guárdalo localmente para los siguientes pasos.

El inicializador se niega a ejecutar si hay datos o más de una pestaña. No es una herramienta de borrado ni de migración. Crea encabezados, calendario desde el 01/01/2026, fórmulas de resumen y controles internos. La app amplía el calendario según los préstamos; no borres las filas de configuración ni cambies sus encabezados.

Mantén el acceso general del archivo en **Restringido**. Comparte como Editor solo con la cuenta del prestamista y, cuando se cree, la cuenta de servicio del Worker. No compartas el libro con los prestatarios.

## 3. Proyecto Google Cloud y OAuth Android

1. Crea tu propio proyecto Google Cloud; anota su ID y número.
2. Habilita **Google Sheets API**.
3. En Google Auth Platform configura marca, contacto, público y URLs HTTPS de tu instalación.
4. Para pruebas externas añade las cuentas de prueba que correspondan. Publicar el proyecto y verificar permisos son procesos diferentes.
5. Crea un cliente OAuth de tipo **Android** para `com.example.controlprestamos.cliente`.
6. Registra el SHA-1 del certificado con el que instalarás el APK. Debe coincidir exactamente con el certificado usado al compilar.

Para desarrollo puedes consultar la huella de tu propio certificado debug:

```bash
keytool -list -v -keystore "$HOME/.android/debug.keystore" -alias androiddebugkey -storepass android
```

Para distribución crea y custodia una clave propia mediante Android Studio → Generate Signed App Bundle / APK. No la subas al repositorio. Registra su SHA-1 como cliente Android en Google antes de instalar. La clave, paquete y una versión superior deben mantenerse para poder actualizar sin desinstalar. Si Google indica que la pareja paquete/SHA-1 ya existe en otro proyecto, usa el proyecto propietario correcto o una clave propia de tu instalación nueva; no elimines credenciales de otras instalaciones para resolverlo.

La gestión solicita `openid`, `userinfo.email` y `https://www.googleapis.com/auth/spreadsheets`. El ID del cliente Android no se inserta como secreto en el APK: Google identifica la instalación por paquete y certificado.

## 4. Cliente web y cuenta de servicio para Consultas

1. En Google Cloud crea un cliente OAuth de tipo **Aplicación web** para el servidor de Consultas. Conserva también el cliente Android.
2. En Cloudflare crea un Worker llamado, por ejemplo, `controlprestamos-consultas`; anota el origen HTTPS que realmente te asigne.
3. Añade al cliente web esta URI exacta, usando tu origen real: `https://TU_WORKER.TU_CUENTA.workers.dev/auth/callback`.
4. Conserva el ID web y su secreto. El secreto solo se guardará en Cloudflare.
5. Crea una cuenta de servicio en Google Cloud sin roles generales de administración.
6. Crea su clave JSON, guárdala fuera del repositorio y comparte el libro nuevo con su correo como **Editor**.

Consultas pide al prestatario `openid email`. El servicio usa la cuenta de servicio para leer únicamente los préstamos autorizados y escribir el registro de accesos. La cuenta de servicio tiene permiso técnico sobre el libro completo; la separación por prestatario la aplica el código del servidor.

## 5. Configuración local de esta copia

Copia `config/proyecto.example.json` a `config/proyecto.local.json` y completa:

```json
{
  "spreadsheetId": "ID_DEL_LIBRO_NUEVO",
  "publicOrigin": "https://TU_WORKER.TU_CUENTA.workers.dev",
  "adminEmail": "PRESTAMISTA@example.com",
  "supportEmail": "SOPORTE@example.com",
  "privacyBaseUrl": "https://TU_DOMINIO",
  "oauthWebClientId": "ID_WEB.apps.googleusercontent.com"
}
```

Estos son marcadores de ejemplo. El archivo `.local.json` está excluido de Git. Nunca añadas la clave JSON de la cuenta de servicio, secretos web ni claves de firma a este archivo ni a recursos Android.

```bash
python3 tools/configurar_proyecto.py --check
python3 tools/configurar_proyecto.py
```

El segundo comando aplica el ID NUEVO a `SheetsIdentity.CLIENT_ID`, los recursos de conexión de ambas apps, las variables públicas de Wrangler y el enlace legal. No hace llamadas a Google ni crea, borra o lee libros. Si cambias el dominio legal después, actualiza también el enlace en `Branding.kt`.

## 6. Publicar las páginas informativas

En `web/cliente/public/` adapta nombre, responsable, correo, dominio y tratamiento de datos a TU instalación. Publica esa carpeta en un alojamiento estático HTTPS, por ejemplo GitHub Pages.

Verifica desde una ventana privada las rutas `/`, `/privacidad/` y `/condiciones/`. Configura esas mismas URLs en la marca de Google. Si usas dominio propio, verifica su propiedad con el mecanismo indicado por Google y conserva su registro DNS. La carpeta no incluye un CNAME asociado a ningún negocio.

La plantilla describe gestión directa con Sheets, copia local, exportación Excel y el servicio de Consultas en Cloudflare. Debes comprobar que represente tu despliegue antes de publicarla.

## 7. Desplegar el servicio de Consultas

```bash
node consultas-api/test/run.mjs
python3 tools/preparar_servidor_consultas.py
```

En Cloudflare → tu Worker → Edit code, pega **todo** `consultas-api/dist/COPIAR_EN_CLOUDFLARE.txt` y despliega.

Configura estas variables públicas en Settings → Variables:

| Nombre | Valor |
|---|---|
| `PUBLIC_ORIGIN` | Origen HTTPS exacto de tu Worker |
| `GOOGLE_CLIENT_ID` | ID OAuth web de TU proyecto |
| `ADMIN_EMAIL` | Correo Google del prestamista administrador |
| `SUPPORT_EMAIL` | Correo de soporte |
| `SPREADSHEET_ID` | ID del libro nuevo inicializado |
| `ADMIN_ADDITIONAL_EMAILS` | Opcional: otros administradores explícitos, separados por coma |

Como **Secrets**, no variables de texto ni código:

| Nombre | Contenido |
|---|---|
| `GOOGLE_CLIENT_SECRET` | Secreto del cliente OAuth web |
| `SHEETS_SERVICE_ACCOUNT_JSON` | JSON privado completo de la cuenta de servicio |

Guarda y vuelve a desplegar. Este servicio usa Google Sheets, no necesita D1. `GET /health` debe responder `configured: true` y `storage: google-sheets`; esto solo acredita variables presentes, no una lectura real de Google. No intercambies el ID Android y el ID web.

La primera sesión administrativa se vincula al identificador Google estable de la cuenta. Cambiar solo `ADMIN_EMAIL` después no transfiere la administración. Planifica la cuenta inicial correcta.

## 8. Compilar e instalar

En Android Studio sincroniza Gradle, selecciona `app` y ejecuta con el certificado registrado. La única app de gestión es `.cliente`; `consultas` es otro módulo.

```bash
./gradlew :app:testDebugUnitTest :consultas:testDebugUnitTest
./gradlew :app:assembleDebug :consultas:assembleDebug
```

Para producción usa Generate Signed APK con tu clave custodiada, configura el cliente OAuth de esa firma y verifica el paquete de salida. Los APK release de Gradle no se firman automáticamente con una clave de este repositorio.

Existe un compilador alternativo para entornos sin Gradle operativo, que necesita dependencias Maven y SDK ya instalados:

```bash
export ANDROID_HOME=/RUTA/A/TU/Android/Sdk
CONTROL_PRODUCTION_BUILD=1 python3 tools/verificar_offline.py
CONTROL_PRODUCTION_BUILD=1 python3 tools/empaquetar_demo_offline.py
python3 tools/construir_consultas_offline.py
```

El alternativo necesita Build Tools 36.0.0 y una caché Maven con las versiones fijadas. Conserva la clave de desarrollo local si existe y no sustituye una configuración de firma comercial. No descarga por sí solo una clave ni dependencias ausentes. Para una instalación nueva normal usa Android Studio/Gradle.

Instala la app de gestión en el teléfono del prestamista y Consultas en el teléfono del prestatario. Permite notificaciones si necesitas recordatorios. El teléfono debe tener bloqueo configurado para las confirmaciones de acciones sensibles.

## 9. Primer uso y comprobación

1. El prestamista conecta su cuenta Google y abre el libro nuevo. Todos los saldos deben ser cero.
2. Registra su capital y las operaciones que correspondan; comprueba sus filas y saldos en Sheets.
3. Comprueba cerrar/reabrir, guardar sin internet y sincronizar al recuperar conexión, en un entorno de comprobación separado antes de operar con datos reales.
4. En Préstamos toca el nombre de un cliente. Conecta el administrador de Consultas, copia el código y habilita la visualización cuando corresponda.
5. El prestatario abre Consultas, se identifica con Google e introduce el código. Solo debe ver sus préstamos autorizados. Prueba también la retirada del permiso.
6. Comprueba cuotas, mora registrada, filtros y recordatorios. Consultas necesita conexión con su servidor; no crea pagos.
7. En gestión → Ajustes → Exportar respaldo en Excel comprueba el `.xlsx` local. Consultas no tiene exportación.

## 10. Verificación Google y entrega

Si tu público y permisos requieren revisión, completa la marca, publica el público adecuado y presenta la justificación de `spreadsheets` y el vídeo real solicitados por Google. Muestra consentimiento en inglés, identificación OAuth y funciones que usan el permiso en un entorno de demostración separado. Publicar un APK o un proyecto no equivale a aprobar ese permiso. Las excepciones dependen del caso concreto; no se presuponen.

Mantén tu firma y el paquete al actualizar, aumenta `versionCode`, sincroniza antes del cambio y conserva respaldos. Los datos de Google no se borran por compilar otro APK, pero desinstalar o borrar datos del teléfono puede descartar operaciones locales pendientes.

## Referencias oficiales

- https://developers.google.com/workspace/sheets/api/scopes
- https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification
- https://developers.google.com/apps-script/reference/spreadsheet/spreadsheet-app
- https://developers.cloudflare.com/workers/configuration/secrets/
- https://developer.android.com/studio/publish/app-signing
