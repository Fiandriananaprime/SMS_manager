package mg.smsgateway.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mg.smsgateway.data.LogDao
import mg.smsgateway.data.LogEntity
import java.util.concurrent.atomic.AtomicInteger

/** Logs vers Logcat + table Room (consultables dans l'app et via GET /api/logs). */
class AppLogger(private val dao: LogDao, private val scope: CoroutineScope) {
    private val counter = AtomicInteger(0)

    fun i(tag: String, msg: String) = log("INFO", tag, msg, null)
    fun w(tag: String, msg: String) = log("WARN", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("ERROR", tag, msg, t)

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "INFO" -> Log.i(tag, msg)
            "WARN" -> Log.w(tag, msg)
            else -> Log.e(tag, msg, t)
        }
        val full = if (t != null) "$msg (${t.javaClass.simpleName}: ${t.message})" else msg
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.insert(LogEntity(timestamp = System.currentTimeMillis(), level = level, tag = tag, message = full))
                if (counter.incrementAndGet() % 100 == 0) dao.trim()
            }
        }
    }
}
