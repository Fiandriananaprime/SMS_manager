package mg.smsgateway.sms

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mg.smsgateway.App
import mg.smsgateway.data.JobStatus
import mg.smsgateway.service.GatewayService
import mg.smsgateway.ui.MainActivity
import java.text.DateFormat
import java.util.Date

class ScheduledCampaignScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(jobId: String, scheduledAt: Long, notifyCreated: Boolean = true) {
        cancelAlarms(jobId)
        scheduleAlarm(jobId, ACTION_FIRE, scheduledAt)
        listOf(5L, 1L).forEach { minutes ->
            val reminderAt = scheduledAt - minutes * 60_000L
            if (reminderAt > System.currentTimeMillis()) scheduleAlarm(jobId, ACTION_REMIND, reminderAt, minutes.toInt())
        }
        if (notifyCreated) postNotification(jobId, "Campagne programmée", "Envoi unique prévu ${formatTime(scheduledAt)}.")
    }

    fun cancel(jobId: String) {
        cancelAlarms(jobId)
        NotificationManagerCompat.from(context).cancel(notificationId(jobId))
    }

    fun retryStart(jobId: String, retryAt: Long) {
        scheduleAlarm(jobId, ACTION_FIRE, retryAt)
    }

    private fun cancelAlarms(jobId: String) {
        listOf(
            alarmIntent(jobId, ACTION_FIRE, 0),
            alarmIntent(jobId, ACTION_REMIND, 5),
            alarmIntent(jobId, ACTION_REMIND, 1),
        ).forEach { alarmManager.cancel(it) }
    }

    private fun scheduleAlarm(jobId: String, action: String, at: Long, minutes: Int = 0) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(jobId, action, minutes))
    }

    private fun alarmIntent(jobId: String, action: String, minutes: Int): PendingIntent {
        val intent = Intent(context, CampaignAlarmReceiver::class.java).apply {
            this.action = action
            data = android.net.Uri.parse("smsgw://campaign/$jobId/${action.substringAfterLast('.')}/$minutes")
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_MINUTES, minutes)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun postReminder(jobId: String, minutes: Int) {
        val suffix = if (minutes == 1) "une minute" else "$minutes minutes"
        postNotification(jobId, "Rappel d'envoi", "L'envoi unique de cette campagne est prévu dans $suffix.")
    }

    fun postStarted(jobId: String) {
        postNotification(jobId, "Envoi démarré", "La campagne a commencé. Les SMS déjà transmis au réseau ne peuvent pas être annulés.")
    }

    private fun postNotification(jobId: String, title: String, text: String) {
        if (!notificationsAllowed()) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            jobId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_JOB_ID, jobId).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CampaignAlarmReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .setData(android.net.Uri.parse("smsgw://campaign/$jobId/cancel"))
                .putExtra(EXTRA_JOB_ID, jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Annuler", cancel)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(jobId), notification)
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Campagnes programmées", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun formatTime(timestamp: Long): String = DateFormat.getDateTimeInstance().format(Date(timestamp))

    private fun notificationId(jobId: String): Int = jobId.hashCode().let { if (it == 0) 1 else it }

    companion object {
        const val ACTION_FIRE = "mg.smsgateway.CAMPAIGN_FIRE"
        const val ACTION_REMIND = "mg.smsgateway.CAMPAIGN_REMIND"
        const val ACTION_CANCEL = "mg.smsgateway.CAMPAIGN_CANCEL"
        const val EXTRA_JOB_ID = "scheduledJobId"
        const val EXTRA_MINUTES = "reminderMinutes"
        const val CHANNEL_ID = "scheduled-campaigns"
    }
}

class CampaignAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(ScheduledCampaignScheduler.EXTRA_JOB_ID) ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val app = context.applicationContext as App
                val container = app.container
                when (intent.action) {
                    ScheduledCampaignScheduler.ACTION_FIRE -> {
                        val activated = container.jobDao.activateScheduled(jobId, System.currentTimeMillis())
                        if (activated > 0) {
                            ScheduledCampaignScheduler(context).cancel(jobId)
                            startQueueService(context, container, jobId)
                        } else {
                            val scheduled = container.jobDao.get(jobId)
                            if (scheduled?.status == JobStatus.SCHEDULED && scheduled.scheduledAt != null) {
                                ScheduledCampaignScheduler(context).schedule(jobId, scheduled.scheduledAt, notifyCreated = false)
                            } else if (scheduled?.status == JobStatus.QUEUED || scheduled?.status == JobStatus.RUNNING) {
                                startQueueService(context, container, jobId)
                            }
                        }

                    }
                    ScheduledCampaignScheduler.ACTION_REMIND -> {
                        val campaign = container.jobDao.get(jobId)
                        if (campaign?.status == JobStatus.SCHEDULED &&
                            campaign.scheduledAt?.let { it > System.currentTimeMillis() } == true
                        ) {
                            val minutes = intent.getIntExtra(ScheduledCampaignScheduler.EXTRA_MINUTES, 1)
                            ScheduledCampaignScheduler(context).postReminder(jobId, minutes)
                        }
                    }
                    ScheduledCampaignScheduler.ACTION_CANCEL -> {
                        container.jobManager.cancelJob(jobId)
                    }
                }
            } catch (e: Exception) {
                (context.applicationContext as? App)?.container?.logger?.e("CampaignAlarm", "Traitement de l'alarme échoué pour $jobId", e)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private fun startQueueService(context: Context, container: mg.smsgateway.AppContainer, jobId: String) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
            ScheduledCampaignScheduler(context).postStarted(jobId)
            container.logger.i("CampaignAlarm", "File d'envoi démarrée pour la campagne $jobId")
        } catch (e: Exception) {
            ScheduledCampaignScheduler(context).retryStart(jobId, System.currentTimeMillis() + 60_000L)
            container.logger.e("CampaignAlarm", "Démarrage du service différé pour $jobId; nouvelle tentative prévue", e)
        }
    }
}

class CampaignBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val container = (context.applicationContext as App).container
                container.jobDao.scheduled().forEach { job ->
                    job.scheduledAt?.let { ScheduledCampaignScheduler(context).schedule(job.id, it, notifyCreated = false) }
                }
            } catch (e: Exception) {
                (context.applicationContext as? App)?.container?.logger?.e("CampaignBoot", "Restauration des envois programmés échouée", e)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
