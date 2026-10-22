package io.github.jcartu.hermex.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class CronJobsResponse(
    val jobs: List<CronJob>? = null,
)

@Serializable
data class CronMutationResponse(
    val ok: Boolean? = null,
    val job: CronJob? = null,
    val error: String? = null,
    /**
     * Set when the server saved the job but warns about it: a Hermes host whose
     * scheduler could not register a new Task (424, #1040). Never decoded from webui.
     * Port of `Models/Cron.swift` `CronMutationResponse.warning`.
     */
    val warning: String? = null,
)

@Serializable(with = CronStatusResponseSerializer::class)
data class CronStatusResponse(
    val jobId: String? = null,
    val running: Boolean? = null,
    val elapsed: Double? = null,
    val runningJobs: Map<String, Double>? = null,
    val error: String? = null,
)

internal object CronStatusResponseSerializer :
    TolerantObjectSerializer<CronStatusResponse>("CronStatusResponse") {

    private val runningJobsSerializer = MapSerializer(String.serializer(), Double.serializer())

    override fun fromJson(json: Json, obj: JsonObject): CronStatusResponse =
        CronStatusResponse(
            jobId = decodeCronStrictValue(json, obj, String.serializer(), "job_id", "jobId"),
            running = decodeCronValue(json, obj, Boolean.serializer(), "running"),
            elapsed = obj.lossyDouble("elapsed"),
            runningJobs = decodeCronValue(json, obj, runningJobsSerializer, "running"),
            error = decodeCronStrictValue(json, obj, String.serializer(), "error"),
        )

    override fun toJson(json: Json, value: CronStatusResponse): JsonObject = buildJsonObject {
        value.jobId?.let { put("job_id", JsonPrimitive(it)) }
        if (value.running != null) {
            put("running", JsonPrimitive(value.running))
        } else {
            value.runningJobs?.let {
                put("running", json.encodeToJsonElement(runningJobsSerializer, it))
            }
        }
        value.elapsed?.let { put("elapsed", JsonPrimitive(it)) }
        value.error?.let { put("error", JsonPrimitive(it)) }
    }
}

@Serializable(with = CronJobSerializer::class)
data class CronJob(
    val jobId: String? = null,
    val name: String? = null,
    val prompt: String? = null,
    val schedule: CronSchedule? = null,
    val scheduleDisplay: String? = null,
    val enabled: Boolean? = null,
    val state: String? = null,
    val nextRunAt: CronDateValue? = null,
    val lastRunAt: CronDateValue? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
    val lastDeliveryError: String? = null,
    val repeatInfo: CronRepeat? = null,
    val deliver: String? = null,
    val skills: List<String>? = null,
    val model: String? = null,
    val provider: String? = null,
    val profile: String? = null,
    val toastNotifications: Boolean? = null,
    /** A Hermes host's claim on a run in progress (`fire_claim`); nil when none is held. */
    val fireClaim: CronFireClaim? = null,
    /**
     * `latest_execution.status` from a Hermes host's executions ledger:
     * `claimed`, `running`, `completed`, `failed` or `unknown`.
     */
    val latestExecutionStatus: String? = null,
    /**
     * `scheduler_heartbeat_age_s`: seconds since a Hermes host's scheduler last
     * ticked for this job's Profile; null when it never has or the host can't tell.
     */
    val schedulerHeartbeatAge: Double? = null,
) {
    val id: String
        get() = jobId ?: name ?: UUID.randomUUID().toString()

    /**
     * The job's Profile as a row names it, "Default" for the host's default
     * Profile; null when the job names none. Port of `CronJob.profileLabel`.
     */
    val profileLabel: String?
        get() {
            val profile = profile?.trim()
            if (profile.isNullOrEmpty()) return null
            return if (profile == "default") "Default" else profile
        }

    val displayName: String
        get() {
            name?.takeIf { it.isNotEmpty() }?.let { return it }
            scheduleText?.takeIf { it.isNotEmpty() }?.let { return it }
            return "Untitled Task"
        }

    val scheduleText: String?
        get() = scheduleDisplay ?: schedule?.displayText

    val editableScheduleText: String?
        get() = schedule?.expression
            ?: schedule?.expr
            ?: schedule?.runAt
            ?: schedule?.every
            ?: scheduleDisplay

    val status: CronJobStatus
        get() {
            if (
                isRecurring &&
                repeatInfo?.times == null &&
                enabled == false &&
                state == "completed" &&
                nextRunAt == null
            ) {
                return CronJobStatus.NEEDS_ATTENTION
            }

            if (
                isRecurring &&
                nextRunAt == null &&
                (state == "error" || lastStatus == "error")
            ) {
                return CronJobStatus.NEEDS_ATTENTION
            }

            if (state == "paused") return CronJobStatus.PAUSED
            if (enabled == false) return CronJobStatus.OFF
            if (lastStatus == "error") return CronJobStatus.ERROR
            return CronJobStatus.ACTIVE
        }

    private val isRecurring: Boolean
        get() = schedule?.kind == "cron" || schedule?.kind == "interval"
}

