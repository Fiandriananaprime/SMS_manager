package mg.smsgateway.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SmsStatus {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object JobStatus {
    const val SCHEDULED = "SCHEDULED"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
    const val CANCELLED = "CANCELLED"
}

/** Ligne unique (id = 1) : configuration + compteur de SMS. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    /** Plafond local de segments SMS (0 tant que l'utilisateur n'a rien configuré => rien ne part). */
    val smsLimit: Int = 0,
    /** Segments SMS consommés dans la période courante. */
    val smsUsed: Int = 0,
    val selectedSubscriptionId: Int = -1,
    val pairingToken: String,
    /** Interrupteur "Autoriser l'envoi par USB". Faux par défaut. */
    val sendingAllowed: Boolean = false,
    val defaultRegion: String = "MG",
    /** Plafond de segments par minute (protection anti-popup Android, 30/min par défaut côté OS). */
    val maxPerMinute: Int = 20,
    val periodStartedAt: Long,
)

@Entity(tableName = "jobs", indices = [Index(value = ["clientRequestId"], unique = true)])
data class JobEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val status: String,
    val subscriptionId: Int,
    val message: String,
    val segmentsPerSms: Int,
    val totalRecipients: Int,
    val duplicatesRemoved: Int = 0,
    val invalidSkipped: Int = 0,
    val cancelRequested: Boolean = false,
    val clientRequestId: String? = null,
    @ColumnInfo(defaultValue = "1") val gatewayAuthorized: Boolean = true,
    val scheduledAt: Long? = null,
)

@Entity(
    tableName = "sms",
    indices = [Index("jobId"), Index("status")],
    foreignKeys = [ForeignKey(
        entity = JobEntity::class,
        parentColumns = ["id"],
        childColumns = ["jobId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val phoneNumber: String,
    val message: String,
    val status: String = SmsStatus.PENDING,
    val attempts: Int = 0,
    val error: String? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
    /** Nombre de segments du message (réservés dans le quota tant que le SMS n'est pas final). */
    val segments: Int,
    val variablesJson: String = "{}",
    val segmentsSent: Int = 0,
    val segmentsFailed: Int = 0,
    val deliveredSegments: Int = 0,
    val lastErrorCode: Int? = null,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
)

@Entity(tableName = "campaign_draft")
data class CampaignDraftEntity(
    @PrimaryKey val id: Int = 1,
    val sourceName: String? = null,
    val message: String = "",
    val recipientsJson: String = "[]",
    val updatedAt: Long,
)

data class StatusCount(val status: String, val count: Int)

data class JobSegments(val sent: Int, val delivered: Int)

data class JobSummary(
    val id: String,
    val createdAt: Long,
    val status: String,
    val totalRecipients: Int,
    val sent: Int,
    val failed: Int,
    val cancelled: Int,
    val pending: Int,
    val sending: Int,
    val scheduledAt: Long?,
)
