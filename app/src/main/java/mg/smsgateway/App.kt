package mg.smsgateway

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mg.smsgateway.data.AppDatabase
import mg.smsgateway.data.SettingsRepository
import mg.smsgateway.domain.JobManager
import mg.smsgateway.domain.MadagascarRule
import mg.smsgateway.domain.PhoneNumberValidator
import mg.smsgateway.domain.SmsValidator
import mg.smsgateway.sim.SimManager
import mg.smsgateway.sms.SmsSender
import mg.smsgateway.util.AppLogger

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Injection de dépendances manuelle (simple, sans framework). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db = AppDatabase.build(app)
    val settingsDao = db.settingsDao()
    val jobDao = db.jobDao()
    val smsDao = db.smsDao()
    val logDao = db.logDao()
    val campaignDraftDao = db.campaignDraftDao()

    val settingsRepository = SettingsRepository(settingsDao)
    val logger = AppLogger(logDao, appScope)
    val simManager = SimManager(app)

    /** Pour supporter d'autres pays : ajouter une CountryRule à cette liste. */
    val phoneValidator = PhoneNumberValidator(listOf(MadagascarRule))

    val smsSender = SmsSender(app)
    val validator = SmsValidator(app, settingsDao, smsDao, simManager, phoneValidator, smsSender)
    val jobManager = JobManager(app, db, settingsDao, jobDao, smsDao, validator, smsSender, simManager, logger)

    init {
        // La ligne de configuration (avec jeton d'appairage) doit exister avant tout accès.
        runBlocking(Dispatchers.IO) { settingsRepository.ensureExists() }
    }
}
