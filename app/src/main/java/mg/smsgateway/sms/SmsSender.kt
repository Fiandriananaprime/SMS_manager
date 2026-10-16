package mg.smsgateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Permet à la file d'attendre (avec timeout) les callbacks "sent" d'un SMS donné. */
object SendWaiters {
    private val map = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    fun register(smsId: Long): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { map[smsId] = it }

    fun complete(smsId: Long) {
        map[smsId]?.complete(Unit)
    }

    fun remove(smsId: Long) {
        map.remove(smsId)
    }
}

object SmsErrors {
    /** Codes de résultat de SmsManager (RESULT_ERROR_*, RESULT_*). */
    fun describe(code: Int): String {
        val name = when (code) {
            1 -> "GENERIC_FAILURE"
            2 -> "RADIO_OFF (mode avion / radio coupée)"
            3 -> "NULL_PDU"
            4 -> "NO_SERVICE (aucun réseau)"
            5 -> "LIMIT_EXCEEDED (limite d'envoi Android)"
            6 -> "FDN_CHECK_FAILURE"
            7 -> "SHORT_CODE_NOT_ALLOWED"
            8 -> "SHORT_CODE_NEVER_ALLOWED"
            9 -> "RADIO_NOT_AVAILABLE"
            10 -> "NETWORK_REJECT"
            11 -> "INVALID_ARGUMENTS"
            12 -> "INVALID_STATE"
            13 -> "NO_MEMORY"
            14 -> "INVALID_SMS_FORMAT"
            15 -> "SYSTEM_ERROR"
            16 -> "MODEM_ERROR"
            17 -> "NETWORK_ERROR"
            18 -> "ENCODING_ERROR"
            19 -> "INVALID_SMSC_ADDRESS"
            20 -> "OPERATION_NOT_ALLOWED"
            21 -> "INTERNAL_ERROR"
            22 -> "NO_RESOURCES"
            23 -> "CANCELLED"
            24 -> "REQUEST_NOT_SUPPORTED"
            else -> "ERROR"
        }
        return "$name [code $code]"
    }

    /** Échecs "avant la radio" : on peut réessayer sans risque de doublon. */
    fun isRetriable(code: Int?): Boolean = code == 2 || code == 4 || code == 9
}

/** Envoi réel via SmsManager lié à une SIM précise (subscriptionId). */
class SmsSender(private val context: Context) {

    @Suppress("DEPRECATION")
    fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    fun divide(subscriptionId: Int, message: String): ArrayList<String> =
        ArrayList(smsManager(subscriptionId).divideMessage(message))

    /**
     * Demande l'envoi. Le retour de cette méthode = "demande acceptée par Android" uniquement.
     * Le résultat réel arrive dans SmsResultReceiver (un callback par segment).
     */
    fun dispatch(smsId: Long, phone: String, subscriptionId: Int, parts: ArrayList<String>) {
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        for (i in parts.indices) {
            sent += pending(ACTION_SENT, smsId, i, mutable = false)
            delivered += pending(ACTION_DELIVERED, smsId, i, mutable = true)
        }
        smsManager(subscriptionId).sendMultipartTextMessage(phone, null, parts, sent, delivered)
    }

    private fun pending(action: String, smsId: Long, part: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java).apply {
            this.action = action
            // data unique => PendingIntent distinct pour chaque (sms, segment, type)
            data = Uri.parse("smsgw://${if (action == ACTION_SENT) "sent" else "delivered"}/$smsId/$part")
            putExtra(EXTRA_SMS_ID, smsId)
            putExtra(EXTRA_PART, part)
        }
        // Les accusés de livraison ajoutent les extras "pdu"/"format" => PendingIntent mutable
        // (intent explicite, donc sûr). Le callback "sent" n'a besoin que du resultCode => immutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    companion object {
        const val ACTION_SENT = "mg.smsgateway.SMS_SENT"
        const val ACTION_DELIVERED = "mg.smsgateway.SMS_DELIVERED"
        const val EXTRA_SMS_ID = "smsId"
        const val EXTRA_PART = "part"
    }
}
