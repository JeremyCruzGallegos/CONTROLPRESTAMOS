# Servicio de Consultas

Worker sin dependencias de ejecución: OAuth web Google, sesiones, códigos de cliente y acceso a préstamos autorizados almacenados en el libro de la instalación.

La configuración empieza vacía. Sigue [la instalación completa](../docs/INSTALACION_DESDE_CERO.md), secciones 4–7. No existe ningún despliegue preconfigurado en esta copia.

```bash
node test/run.mjs
python3 ../tools/preparar_servidor_consultas.py
```

El paquete generado `dist/COPIAR_EN_CLOUDFLARE.txt` se despliega en tu propio Worker. Los secretos `GOOGLE_CLIENT_SECRET` y `SHEETS_SERVICE_ACCOUNT_JSON` se configuran en Cloudflare, nunca en Git.

El cliente Google web requiere redirección a `TU_ORIGEN/auth/callback`; los recursos Android de ambas aplicaciones deben usar ese mismo origen. `/health` valida presencia de configuración; la lectura real y el aislamiento entre usuarios se comprueban en un entorno de instalación nuevo.
