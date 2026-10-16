package mg.smsgateway.domain

import android.Manifest
import android.content.Context
import android.telephony.SmsMessage
import mg.smsgateway.Config
import mg.smsgateway.data.SettingsDao
import mg.smsgateway.data.SmsDao
import mg.smsgateway.sim.SimManager
import mg.smsgateway.sms.SmsSender
import mg.smsgateway.util.hasPermission

data class SendRequest(
    val subscriptionId: Int,
    val message: String,
    val recipients: List<String>,
    val recipientMessages: List<String>? = null,
    val recipientVariables: List<Map<String, String>>? = null,
    val skipInvalid: Boolean = false,
    /** Identifiant d'idempotence fourni par le PC : un même requestId ne crée jamais deux jobs. */
    val requestId: String? = null,
    /** L'API PC exige l'interrupteur USB; l'envoi confirmé depuis l'application ne l'exige pas. */
    val requireGatewayEnabled: Boolean = true,
)

data class ApiIssue(val code: String, val message: String)
data class InvalidEntry(val index: Int, val value: String, val reason: String)

data class ValidationReport(
    val rows: Int,
    val recipients: List<String>,
    val duplicates: Int,
    val invalid: List<InvalidEntry>,
    val segmentsPerSms: Int,
    val messagesByRecipient: Map<String, String>,
    val segmentsByRecipient: Map<String, Int>,
    val variablesByRecipient: Map<String, Map<String, String>>,
    val encoding: String,
    val characters: Int,
    val segmentsRequired: Int,
    val limit: Int,
    val used: Int,
    val reserved: Int,
    val remaining: Int,
    val missing: Int,
    val errors: List<ApiIssue>,
) {
    val valid: Boolean get() = errors.isEmpty()
}

