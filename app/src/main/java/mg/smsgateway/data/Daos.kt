package mg.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(s: SettingsEntity)

    @Query("UPDATE settings SET smsLimit = :limit WHERE id = 1")
    suspend fun setLimit(limit: Int)

    /** Incrément atomique en SQL (pas de lecture-modification-écriture). */
    @Query("UPDATE settings SET smsUsed = smsUsed + :n WHERE id = 1")
    suspend fun addUsed(n: Int)

    @Query("UPDATE settings SET smsUsed = :used WHERE id = 1")
    suspend fun setUsed(used: Int)

    @Query("UPDATE settings SET smsUsed = 0, periodStartedAt = :now WHERE id = 1")
    suspend fun resetUsed(now: Long)

    @Query("UPDATE settings SET selectedSubscriptionId = :id WHERE id = 1")
    suspend fun setSim(id: Int)

    @Query("UPDATE settings SET pairingToken = :token WHERE id = 1")
    suspend fun setToken(token: String)

    @Query("UPDATE settings SET sendingAllowed = :v WHERE id = 1")
    suspend fun setSendingAllowed(v: Boolean)

    @Query("UPDATE settings SET defaultRegion = :r WHERE id = 1")
    suspend fun setRegion(r: String)

    @Query("UPDATE settings SET maxPerMinute = :v WHERE id = 1")
    suspend fun setMaxPerMinute(v: Int)
}

private const val SUMMARY_SQL = """
SELECT j.id AS id, j.createdAt AS createdAt, j.status AS status, j.totalRecipients AS totalRecipients,
 (SELECT COUNT(*) FROM sms s WHERE s.jobId = j.id AND s.status = 'SENT') AS sent,
 (SELECT COUNT(*) FROM sms s WHERE s.jobId = j.id AND s.status = 'FAILED') AS failed,
 (SELECT COUNT(*) FROM sms s WHERE s.jobId = j.id AND s.status = 'CANCELLED') AS cancelled,
 (SELECT COUNT(*) FROM sms s WHERE s.jobId = j.id AND s.status = 'PENDING') AS pending,
 (SELECT COUNT(*) FROM sms s WHERE s.jobId = j.id AND s.status = 'SENDING') AS sending,
 j.scheduledAt AS scheduledAt
FROM jobs j ORDER BY j.createdAt DESC
"""

