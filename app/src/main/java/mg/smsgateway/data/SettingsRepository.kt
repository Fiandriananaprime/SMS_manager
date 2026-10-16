package mg.smsgateway.data

import android.util.Base64
import java.security.SecureRandom

class SettingsRepository(private val dao: SettingsDao) {

    suspend fun ensureExists() {
        dao.insertIfAbsent(
            SettingsEntity(pairingToken = generateToken(), periodStartedAt = System.currentTimeMillis())
        )
    }

    suspend fun regenerateToken(): String {
        val t = generateToken()
        dao.setToken(t)
        return t
    }

    companion object {
        /** 192 bits aléatoires (SecureRandom), encodés base64url. */
        fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