internal object CronJobSerializer : TolerantObjectSerializer<CronJob>("CronJob") {
    override fun fromJson(json: Json, obj: JsonObject): CronJob =
        CronJob(
            jobId = obj.lossyString("id") ?: obj.lossyString("job_id", "jobId"),
            name = obj.lossyString("name"),
            prompt = obj.lossyString("prompt"),
            schedule = decodeCronValue(json, obj, CronSchedule.serializer(), "schedule"),
            scheduleDisplay = obj.lossyString("schedule_display", "scheduleDisplay"),
            enabled = obj.lossyBool("enabled"),
            state = obj.lossyString("state"),
            nextRunAt = decodeCronValue(json, obj, CronDateValue.serializer(), "next_run_at", "nextRunAt"),
            lastRunAt = decodeCronValue(json, obj, CronDateValue.serializer(), "last_run_at", "lastRunAt"),
            lastStatus = obj.lossyString("last_status", "lastStatus"),
            lastError = obj.lossyString("last_error", "lastError"),
            lastDeliveryError = obj.lossyString("last_delivery_error", "lastDeliveryError"),
            repeatInfo = decodeCronValue(json, obj, CronRepeat.serializer(), "repeat"),
            deliver = obj.lossyString("deliver"),
            skills = decodeCronValue(json, obj, cronStringListSerializer, "skills"),
            model = obj.lossyString("model"),
            provider = obj.lossyString("provider"),
            profile = obj.lossyString("profile"),
            toastNotifications = obj.lossyBool("toast_notifications", "toastNotifications"),
            fireClaim = decodeCronValue(json, obj, CronFireClaim.serializer(), "fire_claim", "fireClaim"),
            latestExecutionStatus = obj.lossyString("latest_execution_status", "latestExecutionStatus"),
            schedulerHeartbeatAge = obj.lossyDouble("scheduler_heartbeat_age_s", "schedulerHeartbeatAge"),
        )

    override fun toJson(json: Json, value: CronJob): JsonObject = buildJsonObject {
        value.jobId?.let { put("job_id", JsonPrimitive(it)) }
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.prompt?.let { put("prompt", JsonPrimitive(it)) }
        value.schedule?.let { put("schedule", json.encodeToJsonElement(CronSchedule.serializer(), it)) }
        value.scheduleDisplay?.let { put("schedule_display", JsonPrimitive(it)) }
        value.enabled?.let { put("enabled", JsonPrimitive(it)) }
        value.state?.let { put("state", JsonPrimitive(it)) }
        value.nextRunAt?.let { put("next_run_at", json.encodeToJsonElement(CronDateValue.serializer(), it)) }
        value.lastRunAt?.let { put("last_run_at", json.encodeToJsonElement(CronDateValue.serializer(), it)) }
        value.lastStatus?.let { put("last_status", JsonPrimitive(it)) }
        value.lastError?.let { put("last_error", JsonPrimitive(it)) }
        value.lastDeliveryError?.let { put("last_delivery_error", JsonPrimitive(it)) }
        value.repeatInfo?.let { put("repeat", json.encodeToJsonElement(CronRepeat.serializer(), it)) }
        value.deliver?.let { put("deliver", JsonPrimitive(it)) }
        value.skills?.let { put("skills", json.encodeToJsonElement(cronStringListSerializer, it)) }
        value.model?.let { put("model", JsonPrimitive(it)) }
        value.provider?.let { put("provider", JsonPrimitive(it)) }
        value.profile?.let { put("profile", JsonPrimitive(it)) }
        value.toastNotifications?.let { put("toast_notifications", JsonPrimitive(it)) }
        value.fireClaim?.let { put("fire_claim", json.encodeToJsonElement(CronFireClaim.serializer(), it)) }
        value.latestExecutionStatus?.let { put("latest_execution_status", JsonPrimitive(it)) }
        value.schedulerHeartbeatAge?.let { put("scheduler_heartbeat_age_s", JsonPrimitive(it)) }
    }
}

