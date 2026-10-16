package mg.smsgateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import mg.smsgateway.App
import mg.smsgateway.Config
import mg.smsgateway.server.ApiRouter
import mg.smsgateway.server.LocalHttpServer
import mg.smsgateway.ui.MainActivity

/** État partagé UI <-> service. */
object GatewayState {
    val running = MutableStateFlow(false)
    val serverError = MutableStateFlow<String?>(null)
    val lastAuthContact = MutableStateFlow(0L)
}

/**
 * Service de premier plan : héberge le serveur HTTP local ET la file d'envoi.
 * L'envoi continue même si le câble USB est débranché ou l'écran verrouillé.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: LocalHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Passerelle SMS", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        val c = (application as App).container
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsgateway:queue").apply { setReferenceCounted(false) }

        val router = ApiRouter(
            applicationContext, c.settingsDao, c.jobDao, c.smsDao, c.logDao,
            c.simManager, c.validator, c.jobManager, c.logger,
        )
        try {
            server = LocalHttpServer(Config.PORT, scope, c.logger) { router.handle(it) }.also { it.start() }
            GatewayState.serverError.value = null
            GatewayState.running.value = true
        } catch (e: Exception) {
            GatewayState.serverError.value = "Port ${Config.PORT} indisponible : ${e.message}"
            GatewayState.running.value = false
            c.logger.e("GatewayService", "Démarrage du serveur impossible", e)
        }

        scope.launch {
            c.jobManager.runQueue(
                onBusy = { wakeLock?.acquire(30 * 60 * 1000L) },
                onIdle = { wakeLock?.let { if (it.isHeld) it.release() } },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        GatewayState.running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Gateway")
            .setContentText("Passerelle active — 127.0.0.1:${Config.PORT}")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gateway"
        private const val NOTIF_ID = 1
    }
}
