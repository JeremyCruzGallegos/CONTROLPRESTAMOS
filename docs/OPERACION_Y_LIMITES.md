# Operación y límites

Los cambios de gestión se guardan localmente y se intentan sincronizar con Google. Una operación visible en el teléfono puede estar pendiente de confirmación remota. Los reintentos buscan evitar duplicados y comprueban cambios concurrentes.

El aviso de 24 horas se calcula desde el primer fallo registrado de sincronización, con varios intentos fallidos; no es una caducidad ni borra registros. La notificación se presenta en un intento posterior si tiene permiso. Revisar internet, autorización y conflictos. Elegir «Usar datos de Google» en un conflicto descarta el cambio local rechazado; debe revisarse antes.

El respaldo Excel contiene los datos locales del libro seleccionado y los cambios pendientes; no tiene importación/restauración automática. Mantener copias del libro y de los archivos de respaldo en una ubicación controlada.

El crecimiento del calendario y los préstamos está limitado por el tamaño del libro y los controles de la aplicación. No se garantiza almacenamiento indefinido ni ausencia de mantenimiento. Revisar lentitud, errores, sincronización, capacidad, permisos y actualizaciones de proveedores.

La interfaz de Consultas incluye Información general y Préstamos. Las cuotas pagadas tienen fondo verde y las vencidas rojo; hay filtros Todos/Pagados/Moras/Próximos. La línea «Mora pendiente» de una cuota solo se muestra con un importe explícitamente registrado. Los importes estimados de mora pueden seguir formando parte de los saldos según el acuerdo registrado.

Las pruebas automatizadas usan datos ficticios. No acreditan por sí solas una autorización OAuth o notificaciones en un teléfono concreto. En esta exportación no se ha creado ningún recurso Cloud ni ejecutado una prueba sobre un libro remoto.