/**
 * A Hermes host's `fire_claim`, `{at, by}`: a run of the job is in progress.
 * `by` names the claiming machine and is never decoded.
 * Port of `Models/Cron.swift` `CronFireClaim`.
 */
@Serializable(with = CronFireClaimSerializer::class)
data class CronFireClaim(
    val at: Instant? = null,
)

internal object CronFireClaimSerializer :
    TolerantObjectSerializer<CronFireClaim>("CronFireClaim") {

    override fun fromJson(json: Json, obj: JsonObject): CronFireClaim =
        CronFireClaim(
            at = decodeCronValue(json, obj, CronDateValue.serializer(), "at")?.date,
        )

    override fun toJson(json: Json, value: CronFireClaim): JsonObject = buildJsonObject {
        value.at?.let { put("at", json.encodeToJsonElement(CronDateValue.serializer(), CronDateValue(it))) }
    }
}

@Serializable(with = CronScheduleSerializer::class)
data class CronSchedule(
    val kind: String? = null,
    val expression: String? = null,
    val expr: String? = null,
    val runAt: String? = null,
    val every: String? = null,
) {
    val displayText: String?
        get() = expression ?: expr ?: runAt ?: every ?: kind
}

internal object CronScheduleSerializer : KSerializer<CronSchedule> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CronSchedule")

    override fun deserialize(decoder: Decoder): CronSchedule {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("CronSchedule supports JSON decoding only")
        val element = input.decodeJsonElement()

        if (element is JsonPrimitive && element !is JsonNull && element.isString) {
            return CronSchedule(expression = element.content)
        }

        val obj = element as? JsonObject
            ?: throw SerializationException("Expected JSON object or string for CronSchedule")
        return CronSchedule(
            kind = obj.lossyString("kind"),
            expression = obj.lossyString("expression"),
            expr = obj.lossyString("expr"),
            runAt = obj.lossyString("run_at", "runAt"),
            every = obj.lossyString("every"),
        )
    }

    override fun serialize(encoder: Encoder, value: CronSchedule) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("CronSchedule supports JSON encoding only")
        output.encodeJsonElement(
            buildJsonObject {
                value.kind?.let { put("kind", JsonPrimitive(it)) }
                value.expression?.let { put("expression", JsonPrimitive(it)) }
                value.expr?.let { put("expr", JsonPrimitive(it)) }
                value.runAt?.let { put("run_at", JsonPrimitive(it)) }
                value.every?.let { put("every", JsonPrimitive(it)) }
            },
        )
    }
}

@Serializable
data class CronRepeat(
    val times: Int? = null,
    val completed: Int? = null,
)

@Serializable(with = CronOutputResponseSerializer::class)
data class CronOutputResponse(
    val jobId: String? = null,
    val outputs: List<CronOutputItem>? = null,
)

internal object CronOutputResponseSerializer :
    TolerantObjectSerializer<CronOutputResponse>("CronOutputResponse") {

    private val outputListSerializer = ListSerializer(CronOutputItem.serializer())

    override fun fromJson(json: Json, obj: JsonObject): CronOutputResponse =
        CronOutputResponse(
            jobId = decodeCronStrictValue(json, obj, String.serializer(), "job_id", "jobId"),
            outputs = decodeCronValue(json, obj, outputListSerializer, "outputs"),
        )

    override fun toJson(json: Json, value: CronOutputResponse): JsonObject = buildJsonObject {
        value.jobId?.let { put("job_id", JsonPrimitive(it)) }
        value.outputs?.let { put("outputs", json.encodeToJsonElement(outputListSerializer, it)) }
    }
}

