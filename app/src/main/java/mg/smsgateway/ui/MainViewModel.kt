package mg.smsgateway.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.smsgateway.data.CampaignDraftEntity
import mg.smsgateway.App
import mg.smsgateway.Config
import mg.smsgateway.data.SmsEntity
import mg.smsgateway.domain.JobProgress
import mg.smsgateway.domain.CreateOutcome
import mg.smsgateway.domain.MessageTemplate
import mg.smsgateway.domain.PhoneColumnCandidate
import mg.smsgateway.domain.PhoneColumnDetector
import mg.smsgateway.domain.PhoneResult
import mg.smsgateway.domain.SendRequest
import mg.smsgateway.domain.SpreadsheetDocument
import mg.smsgateway.domain.SpreadsheetImporter
import mg.smsgateway.service.GatewayService
import mg.smsgateway.service.GatewayState
import mg.smsgateway.sim.SimInfo
import mg.smsgateway.util.hasPermission
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class UsbInfo(val cablePlugged: Boolean = false, val adbEnabled: Boolean = false, val pcActive: Boolean = false)

data class PermState(
    val sendSms: Boolean = false,
    val phoneState: Boolean = false,
    val phoneNumbers: Boolean = false,
    val notifications: Boolean = true,
) {
    val requiredGranted: Boolean get() = sendSms && phoneState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val c = (application as App).container
    private val eager = SharingStarted.WhileSubscribed(5_000)
    private val campaignSubmissionMutex = Mutex()

    val settings = c.settingsDao.observe().stateIn(viewModelScope, eager, null)
    val reserved = c.smsDao.observeReserved().stateIn(viewModelScope, eager, 0)
    val summaries = c.jobDao.observeSummaries().stateIn(viewModelScope, eager, emptyList())
    val logs = c.logDao.observeRecent(300).stateIn(viewModelScope, eager, emptyList())
    val campaignDraftReady = MutableStateFlow(false)
    val campaignDraft = flow {
        emit(c.campaignDraftDao.get())
        emitAll(c.campaignDraftDao.observe())
    }.onEach { campaignDraftReady.value = true }
        .stateIn(viewModelScope, eager, null)

    val sims = MutableStateFlow<List<SimInfo>>(emptyList())
    val perms = MutableStateFlow(PermState())
    val importedDocument = MutableStateFlow<SpreadsheetDocument?>(null)
    val phoneColumns = MutableStateFlow<List<PhoneColumnCandidate>>(emptyList())
    val importError = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    val draftSaveStatus = MutableStateFlow<String?>(null)
    val draftRestoreError = MutableStateFlow<String?>(null)
    val campaignSendState = MutableStateFlow<CampaignSendState>(CampaignSendState.Idle)
    val scheduledCampaignDraft = MutableStateFlow<ScheduledCampaignDraft?>(null)
    val deleteJobError = MutableStateFlow<String?>(null)

    val usb = flow {
        while (true) {
            emit(readUsb())
            delay(2_000)
        }
    }.stateIn(viewModelScope, eager, UsbInfo())

    fun refresh() {
        perms.value = PermState(
            sendSms = app.hasPermission(Manifest.permission.SEND_SMS),
            phoneState = app.hasPermission(Manifest.permission.READ_PHONE_STATE),
            phoneNumbers = app.hasPermission(Manifest.permission.READ_PHONE_NUMBERS),
            notifications = Build.VERSION.SDK_INT < 33 || app.hasPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
        viewModelScope.launch(Dispatchers.IO) { sims.value = c.simManager.listSims() }
    }

    private fun readUsb(): UsbInfo {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val usbPlugged = (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0
        val adb = Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val active = System.currentTimeMillis() - GatewayState.lastAuthContact.value < Config.PC_ACTIVE_WINDOW_MS
        return UsbInfo(usbPlugged, adb, active)
    }

    // --- gateway
    fun startGateway() {
        ContextCompat.startForegroundService(app, Intent(app, GatewayService::class.java))
    }

    fun stopGateway() {
        app.stopService(Intent(app, GatewayService::class.java))
    }

    // --- settings
    fun selectSim(subscriptionId: Int) = viewModelScope.launch { c.settingsDao.setSim(subscriptionId) }
    fun setSendingAllowed(v: Boolean) = viewModelScope.launch { c.settingsDao.setSendingAllowed(v) }
    fun setLimit(n: Int) = viewModelScope.launch { c.settingsDao.setLimit(n.coerceAtLeast(0)) }
    fun setUsed(n: Int) = viewModelScope.launch { c.settingsDao.setUsed(n.coerceAtLeast(0)) }
    fun newPeriod(newLimit: Int?) = viewModelScope.launch {
        c.settingsDao.resetUsed(System.currentTimeMillis())
        if (newLimit != null) c.settingsDao.setLimit(newLimit.coerceAtLeast(0))
        c.logger.i("Quota", "Nouvelle période de forfait démarrée (compteur remis à 0)")
    }
    fun setRegion(r: String) = viewModelScope.launch { c.settingsDao.setRegion(r.trim().uppercase()) }
    fun setMaxPerMinute(n: Int) = viewModelScope.launch { c.settingsDao.setMaxPerMinute(n.coerceIn(1, 1000)) }
    fun regenerateToken() = viewModelScope.launch {
        c.settingsRepository.regenerateToken()
        c.logger.w("Security", "Jeton d'appairage régénéré")
    }

    // --- jobs
    fun observeJob(id: String): Flow<JobProgress?> =
        combine(c.jobDao.observe(id), c.smsDao.observeCounts(id)) { job, counts ->
            job?.let { JobProgress.from(it, counts) }
        }

    fun observeRecipients(id: String): Flow<List<SmsEntity>> = c.smsDao.observeByJob(id)
    fun cancelJob(id: String) = viewModelScope.launch { c.jobManager.cancelJob(id) }
    fun deleteFinishedJob(id: String, onDeleted: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        deleteJobError.value = null
        try {
            if (c.jobManager.deleteFinishedJob(id)) withContext(Dispatchers.Main) { onDeleted() }
            else deleteJobError.value = "Cette campagne n'est pas terminée ou a déjà été supprimée."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deleteJobError.value = "Suppression impossible : ${e.message ?: "erreur de stockage"}"
            c.logger.e("CampaignHistory", "Suppression de la campagne $id échouée", e)
        }
    }
    fun clearDeleteJobError() {
        deleteJobError.value = null
    }

    fun readSpreadsheet(uri: Uri, displayName: String) = viewModelScope.launch(Dispatchers.IO) {
        importedDocument.value = null
        phoneColumns.value = emptyList()
        importError.value = null
        importing.value = true
        try {
            val document = SpreadsheetImporter(app.contentResolver).read(uri, displayName)
            importedDocument.value = document
            val region = c.settingsDao.get()?.defaultRegion ?: "MG"
            phoneColumns.value = PhoneColumnDetector(c.phoneValidator).detect(document, region)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            importError.value = e.message ?: "Impossible de lire ce fichier."
            c.logger.e("SpreadsheetImport", "Import du fichier $displayName échoué", e)
        } finally {
            importing.value = false
        }
    }

    fun saveCampaignDraft(sourceName: String?, message: String, recipients: List<CampaignRecipient>) =
        viewModelScope.launch(Dispatchers.IO) {
            draftSaveStatus.value = "Enregistrement du brouillon…"
            try {
                val json = JSONArray()
                recipients.forEach { recipient ->
                    json.put(
                        JSONObject()
                            .put("phone", recipient.phone)
                            .put("selected", recipient.selected)
                            .put("sourceLine", recipient.sourceLine ?: JSONObject.NULL)
                            .put("numericExcelValue", recipient.numericExcelValue)
                            .put("variables", JSONObject(recipient.variables))
                    )
                }
                c.campaignDraftDao.save(
                    CampaignDraftEntity(
                        sourceName = sourceName,
                        message = message,
                        recipientsJson = json.toString(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                draftSaveStatus.value = "Brouillon enregistré sur cet appareil."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draftSaveStatus.value = "Échec de l'enregistrement : ${e.message ?: "erreur de stockage"}"
                c.logger.e("CampaignDraft", "Écriture du brouillon échouée", e)
            }
        }

    fun restoreDraftRecipients(json: String): List<CampaignRecipient> {
        draftRestoreError.value = null
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                CampaignRecipient(
                    phone = item.optString("phone"),
                    selected = item.optBoolean("selected", true),
                    sourceLine = item.optInt("sourceLine").takeIf { !item.isNull("sourceLine") },
                    numericExcelValue = item.optBoolean("numericExcelValue", false),
                    variables = item.optJSONObject("variables")?.let { variables ->
                        buildMap {
                            val keys = variables.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, variables.optString(key))
                            }
                        }
                    }.orEmpty(),
                )
            }
        } catch (e: Exception) {
            draftRestoreError.value = "Le brouillon enregistré est illisible; vérifiez-le et enregistrez-en un nouveau."
            c.logger.e("CampaignDraft", "Lecture du brouillon échouée", e)
            emptyList()
        }
    }

    fun clearCampaignDraft() = viewModelScope.launch(Dispatchers.IO) {
        draftSaveStatus.value = "Suppression du brouillon…"
        try {
            c.campaignDraftDao.clear()
            draftSaveStatus.value = "Brouillon supprimé de cet appareil."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            draftSaveStatus.value = "Suppression impossible : ${e.message ?: "erreur de stockage"}"
            c.logger.e("CampaignDraft", "Suppression du brouillon échouée", e)
        }
    }

    fun validatePhone(phone: String): PhoneResult {
        return c.phoneValidator.validate(phone, settings.value?.defaultRegion ?: "MG")
    }

    fun sendCampaign(message: String, recipients: List<CampaignRecipient>) = viewModelScope.launch(Dispatchers.IO) {
        if (campaignSendState.value is CampaignSendState.Accepted || !campaignSubmissionMutex.tryLock()) return@launch
        campaignSendState.value = CampaignSendState.Sending
        try {
            val settings = c.settingsDao.get()
            val subscriptionId = settings?.selectedSubscriptionId
            if (subscriptionId == null || subscriptionId < 0) {
                campaignSendState.value = CampaignSendState.Rejected("Sélectionnez une SIM dans les paramètres avant l'envoi.")
                return@launch
            }
            when (val result = c.jobManager.createJob(
                SendRequest(
                    subscriptionId = subscriptionId,
                    message = message,
                    recipients = recipients.map { it.phone },
                    recipientMessages = recipients.map { MessageTemplate.render(message, it.variables) },
                    recipientVariables = recipients.map { it.variables },
                    requireGatewayEnabled = false,
                )
            )) {
                is CreateOutcome.Accepted -> campaignSendState.value = CampaignSendState.Accepted(result.jobId)
                is CreateOutcome.Rejected -> campaignSendState.value =
                    CampaignSendState.Rejected(result.report.errors.joinToString("\n") { it.message })
            }
        } catch (e: Exception) {
            campaignSendState.value = CampaignSendState.Rejected(e.message ?: "L'envoi n'a pas pu être lancé.")
        } finally {
            campaignSubmissionMutex.unlock()
        }
    }

    fun scheduleCampaign(message: String, recipients: List<CampaignRecipient>, scheduledAt: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (campaignSendState.value is CampaignSendState.Accepted || !campaignSubmissionMutex.tryLock()) return@launch
        campaignSendState.value = CampaignSendState.Sending
        try {
            val settings = c.settingsDao.get()
            val subscriptionId = settings?.selectedSubscriptionId
            if (subscriptionId == null || subscriptionId < 0) {
                campaignSendState.value = CampaignSendState.Rejected("Sélectionnez une SIM dans les paramètres avant de programmer.")
                return@launch
            }
            when (val result = c.jobManager.createScheduledJob(
                SendRequest(
                    subscriptionId = subscriptionId,
                    message = message,
                    recipients = recipients.map { it.phone },
                    recipientMessages = recipients.map { MessageTemplate.render(message, it.variables) },
                    recipientVariables = recipients.map { it.variables },
                    requireGatewayEnabled = false,
                ),
                scheduledAt,
            )) {
                is CreateOutcome.Accepted -> campaignSendState.value =
                    CampaignSendState.Accepted(result.jobId, scheduled = true)
                is CreateOutcome.Rejected -> campaignSendState.value =
                    CampaignSendState.Rejected(result.report.errors.joinToString("\n") { it.message })
            }
        } catch (e: Exception) {
            campaignSendState.value = CampaignSendState.Rejected(e.message ?: "La campagne n'a pas pu être programmée.")
        } finally {
            campaignSubmissionMutex.unlock()
        }
    }

    fun resetCampaignSendState() {
        campaignSendState.value = CampaignSendState.Idle
    }

    fun loadScheduledCampaign(jobId: String) = viewModelScope.launch(Dispatchers.IO) {
        val job = c.jobDao.get(jobId)
        if (job?.status != mg.smsgateway.data.JobStatus.SCHEDULED || job.scheduledAt == null) {
            scheduledCampaignDraft.value = null
            return@launch
        }
        val items = c.smsDao.byJob(jobId)
        scheduledCampaignDraft.value = ScheduledCampaignDraft(
            id = jobId,
            scheduledAt = job.scheduledAt,
            message = job.message,
            recipients = items.map { item ->
                val variables = JSONObject(item.variablesJson)
                val values = buildMap {
                    val keys = variables.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, variables.optString(key))
                    }
                }
                CampaignRecipient(item.phoneNumber, variables = values)
            },
        )
    }

    fun updateScheduledCampaign(jobId: String, message: String, recipients: List<CampaignRecipient>, scheduledAt: Long) =
        viewModelScope.launch(Dispatchers.IO) {
            if (campaignSendState.value is CampaignSendState.Accepted || !campaignSubmissionMutex.tryLock()) return@launch
            campaignSendState.value = CampaignSendState.Sending
            try {
                val selectedSim = c.settingsDao.get()?.selectedSubscriptionId ?: -1
                if (selectedSim < 0) {
                    campaignSendState.value = CampaignSendState.Rejected("Sélectionnez une SIM avant de modifier la campagne.")
                    return@launch
                }
                when (val result = c.jobManager.updateScheduledJob(
                    jobId,
                    SendRequest(
                        subscriptionId = selectedSim,
                        message = message,
                        recipients = recipients.map { it.phone },
                        recipientMessages = recipients.map { MessageTemplate.render(message, it.variables) },
                        recipientVariables = recipients.map { it.variables },
                        requireGatewayEnabled = false,
                    ),
                    scheduledAt,
                )) {
                    is CreateOutcome.Accepted -> campaignSendState.value =
                        CampaignSendState.Accepted(result.jobId, scheduled = true)
                    is CreateOutcome.Rejected -> campaignSendState.value =
                        CampaignSendState.Rejected(result.report.errors.joinToString("\n") { it.message })
                }
            } catch (e: Exception) {
                campaignSendState.value = CampaignSendState.Rejected(e.message ?: "La campagne n'a pas pu être modifiée.")
            } finally {
                campaignSubmissionMutex.unlock()
            }
        }
}
