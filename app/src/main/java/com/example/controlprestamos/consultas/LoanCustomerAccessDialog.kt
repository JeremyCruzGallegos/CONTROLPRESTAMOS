package com.example.controlprestamos.consultas

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.controlprestamos.R as AppR
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.example.controlprestamos.data.google.SheetLoan
import com.example.controlprestamos.data.google.SheetsIdentity

private fun login(activity: ComponentActivity, model: ConsultasViewModel) {
    model.startLogin()?.let { uri -> runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,uri)) }
        .onFailure { model.cancelLogin();model.error("No se pudo abrir Google. Vuelve a intentarlo.") } }
}
@Composable private fun AccessLogin(model:ConsultasViewModel,state:ConsultasState,activity:ComponentActivity){
    when {
        state.profile==null->{
            Text("Conecta una vez tu cuenta Google administradora para consultar el código y cambiar el permiso.")
            Button(onClick={login(activity,model)},enabled=!state.connecting && !state.busy){Text(if(state.connecting)"Conectando…" else "Conectar Google")}
            if(state.connecting)TextButton(onClick=model::cancelLogin){Text("Cancelar conexión")}
        }
        state.profile.administrator.not()->{
            Text("${state.profile.email} no tiene permiso para administrar las consultas.")
            TextButton(onClick=model::logout){Text("Usar otra cuenta Google")}
        }
    }
}

@Composable internal fun LoanCustomerAccessDialog(loans:List<SheetLoan>,bookId:String,dismiss:()->Unit){
    val activity=LocalContext.current as ComponentActivity
    val model=remember(activity){ViewModelProvider(activity)[ConsultasViewModel::class.java]}
    val state by model.state.collectAsState()
    val pending by model.sensitiveRequest.collectAsState()
    val clipboard=LocalClipboardManager.current
    val name=loans.first().client
    val ids=loans.map{it.id}.distinct().sorted()
    LaunchedEffect(state.profile?.sub,ids,bookId){
        if(bookId==SheetsIdentity.CLIENT_ID && state.profile?.administrator==true)model.openLoanCustomer(name,ids)
    }
    DisposableEffect(Unit){onDispose{model.closeLoanCustomer()}}
    AlertDialog(onDismissRequest={if(!state.busy && pending==null)dismiss()},title={Text(name)},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(bookId!=SheetsIdentity.CLIENT_ID)Text("Este libro no es el que está conectado con Consultas. Abre el libro del cliente para gestionar su código.")
            else {
                AccessLogin(model,state,activity)
                if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let{Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={model.dismissError();if(state.profile?.administrator==true)model.openLoanCustomer(name,ids)}){Text("Reintentar")}}
                state.loanCustomer?.let{customer->
                    Text("Código de cliente",style=MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)){Text(customer.code,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
                        IconButton(onClick={clipboard.setText(AnnotatedString(customer.shareMessage))}) {
                            Icon(painterResource(AppR.drawable.ic_copy),"Copiar código de cliente")
                        }
                    }
                    HorizontalDivider()
                    val editable=!state.busy && pending==null
                    Row(Modifier.fillMaxWidth().clickable(enabled=editable){model.setLoanCustomerVisibility(customer,!customer.enabled)},verticalAlignment=Alignment.CenterVertically){
                        Checkbox(checked=customer.enabled,onCheckedChange={model.setLoanCustomerVisibility(customer,it)},enabled=editable)
                        Text("Permitir visualizar su información",modifier=Modifier.weight(1f))
                    }
                    Text(if(customer.enabled)"Consulta permitida" else "Consulta deshabilitada",style=MaterialTheme.typography.bodySmall)
                    if(customer.email.isNotBlank())Text(customer.email,style=MaterialTheme.typography.bodySmall)
                    Text("El cambio se confirma con tu huella, rostro o bloqueo del teléfono.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    },confirmButton={TextButton(onClick=dismiss,enabled=!state.busy && pending==null){Text("Cerrar")}})
}

@Composable internal fun LoanPaymentInstructionsDialog(bookId:String,dismiss:()->Unit){
    val activity=LocalContext.current as ComponentActivity
    val model=remember(activity){ViewModelProvider(activity)[ConsultasViewModel::class.java]}
    val state by model.state.collectAsState()
    val pending by model.sensitiveRequest.collectAsState()
    var instructions by remember{mutableStateOf("")}
    LaunchedEffect(state.profile?.sub){if(bookId==SheetsIdentity.CLIENT_ID && state.profile?.administrator==true)model.loadAdministration()}
    LaunchedEffect(state.paymentInstructions){instructions=state.paymentInstructions}
    AlertDialog(onDismissRequest={if(!state.busy && pending==null)dismiss()},title={Text("Instrucciones de pago")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        AccessLogin(model,state,activity)
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(state.profile?.administrator==true)OutlinedTextField(instructions,{instructions=it.take(4000)},minLines=4,label={Text("Lo que verá el cliente en Cómo pagar")},enabled=!state.busy && pending==null)
    }},confirmButton={TextButton(onClick={model.savePaymentInstructions(instructions)},enabled=state.profile?.administrator==true && !state.busy && pending==null){Text("Guardar")}},dismissButton={TextButton(onClick=dismiss,enabled=!state.busy && pending==null){Text("Cerrar")}})
}