@Serializable(with = CronOutputItemSerializer::class)
data class CronOutputItem(
    val filename: String? = null,
    val content: String? = null,
) {
    val id: String
        get() = filename ?: UUID.randomUUID().toString()
}

internal object CronOutputItemSerializer : TolerantObjectSerializer<CronOutputItem>("CronOutputItem") {
    override fun fromJson(json: Json, obj: JsonObject): CronOutputItem =
        CronOutputItem(
            filename = decodeCronStrictValue(json, obj, String.serializer(), "filename"),
            content = decodeCronStrictValue(json, obj, String.serializer(), "content"),
        )

    override fun toJson(json: Json, value: CronOutputItem): JsonObject = buildJsonObject {
        value.filename?.let { put("filename", JsonPrimitive(it)) }
        value.content?.let { put("content", JsonPrimitive(it)) }
    }
}

@Serializable(with = CronDeliveryOptionsResponseSerializer::class)
data class CronDeliveryOptionsResponse(
    val platforms: List<CronDeliveryOption>? = null,
)

internal object CronDeliveryOptionsResponseSerializer :
    TolerantObjectSerializer<CronDeliveryOptionsResponse>("CronDeliveryOptionsResponse") {

    private val optionListSerializer = ListSerializer(CronDeliveryOption.serializer())

    override fun fromJson(json: Json, obj: JsonObject): CronDeliveryOptionsResponse =
        CronDeliveryOptionsResponse(
            platforms = decodeCronValue(json, obj, optionListSerializer, "platforms"),
        )

    override fun toJson(json: Json, value: CronDeliveryOptionsResponse): JsonObject = buildJsonObject {
        value.platforms?.let { put("platforms", json.encodeToJsonElement(optionListSerializer, it)) }
    }
}

@Serializable(with = CronDeliveryOptionSerializer::class)
data class CronDeliveryOption(
    val value: String? = null,
    val label: String? = null,
) {
    val id: String
        get() = value ?: label ?: UUID.randomUUID().toString()
}

internal object CronDeliveryOptionSerializer :
    TolerantObjectSerializer<CronDeliveryOption>("CronDeliveryOption") {

    override fun fromJson(json: Json, obj: JsonObject): CronDeliveryOption =
        CronDeliveryOption(
            value = obj.lossyString("value"),
            label = obj.lossyString("label"),
        )

    override fun toJson(json: Json, value: CronDeliveryOption): JsonObject = buildJsonObject {
        value.value?.let { put("value", JsonPrimitive(it)) }
        value.label?.let { put("label", JsonPrimitive(it)) }
    }
}

/** One selectable row in the cron deliver picker. */
data class CronDeliverPickerOption(
    val value: String,
    val label: String,
    /**
     * `true` when the row exists only to round-trip a draft value that the
     * server did not list (unknown/legacy deliver target).
     */
    val isCustom: Boolean,
) {
    val id: String
        get() = value
}

object CronDeliverPicker {
    /**
     * Builds picker rows from server-provided delivery options.
     *
     * Returns `null` when the picker should fall back to free-text entry:
     * options missing/empty (endpoint failed or returned nothing usable) or
     * the current draft value is blank (nothing safe to select).
     * A current value outside the server list is preserved as an extra custom
     * row instead of being clobbered. [initialValue] (the draft's deliver value
     * when the editor opened) also keeps its custom row so an unknown/legacy
     * value can be re-selected after choosing another option.
     */
    fun options(
        serverOptions: List<CronDeliveryOption>?,
        currentValue: String,
        initialValue: String? = null,
    ): List<CronDeliverPickerOption>? {
        val seenValues = mutableSetOf<String>()
        val valid = (serverOptions ?: emptyList()).mapNotNull { option ->
            val value = option.value?.trim()
            if (value.isNullOrEmpty() || !seenValues.add(value)) {
                return@mapNotNull null
            }

            val label = option.label?.trim()?.takeIf { it.isNotEmpty() } ?: value
            CronDeliverPickerOption(value = value, label = label, isCustom = false)
        }

        if (valid.isEmpty()) return null

        val current = currentValue.trim()
        if (current.isEmpty()) return null

        val options = valid.toMutableList()
        val knownValues = valid.mapTo(mutableSetOf()) { it.value }
        val initial = initialValue?.trim().orEmpty()
        if (initial.isNotEmpty() && knownValues.add(initial)) {
            options += CronDeliverPickerOption(value = initial, label = initial, isCustom = true)
        }
        if (knownValues.add(current)) {
            options += CronDeliverPickerOption(value = current, label = current, isCustom = true)
        }
        return options
    }
}

