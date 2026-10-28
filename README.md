# ControlPrestamos: instalación desde cero

Código fuente de dos aplicaciones Android para registrar préstamos y consultar la información autorizada de cada cliente.

| Aplicación | Paquete | Función |
|---|---|---|
| ControlPrestamos Cliente | `com.example.controlprestamos.cliente` | Gestión del prestamista: préstamos, cuotas, pagos, moras, caja y respaldo Excel local |
| ControlPrestamos Consultas | `com.example.controlprestamos.consultas` | Información general y detalle de los préstamos asignados al prestatario |

**Este repositorio no contiene ningún libro, cuenta, registro financiero, ID de libro real, contraseña, token ni certificado de firma.** La configuración comienza vacía. Los datos sintéticos de las pruebas unitarias son independientes y no se cargan en el libro ni en una instalación nueva. El acceso visible al modo de demostración se ha retirado de esta distribución.

La implementación utiliza Google Sheets API para gestión y un servicio de Cloudflare Workers para las consultas autorizadas. El permiso OAuth de gestión sigue siendo `spreadsheets`; no se ha cambiado a `drive.file`. El prestatario no necesita acceso directo al libro.

## Instalación completa

Sigue **[INSTALACION_DESDE_CERO.md](docs/INSTALACION_DESDE_CERO.md)**, en este orden:

1. Preparar las herramientas de desarrollo.
2. Crear e inicializar un libro NUEVO completamente vacío.
3. Crear el proyecto Google Cloud, su cliente Android y el cliente web de Consultas.
4. Configurar y desplegar el Worker y sus secretos privados.
5. Aplicar la configuración local del proyecto y compilar los APK.
6. Instalar y verificar gestión, sincronización y acceso individual de Consultas.
7. Completar la publicación y verificación OAuth que corresponda al público de tu proyecto.

El script `tools/InicializarLibro.gs` prepara únicamente la estructura del libro nuevo, con capital cero. Se usa una vez durante la instalación; la operación diaria no requiere desplegar un servicio de Apps Script.

## Desarrollo

El proyecto fija Gradle 9.6.0, AGP 9.4.1, Kotlin 2.2.10, JVM de Gradle 25 y SDK Android 37. Usa las versiones del repositorio o verifica su compatibilidad antes de cambiarlas. Las aplicaciones requieren Android 8 / API 26 o superior.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :consultas:assembleDebug :consultas:testDebugUnitTest
node consultas-api/test/run.mjs
node tools/test/initialization.test.cjs
```

Los APK debug requieren su propio cliente OAuth Android con su paquete y SHA-1. La firma de producción debe pertenecer al instalador y conservarse para las actualizaciones. No se distribuye una clave privada en este repositorio.

## Estructura

- `app/`: aplicación del prestamista y pruebas.
- `consultas/`: aplicación del prestatario y pruebas.
- `shared/`: modelos de Consultas y presentación compartida.
- `consultas-api/`: Worker, autenticación, autorizaciones y pruebas.
- `tools/`: configuración local, inicialización y herramientas de compilación.
- `config/`: ejemplo vacío y fórmulas de la estructura inicial.
- `web/cliente/public/`: páginas informativas que debes adaptar y publicar en tu propio dominio.
- `docs/`: instalación, operación y límites de verificación.

No se incluyen APK, cachés de Android Studio, historiales, capturas, despliegues anteriores ni volcados de información del negocio. No se ha elegido una licencia de terceros automáticamente; antes de redistribuir este proyecto, su titular debe definir los términos aplicables.
