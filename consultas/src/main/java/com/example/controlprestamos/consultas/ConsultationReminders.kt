package com.example.controlprestamos.consultas

import android.Manifest
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal data class ClientReminderSettings(val enabled:Boolean=false,val hour:Int=8,val minute:Int=0,val amounts:Boolean=false,val owner:String=""){
    fun next(after:ZonedDateTime):ZonedDateTime=after.toLocalDate().atTime(hour,minute).atZone(after.zone).let{if(it.isAfter(after))it else it.plusDays(1)}
}
internal object ConsultationReminders{
    private const val ALARM=52101
    private const val NOTICE=52102
    private const val JOB=52103
    private const val CHANNEL="client-payment-reminders"
    private val lima=ZoneId.of("America/Lima")
    fun load(context:Context):ClientReminderSettings{val p=context.getSharedPreferences("client-reminders",0);return ClientReminderSettings(p.getBoolean("enabled",false),p.getInt("hour",8).coerceIn(0,23),p.getInt("minute",0).coerceIn(0,59),p.getBoolean("amounts",false),p.getString("owner","").orEmpty())}
    fun save(context:Context,settings:ClientReminderSettings){context.getSharedPreferences("client-reminders",0).edit().putBoolean("enabled",settings.enabled).putInt("hour",settings.hour).putInt("minute",settings.minute).putBoolean("amounts",settings.amounts).putString("owner",settings.owner).apply();cancel(context);schedule(context)}
    private fun alarm(context:Context)=PendingIntent.getBroadcast(context,ALARM,Intent(context,ConsultationReminderReceiver::class.java).setAction("CLIENT_PAYMENT_REMINDER"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context:Context){context.getSystemService(AlarmManager::class.java).cancel(alarm(context));context.getSystemService(JobScheduler::class.java).cancel(JOB);context.getSystemService(NotificationManager::class.java).cancel(NOTICE);context.getSharedPreferences("client-reminders",0).edit().remove("scheduledAt").apply()}
    fun disable(context:Context){val current=load(context);save(context,current.copy(enabled=false,amounts=false,owner=""))}
    private fun session(context:Context):JSONObject?=SecureSession(context).read("session")?.let{runCatching{JSONObject(it)}.getOrNull()}
    fun observe(context:Context,profile:Profile?,busy:Boolean){
        val settings=load(context)
        if(profile==null){if(!busy && session(context)==null)disable(context);return}
        if(settings.enabled && settings.owner!=profile.sub){disable(context);return}
        if(profile.authorized)schedule(context)else cancel(context)
    }
    fun schedule(context:Context,force:Boolean=false){
        val settings=load(context);if(!settings.enabled || settings.owner.isBlank() || session(context)==null)return
        val prefs=context.getSharedPreferences("client-reminders",0);if(!force && prefs.getLong("scheduledAt",0)>System.currentTimeMillis())return
        val next=settings.next(ZonedDateTime.now(lima)).toInstant().toEpochMilli();prefs.edit().putLong("scheduledAt",next).apply()
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm(context))
    }
    fun enqueue(context:Context){
        if(!load(context).enabled)return
        val job=JobInfo.Builder(JOB,android.content.ComponentName(context,ConsultationReminderJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }
    fun showFresh(context:Context,active:()->Boolean){
        val settings=load(context);if(!settings.enabled)return
        val saved=session(context) ?: return
        val config=ConsultasConfig.load(context);if(saved.optString("origin")!=config.origin)return
        val token=saved.optString("token");if(token.isBlank())return
        val response=try{ConsultasApi(config).request("/v1/loans",token)}catch(error:ConsultasFailure){if(error.status==401 || error.status==403)disable(context);return}
        val profile=Profile.decode(response.getJSONObject("profile"))
        if(!profile.authorized || profile.sub!=settings.owner)return
        val loans=response.getJSONArray("loans").objects().map(ConsultationLoan::decode);val day=LocalDate.parse(response.getString("asOf"))
        val totals=loans.map{it.pendingThrough(day)};val count=totals.sumOf{it.count};val amount=totals.sumOf{it.total};if(count==0)return
        val current=load(context)
        if(!active() || !current.enabled || current.owner!=profile.sub || session(context)?.optString("token")!=token)return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Recordatorios de mis pagos",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).putExtra("open_payments",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open=PendingIntent.getActivity(context,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notifications).setContentTitle("ControlPrestamos Consultas").setContentText("Tienes un recordatorio de pagos").build()
        val text=if(current.amounts)"$count cuota(s) pendientes hasta hoy: ${money(amount)}, incluida la mora." else "Revisa las cuotas pendientes y la mora de tus préstamos."
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notifications).setContentTitle("Recuerda tus pagos")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setColor(0xFF10B981.toInt()).build()
        manager.notify(NOTICE,notification)
    }
}
class ConsultationReminderReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action=="CLIENT_PAYMENT_REMINDER")ConsultationReminders.enqueue(context)
        ConsultationReminders.schedule(context,force=true)
    }
}
class ConsultationReminderJob:JobService(){
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var generation=0
    private var work:Future<*>?=null
    override fun onStartJob(params:JobParameters):Boolean{val run=++generation;work=executor.submit{
        try{ConsultationReminders.showFresh(applicationContext){generation==run}}catch(_:Exception){/* No stale financial notification on network or parsing failure. */}
        if(generation==run)jobFinished(params,false)
    };return true}
    override fun onStopJob(params:JobParameters):Boolean{generation++;work?.cancel(true);return ConsultationReminders.load(applicationContext).enabled}
    override fun onDestroy(){generation++;executor.shutdownNow();super.onDestroy()}
}

@Composable internal fun ConsultationReminderSettings(owner:String,dismiss:()->Unit){
    val context=LocalContext.current
    var settings by remember{mutableStateOf(ConsultationReminders.load(context).let{if(it.owner==owner)it else it.copy(enabled=false,amounts=false,owner=owner)})}
    var error by remember{mutableStateOf<String?>(null)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)settings=settings.copy(enabled=true)else error="Permite las notificaciones en los ajustes del teléfono para recibir recordatorios."}
    AlertDialog(onDismissRequest=dismiss,title={Text("Notificaciones de mis pagos")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Switch(settings.enabled,onCheckedChange={enabled->if(enabled && Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)else settings=settings.copy(enabled=enabled)});Text("Recordatorio diario",Modifier.padding(start=10.dp))}
        OutlinedButton(onClick={TimePickerDialog(context,{_,hour,minute->settings=settings.copy(hour=hour,minute=minute)},settings.hour,settings.minute,true).show()}){Text("Hora: %02d:%02d".format(settings.hour,settings.minute))}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(settings.amounts,onCheckedChange={settings=settings.copy(amounts=it)});Text("Mostrar importes en la notificación")}
        Text("Se avisa si tienes cuotas pendientes hasta hoy. La información se actualiza al conectar.",style=MaterialTheme.typography.bodySmall)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={ConsultationReminders.save(context,settings.copy(owner=owner));dismiss()}){Text("Guardar")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})
}