enum class CronJobStatus {
    ACTIVE,
    PAUSED,
    OFF,
    ERROR,
    NEEDS_ATTENTION;

    val label: String
        get() = when (this) {
            ACTIVE -> "Active"
            PAUSED -> "Paused"
            OFF -> "Off"
            ERROR -> "Error"
            NEEDS_ATTENTION -> "Needs Attention"
        }
}

data class CronJobEditorDraft(
    var name: String = "",
    var prompt: String = "",
    var schedule: String = "",
    var deliver: String = "local",
    var skillsText: String = "",
    var model: String = "",
    var provider: String = "",
    var profile: String = "",
    var toastNotifications: Boolean = true,
) {
    constructor(job: CronJob) : this(
        name = job.name ?: "",
        prompt = job.prompt ?: "",
        schedule = job.editableScheduleText ?: "",
        deliver = job.deliver ?: "local",
        skillsText = job.skills?.joinToString(", ") ?: "",
        model = job.model ?: "",
        provider = job.provider ?: "",
        profile = job.profile ?: "",
        toastNotifications = job.toastNotifications ?: true,
    )

    val trimmedName: String?
        get() = nonEmpty(name)

    val trimmedPrompt: String
        get() = prompt.trim()

    val trimmedSchedule: String
        get() = schedule.trim()

    val trimmedDeliver: String?
        get() = nonEmpty(deliver)

    val trimmedModel: String?
        get() = nonEmpty(model)

    val trimmedProvider: String?
        get() = nonEmpty(provider)

    val trimmedProfile: String?
        get() = nonEmpty(profile)

    val skills: List<String>
        get() = skillsText
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    val validationMessage: String?
        get() {
            if (trimmedPrompt.isEmpty()) return "Prompt is required."
            if (trimmedSchedule.isEmpty()) return "Schedule is required."
            return null
        }

    /**
     * Applies a model picked in the editor, or `null` for "Server default".
     *
     * Model and provider always move together, because every picker option
     * names both: writing one without the other is how a job ends up asking a
     * provider for a model it does not serve. Clearing writes both back to
     * empty, which is the server's cue to fall back to the selected profile.
     * Port of `CronJobEditorDraft.applyModelSelection(_:)`.
     */
    fun applyModelSelection(option: ModelCatalogOption?) {
        model = option?.id ?: ""
        provider = option?.providerID ?: ""
    }

    /**
     * Applies a profile picked in the editor, or `null` for "Server default".
     *
     * Deliberately leaves `model` and `provider` alone. Upstream computes both
     * from the profile's environment only when at least one of them is blank
     * (`_selected_profile_snapshot_updates`), so prefilling the model here
     * would suppress the server's own snapshot and move a server decision into
     * the app. Port of `CronJobEditorDraft.applyProfileSelection(_:)`.
     */
    fun applyProfileSelection(profileName: String?) {
        profile = profileName?.trim() ?: ""
    }

    /**
     * Applies a skill selection from the picker.
     *
     * `skillsText` stays the storage, so a job created before the picker
     * existed keeps round-tripping through the same comma-separated form the
     * server has always received. Port of `CronJobEditorDraft.applySkillSelection(_:)`.
     */
    fun applySkillSelection(names: List<String>) {
        skillsText = names.joinToString(", ")
    }

    /**
     * Drops the delivery targets and skills chosen in [earlier], the draft when
     * another Profile was picked, that the new Profile doesn't offer, once its
     * lists have loaded (#1040). Choices made since the pick stay. A target is
     * kept when its platform (`telegram` in `telegram:123`) is offered; with
     * none left, delivery falls back to `local`, which every Profile offers.
     * A list that didn't load (null) checks nothing.
     * Port of `CronJobEditorDraft.keepChoices(madeBefore:offeredTargets:offeredSkills:)`.
     */
    fun keepChoices(
        madeBefore: CronJobEditorDraft,
        offeredTargets: List<String>?,
        offeredSkills: List<String>?,
    ) {
        if (offeredTargets != null && deliver == madeBefore.deliver) {
            val kept = deliver.split(',')
                .map { it.trim() }
                .filter { target -> offeredTargets.contains(target.substringBefore(':')) }
            deliver = if (kept.isEmpty()) "local" else kept.joinToString(",")
        }
        if (offeredSkills != null) {
            applySkillSelection(skills.filter { offeredSkills.contains(it) || !madeBefore.skills.contains(it) })
        }
    }

    companion object {
        fun nonEmpty(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

        /**
         * [selection] with [name] added if absent, removed if present.
         *
         * Order is the user's: a newly selected skill goes on the end rather
         * than re-sorting a list they just read. Matching is exact, because the
         * server stores back whatever spelling it was sent.
         * Port of `CronJobEditorDraft.togglingSkill(_:in:)`.
         */
        fun togglingSkill(name: String, selection: List<String>): List<String> {
            val index = selection.indexOf(name)
            if (index < 0) return selection + name
            return selection.toMutableList().also { it.removeAt(index) }
        }
    }
}

@Serializable(with = CronDateValueSerializer::class)
data class CronDateValue(
    val date: Instant,
) {
    val formatted: String
        get() = displayFormatter.format(date.atZone(ZoneId.systemDefault()))

    companion object {
        val displayFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}

internal object CronDateValueSerializer : KSerializer<CronDateValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CronDateValue")

    override fun deserialize(decoder: Decoder): CronDateValue {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("CronDateValue supports JSON decoding only")
        val element = input.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Unsupported cron date value")

        if (primitive !is JsonNull && !primitive.isString) {
            primitive.doubleOrNull?.let(::instantFromEpochSeconds)?.let {
                return CronDateValue(it)
            }
        }

        if (primitive !is JsonNull && primitive.isString) {
            val stringValue = primitive.content
            stringValue.toDoubleOrNull()?.let(::instantFromEpochSeconds)?.let {
                return CronDateValue(it)
            }
            runCatching { Instant.parse(stringValue) }
                .getOrNull()
                ?.let { return CronDateValue(it) }
        }

        throw SerializationException("Unsupported cron date value")
    }

    override fun serialize(encoder: Encoder, value: CronDateValue) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("CronDateValue supports JSON encoding only")
        val epochSeconds = value.date.epochSecond + value.date.nano / 1_000_000_000.0
        output.encodeJsonElement(JsonPrimitive(epochSeconds))
    }

    private fun instantFromEpochSeconds(timestamp: Double): Instant? {
        if (!timestamp.isFinite()) return null
        return runCatching {
            val wholeSeconds = timestamp.toLong()
            val nanos = ((timestamp - wholeSeconds) * 1_000_000_000.0).toLong()
            Instant.ofEpochSecond(wholeSeconds, nanos)
        }.getOrNull()
    }
}

private val cronStringListSerializer = ListSerializer(String.serializer())

private fun <T> decodeCronValue(
    json: Json,
    obj: JsonObject,
    serializer: DeserializationStrategy<T>,
    vararg keys: String,
): T? {
    for (key in keys) {
        val element: JsonElement = obj[key] ?: continue
        if (element is JsonNull) continue
        runCatching { json.decodeFromJsonElement(serializer, element) }
            .getOrNull()
            ?.let { return it }
    }
    return null
}

private fun <T> decodeCronStrictValue(
    json: Json,
    obj: JsonObject,
    serializer: DeserializationStrategy<T>,
    vararg keys: String,
): T? {
    val element = obj.presentElement(*keys) ?: return null
    return json.decodeFromJsonElement(serializer, element)
}
