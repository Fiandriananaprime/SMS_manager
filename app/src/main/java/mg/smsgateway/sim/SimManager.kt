package mg.smsgateway.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import mg.smsgateway.util.hasPermission

data class SimInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val carrierName: String,
    /** "SIM 1", "SIM 2"... (numéro de SLOT physique + 1, JAMAIS une identité d'opérateur) */
    val displayName: String,
    val number: String?,
    /** true si SIM_STATE_READY */
    val available: Boolean,
    val state: String,
    /** null = inconnu (permission/API), true = en service, false = hors service / urgence seulement */
    val networkInService: Boolean?,
)

/**
 * Détection des SIM via SubscriptionManager. L'identité d'une SIM est son subscriptionId,
 * pas son rang dans la liste.
 */
class SimManager(private val context: Context) {

    private val subscriptionManager: SubscriptionManager =
        context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager: TelephonyManager =
        context.getSystemService(TelephonyManager::class.java)

    @SuppressLint("MissingPermission")
    fun listSims(): List<SimInfo> {
        if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val subs: List<SubscriptionInfo> = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        return subs.sortedBy { it.simSlotIndex }.map { toSimInfo(it) }
    }

    fun get(subscriptionId: Int): SimInfo? = listSims().firstOrNull { it.subscriptionId == subscriptionId }

    @SuppressLint("MissingPermission")
    private fun toSimInfo(info: SubscriptionInfo): SimInfo {
        val tm = telephonyManager.createForSubscriptionId(info.subscriptionId)
        val simState = try { tm.simState } catch (e: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }
        val inService: Boolean? = try {
            tm.serviceState?.let { it.state == ServiceState.STATE_IN_SERVICE }
        } catch (e: Exception) {
            null
        }
        val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: info.displayName?.toString()
            ?: "?"
        return SimInfo(
            slotIndex = info.simSlotIndex,
            subscriptionId = info.subscriptionId,
            carrierName = carrier,
            displayName = "SIM ${info.simSlotIndex + 1}",
            number = phoneNumber(info),
            available = simState == TelephonyManager.SIM_STATE_READY,
            state = stateName(simState),
            networkInService = inService,
        )
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun phoneNumber(info: SubscriptionInfo): String? {
        val n: String? = try {
            if (Build.VERSION.SDK_INT >= 33) subscriptionManager.getPhoneNumber(info.subscriptionId) else info.number
        } catch (t: Throwable) {
            null
        }
        return n?.takeIf { it.isNotBlank() }
    }

    private fun stateName(s: Int) = when (s) {
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        else -> "UNKNOWN"
    }
}
