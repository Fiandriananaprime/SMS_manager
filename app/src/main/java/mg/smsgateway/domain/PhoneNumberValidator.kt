package mg.smsgateway.domain

/**
 * Règle par pays. Pour ajouter un pays : créer un objet qui implémente CountryRule
 * et l'ajouter à la liste passée à PhoneNumberValidator (voir App.kt).
 * (Alternative : brancher libphonenumber derrière la même interface.)
 */
interface CountryRule {
    val region: String
    val callingCode: String

    /** @param nsn numéro national significatif (sans indicatif pays) */
    fun isValidMobile(nsn: String): Boolean

    /** Convertit un format local (ex. 0341234567) en NSN, ou null si le format n'est pas reconnu. */
    fun localToNsn(digits: String): String?
}

object MadagascarRule : CountryRule {
    override val region = "MG"
    override val callingCode = "261"
    private val mobile = Regex("3\\d{8}")
    override fun isValidMobile(nsn: String) = mobile.matches(nsn)
    override fun localToNsn(digits: String) =
        if (digits.length == 10 && digits.startsWith("03")) digits.substring(1) else null
}

sealed class PhoneResult {
    data class Valid(val e164: String, val region: String?) : PhoneResult()
    data class Invalid(val reason: String) : PhoneResult()
}

class PhoneNumberValidator(private val rules: List<CountryRule>) {

    private val separators = Regex("[\\s().\\-]")

    fun validate(raw: String, defaultRegion: String): PhoneResult {
        val cleaned = raw.trim().replace(separators, "")
        if (cleaned.isEmpty()) return PhoneResult.Invalid("Numéro vide")
        val defaultRule = rules.firstOrNull { it.region.equals(defaultRegion, ignoreCase = true) }

        val digits: String = when {
            cleaned.startsWith("+") -> cleaned.substring(1)
            cleaned.startsWith("00") -> cleaned.substring(2)
            else -> {
                if (!cleaned.all { it in '0'..'9' }) return PhoneResult.Invalid("Caractères invalides")
                val nsn = defaultRule?.localToNsn(cleaned)
                if (defaultRule != null && nsn != null) {
                    defaultRule.callingCode + nsn
                } else if (defaultRule != null && cleaned.startsWith(defaultRule.callingCode) &&
                    defaultRule.isValidMobile(cleaned.removePrefix(defaultRule.callingCode))
                ) {
                    cleaned // "+" perdu par Excel : 261341234567
                } else {
                    return PhoneResult.Invalid("Format international requis (ex. +261341234567)")
                }
            }
        }

        if (!digits.all { it in '0'..'9' }) return PhoneResult.Invalid("Caractères invalides")
        if (digits.length !in 8..15 || digits.startsWith("0")) {
            return PhoneResult.Invalid("Numéro invalide (E.164 : 8 à 15 chiffres, indicatif pays requis)")
        }
        val rule = rules.firstOrNull { digits.startsWith(it.callingCode) }
        if (rule != null && !rule.isValidMobile(digits.removePrefix(rule.callingCode))) {
            return PhoneResult.Invalid("Numéro mobile ${rule.region} invalide")
        }
        return PhoneResult.Valid("+$digits", rule?.region)
    }
}
