package com.example.controlprestamos.consultas

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ConsultasState(val profile: Profile? = null, val loans: List<ConsultationLoan> = emptyList(),
    val busy: Boolean = false, val connecting: Boolean = false, val error: String? = null,
    val demo: Boolean = false, val fetchedAt: Instant? = null, val asOf: LocalDate? = null,
    val accounts: List<AccountAccess> = emptyList(), val catalog: List<CatalogLoan> = emptyList(), val notice: String? = null, val customers: List<CustomerAccess> = emptyList(), val paymentInstructions: String = "", val loanCustomer: CustomerAccess? = null)

internal class ConsultasViewModel(application: Application) : AndroidViewModel(application) {
    val config = ConsultasConfig.load(application)
    private val api = ConsultasApi(config)
    private val secure = SecureSession(application)
    private val mutable = MutableStateFlow(ConsultasState())
    val state = mutable.asStateFlow()
    private var session: String? = null
    private var operation: Job? = null
    private var generation = 0
    val lenderApp = !application.packageName.endsWith(".consultas")
    private val callbackScheme = if (!lenderApp) "cpconsultas" else application.packageName + ".consultasadmin"
    private val confirmation = MutableStateFlow<AccessConfirmation?>(null)
    val sensitiveRequest = confirmation.asStateFlow()
    private val approvalGate = AccessApprovalGate { android.os.SystemClock.elapsedRealtime() }
    private fun approvalOwner() = "${mutable.value.profile?.sub}|${session}|$generation"
    private fun confirm(purpose: String, action: () -> Unit) {
        if (!lenderApp || mutable.value.profile?.administrator != true || mutable.value.busy || confirmation.value != null) return
        val request=AccessConfirmation(token(),purpose)
        if(approvalGate.request(request.id,approvalOwner(),action))confirmation.value=request
    }
    fun deviceAuthenticationFinished(id: String, success: Boolean, message: String?) {
        if (confirmation.value?.id != id) return
        confirmation.value = null
        if (!approvalGate.resolve(id, approvalOwner(), success && mutable.value.profile?.administrator == true))
            error(message ?: "Verificación cancelada. No se guardó el cambio.")
    }
    init {
        val saved = secure.read("session")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (config.configured && saved?.optString("origin") == config.origin) {
            session = saved.optString("token").takeIf { it.isNotEmpty() }
            if (session != null) refresh()
        } else secure.write("session", null)
    }
    fun error(message: String) { mutable.value = mutable.value.copy(error = message, connecting = false) }
    fun dismissError() { mutable.value = mutable.value.copy(error = null, notice = null) }
    fun startLogin(): Uri? {
        if (!config.configured || mutable.value.busy || mutable.value.connecting) return null
        val attempt = LoginAttempt.create()
        secure.write("attempt", JSONObject().put("origin", config.origin).put("attempt", attempt.json()).toString())
        mutable.value = mutable.value.copy(connecting = true, error = null)
        return Uri.parse(config.origin + "/auth/start").buildUpon().appendQueryParameter("app", if (!lenderApp) "customer" else if (getApplication<Application>().packageName.endsWith(".cliente")) "lender-client" else "lender").appendQueryParameter("code_challenge", challenge(attempt.verifier))
            .appendQueryParameter("state", attempt.state).build()
    }
    fun cancelLogin() { secure.write("attempt", null); mutable.value = mutable.value.copy(connecting = false) }
    fun acceptCallback(uri: Uri?) {
        if (uri == null || uri.scheme != callbackScheme || uri.host != "oauth" || uri.path != "/callback") return
        val saved = secure.read("attempt")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val attempt = runCatching { LoginAttempt.decode(saved.getString("attempt")) }.getOrNull() ?: return
        if (saved.optString("origin") != config.origin || !attempt.matches(uri.getQueryParameter("state").orEmpty(), System.currentTimeMillis())) {
            error("La conexión de Google venció. Vuelve a iniciarla."); return
        }
        secure.write("attempt", null)
        if (uri.getQueryParameter("error") != null) { error("Conexión cancelada. Puedes volver a intentarlo."); return }
        val code = uri.getQueryParameter("code")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
            ?: run { error("La conexión no se completó. Reintenta con Google."); return }
        launchOperation {
            val response = withContext(Dispatchers.IO) { api.request("/auth/exchange", method = "POST", body = JSONObject().put("code", code).put("verifier", attempt.verifier)) }
            val next = response.getString("sessionToken"); require(next.matches(Regex("[A-Za-z0-9_-]{43}")))
            secure.write("session", JSONObject().put("origin", config.origin).put("token", next).toString())
            session = next; mutable.value = ConsultasState(profile = Profile.decode(response.getJSONObject("profile")), busy = true)
            loadLoans(next)
        }
    }
    private fun launchOperation(action: suspend () -> Unit) {
        operation?.cancel(); val run = generation
        mutable.value = mutable.value.copy(busy = true, error = null, connecting = false, notice = null)
        operation = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (run == generation) {
                    val authorizationLost = failure is ConsultasFailure && (failure.status == 401 || failure.code == "ACCESS_REVOKED")
                    if (authorizationLost) { secure.write("session", null); session = null; mutable.value = ConsultasState(error = failure.message) }
                    else mutable.value = mutable.value.copy(error = if (failure is ConsultasFailure) failure.message
                        else "No se pudo actualizar. Revisa tu conexión y vuelve a intentarlo.")
                }
            } finally { if (run == generation) mutable.value = mutable.value.copy(busy = false, connecting = false) }
        }
    }
    private suspend fun loadLoans(current: String) {
        val response = withContext(Dispatchers.IO) { api.request("/v1/loans", current) }
        val profile = Profile.decode(response.getJSONObject("profile"))
        val instructions = if (profile.authorized) withContext(Dispatchers.IO) { api.request("/v1/payment-instructions", current).optString("instructions") } else ""
        mutable.value = mutable.value.copy(profile = profile, loans = response.getJSONArray("loans").objects().map(ConsultationLoan::decode),
            fetchedAt = Instant.parse(response.getString("fetchedAt")), asOf = LocalDate.parse(response.getString("asOf")), paymentInstructions = instructions)
    }
    fun refresh() {
        if (mutable.value.demo) return
        val current = session ?: return
        launchOperation { loadLoans(current) }
    }
    fun claimCustomer(code: String) {
        val current = session ?: return
        launchOperation {
            withContext(Dispatchers.IO) { api.request("/v1/customer/claim", current, "POST", JSONObject().put("code",code.trim())) }
            loadLoans(current)
        }
    }
    private suspend fun loadCustomers(current: String) {
        val result = withContext(Dispatchers.IO) { api.request("/v1/admin/customers",current) }
        mutable.value = mutable.value.copy(customers = result.getJSONArray("customers").objects().map(CustomerAccess::decode), paymentInstructions = result.optString("paymentInstructions"), loanCustomer = mutable.value.loanCustomer?.let { current -> result.getJSONArray("customers").objects().map(CustomerAccess::decode).firstOrNull { it.code == current.code } })
    }
    fun loadAdministration() {
        val current = session ?: return
        if (!lenderApp || mutable.value.profile?.administrator != true) return
        launchOperation {
            val (accounts, catalog) = withContext(Dispatchers.IO) { api.request("/v1/admin/accounts",current) to api.request("/v1/admin/catalog",current) }
            mutable.value = mutable.value.copy(accounts=accounts.getJSONArray("accounts").objects().map(AccountAccess::decode),
                catalog=catalog.getJSONArray("loans").objects().map { CatalogLoan(it.getString("id"),it.getString("clientName")) })
            loadCustomers(current)
        }
    }
    fun openLoanCustomer(name: String, ids: List<String>) {
        val current = session ?: return
        if (!lenderApp || mutable.value.profile?.administrator != true) return
        mutable.value = mutable.value.copy(loanCustomer = null)
        launchOperation {
            val response=withContext(Dispatchers.IO) { api.request("/v1/admin/customer-for-loans", current, "POST", JSONObject().put("name",name).put("loanIds",JSONArray(ids.sorted()))) }
            mutable.value = mutable.value.copy(loanCustomer = CustomerAccess.decode(response.getJSONObject("customer")))
        }
    }
    fun closeLoanCustomer() {
        approvalGate.cancel(); confirmation.value = null
        mutable.value = mutable.value.copy(loanCustomer = null)
    }
    fun setLoanCustomerVisibility(customer: CustomerAccess, enabled: Boolean) =
        saveCustomer(customer, customer.name, customer.loanIds, enabled, customer.linkedSub)
    fun createCustomer(name: String) = confirm("Crear el código de cliente de ${name.trim()}") {
        val current=session ?: return@confirm
        launchOperation {
            withContext(Dispatchers.IO) { api.request("/v1/admin/customers",current,"POST",JSONObject().put("name",name.trim())) }
            loadCustomers(current)
        }
    }
    fun saveCustomer(customer: CustomerAccess, name: String, ids: Set<String>, enabled: Boolean, sub: String?) {
        val body=JSONObject().put("code",customer.code).put("name",name.trim()).put("loanIds",JSONArray(ids.sorted())).put("enabled",enabled).put("linkedSub",sub ?: JSONObject.NULL)
        confirm(if(enabled) "Autorizar a $name a consultar ${ids.size} préstamo(s)" else "Guardar $name con el acceso de consulta deshabilitado") {
            val current=session ?: return@confirm
            launchOperation {
                withContext(Dispatchers.IO) { api.request("/v1/admin/customers",current,"PUT",body) }
                loadCustomers(current);mutable.value=mutable.value.copy(notice="Cliente y permiso actualizados.")
            }
        }
    }
    fun savePaymentInstructions(instructions: String) = confirm("Actualizar las instrucciones de pago que verán los clientes") {
        val current=session ?: return@confirm
        launchOperation {
            withContext(Dispatchers.IO) { api.request("/v1/admin/payment-instructions",current,"PUT",JSONObject().put("instructions",instructions)) }
            loadCustomers(current)
        }
    }
    fun logout() {
        val previous = session; approvalGate.cancel(); confirmation.value = null; generation++; operation?.cancel(); session = null
        secure.write("session", null); secure.write("attempt", null); mutable.value = ConsultasState()
        if (previous != null) viewModelScope.launch(Dispatchers.IO) { runCatching { api.request("/v1/logout", previous, "POST") } }
    }
    fun showExample() {
        generation++; operation?.cancel(); session = null
        mutable.value = ConsultasState(Profile("example", "ejemplo@cliente", "Cliente de ejemplo", "EJEMPLO", false, true, config.supportEmail),
            loans = exampleLoans(), demo = true, fetchedAt = Instant.now(), asOf = LocalDate.now(ZoneId.of("America/Lima")))
    }
}