@Dao
interface JobDao {
    @Insert
    suspend fun insert(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun observe(id: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE clientRequestId = :rid LIMIT 1")
    suspend fun findByRequestId(rid: String): JobEntity?

    @Query("UPDATE jobs SET status = :status WHERE id = :id AND finishedAt IS NULL")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE jobs SET status = 'QUEUED' WHERE id = :id AND status = 'SCHEDULED' AND cancelRequested = 0 AND scheduledAt <= :now")
    suspend fun activateScheduled(id: String, now: Long): Int

    @Query("UPDATE jobs SET status = 'CANCELLED', cancelRequested = 1, finishedAt = :now WHERE id = :id AND status = 'SCHEDULED' AND finishedAt IS NULL")
    suspend fun cancelScheduled(id: String, now: Long): Int

    @Query("SELECT * FROM jobs WHERE status = 'SCHEDULED' AND finishedAt IS NULL ORDER BY scheduledAt")
    suspend fun scheduled(): List<JobEntity>

    @Query("UPDATE jobs SET message = :message, segmentsPerSms = :segments, totalRecipients = :total, scheduledAt = :scheduledAt WHERE id = :id AND status = 'SCHEDULED' AND finishedAt IS NULL AND cancelRequested = 0")
    suspend fun updateScheduled(id: String, message: String, segments: Int, total: Int, scheduledAt: Long): Int

    @Query("DELETE FROM jobs WHERE id = :id AND finishedAt IS NOT NULL")
    suspend fun deleteFinished(id: String): Int

    @Query("UPDATE jobs SET status = :status, finishedAt = :at WHERE id = :id")
    suspend fun finish(id: String, status: String, at: Long)

    @Query("UPDATE jobs SET cancelRequested = 1 WHERE id = :id AND finishedAt IS NULL AND status != 'SCHEDULED'")
    suspend fun markCancelRequested(id: String)

    @Query("SELECT id FROM jobs WHERE finishedAt IS NULL")
    suspend fun unfinishedIds(): List<String>

    @Query(SUMMARY_SQL)
    fun observeSummaries(): Flow<List<JobSummary>>

    @Query(SUMMARY_SQL + " LIMIT :limit")
    suspend fun summaries(limit: Int): List<JobSummary>
}

@Dao
interface SmsDao {
    @Insert
    suspend fun insertAll(items: List<SmsEntity>)

    @Update
    suspend fun update(sms: SmsEntity)

    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun get(id: Long): SmsEntity?

    @Query("SELECT * FROM sms WHERE status = 'PENDING' AND jobId IN (SELECT id FROM jobs WHERE status IN ('QUEUED','RUNNING') AND cancelRequested = 0 AND (gatewayAuthorized = 0 OR (SELECT sendingAllowed FROM settings WHERE id = 1) = 1)) ORDER BY id LIMIT 1")
    suspend fun nextPending(): SmsEntity?

    @Query("SELECT * FROM sms WHERE status = 'SENDING'")
    suspend fun sending(): List<SmsEntity>

    @Query("UPDATE sms SET status = 'SENDING', attempts = attempts + 1 WHERE id = :id AND status = 'PENDING' AND jobId IN (SELECT id FROM jobs WHERE status IN ('QUEUED','RUNNING') AND cancelRequested = 0 AND (gatewayAuthorized = 0 OR (SELECT sendingAllowed FROM settings WHERE id = 1) = 1))")
    suspend fun markSending(id: Long): Int

    @Query("UPDATE sms SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    /** Remet en file un SMS (échec avant envoi radio), sauf si le job a été annulé entre-temps. */
    @Query(
        "UPDATE sms SET status = 'PENDING', error = NULL, segmentsFailed = 0, lastErrorCode = NULL " +
            "WHERE id = :id AND status IN ('FAILED','SENDING') " +
            "AND jobId IN (SELECT id FROM jobs WHERE cancelRequested = 0)"
    )
    suspend fun requeue(id: Long): Int

    @Query("UPDATE sms SET status = 'CANCELLED' WHERE jobId = :jobId AND status = 'PENDING'")
    suspend fun cancelPending(jobId: String): Int

    @Query("UPDATE sms SET deliveredSegments = deliveredSegments + 1 WHERE id = :id")
    suspend fun addDelivered(id: Long)

    @Query("SELECT status AS status, COUNT(*) AS count FROM sms WHERE jobId = :jobId GROUP BY status")
    suspend fun counts(jobId: String): List<StatusCount>

    @Query("SELECT status AS status, COUNT(*) AS count FROM sms WHERE jobId = :jobId GROUP BY status")
    fun observeCounts(jobId: String): Flow<List<StatusCount>>

    @Query("SELECT * FROM sms WHERE jobId = :jobId ORDER BY id")
    fun observeByJob(jobId: String): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms WHERE jobId = :jobId ORDER BY id")
    suspend fun byJob(jobId: String): List<SmsEntity>

    @Query("DELETE FROM sms WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: String)

    @Query(
        "SELECT * FROM sms WHERE jobId = :jobId AND (:status IS NULL OR status = :status) " +
            "ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun page(jobId: String, status: String?, limit: Int, offset: Int): List<SmsEntity>

    @Query(
        "SELECT COALESCE(SUM(segmentsSent),0) AS sent, COALESCE(SUM(deliveredSegments),0) AS delivered " +
            "FROM sms WHERE jobId = :jobId"
    )
    suspend fun segments(jobId: String): JobSegments

    /** Segments réservés par les SMS pas encore finaux (PENDING / SENDING). */
    @Query("SELECT COALESCE(SUM(segments - segmentsSent),0) FROM sms WHERE status IN ('PENDING','SENDING') AND (:excludeJobId IS NULL OR jobId != :excludeJobId)")
    suspend fun reservedSegments(excludeJobId: String? = null): Int

    @Query("SELECT COALESCE(SUM(segments - segmentsSent),0) FROM sms WHERE status IN ('PENDING','SENDING')")
    fun observeReserved(): Flow<Int>
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntity)

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LogEntity>

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntity>>

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT 2000)")
    suspend fun trim()
}

@Dao
interface CampaignDraftDao {
    @Query("SELECT * FROM campaign_draft WHERE id = 1")
    fun observe(): Flow<CampaignDraftEntity?>

    @Query("SELECT * FROM campaign_draft WHERE id = 1")
    suspend fun get(): CampaignDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: CampaignDraftEntity)

    @Query("DELETE FROM campaign_draft WHERE id = 1")
    suspend fun clear()
}