/** Valide une demande d'envoi SANS rien écrire : permissions, SIM, quota, numéros, doublons. */
class SmsValidator(
    private val context: Context,
    private val settingsDao: SettingsDao,
    private val smsDao: SmsDao,
    private val simManager: SimManager,
    private val phoneValidator: PhoneNumberValidator,
    private val sender: SmsSender,
) {
    suspend fun validate(req: SendRequest, excludeJobId: String? = null): ValidationReport {
        val settings = settingsDao.get() ?: error("settings missing")
        val errors = mutableListOf<ApiIssue>()

        // --- Permissions
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) {
            errors += ApiIssue("PERMISSION_DENIED", "Permission SEND_SMS refusée sur le téléphone.")
        }
        if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            errors += ApiIssue("PERMISSION_DENIED", "Permission READ_PHONE_STATE refusée (détection des SIM impossible).")
        }

        // --- Interrupteur + SIM
        if (req.requireGatewayEnabled && !settings.sendingAllowed) {
            errors += ApiIssue("SENDING_DISABLED", "L'envoi par USB est désactivé sur le téléphone (interrupteur de l'écran principal).")
        }
        if (settings.selectedSubscriptionId < 0) {
            errors += ApiIssue("NO_SIM_SELECTED", "Aucune SIM sélectionnée sur le téléphone.")
        } else {
            if (req.subscriptionId != settings.selectedSubscriptionId) {
                errors += ApiIssue(
                    "SIM_MISMATCH",
                    "subscriptionId ${req.subscriptionId} différent de la SIM choisie sur le téléphone (${settings.selectedSubscriptionId})."
                )
            }
            val sim = simManager.get(settings.selectedSubscriptionId)
            if (sim == null) {
                errors += ApiIssue("SIM_NOT_FOUND", "La SIM sélectionnée n'est plus active (retirée ou désactivée).")
            } else {
                if (!sim.available) errors += ApiIssue("SIM_NOT_READY", "SIM non prête (état : ${sim.state}).")
                if (sim.networkInService == false) errors += ApiIssue("NO_NETWORK_SERVICE", "Aucun service réseau sur la SIM sélectionnée.")
            }
        }

        // --- Message / segments
        val msg = req.message
        var segments = 0
        var encoding = "GSM-7"
        val personalizedMessages = req.recipientMessages
        if (personalizedMessages != null && personalizedMessages.size != req.recipients.size) {
            errors += ApiIssue("INVALID_PERSONALIZED_MESSAGES", "Le nombre de messages personnalisés ne correspond pas au nombre de destinataires.")
        }
        val personalizedVariables = req.recipientVariables
        if (personalizedVariables != null && personalizedVariables.size != req.recipients.size) {
            errors += ApiIssue("INVALID_PERSONALIZED_VARIABLES", "Le nombre de jeux de variables ne correspond pas au nombre de destinataires.")
        }
        if (msg.isBlank()) {
            errors += ApiIssue("EMPTY_MESSAGE", "Message vide.")
        } else if (personalizedMessages == null) {
            try {
                segments = sender.divide(req.subscriptionId, msg).size
                val enc = SmsMessage.calculateLength(msg, false)[3]
                encoding = if (enc == SmsMessage.ENCODING_16BIT) "UCS-2 (Unicode, 70 car./segment)" else "GSM-7 (160 car./segment)"
            } catch (e: Exception) {
                errors += ApiIssue("SUBSCRIPTION_ERROR", "Impossible de calculer les segments : ${e.message}")
            }
        }

        // --- Destinataires : normalisation, doublons, invalides
        val unique = LinkedHashSet<String>()
        val messagesByRecipient = LinkedHashMap<String, String>()
        val segmentsByRecipient = LinkedHashMap<String, Int>()
        val variablesByRecipient = LinkedHashMap<String, Map<String, String>>()
        val invalid = mutableListOf<InvalidEntry>()
        var duplicates = 0
        req.recipients.forEachIndexed { i, raw ->
            when (val r = phoneValidator.validate(raw, settings.defaultRegion)) {
                is PhoneResult.Valid -> if (!unique.add(r.e164)) {
                    duplicates++
                } else {
                    val recipientMessage = personalizedMessages?.getOrNull(i) ?: msg
                    messagesByRecipient[r.e164] = recipientMessage
                    variablesByRecipient[r.e164] = personalizedVariables?.getOrNull(i).orEmpty()
                    if (recipientMessage.isBlank()) {
                        errors += ApiIssue("EMPTY_MESSAGE", "Le message personnalisé du destinataire ${i + 1} est vide.")
                        segmentsByRecipient[r.e164] = 0
                    } else if (personalizedMessages == null) {
                        segmentsByRecipient[r.e164] = segments
                    } else {
                        try {
                            val recipientSegments = sender.divide(req.subscriptionId, recipientMessage).size
                            segments = maxOf(segments, recipientSegments)
                            segmentsByRecipient[r.e164] = recipientSegments
                            val enc = SmsMessage.calculateLength(recipientMessage, false)[3]
                            if (segmentsByRecipient.size == 1) {
                                encoding = if (enc == SmsMessage.ENCODING_16BIT) {
                                    "UCS-2 (Unicode, 70 car./segment)"
                                } else {
                                    "GSM-7 (160 car./segment)"
                                }
                            }
                        } catch (e: Exception) {
                            errors += ApiIssue("SUBSCRIPTION_ERROR", "Impossible de calculer les segments : ${e.message}")
                            segmentsByRecipient[r.e164] = 0
                        }
                    }
                }
                is PhoneResult.Invalid -> invalid += InvalidEntry(i, raw, r.reason)
            }
        }
        if (segments > Config.MAX_SEGMENTS_PER_SMS) {
            errors += ApiIssue("MESSAGE_TOO_LONG", "Message trop long : jusqu'à $segments segments (maximum ${Config.MAX_SEGMENTS_PER_SMS}).")
        }
        if (req.recipients.isEmpty()) errors += ApiIssue("NO_RECIPIENTS", "Aucun destinataire.")
        if (req.recipients.size > Config.MAX_RECIPIENTS_PER_JOB) {
            errors += ApiIssue("TOO_MANY_RECIPIENTS", "Maximum ${Config.MAX_RECIPIENTS_PER_JOB} lignes par job.")
        }
        if (invalid.isNotEmpty() && !req.skipInvalid) {
            errors += ApiIssue("INVALID_RECIPIENTS", "${invalid.size} numéro(s) invalide(s). Corrigez-les ou envoyez avec skipInvalid=true.")
        }
        if (req.recipients.isNotEmpty() && unique.isEmpty()) {
            errors += ApiIssue("NO_VALID_RECIPIENTS", "Aucun numéro valide.")
        }

        // --- Quota (segments)
        val reserved = smsDao.reservedSegments(excludeJobId)
        val remaining = maxOf(0, settings.smsLimit - settings.smsUsed - reserved)
        val required = segmentsByRecipient.values.sum()
        val missing = maxOf(0, required - remaining)
        if (segments > 0 && required > remaining) {
            errors += ApiIssue(
                "QUOTA_EXCEEDED",
                "Envoi impossible. SMS disponibles : $remaining, SMS nécessaires : $required. Il manque $missing SMS."
            )
        }

        return ValidationReport(
            rows = req.recipients.size,
            recipients = unique.toList(),
            duplicates = duplicates,
            invalid = invalid,
            segmentsPerSms = segments,
            messagesByRecipient = messagesByRecipient,
            segmentsByRecipient = segmentsByRecipient,
            variablesByRecipient = variablesByRecipient,
            encoding = encoding,
            characters = msg.length,
            segmentsRequired = required,
            limit = settings.smsLimit,
            used = settings.smsUsed,
            reserved = reserved,
            remaining = remaining,
            missing = missing,
            errors = errors,
        )
    }
}
