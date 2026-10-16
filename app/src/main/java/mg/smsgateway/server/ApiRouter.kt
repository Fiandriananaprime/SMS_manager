package mg.smsgateway.server

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import mg.smsgateway.Config
import mg.smsgateway.data.JobDao
import mg.smsgateway.data.LogDao
import mg.smsgateway.data.SettingsDao
import mg.smsgateway.data.SmsDao
import mg.smsgateway.data.SmsEntity
import mg.smsgateway.domain.CreateOutcome
import mg.smsgateway.domain.JobManager
import mg.smsgateway.domain.JobProgress
import mg.smsgateway.domain.SendRequest
import mg.smsgateway.domain.SmsValidator
import mg.smsgateway.domain.ValidationReport
import mg.smsgateway.service.GatewayState
import mg.smsgateway.sim.SimInfo
import mg.smsgateway.sim.SimManager
import mg.smsgateway.util.AppLogger
import mg.smsgateway.util.hasPermission
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/**
 * API locale. Chaque requête passe par : contrôle Host/Origin -> verrouillage anti brute-force
 * -> jeton d'appairage (Bearer) -> routage -> validation.
 */
class ApiRouter(
    private val context: Context,
    private val settingsDao: SettingsDao,
    private val jobDao: JobDao,
    private val smsDao: SmsDao,
    private val logDao: LogDao,
    private val simManager: SimManager,
    private val validator: SmsValidator,
    private val jobManager: JobManager,
    private val logger: AppLogger,
) {
    private val failures = ArrayDeque<Long>()

    suspend fun handle(req: HttpRequest): HttpResponse {
        return try {
            route(req)
        } catch (e: ApiException) {
            error(e.status, e.code, e.message ?: "")
        } catch (e: JSONException) {
            error(400, "BAD_JSON", "JSON invalide : ${e.message}")
        }
    }

    private suspend fun route(req: HttpRequest): HttpResponse {
        // 1) Un navigateur du PC (page web malveillante, DNS rebinding) ne doit pas pouvoir atteindre l'API
        val host = req.headers["host"]?.substringBefore(':')?.lowercase()
        if (host != "127.0.0.1" && host != "localhost") throw ApiException(403, "BAD_HOST", "Host non autorisé")
        if (req.headers.containsKey("origin") || req.headers.containsKey("sec-fetch-site")) {
            throw ApiException(403, "BROWSER_REQUEST_REFUSED", "Requêtes de navigateur refusées")
        }

        if (req.method == "GET" && req.path == "/api/ping") {
            return json(200, JSONObject().put("app", "sms-gateway").put("apiVersion", Config.API_VERSION))
        }

        // 2) Authentification par jeton d'appairage + verrouillage après 10 échecs / minute
        synchronized(failures) {
            val now = System.currentTimeMillis()
            while (failures.isNotEmpty() && now - failures.first() > 60_000) failures.removeFirst()
            if (failures.size >= 10) throw ApiException(429, "LOCKED", "Trop d'échecs d'authentification, réessayez dans 1 minute")
        }
        val settings = settingsDao.get() ?: throw ApiException(500, "NO_SETTINGS", "Configuration absente")
        val provided = req.headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
        if (!MessageDigest.isEqual(provided.toByteArray(), settings.pairingToken.toByteArray())) {
            synchronized(failures) { failures.addLast(System.currentTimeMillis()) }
            logger.w("Api", "Authentification refusée pour ${req.method} ${req.path}")
            throw ApiException(401, "UNAUTHORIZED", "Jeton d'appairage invalide ou absent")
        }
        GatewayState.lastAuthContact.value = System.currentTimeMillis()

        // 3) Routage
        val seg = req.path.trim('/').split('/')
        val get = req.method == "GET"
        val post = req.method == "POST"
        return when {
            get && req.path == "/api/device" -> device()
            get && req.path == "/api/sims" -> sims()
            get && req.path == "/api/status" -> status()
            get && req.path == "/api/logs" -> logs(req)
            get && req.path == "/api/jobs" -> jobs()
            post && req.path == "/api/sms/validate" -> validate(req)
            post && req.path == "/api/sms/send" -> send(req)
            post && req.path == "/api/sms/cancel" -> cancel(JSONObject(req.body.ifBlank { "{}" }).optString("jobId", ""))
            seg.size == 3 && seg[0] == "api" && seg[1] == "jobs" && get -> job(seg[2])
            seg.size == 4 && seg[0] == "api" && seg[1] == "jobs" && seg[3] == "cancel" && post -> cancel(seg[2])
            seg.size == 4 && seg[0] == "api" && seg[1] == "jobs" && seg[3] == "recipients" && get -> recipients(seg[2], req)
            else -> error(404, "NOT_FOUND", "Route inconnue : ${req.method} ${req.path}")
        }
    }

    // ------------------------------------------------------------------ endpoints

    private fun device(): HttpResponse {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return json(
            200, JSONObject()
                .put("deviceId", id)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("androidVersion", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                .put("connected", true)
        )
    }

    private suspend fun sims(): HttpResponse {
        val selected = settingsDao.get()?.selectedSubscriptionId ?: -1
        val arr = JSONArray()
        simManager.listSims().forEach { arr.put(simJson(it, selected)) }
        return json(200, JSONObject().put("sims", arr))
    }

    private fun simJson(s: SimInfo, selected: Int) = JSONObject()
        .put("slotIndex", s.slotIndex)
        .put("subscriptionId", s.subscriptionId)
        .put("carrierName", s.carrierName)
        .put("displayName", s.displayName)
        .putOpt("number", s.number)
        .put("available", s.available)
        .put("state", s.state)
        .putOpt("networkInService", s.networkInService)
        .put("selected", s.subscriptionId == selected)

    private suspend fun status(): HttpResponse {
        val s = settingsDao.get()!!
        val reserved = smsDao.reservedSegments()
        return json(
            200, JSONObject()
                .put("apiVersion", Config.API_VERSION)
                .put("sendingAllowed", s.sendingAllowed)
                .put("selectedSubscriptionId", s.selectedSubscriptionId)
                .put("defaultRegion", s.defaultRegion)
                .put("maxSegmentsPerMinute", s.maxPerMinute)
                .put(
                    "quota", JSONObject()
                        .put("limit", s.smsLimit).put("used", s.smsUsed).put("reserved", reserved)
                        .put("remaining", maxOf(0, s.smsLimit - s.smsUsed - reserved))
                        .put("periodStartedAt", s.periodStartedAt)
                )
                .put(
                    "permissions", JSONObject()
                        .put("sendSms", context.hasPermission(Manifest.permission.SEND_SMS))
                        .put("readPhoneState", context.hasPermission(Manifest.permission.READ_PHONE_STATE))
                )
                .putOpt("activeJobId", jobDao.unfinishedIds().firstOrNull())
        )
    }

    private suspend fun validate(req: HttpRequest): HttpResponse {
        val report = validator.validate(parseSend(req.body))
        return json(200, reportJson(report))
    }

    private suspend fun send(req: HttpRequest): HttpResponse {
        val sendReq = parseSend(req.body)
        return when (val out = jobManager.createJob(sendReq)) {
            is CreateOutcome.Rejected -> json(422, reportJson(out.report).put("accepted", false))
            is CreateOutcome.Accepted -> {
                val body = JSONObject().put("jobId", out.jobId)
                    .put("status", if (out.alreadyExisted) (jobDao.get(out.jobId)?.status ?: "QUEUED") else "QUEUED")
                    .put("duplicateRequest", out.alreadyExisted)
                out.report?.let {
                    body.put("recipientCount", it.recipients.size)
                        .put("smsSegmentsRequired", it.segmentsRequired)
                        .put("duplicatesRemoved", it.duplicates)
                        .put("invalidSkipped", it.invalid.size)
                }
                json(if (out.alreadyExisted) 200 else 202, body)
            }
        }
    }

    private suspend fun cancel(jobId: String): HttpResponse {
        if (jobId.isBlank()) throw ApiException(400, "MISSING_FIELD", "jobId requis")
        val n = jobManager.cancelJob(jobId) ?: throw ApiException(404, "JOB_NOT_FOUND", "Job inconnu")
        val p = jobManager.progress(jobId)!!
        return json(200, progressJson(p).put("cancelledNow", n))
    }

    private suspend fun job(id: String): HttpResponse {
        val p = jobManager.progress(id) ?: throw ApiException(404, "JOB_NOT_FOUND", "Job inconnu")
        val seg = smsDao.segments(id)
        return json(200, progressJson(p).put("segmentsSent", seg.sent).put("segmentsDelivered", seg.delivered))
    }

    private suspend fun jobs(): HttpResponse {
        val arr = JSONArray()
        jobDao.summaries(50).forEach {
            arr.put(
                JSONObject().put("jobId", it.id).put("status", it.status).put("createdAt", it.createdAt)
                    .put("totalRecipients", it.totalRecipients).put("sent", it.sent).put("failed", it.failed)
                    .put("cancelled", it.cancelled).put("pending", it.pending).put("sending", it.sending)
            )
        }
        return json(200, JSONObject().put("jobs", arr))
    }

    private suspend fun recipients(id: String, req: HttpRequest): HttpResponse {
        if (jobDao.get(id) == null) throw ApiException(404, "JOB_NOT_FOUND", "Job inconnu")
        val limit = (req.query["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 500)
        val offset = (req.query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val status = req.query["status"]?.uppercase()?.takeIf { it.isNotBlank() }
        val arr = JSONArray()
        smsDao.page(id, status, limit, offset).forEach { arr.put(smsJson(it)) }
        return json(200, JSONObject().put("jobId", id).put("offset", offset).put("limit", limit).put("recipients", arr))
    }

    private suspend fun logs(req: HttpRequest): HttpResponse {
        val limit = (req.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val arr = JSONArray()
        logDao.recent(limit).forEach {
            arr.put(JSONObject().put("ts", it.timestamp).put("level", it.level).put("tag", it.tag).put("message", it.message))
        }
        return json(200, JSONObject().put("logs", arr))
    }

    // ------------------------------------------------------------------ JSON helpers

    private fun parseSend(body: String): SendRequest {
        if (body.isBlank()) throw ApiException(400, "EMPTY_BODY", "Corps JSON requis")
        val o = JSONObject(body)
        val sub = o.optInt("subscriptionId", Int.MIN_VALUE)
        if (sub == Int.MIN_VALUE) throw ApiException(400, "MISSING_FIELD", "subscriptionId requis (entier)")
        val arr = o.optJSONArray("recipients") ?: throw ApiException(400, "MISSING_FIELD", "recipients requis (tableau)")
        val list = (0 until arr.length()).map { arr.optString(it, "") }
        return SendRequest(
            subscriptionId = sub,
            message = o.optString("message", ""),
            recipients = list,
            skipInvalid = o.optBoolean("skipInvalid", false),
            requestId = o.optString("requestId", "").ifBlank { null },
        )
    }

    private fun reportJson(r: ValidationReport): JSONObject {
        val invalid = JSONArray()
        r.invalid.forEach { invalid.put(JSONObject().put("index", it.index).put("value", it.value).put("reason", it.reason)) }
        val errors = JSONArray()
        r.errors.forEach { errors.put(JSONObject().put("code", it.code).put("message", it.message)) }
        return JSONObject()
            .put("valid", r.valid)
            .put("recipientCount", r.recipients.size)
            .put("rows", r.rows)
            .put("duplicates", r.duplicates)
            .put("invalidCount", r.invalid.size)
            .put("invalidRecipients", invalid)
            .put("segmentsPerSms", r.segmentsPerSms)
            .put("encoding", r.encoding)
            .put("characters", r.characters)
            .put("smsSegmentsRequired", r.segmentsRequired)
            .put("smsLimit", r.limit)
            .put("smsAlreadyUsed", r.used)
            .put("smsReserved", r.reserved)
            .put("smsRemaining", r.remaining)
            .put("smsMissing", r.missing)
            .put("errors", errors)
    }

    private fun progressJson(p: JobProgress): JSONObject = JSONObject()
        .put("jobId", p.jobId).put("status", p.status)
        .put("totalRecipients", p.total).put("pending", p.pending).put("sending", p.sending)
        .put("sent", p.sent).put("failed", p.failed).put("cancelled", p.cancelled)
        .put("percent", p.percent).put("segmentsPerSms", p.segmentsPerSms)
        .put("createdAt", p.createdAt).putOpt("finishedAt", p.finishedAt)

    private fun smsJson(s: SmsEntity) = JSONObject()
        .put("id", s.id).put("phoneNumber", s.phoneNumber).put("status", s.status).put("attempts", s.attempts)
        .putOpt("error", s.error).put("createdAt", s.createdAt).putOpt("sentAt", s.sentAt)
        .put("segments", s.segments).put("segmentsSent", s.segmentsSent).put("deliveredSegments", s.deliveredSegments)

    private fun json(status: Int, o: JSONObject) = HttpResponse(status, o.toString())

    private fun error(status: Int, code: String, message: String) =
        json(status, JSONObject().put("error", JSONObject().put("code", code).put("message", message)))
}
