package com.example.controlprestamos.notifications

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.controlprestamos.MainActivity
import com.example.controlprestamos.R
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import java.time.*
import java.util.concurrent.Executors

internal data class ReminderSettings(val enabled: Boolean = false, val count: Int = 2, val hour: Int = 8,
    val minute: Int = 0, val endHour: Int = 18, val days: Set<Int> = (1..7).toSet(),
    val onlyPending: Boolean = true, val privateContent: Boolean = false, val sound: Boolean = true) {
    fun times(): List<LocalTime> {
        val start = hour * 60 + minute
        val end = maxOf(start, endHour * 60)
        return List(count.coerceIn(1, 4)) { index ->
            LocalTime.ofSecondOfDay((start + if (count <= 1) 0 else (end - start) * index / (count - 1)) * 60L)
        }.distinct()
    }
    fun next(after: ZonedDateTime): ZonedDateTime? = (0..7).asSequence().map { after.toLocalDate().plusDays(it.toLong()) }
        .filter { it.dayOfWeek.value in days }.flatMap { day -> times().asSequence().map { day.atTime(it).atZone(after.zone) } }
        .firstOrNull { it.isAfter(after) }
}

internal object CollectionNotifications {
    private const val NOTICE = 43100
    private const val SNOOZE = 43101
    fun load(context: Context): ReminderSettings {
        val p = context.getSharedPreferences("reminders", 0)
        return ReminderSettings(p.getBoolean("enabled", false), p.getInt("count", 2).coerceIn(1, 4),
            p.getInt("hour", 8).coerceIn(0, 23), p.getInt("minute", 0).coerceIn(0, 59), p.getInt("endHour", 18).coerceIn(0, 23),
            p.getString("days", "1,2,3,4,5,6,7").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
            p.getBoolean("onlyPending", true), p.getBoolean("privateContent", false), p.getBoolean("sound", true))
    }
    fun save(context: Context, value: ReminderSettings) {
        context.getSharedPreferences("reminders", 0).edit().putBoolean("enabled", value.enabled)
            .putInt("count", value.count).putInt("hour", value.hour).putInt("minute", value.minute).putInt("endHour", value.endHour)
            .putString("days", value.days.sorted().joinToString(",")).putBoolean("onlyPending", value.onlyPending)
            .putBoolean("privateContent", value.privateContent).putBoolean("sound", value.sound).apply()
        cancel(context); schedule(context)
    }
    private fun alarm(context: Context, code: Int = NOTICE) = PendingIntent.getBroadcast(context, code,
        Intent(context, CollectionReminderReceiver::class.java).setAction("COLLECTION_REMINDER"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, force: Boolean = false) {
        val settings = load(context)
        if (!settings.enabled || context.getSharedPreferences("google-session", 0).getString("subject", null) == null) return
        val prefs = context.getSharedPreferences("reminders", 0)
        if (!force && prefs.getLong("scheduledAt", 0) != 0L) return
        val next = settings.next(ZonedDateTime.now(LIMA)) ?: return
        prefs.edit().putLong("scheduledAt", next.toInstant().toEpochMilli()).apply()
        // Inexact alarms respect Android's battery policy and do not require exact-alarm access.
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(), alarm(context))
    }
    fun cancel(context: Context) {
        context.getSharedPreferences("reminders", 0).edit().remove("scheduledAt").apply()
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context, SNOOZE))
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE)
    }
    fun snooze(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 30 * 60_000, alarm(context, SNOOZE))
    }
    fun show(context: Context) {
        val settings = load(context)
        if (!settings.enabled || today().dayOfWeek.value !in settings.days) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("google-session", 0)
        val subject = prefs.getString("subject", null) ?: return
        val connection = GoogleConnection.load(context)
        val copy = connection.effectiveCopy(prefs.getBoolean("copy", false))
        val book = if (copy) connection.testBookId else connection.productionBookId
        val local = OfflineBookStore(context, subject, book)
        val queue = OfflineSync.outbox(context, subject, book).read()
        val rows = local.payments(today(), today())
        val visible = rows.map { row -> queue.changes[row.id]?.second?.let { row.copy(unpaid = it.unpaid, late = it.collectedLate) } ?: row }
        val pending = visible.filter { it.unpaid }
        val expected = local.loans().count { today() in it.firstDue..it.lastDue }
        if (settings.onlyPending && pending.isEmpty() && visible.size >= expected) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTICE); return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (settings.sound) "collections" else "collections-silent"
        manager.createNotificationChannel(NotificationChannel(channel, if (settings.sound) "Cobros de hoy" else "Cobros de hoy · silencioso",
            if (settings.sound) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW))
        val title = (if (copy) "Pruebas · " else "") + "Cobros de hoy · ${dateLabel(today())}"
        val message = when {
            settings.privateContent -> "Revisa los cobros de hoy en la app."
            visible.size < expected -> "Falta actualizar algunas cuotas de hoy. Abre Pagos para revisar."
            pending.isEmpty() -> "Todas las cuotas de hoy están cobradas."
            else -> "${pending.size} cobro(s) pendiente(s) · ${soles(pending.sumOf { it.base + it.late })}"
        }
        val lines = if (settings.privateContent) emptyList() else pending.take(5).map { "${it.loan.client}: ${soles(it.base + it.late)}" }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .putExtra("open_payments", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val later = PendingIntent.getBroadcast(context, 2, Intent(context, CollectionReminderReceiver::class.java).setAction("SNOOZE_COLLECTIONS"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_payments)
            .setContentTitle("Cobros de hoy").setContentText("Abre la app para revisar tus cobros.").build()
        val notification = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_payments).setContentTitle(title)
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText((listOf(message) + lines +
                if (settings.privateContent) emptyList() else listOf("Según los datos guardados en el teléfono.")).joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).addAction(Notification.Action.Builder(null, "Recordar en 30 min", later).build()).build()
        manager.notify(NOTICE, notification)
    }
}

class CollectionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "SNOOZE_COLLECTIONS") { CollectionNotifications.snooze(context); return }
        CollectionNotifications.schedule(context, force = true)
        val result = goAsync()
        executor.execute { try { CollectionNotifications.show(context) } finally { result.finish() } }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CollectionNotifications.schedule(context, force = true)
        OfflineSync.schedule(context)
    }
}
