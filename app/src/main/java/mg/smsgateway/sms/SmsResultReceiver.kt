package mg.smsgateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mg.smsgateway.App

/** Reçoit, par segment, le résultat d'envoi (SENT) et l'accusé de livraison (DELIVERED). */
class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val smsId = intent.getLongExtra(SmsSender.EXTRA_SMS_ID, -1L)
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        if (smsId < 0) return
        val code = resultCode
        val action = intent.action
        val delivered = if (action == SmsSender.ACTION_DELIVERED) isDelivered(intent, code) else false

        val container = (context.applicationContext as App).container
        val pending = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                when (action) {
                    SmsSender.ACTION_SENT -> container.jobManager.onSentResult(smsId, part, code)
                    SmsSender.ACTION_DELIVERED -> container.jobManager.onDeliveryReport(smsId, delivered)
                }
            } catch (t: Throwable) {
                container.logger.e("SmsResultReceiver", "Erreur traitement callback sms=$smsId", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Statut 0 du rapport SMS-STATUS-REPORT = "reçu par le destinataire". */
    private fun isDelivered(intent: Intent, resultCode: Int): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        val pdu = intent.getByteArrayExtra("pdu") ?: return false
        val format = intent.getStringExtra("format")
        return try {
            SmsMessage.createFromPdu(pdu, format).status == 0
        } catch (t: Throwable) {
            false
        }
    }
}
