package com.example.controlprestamos.consultas

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.ui.AppNavigation
import com.example.controlprestamos.ui.FittedSingleLine
import com.example.controlprestamos.ui.theme.*
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConsultasApp(model:ConsultasViewModel,notificationEntry:Int=0,login:()->Unit){
    val state by model.state.collectAsState()
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("appearance",0)}
    var dark by rememberSaveable{mutableStateOf(prefs.getBoolean("dark",true))}
    var accent by rememberSaveable{mutableIntStateOf(prefs.getInt("accent",0).coerceIn(BrandAccents.indices))}
    var page by rememberSaveable{mutableIntStateOf(0)}
    var detailId by rememberSaveable{mutableStateOf<String?>(null)}
    var menu by remember{mutableStateOf(false)}
    var reminders by remember{mutableStateOf(false)}
    var palette by remember{mutableStateOf(false)}
    var showClosed by rememberSaveable{mutableStateOf(true)}
    val uri=LocalUriHandler.current
    val asOf=state.asOf ?: LocalDate.now(ZoneId.of("America/Lima"))
    val selected=state.loans.firstOrNull{it.id==detailId}
    LaunchedEffect(notificationEntry){if(notificationEntry>0){page=1;detailId=null}}
    LaunchedEffect(state.profile?.sub,state.profile?.authorized){detailId=null;reminders=false}
    BackHandler(enabled=detailId!=null){detailId=null}
    SideEffect{(context as? ComponentActivity)?.enableEdgeToEdge(
        statusBarStyle=if(dark)SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
        navigationBarStyle=if(dark)SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))}
    ControlPrestamosTheme(darkTheme=dark,accent=BrandAccents[accent].second){
        Scaffold(topBar={TopAppBar(title={Column{
            Text(if(selected!=null)"Préstamo ${selected.id}" else if(page==0)"Información general" else "Mis préstamos",style=MaterialTheme.typography.titleLarge)
            state.profile?.let{Text(it.customerName.ifBlank{it.email},style=MaterialTheme.typography.labelSmall,maxLines=1,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }},navigationIcon={if(detailId!=null)IconButton(onClick={detailId=null}){Icon(painterResource(R.drawable.ic_back),"Volver a mis préstamos")}},actions={
            if(state.profile!=null)IconButton(onClick=model::refresh,enabled=!state.busy){Icon(painterResource(R.drawable.ic_refresh),"Actualizar mis datos")}
            Box{IconButton(onClick={menu=true}){Icon(painterResource(R.drawable.ic_more),"Opciones")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    if(state.profile?.authorized==true){
                        DropdownMenuItem(text={Text("Notificaciones")},leadingIcon={Icon(painterResource(R.drawable.ic_notifications),null)},onClick={menu=false;reminders=true})
                        if(page==1 && detailId==null)DropdownMenuItem(text={Text(if(showClosed)"Ocultar préstamos concluidos" else "Mostrar préstamos concluidos")},onClick={showClosed=!showClosed;menu=false})
                    }
                    DropdownMenuItem(text={Text(if(dark)"Tema claro" else "Tema oscuro")},leadingIcon={Icon(painterResource(if(dark)R.drawable.ic_sun else R.drawable.ic_moon),null)},onClick={dark=!dark;prefs.edit().putBoolean("dark",dark).apply();menu=false})
                    DropdownMenuItem(text={Text("Color de la app")},onClick={palette=true;menu=false})
                    DropdownMenuItem(text={Text("Privacidad")},onClick={menu=false;runCatching{uri.openUri(model.config.origin+"/privacy")}})
                    if(state.profile!=null)DropdownMenuItem(text={Text("Cerrar sesión")},onClick={menu=false;ConsultationReminders.disable(context);model.logout()})
                }
            }
        })},bottomBar={if(state.profile?.authorized==true)AppNavigation(page,listOf("Información general","Préstamos"),listOf(R.drawable.ic_home,R.drawable.ic_loans),{page=it;detailId=null})}){padding->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let{error->Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.small){Column(Modifier.padding(12.dp)){Text(error,color=MaterialTheme.colorScheme.onErrorContainer);TextButton(onClick=model::dismissError){Text("Cerrar aviso")}}}}
                if(state.profile?.authorized!=true)Registration(state,model,login)
                else if(selected!=null)LoanDetail(selected,asOf)
                else{
                    if(page==0)GeneralInformation(state,asOf)
                    else LoanTable(state.loans.filter{showClosed || !it.completed},asOf,open={detailId=it.id})
                }
            }
        }
        if(reminders)state.profile?.takeIf{it.authorized}?.let{ConsultationReminderSettings(it.sub,dismiss={reminders=false})}
        if(palette)AlertDialog(onDismissRequest={palette=false},title={Text("Color de la app")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            BrandAccents.indices.toList().chunked(5).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                row.forEach{index->Surface(onClick={accent=index;prefs.edit().putInt("accent",index).apply();palette=false},shape=CircleShape,color=BrandAccents[index].second,
                    border=if(accent==index)BorderStroke(3.dp,MaterialTheme.colorScheme.onSurface)else null,modifier=Modifier.size(44.dp)){Box(contentAlignment=Alignment.Center){if(accent==index)Text("✓",color=Color.White)}}}
            }}
        }},confirmButton={TextButton(onClick={palette=false}){Text("Cerrar")}})
    }
}
@Composable private fun ColumnScope.Registration(state:ConsultasState,model:ConsultasViewModel,login:()->Unit){
    var code by rememberSaveable{mutableStateOf("")}
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        Icon(painterResource(R.drawable.ic_consultas),null,Modifier.size(70.dp),tint=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(if(state.profile==null)"Tus préstamos, a la mano" else "Vincula tu código de cliente",style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        if(state.profile==null){
            Text("Consulta tus préstamos, cuotas y pagos con la información de tu prestamista.",textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp));Button(onClick=login,enabled=!state.busy && !state.connecting){Text(if(state.connecting)"Conectando…" else "Continuar con Google")}
            if(state.connecting)TextButton(onClick=model::cancelLogin){Text("Cancelar conexión")}
        }else{
            Text(state.profile.email,style=MaterialTheme.typography.bodyMedium)
            Text("Ingresa el código que te compartió tu prestamista. Puedes pegar el mensaje completo.",textAlign=TextAlign.Center,modifier=Modifier.padding(vertical=16.dp))
            OutlinedTextField(code,{code=it},modifier=Modifier.fillMaxWidth(),label={Text("Código de cliente")},singleLine=true,enabled=!state.busy)
            Button(onClick={model.claimCustomer(code)},enabled=code.isNotBlank() && !state.busy){Text("Vincular código")}
            if(state.profile.customerCode.isNotBlank())Text("Código ${state.profile.customerCode}\nTu prestamista debe permitir la visualización para mostrar tus datos.",textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=model::refresh,enabled=!state.busy){Text("Comprobar autorización")}
        }
    }
}
@Composable private fun ColumnScope.GeneralInformation(state:ConsultasState,day:LocalDate){
    val dues=state.loans.flatMap{it.dues}
    val next=dues.filter{!it.settled && it.due>=day}.minByOrNull{it.due}?.due
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)){
        item{ClientBalanceHeader(state)}
        item{Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Resumen de mis préstamos",style=MaterialTheme.typography.titleMedium)
            ClientLine("Total recibido",money(state.loans.sumOf{it.principal}))
            ClientLine("Total pagado",money(state.loans.sumOf{it.collected}))
            ClientLine("Pendiente hasta hoy",money(state.loans.sumOf{it.pendingThrough(day).total}))
            ClientLine("Cuotas vencidas",dues.count{it.status==DueStatus.OVERDUE}.toString())
            ClientLine("Cuotas pagadas","${dues.count{it.settled}} de ${dues.size}")
            val late=state.loans.sumOf{it.pendingLate}
            if(late>0)ClientLine("Mora pendiente",money(late))
            ClientLine("Préstamos concluidos",state.loans.count{it.completed}.toString())
        }}}
        item{Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Mi próximo pago",style=MaterialTheme.typography.titleMedium)
            if(next==null)Text("No tienes próximas cuotas pendientes.")else{
                ClientLine("Fecha",dateText(next))
                ClientLine("Cuotas de ese día",money(dues.filter{!it.settled && it.due==next}.sumOf{it.base+it.late}))
            }
            Text("Revisa Préstamos para consultar las fechas, cuotas y condiciones de cada préstamo.",style=MaterialTheme.typography.bodySmall)
        }}}
        item{Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Cómo realizar mi pago",style=MaterialTheme.typography.titleMedium)
            SelectionContainer{Text(state.paymentInstructions.ifBlank{"Solicita las instrucciones de pago a tu prestamista."})}
        }}}
        if(state.loans.any{it.needsReview})item{Text("Hay pagos registrados con fecha por confirmar. Consulta a tu prestamista.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable private fun ClientBalanceHeader(state:ConsultasState){
    val colors=MaterialTheme.colorScheme
    Surface(shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,colors.outlineVariant),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer,colors.surface))).padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("Mi saldo pendiente",style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)
            FittedSingleLine(money(state.loans.sumOf{it.balance}),Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineMedium,color=colors.onSurface)
            Text("Incluye cuotas pendientes y la mora registrada a la fecha.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            Text("${state.loans.count{!it.completed}} préstamo(s) pendiente(s) · ${state.profile?.customerCode.orEmpty()}",style=MaterialTheme.typography.labelSmall)
            state.fetchedAt?.let{Text("Actualizado ${updateText(it)}",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)}
        }
    }
}
@Composable private fun ColumnScope.LoanTable(loans:List<ConsultationLoan>,day:LocalDate,open:(ConsultationLoan)->Unit){
    if(loans.isEmpty()){EmptyClientText("No tienes préstamos pendientes para mostrar.\nPuedes consultar los concluidos desde los tres puntos.");return}
    val colors=MaterialTheme.colorScheme
    var filter by rememberSaveable{mutableStateOf(ClientLoanFilter.ALL)}
    val filtered=loans.filter{loan->when(filter){
        ClientLoanFilter.ALL->true
        ClientLoanFilter.PAID->loan.completed
        ClientLoanFilter.LATE->loan.dues.any{ClientLoanFilter.LATE.matches(it,day)}
        ClientLoanFilter.UPCOMING->loan.dues.any{ClientLoanFilter.UPCOMING.matches(it,day)}
    }}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        ClientLoanFilter.entries.forEach{option->FilterChip(selected=filter==option,onClick={filter=option},label={Text(option.label)})}
    }
    if(filtered.isEmpty()){EmptyClientText("No hay préstamos en este filtro.");return}
    Card(Modifier.fillMaxWidth().weight(1f),border=BorderStroke(1.dp,colors.outlineVariant)){
        Surface(color=colors.surfaceContainerHigh){Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=12.dp)){
            listOf("Préstamo","Prestado","Pendiente","A hoy").forEachIndexed{i,title->FittedSingleLine(title,Modifier.weight(if(i==0)1.2f else 1f).padding(horizontal=3.dp),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant,textAlign=if(i==0)TextAlign.Start else TextAlign.End)}
        }}
        LazyColumn{items(filtered,key={it.id}){loan->
            Row(Modifier.fillMaxWidth().clickable{open(loan)}.padding(horizontal=10.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1.2f)){FittedSingleLine(loan.id,style=MaterialTheme.typography.labelLarge,color=colors.onSurface);Text(dateText(loan.disbursed),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)}
                listOf(loan.principal,loan.balance,loan.pendingThrough(day).total).forEach{amount->FittedSingleLine(money(amount),Modifier.weight(1f).padding(start=4.dp),style=MaterialTheme.typography.labelMedium,color=colors.onSurface,textAlign=TextAlign.End)}
            }
            Text(if(loan.completed)"Concluido · ${loan.paidCount}/${loan.totalCount} cuotas" else "${loan.paidCount}/${loan.totalCount} cuotas pagadas · Toca para ver el detalle",Modifier.padding(start=10.dp,bottom=10.dp),style=MaterialTheme.typography.labelSmall,color=if(loan.completed)colors.secondary else colors.primary)
            HorizontalDivider(color=colors.outlineVariant)
        }}
    }
}
@Composable private fun ColumnScope.LoanDetail(loan:ConsultationLoan,day:LocalDate){
    var filter by rememberSaveable(loan.id){mutableStateOf(ClientLoanFilter.ALL)}
    val dues=loan.dues.filter{filter.matches(it,day)}
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=18.dp)){
        item{Card{Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(if(loan.completed)"Préstamo concluido" else "Tu préstamo",style=MaterialTheme.typography.titleLarge)
            ClientLine("Monto recibido",money(loan.principal));ClientLine("Total pactado",money(loan.agreed));ClientLine("Saldo pendiente",money(loan.balance));ClientLine("Pendiente hasta hoy",money(loan.pendingThrough(day).total))
            HorizontalDivider();ClientLine("Interés mensual","${loan.rate}%");ClientLine("Plazo","${loan.months} mes(es) · ${loan.totalCount} cuotas");if(loan.dailyLate>0)ClientLine("Mora por día",money(loan.dailyLate));ClientLine("Desembolso",dateText(loan.disbursed))
            Text("${loan.paidCount} de ${loan.totalCount} cuotas pagadas",style=MaterialTheme.typography.labelLarge)
        }}}
        item{Text("Cuotas y pagos",style=MaterialTheme.typography.titleMedium)}
        item{Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            ClientLoanFilter.entries.forEach{option->FilterChip(selected=filter==option,onClick={filter=option},label={Text(option.label)})}
        }}
        if(dues.isEmpty())item{Text("No hay cuotas en este filtro.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(dues,key={it.number}){due->DueRow(due)}
    }
}
@Composable private fun DueRow(due:Due,framed:Boolean=true){
    val content:@Composable ()->Unit={Column(Modifier.fillMaxWidth().padding(if(framed)14.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth()){Text("Cuota ${due.number} · ${dateText(due.due)}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);Text(due.status.label,style=MaterialTheme.typography.labelSmall,color=if(due.settled)MaterialTheme.colorScheme.secondary else if(due.status==DueStatus.OVERDUE)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
        ClientLine("Cuota",money(due.base));if(due.settled && due.late>0)ClientLine("Mora registrada",money(due.late))
        else if(!due.settled && due.recordedLate>0)ClientLine("Mora pendiente",money(due.recordedLate))
        if(due.settled)ClientLine("Pago registrado",money(due.paid))else ClientLine("Total de la cuota",money(due.base+due.late))
        due.paidOn?.let{Text("Registrado el ${dateText(it)}",style=MaterialTheme.typography.bodySmall)}
        if(due.status==DueStatus.REVIEW)Text("El pago figura registrado; su fecha está por confirmar.",style=MaterialTheme.typography.bodySmall)
    }}
    val dark=MaterialTheme.colorScheme.surface.luminance()<0.5f
    val background=when{
        due.settled->if(dark)Color(0xFF16452B)else Color(0xFFCEF2DA)
        due.status==DueStatus.OVERDUE->if(dark)Color(0xFF601D27)else Color(0xFFFFDAD9)
        else->MaterialTheme.colorScheme.surfaceContainer
    }
    if(framed)Card(colors=CardDefaults.cardColors(containerColor=background)){content()}else content()
}
@Composable private fun ClientLine(label:String,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);FittedSingleLine(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold),color=MaterialTheme.colorScheme.onSurface,textAlign=TextAlign.End)}}
@Composable private fun ColumnScope.EmptyClientText(text:String){Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text(text,textAlign=TextAlign.Center,fontStyle=FontStyle.Italic,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))}}
