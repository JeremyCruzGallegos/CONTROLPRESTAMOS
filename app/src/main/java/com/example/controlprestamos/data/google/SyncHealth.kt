package com.example.controlprestamos.data.google

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.controlprestamos.MainActivity
import com.example.controlprestamos.R
import org.json.JSONObject

internal const val SYNC_FAILURE_DELAY = 24 * 60 * 60_000L
internal data class SyncFailurePeriod(val first: Long, val last: Long, val attempts: Int, val notified: Boolean = false) {
    fun shouldNotify(now: Long) = attempts >= 2 && now - first >= SYNC_FAILURE_DELAY && !notified
    fun next(now: Long) = copy(last = now, attempts = attempts + 1)
}
internal class SyncHealth(private val file: SheetCache) {
    fun read(): SyncFailurePeriod? = file.read()?.let { SyncFailurePeriod(it.getLong("first"), it.getLong("last"), it.getInt("attempts"), it.optBoolean("notified")) }
    private fun write(value: SyncFailurePeriod) = file.write(JSONObject().put("first", value.first).put("last", value.last)
        .put("attempts", value.attempts).put("notified", value.notified))
    fun failed(now: Long = System.currentTimeMillis()): SyncFailurePeriod = synchronized(lock) {
        val value = read()?.next(now) ?: SyncFailurePeriod(now, now, 1)
        write(value); value
    }
    fun notified() = synchronized(lock) { read()?.let { write(it.copy(notified = true)) } }
    fun success() = synchronized(lock) { file.delete() }
    companion object { private val lock = Any() }
}
internal object SyncFailureNotifications {
    private const val CHANNEL = "sync-failures"
    private fun code(subject: String, book: String) = remoteCacheKey(subject, book).hashCode()
    fun failure(context: Context, subject: String, book: String) {
        val health = SyncHealth(accountFile(context, subject, book, "sync-health"))
        val period = health.failed()
        if (!period.shouldNotify(System.currentTimeMillis())) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sincronización pendiente", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, code(subject, book), Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(code(subject, book), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_loans)
            .setContentTitle("No se pudo sincronizar durante 24 horas")
            .setContentText("Tus cambios siguen guardados en el teléfono. Abre la app para revisar la conexión con Google.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())
        health.notified()
    }
    fun recovered(context: Context, subject: String, book: String) {
        SyncHealth(accountFile(context, subject, book, "sync-health")).success()
        context.getSystemService(NotificationManager::class.java).cancel(code(subject, book))
    }
    fun overdue(context: Context, subject: String, book: String): Boolean =
        SyncHealth(accountFile(context, subject, book, "sync-health")).read()?.let { System.currentTimeMillis() - it.first >= SYNC_FAILURE_DELAY } == true
}
