package mg.smsgateway.domain

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mg.smsgateway.Config
import mg.smsgateway.data.AppDatabase
import mg.smsgateway.data.JobDao
import mg.smsgateway.data.JobEntity
import mg.smsgateway.data.JobStatus
import mg.smsgateway.data.SettingsDao
import mg.smsgateway.data.SettingsEntity
import mg.smsgateway.data.SmsDao
import mg.smsgateway.data.SmsEntity
import mg.smsgateway.data.SmsStatus
import mg.smsgateway.data.StatusCount
import mg.smsgateway.sim.SimManager
import mg.smsgateway.sms.SendWaiters
import mg.smsgateway.sms.SmsErrors
import mg.smsgateway.sms.SmsSender
import mg.smsgateway.util.AppLogger
import mg.smsgateway.sms.ScheduledCampaignScheduler
import mg.smsgateway.util.hasPermission
import mg.smsgateway.util.maskPhone
import org.json.JSONObject
import java.util.UUID

sealed class CreateOutcome {
    data class Accepted(val jobId: String, val report: ValidationReport?, val alreadyExisted: Boolean) : CreateOutcome()
    data class Rejected(val report: ValidationReport) : CreateOutcome()
}

data class JobProgress(
    val jobId: String,
    val status: String,
    val total: Int,
    val pending: Int,
    val sending: Int,
    val sent: Int,
    val failed: Int,
    val cancelled: Int,
    val segmentsPerSms: Int,
    val createdAt: Long,
    val finishedAt: Long?,
    val scheduledAt: Long?,
    val message: String,
) {
    val done: Int get() = sent + failed + cancelled
    val percent: Int get() = if (total == 0) 100 else done * 100 / total

    companion object {
        fun from(job: JobEntity, counts: List<StatusCount>): JobProgress {
            fun c(s: String) = counts.firstOrNull { it.status == s }?.count ?: 0
            return JobProgress(
                jobId = job.id, status = job.status, total = job.totalRecipients,
                pending = c(SmsStatus.PENDING), sending = c(SmsStatus.SENDING), sent = c(SmsStatus.SENT),
                failed = c(SmsStatus.FAILED), cancelled = c(SmsStatus.CANCELLED),
                segmentsPerSms = job.segmentsPerSms, createdAt = job.createdAt, finishedAt = job.finishedAt,
                scheduledAt = job.scheduledAt,
                message = job.message,
            )
        }
    }
}

/**
 * Cœur du système : création des jobs (avec réservation de quota atomique), file d'envoi
 * contrôlée (UN SEUL SMS à la fois), traitement des callbacks, annulation, reprise après crash.
 */
class JobManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsDao: SettingsDao,
    private val jobDao: JobDao,
    private val smsDao: SmsDao,
    private val validator: SmsValidator,
    private val sender: SmsSender,
    private val simManager: SimManager,
    private val logger: AppLogger,
) {
    private val tag = "JobManager"
    private val createMutex = Mutex()
    private val scheduleMutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sentWindow = ArrayDeque<Pair<Long, Int>>() // (elapsedRealtime, segments) sur 60 s

    // ------------------------------------------------------------------ création

    suspend fun createJob(req: SendRequest): CreateOutcome = createMutex.withLock {
        req.requestId?.let { rid ->
            jobDao.findByRequestId(rid)?.let { return@withLock CreateOutcome.Accepted(it.id, null, true) }
        }
        // Validation + insertion sous verrou : deux requêtes concurrentes ne peuvent pas
        // réserver le même quota.
        val report = validator.validate(req)
        if (!report.valid) {
            logger.w(tag, "Job refusé : ${report.errors.joinToString { it.code }}")
            return@withLock CreateOutcome.Rejected(report)
        }
        val now = System.currentTimeMillis()
        val jobId = UUID.randomUUID().toString()
        db.withTransaction {
            jobDao.insert(
                JobEntity(
                    id = jobId, createdAt = now, status = JobStatus.QUEUED,
                    subscriptionId = req.subscriptionId, message = req.message,
                    segmentsPerSms = report.segmentsPerSms, totalRecipients = report.recipients.size,
                    duplicatesRemoved = report.duplicates, invalidSkipped = report.invalid.size,
                    clientRequestId = req.requestId,
                    gatewayAuthorized = req.requireGatewayEnabled,
                )
            )
            smsDao.insertAll(report.recipients.map { phone ->
                SmsEntity(
                    jobId = jobId,
                    phoneNumber = phone,
                    message = report.messagesByRecipient.getValue(phone),
                    createdAt = now,
                    segments = report.segmentsByRecipient.getValue(phone),
                    variablesJson = JSONObject(report.variablesByRecipient.getValue(phone)).toString(),
                )
            })
        }
        logger.i(
            tag,
            "Job $jobId créé : ${report.recipients.size} destinataires, ${report.segmentsRequired} segment(s) au total (maximum ${report.segmentsPerSms} par SMS)",
        )
        wake.trySend(Unit)
        CreateOutcome.Accepted(jobId, report, false)
    }

    suspend fun createScheduledJob(req: SendRequest, scheduledAt: Long): CreateOutcome =
        createMutex.withLock { scheduleMutex.withLock { createScheduledJobLocked(req, scheduledAt) } }

    private suspend fun createScheduledJobLocked(req: SendRequest, scheduledAt: Long): CreateOutcome {
        req.requestId?.let { rid ->
            jobDao.findByRequestId(rid)?.let { return CreateOutcome.Accepted(it.id, null, true) }
        }
        require(scheduledAt > System.currentTimeMillis()) { "La date d'envoi doit être dans le futur." }
        val report = validator.validate(req.copy(requireGatewayEnabled = false))
        if (!report.valid) return CreateOutcome.Rejected(report)
        require(scheduledAt > System.currentTimeMillis()) { "La date d'envoi doit être dans le futur." }
        val now = System.currentTimeMillis()
        val jobId = UUID.randomUUID().toString()
        db.withTransaction {
            jobDao.insert(
                JobEntity(
                    id = jobId,
                    createdAt = now,
                    status = JobStatus.SCHEDULED,
                    subscriptionId = req.subscriptionId,
                    message = req.message,
                    segmentsPerSms = report.segmentsPerSms,
                    totalRecipients = report.recipients.size,
                    duplicatesRemoved = report.duplicates,
                    invalidSkipped = report.invalid.size,
                    gatewayAuthorized = false,
                    scheduledAt = scheduledAt,
                    clientRequestId = req.requestId,
                )
            )
            smsDao.insertAll(report.recipients.map { phone ->
                SmsEntity(
                    jobId = jobId,
                    phoneNumber = phone,
                    message = report.messagesByRecipient.getValue(phone),
                    createdAt = now,
                    segments = report.segmentsByRecipient.getValue(phone),
                    variablesJson = JSONObject(report.variablesByRecipient.getValue(phone)).toString(),
                )
            })
        }
        try {
            ScheduledCampaignScheduler(context).schedule(jobId, scheduledAt)
        } catch (e: Exception) {
            db.withTransaction {
                jobDao.cancelScheduled(jobId, System.currentTimeMillis())
                smsDao.cancelPending(jobId)
            }
            ScheduledCampaignScheduler(context).cancel(jobId)
            throw e
        }
        logger.i(tag, "Campagne $jobId programmée pour $scheduledAt (${report.recipients.size} destinataires)")
        return CreateOutcome.Accepted(jobId, report, false)
    }

    suspend fun updateScheduledJob(jobId: String, req: SendRequest, scheduledAt: Long): CreateOutcome =
        createMutex.withLock { scheduleMutex.withLock { updateScheduledJobLocked(jobId, req, scheduledAt) } }

    private suspend fun updateScheduledJobLocked(jobId: String, req: SendRequest, scheduledAt: Long): CreateOutcome {
        val current = jobDao.get(jobId)
            ?: throw IllegalArgumentException("Campagne inconnue.")
        check(current.status == JobStatus.SCHEDULED && current.finishedAt == null) {
            "L'envoi a commencé ou la campagne n'est plus modifiable."
        }
        require(scheduledAt > System.currentTimeMillis()) { "La date d'envoi doit être dans le futur." }
        val report = validator.validate(req.copy(requireGatewayEnabled = false), excludeJobId = jobId)
        if (!report.valid) return CreateOutcome.Rejected(report)
        require(scheduledAt > System.currentTimeMillis()) { "La date d'envoi doit être dans le futur." }
        val now = System.currentTimeMillis()
        db.withTransaction {
            val updated = jobDao.updateScheduled(
                jobId,
                req.message,
                report.segmentsPerSms,
                report.recipients.size,
                scheduledAt,
            )
            check(updated == 1) { "La campagne a démarré pendant sa modification." }
            smsDao.deleteByJob(jobId)
            smsDao.insertAll(report.recipients.map { phone ->
                SmsEntity(
                    jobId = jobId,
                    phoneNumber = phone,
                    message = report.messagesByRecipient.getValue(phone),
                    createdAt = now,
                    segments = report.segmentsByRecipient.getValue(phone),
                    variablesJson = JSONObject(report.variablesByRecipient.getValue(phone)).toString(),
                )
            })
        }
        try {
                ScheduledCampaignScheduler(context).schedule(jobId, scheduledAt)
        } catch (e: Exception) {
            db.withTransaction {
                jobDao.cancelScheduled(jobId, System.currentTimeMillis())
                smsDao.cancelPending(jobId)
            }
            ScheduledCampaignScheduler(context).cancel(jobId)
            throw e
        }
        logger.i(tag, "Campagne programmée $jobId mise à jour pour $scheduledAt")
        return CreateOutcome.Accepted(jobId, report, false)
    }

    suspend fun progress(jobId: String): JobProgress? {
        val job = jobDao.get(jobId) ?: return null
        return JobProgress.from(job, smsDao.counts(jobId))
    }

    suspend fun deleteFinishedJob(jobId: String): Boolean = db.withTransaction {
        val job = jobDao.get(jobId) ?: return@withTransaction false
        if (job.finishedAt == null) return@withTransaction false
        jobDao.deleteFinished(jobId) > 0
    }

    // ------------------------------------------------------------------ annulation

    /** Les SMS déjà envoyés restent envoyés ; les PENDING passent à CANCELLED. @return null si job inconnu */
    suspend fun cancelJob(jobId: String): Int? = scheduleMutex.withLock {
        cancelJobLocked(jobId)
    }

    private suspend fun cancelJobLocked(jobId: String): Int? {
        val job = jobDao.get(jobId) ?: return null
        if (job.status == JobStatus.SCHEDULED) {
            val result = db.withTransaction {
                val cancelled = jobDao.cancelScheduled(jobId, System.currentTimeMillis())
                val recipients = if (cancelled > 0) smsDao.cancelPending(jobId) else 0
                cancelled to recipients
            }
            if (result.first > 0) {
                ScheduledCampaignScheduler(context).cancel(jobId)
                logger.i(tag, "Campagne $jobId annulée avant démarrage")
                return result.second
            }
            if (jobDao.get(jobId) == null) return null
        }
        val n = db.withTransaction {
            jobDao.markCancelRequested(jobId)
            smsDao.cancelPending(jobId)
        }
        logger.i(tag, "Job $jobId : annulation demandée, $n SMS PENDING annulés")
        if (job.finishedAt == null) refreshJob(jobId)
        return n
    }

    // ------------------------------------------------------------------ file d'envoi

    suspend fun runQueue(onBusy: () -> Unit, onIdle: () -> Unit) {
        recoverInterrupted()
        wake.trySend(Unit)
        while (currentCoroutineContext().isActive) {
            val settings = settingsDao.get()
            if (settings == null) {
                onIdle()
                delay(1_000)
                continue
            }
            val next = smsDao.nextPending()
            if (next == null) {
                onIdle()
                withTimeoutOrNull(2_000) { wake.receive() } // réveil sur nouveau job, sinon re-vérification
                continue
            }
            onBusy()
            try {
                processOne(next, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.e(tag, "Erreur interne sur sms=${next.id}", t)
                markUncertain(next.id, "INTERNAL_ERROR: ${t.javaClass.simpleName}: ${t.message} (état incertain)")
            }
            refreshJob(next.jobId)
            delay(Config.MIN_SPACING_MS)
        }
    }

    private suspend fun processOne(candidate: SmsEntity, settings: SettingsEntity) {
        if (smsDao.markSending(candidate.id) == 0) return // annulé entre-temps
        val sms = smsDao.get(candidate.id) ?: return
        val job = jobDao.get(sms.jobId) ?: return
        if (job.status == JobStatus.QUEUED) jobDao.setStatus(job.id, JobStatus.RUNNING)

        precheck(job, settings)?.let { (reason, retriable) ->
            failBeforeSend(sms, reason, retriable); return
        }
        val parts = try {
            sender.divide(job.subscriptionId, sms.message)
        } catch (e: Exception) {
            failBeforeSend(sms, "SUBSCRIPTION_ERROR: ${e.message}", false); return
        }
        if (parts.size > sms.segments) {
            failBeforeSend(sms, "SEGMENT_MISMATCH: ${parts.size} segments > ${sms.segments} réservés", false); return
        }
        // Dernier garde-fou : jamais dépasser le plafond, même si l'utilisateur l'a baissé en cours de job.
        if (settings.smsUsed + parts.size > settings.smsLimit) {
            failBeforeSend(sms, "QUOTA_LIMIT: plafond local atteint (${settings.smsUsed}/${settings.smsLimit})", false); return
        }

        throttle(parts.size, maxOf(settings.maxPerMinute, 1))

        val waiter = SendWaiters.register(sms.id)
        try {
            sender.dispatch(sms.id, sms.phoneNumber, job.subscriptionId, parts)
        } catch (e: Exception) { // SecurityException, IllegalArgumentException...
            SendWaiters.remove(sms.id)
            failBeforeSend(sms, "SMS_MANAGER_ERROR: ${e.javaClass.simpleName}: ${e.message}", false); return
        }
        sentWindow.addLast(SystemClock.elapsedRealtime() to parts.size)
        logger.i(tag, "sms=${sms.id} ${maskPhone(sms.phoneNumber)} : demande acceptée par Android (${parts.size} segment(s)), attente du résultat")

        val finished = withTimeoutOrNull(Config.CALLBACK_TIMEOUT_MS) { waiter.await(); true }
        SendWaiters.remove(sms.id)
        if (finished == null) {
            markUncertain(sms.id, "TIMEOUT: aucun résultat d'Android en ${Config.CALLBACK_TIMEOUT_MS / 1000}s (état incertain, vérifier manuellement)")
            return
        }
        val after = smsDao.get(sms.id) ?: return
        if (after.status == SmsStatus.FAILED && after.segmentsSent == 0 &&
            SmsErrors.isRetriable(after.lastErrorCode) && after.attempts < Config.MAX_ATTEMPTS
        ) {
            logger.w(tag, "sms=${after.id} échec avant radio (${after.error}), nouvelle tentative")
            delay(Config.RETRY_DELAY_MS)
            smsDao.requeue(after.id)
        }
    }

    /** @return (raison, réessayable) ou null si tout est OK */
    private fun precheck(job: JobEntity, settings: SettingsEntity): Pair<String, Boolean>? {
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) return "PERMISSION_DENIED: SEND_SMS" to false
        if (settings.selectedSubscriptionId != job.subscriptionId) return "SIM_CHANGED: la SIM sélectionnée a changé pendant le job" to false
        val sim = simManager.get(job.subscriptionId) ?: return "SIM_NOT_FOUND: SIM retirée ou désactivée" to true
        if (!sim.available) return "SIM_NOT_READY: ${sim.state}" to true
        if (sim.networkInService == false) return "NO_NETWORK_SERVICE" to true
        return null
    }

    private suspend fun failBeforeSend(sms: SmsEntity, reason: String, retriable: Boolean) {
        logger.w(tag, "sms=${sms.id} ${maskPhone(sms.phoneNumber)} : $reason")
        if (retriable && sms.attempts < Config.MAX_ATTEMPTS) {
            delay(Config.RETRY_DELAY_MS)
            if (smsDao.requeue(sms.id) > 0) return
        }
        smsDao.markFailed(sms.id, reason)
    }

    /** Limite glissante de segments/minute : évite la boîte de dialogue anti-spam d'Android. */
    private suspend fun throttle(parts: Int, maxPerMinute: Int) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            while (sentWindow.isNotEmpty() && now - sentWindow.first().first > 60_000) sentWindow.removeFirst()
            val inWindow = sentWindow.sumOf { it.second }
            if (inWindow + parts <= maxPerMinute) return
            delay(500)
        }
    }

    // ------------------------------------------------------------------ callbacks Android

    suspend fun onSentResult(smsId: Long, part: Int, resultCode: Int) {
        var completed = false
        db.withTransaction {
            val sms = smsDao.get(smsId)
            if (sms == null || sms.status != SmsStatus.SENDING) {
                logger.w(tag, "Callback tardif ignoré : sms=$smsId part=$part code=$resultCode")
            } else {
                var s = sms
                if (resultCode == Activity.RESULT_OK) {
                    s = s.copy(segmentsSent = s.segmentsSent + 1)
                    settingsDao.addUsed(1) // quota consommé : 1 segment réellement parti
                } else {
                    s = s.copy(
                        segmentsFailed = s.segmentsFailed + 1,
                        lastErrorCode = resultCode,
                        error = s.error ?: SmsErrors.describe(resultCode),
                    )
                }
                if (s.segmentsSent + s.segmentsFailed >= s.segments) {
                    s = if (s.segmentsFailed == 0) s.copy(status = SmsStatus.SENT, sentAt = System.currentTimeMillis(), error = null)
                    else s.copy(status = SmsStatus.FAILED)
                    completed = true
                }
                smsDao.update(s)
            }
        }
        if (completed) {
            logger.i(tag, "sms=$smsId terminé (dernier callback code=$resultCode)")
            SendWaiters.complete(smsId)
        }
    }

    suspend fun onDeliveryReport(smsId: Long, delivered: Boolean) {
        if (delivered) smsDao.addDelivered(smsId)
    }

    // ------------------------------------------------------------------ cohérence / reprise

    /**
     * SMS dont l'issue est inconnue : on ne le renvoie JAMAIS automatiquement (risque de doublon),
     * et on compte ses segments non résolus comme CONSOMMÉS (hypothèse prudente pour le quota).
     */
    private suspend fun markUncertain(smsId: Long, reason: String) {
        db.withTransaction {
            val s = smsDao.get(smsId)
            if (s != null && s.status == SmsStatus.SENDING) {
                val uncertain = (s.segments - s.segmentsSent - s.segmentsFailed).coerceAtLeast(0)
                if (uncertain > 0) settingsDao.addUsed(uncertain)
                smsDao.update(s.copy(status = SmsStatus.FAILED, error = reason))
            }
        }
        logger.w(tag, "sms=$smsId : $reason")
    }

    private suspend fun recoverInterrupted() {
        for (s in smsDao.sending()) {
            markUncertain(s.id, "INTERRUPTED: application redémarrée pendant l'envoi (état incertain, vérifier manuellement)")
        }
        for (id in jobDao.unfinishedIds()) refreshJob(id)
    }

    /** Clôt le job quand plus aucun SMS n'est PENDING/SENDING. */
    suspend fun refreshJob(jobId: String) {
        val job = jobDao.get(jobId) ?: return
        if (job.finishedAt != null) return
        val counts = smsDao.counts(jobId)
        fun c(s: String) = counts.firstOrNull { it.status == s }?.count ?: 0
        if (c(SmsStatus.PENDING) + c(SmsStatus.SENDING) > 0) return
        val status = when {
            job.cancelRequested || c(SmsStatus.CANCELLED) > 0 -> JobStatus.CANCELLED
            c(SmsStatus.FAILED) > 0 -> JobStatus.COMPLETED_WITH_ERRORS
            else -> JobStatus.COMPLETED
        }
        jobDao.finish(jobId, status, System.currentTimeMillis())
        logger.i(tag, "Job $jobId terminé : $status")
    }
}
