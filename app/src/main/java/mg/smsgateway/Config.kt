package mg.smsgateway

/** Constantes de l'application. */
object Config {
    /** Port TCP du serveur local (écoute uniquement sur 127.0.0.1 du téléphone). */
    const val PORT = 8765
    const val API_VERSION = 1

    /** Délai minimal entre deux SMS. */
    const val MIN_SPACING_MS = 800L

    /** Attente maximale des callbacks Android après sendMultipartTextMessage. */
    const val CALLBACK_TIMEOUT_MS = 120_000L

    const val MAX_SEGMENTS_PER_SMS = 10
    const val MAX_RECIPIENTS_PER_JOB = 2000
    const val MAX_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 5_000L
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** Le PC est considéré "connecté" s'il a fait une requête authentifiée récemment. */
    const val PC_ACTIVE_WINDOW_MS = 20_000L
}
