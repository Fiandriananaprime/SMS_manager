package mg.smsgateway.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledCampaignDaoTest {
    @Test
    fun scheduledRecipientsStayOutOfQueueAndOnlyOneConcurrentTransitionWins() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            db.settingsDao().insertIfAbsent(SettingsEntity(pairingToken = "test", periodStartedAt = now))
            db.jobDao().insert(
                JobEntity(
                    id = "scheduled",
                    createdAt = now,
                    status = JobStatus.SCHEDULED,
                    subscriptionId = 1,
                    message = "Test",
                    segmentsPerSms = 1,
                    totalRecipients = 1,
                    scheduledAt = now,
                    gatewayAuthorized = false,
                )
            )
            db.smsDao().insertAll(
                listOf(SmsEntity(jobId = "scheduled", phoneNumber = "+261341234567", message = "Test", createdAt = now, segments = 1))
            )

            assertNull(db.smsDao().nextPending())
            val started = async(Dispatchers.IO) { db.jobDao().activateScheduled("scheduled", now) }
            val cancelled = async(Dispatchers.IO) { db.jobDao().cancelScheduled("scheduled", now) }
            val transitions = withContext(Dispatchers.IO) { started.await() + cancelled.await() }

            assertEquals(1, transitions)
            val job = db.jobDao().get("scheduled")
            assertNotNull(job)
            when (job?.status) {
                JobStatus.QUEUED -> assertNotNull(db.smsDao().nextPending())
                JobStatus.CANCELLED -> {
                    assertNull(db.smsDao().nextPending())
                    assertEquals(1, db.smsDao().cancelPending("scheduled"))
                }
                else -> throw AssertionError("Unexpected final campaign state: ${job?.status}")
            }
            assertEquals(0, db.jobDao().activateScheduled("scheduled", now))
        } finally {
            db.close()
        }
    }
}
