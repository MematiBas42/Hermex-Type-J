package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronDeliveryOptionsResponse
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesCronJobList
import io.github.jcartu.hermex.model.hermes.HermesCronRecentCompletion
import io.github.jcartu.hermex.model.hermes.HermesCronRun
import io.github.jcartu.hermex.model.lossyBool
import io.github.jcartu.hermex.model.lossyDouble
import io.github.jcartu.hermex.model.lossyInt
import io.github.jcartu.hermex.model.lossyString
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Port of `HermesCronClient` (`Networking/Hermes/HermesCronClient.swift`): the Tasks screens'
 * client on a Hermes host (#1040): `/api/cron/…` and the Profile's skills over the sign-in,
 * headers and cookie jar the server's Bot screens share, and the editor's model and Profile
 * lists over the shared gateway socket. A mutation names the job's own Profile (`?profile=`),
 * which the host treats as a hint and checks. A refusal the host explains reads as its
 * `detail` ([accepted]). A run is the session it ran in (#1042), and its output is that
 * session's final reply.
 */
class HermesCronClient(private val http: HermesConnection) {

    /**
     * The jobs with their running state and recent runs read off them (`CronJobList(hermesJobs:)`):
     * a job is running while it holds a fire claim or its latest execution is still claimed or
     * running; each job that has run contributes its last run, as webui's recent feed does.
     */
    suspend fun cronJobs(nowEpochSeconds: Double = System.currentTimeMillis() / 1000.0): HermesCronJobList {
        val body = send(HermesREST.CronJobs)
        val rows = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonArray }.getOrNull()
            ?: throw ApiError.Decoding(SerializationException("The cron list is not an array"))
        val jobs = ArrayList<CronJob>()
        val running = LinkedHashMap<String, Double>()
        val recent = ArrayList<HermesCronRecentCompletion>()
        for (element in rows) {
            val obj = element as? JsonObject ?: continue
            val job = runCatching { JSON.decodeFromJsonElement(CronJob.serializer(), obj) }.getOrNull() ?: continue
            jobs += job
            val claimAt = (obj["fire_claim"] as? JsonObject)?.let { claim -> lossyDouble(claim["at"]) }
            val hasClaim = obj["fire_claim"] is JsonObject
            val executing = (obj["latest_execution"] as? JsonObject)?.let { lossyString(it["status"]) } in setOf("claimed", "running")
            val id = job.jobId
            if (id != null && (hasClaim || executing)) {
                running[id] = claimAt?.let { maxOf(0.0, nowEpochSeconds - it) } ?: 0.0
            }
            job.lastRunAt?.let { last ->
                recent += HermesCronRecentCompletion(
                    jobId = job.jobId, name = job.name, status = job.lastStatus,
                    completedAt = last.date.epochSecond + last.date.nano / 1_000_000_000.0,
                )
            }
        }
        return HermesCronJobList(jobs = jobs, runningJobs = running, recentRuns = recent)
    }

    suspend fun cronDeliveryOptions(profile: String?): CronDeliveryOptionsResponse {
        val targets = json(send(HermesREST.CronDeliveryTargets(profile)))["targets"].list ?: emptyList()
        return CronDeliveryOptionsResponse(
            platforms = targets.mapNotNull { target ->
                target["id"].text?.let { CronDeliveryOption(value = it, label = target["name"].text) }
            },
        )
    }

    /** `toastNotifications` is never sent: the host has no toast setting. */
    data class CreateResult(val response: CronMutationResponse, val warning: String?)

    /**
     * A 424 means the job was saved but the host's scheduler could not register it: it comes
     * back as saved, without the job, carrying the host's warning, so the list reloads.
     */
    suspend fun createCron(
        prompt: String,
        schedule: String,
        name: String?,
        deliver: String?,
        skills: List<String>,
        model: String?,
        provider: String?,
        profile: String?,
    ): CreateResult {
        val fields = linkedMapOf(
            "schedule" to BotJSON.string(schedule), "prompt" to BotJSON.string(prompt), "name" to BotJSON.string(name ?: ""),
            "deliver" to BotJSON.string(deliver ?: "local"), "skills" to BotJSON.array(skills.map(BotJSON::string)),
        )
        model?.let { fields["model"] = BotJSON.string(it) }
        provider?.let { fields["provider"] = BotJSON.string(it) }
        val answer = reply(HermesREST.CronCreate(profile, fields))
        if (answer.status == 424) {
            val warning = answer.json?.get("detail")?.get("error")?.text
            return CreateResult(CronMutationResponse(ok = true, job = null, error = null), warning)
        }
        return CreateResult(job(accepted(answer)), null)
    }

    /** Sends what the editor holds as `{updates}`. [profile] only routes the request. */
    suspend fun updateCron(
        jobID: String,
        prompt: String?,
        schedule: String?,
        name: String?,
        deliver: String?,
        skills: List<String>?,
        model: String?,
        provider: String?,
        profile: String?,
    ): CronMutationResponse {
        val updates = LinkedHashMap<String, BotJSON>()
        for ((key, value) in listOf("prompt" to prompt, "schedule" to schedule, "name" to name, "deliver" to deliver, "model" to model, "provider" to provider)) {
            if (value != null) updates[key] = BotJSON.string(value)
        }
        skills?.let { updates["skills"] = BotJSON.array(it.map(BotJSON::string)) }
        return job(send(HermesREST.CronUpdate(jobID, profile, updates)))
    }

    suspend fun pauseCron(jobID: String, profile: String?): CronMutationResponse =
        job(send(HermesREST.CronPause(jobID, profile)))

    /** Resuming a one-shot whose time has passed is the host's unhandled 500. */
    suspend fun resumeCron(jobID: String, profile: String?): CronMutationResponse =
        job(send(HermesREST.CronResume(jobID, profile)))

    suspend fun deleteCron(jobID: String, profile: String?): CronMutationResponse {
        val ok = json(send(HermesREST.CronDelete(jobID, profile)))["ok"].flag
        return CronMutationResponse(ok = ok, job = null, error = null)
    }

    /**
     * The host's trigger (#1041), which runs the Task before it answers with the job, so it
     * gets the long deadline. A paused Task is resumed as it runs; one already running is
     * refused with 409 and the host's `detail`.
     */
    suspend fun runCron(jobID: String, profile: String?): CronMutationResponse =
        job(accepted(reply(HermesREST.CronTrigger(jobID, profile), HermesConnection.Deadline.PROVISIONING)))

    /** `model.options` for the Profile, or the host's own when none is named. */
    suspend fun cronModelGroups(profile: String?): List<ModelCatalogGroup> {
        val call = if (!profile.isNullOrEmpty()) HermesCall.ProfileModelOptions(profile) else HermesCall.ConfiguredModelOptions
        return HermesModelCatalog(gateway(call)).groups
    }

    /** `profiles.list`. A row's `path` is a host path and is not kept. */
    suspend fun cronProfiles(): List<ProfileSummary> =
        (gateway(HermesCall.ProfilesList(includeSessions = false))["profiles"].list ?: emptyList()).mapNotNull { row ->
            val name = row["name"].text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ProfileSummary(
                name = name, path = null, isDefault = row["is_default"].flag, isActive = null,
                gatewayRunning = null, model = row["model"].text, provider = row["provider"].text,
                hasEnv = null, skillCount = null,
            )
        }

    /** A disabled skill comes back `disabled`, which the editor leaves out. */
    suspend fun cronSkills(profile: String?): List<SkillSummary> =
        HermesSkillsClient.skills(send(HermesREST.Skills(profile)))

    /**
     * The Task's newest 100 runs, the most the host lists and the one page it has: it takes
     * no offset and reports no total, so the screens offer no more (#1042). Any other offset
     * is an empty page.
     */
    suspend fun cronHistory(jobID: String, profile: String?, offset: Int): List<HermesCronRun> {
        if (offset != 0) return emptyList()
        val page = send(HermesREST.CronRuns(jobID, profile, limit = 100))
        return HermesCronRun.runs(page)
    }

    /**
     * One run's output: its session's final reply, the last assistant message that is not a
     * tool call, as a side session's result is; null when it gave none. A run the host no
     * longer has (404) is [HermesCronRunUnavailable].
     */
    suspend fun cronRunDetail(profile: String?, runID: String): String? {
        val answer = reply(HermesREST.SessionMessages(runID, profile ?: ""))
        if (answer.status == 404) throw HermesCronRunUnavailable()
        val messages = json(accepted(answer))["messages"].list ?: emptyList()
        return HermesSideSessionResult.result(messages)
    }

    // MARK: - Wire

    /** One request's body, or the failure [accepted] reads from its status. */
    private suspend fun send(rest: HermesREST): ByteArray = accepted(reply(rest))

    /** A dropped request reads as the webui's network failure, so a cancellation is recognised as one. */
    private suspend fun reply(rest: HermesREST, deadline: HermesConnection.Deadline = HermesConnection.Deadline.STANDARD): HermesConnection.Reply =
        try {
            http.reply(rest, deadline)
        } catch (error: BotFailure.Network) {
            throw ApiError.Network(error.underlying)
        }

    /** One gateway call on its own attachment to the connection's shared socket, left once the host answers. */
    private suspend fun gateway(call: HermesCall): BotJSON {
        val client = BotClient(http)
        try {
            client.connect()
            return client.call(call)
        } finally {
            client.close()
        }
    }

    companion object {
        internal val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        /**
         * A 2xx reply's body. A 4xx the host explains in `detail`, such as a schedule it can't
         * parse or a Task that is gone, reads as that reason ([HermesCronRefusal]). Hermes never
         * answers 403, 502-504 or 520-530 itself, so those get the Hermes connection's copy for
         * the proxy or tunnel in front of it. Any other status is [ApiError.Http].
         */
        fun accepted(reply: HermesConnection.Reply): ByteArray {
            when (reply.status) {
                in 200..299 -> return reply.body
                403, in 502..504, in 520..530 -> throw BotFailure.Rejected(reply.status)
                in 400..499 -> {
                    val detail = reply.json?.get("detail")?.text?.trim()
                    if (!detail.isNullOrEmpty()) throw HermesCronRefusal(detail)
                }
            }
            throw ApiError.Http(reply.status, reply.body.toString(Charsets.UTF_8))
        }

        private fun job(body: ByteArray): CronMutationResponse {
            val job = try {
                JSON.decodeFromString(CronJob.serializer(), body.toString(Charsets.UTF_8))
            } catch (error: SerializationException) {
                throw ApiError.Decoding(error)
            } catch (error: IllegalArgumentException) {
                throw ApiError.Decoding(error)
            }
            return CronMutationResponse(ok = true, job = job, error = null)
        }

        internal fun json(body: ByteArray): BotJSON = try {
            BotJSON.parse(body)
        } catch (error: SerializationException) {
            throw ApiError.Decoding(error)
        } catch (error: IllegalArgumentException) {
            throw ApiError.Decoding(error)
        }
    }
}

/** Port of `HermesCronRefusal`: a Tasks or Skills request a Hermes host refused with its reason (`{detail}`). */
class HermesCronRefusal(val detail: String) : Exception() {
    override val message: String get() = "The server rejected the request: $detail"
}

/** Port of `HermesCronRunUnavailable`: a run whose session the host no longer has (#1042). */
class HermesCronRunUnavailable : Exception() {
    override val message: String get() = "This run is no longer on the server."
}

/** Port of `HermesChatSideTasks.result(_:)`: a side session's result, its last reply that is not a tool call. */
object HermesSideSessionResult {
    fun result(messages: List<BotJSON>): String? =
        messages.lastOrNull { message ->
            message["role"].text == "assistant" && (message["tool_calls"].list ?: emptyList()).isEmpty() &&
                !text(message["content"]).isNullOrBlank()
        }?.let { text(it["content"]) }

    /** A row's text: a string, or its text parts joined. */
    fun text(content: BotJSON): String? {
        content.text?.let { return it }
        return content.list?.let { parts -> parts.mapNotNull { it["text"].text }.joinToString("") }
    }
}

/** Decodes a Hermes host's `lossy` fields of one cron run row; see [HermesCronRun]. */
internal fun cronRunFromJson(row: JsonObject): HermesCronRun? {
    val id = lossyString(row["id"])?.takeIf { it.isNotEmpty() } ?: return null
    fun date(key: String): Double? = lossyDouble(row[key])?.takeIf { it.isFinite() }
    val started = date("started_at")
    val ended = date("ended_at")
    val input = lossyInt(row["input_tokens"])
    val output = lossyInt(row["output_tokens"])
    val total = if (input == null && output == null) {
        null
    } else {
        val sum = (input ?: 0).toLong() + (output ?: 0).toLong()
        if (sum > Int.MAX_VALUE) null else sum.toInt()
    }
    return HermesCronRun(
        id = id,
        startedAt = started,
        endedAt = ended,
        isRunning = lossyBool(row["is_active"]) ?: false,
        model = lossyString(row["model"]),
        estimatedCostUsd = lossyDouble(row["actual_cost_usd"]) ?: lossyDouble(row["estimated_cost_usd"]),
        durationSeconds = if (started != null && ended != null) ended - started else null,
        inputTokens = input,
        outputTokens = output,
        totalTokens = total,
    )
}
