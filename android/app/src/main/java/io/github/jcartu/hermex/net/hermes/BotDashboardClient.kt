package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON

/**
 * Port of `HermexCompanionRoutes` (`Features/Bots/HermexPushPairing.swift`): what the
 * hermex-push routes the dashboard has mounted, from
 * `GET /api/plugins/hermex-push/capabilities` (plugin 0.5.0, #1235). Each route is a method
 * and the path below the plugin's mount, as in `"GET /pairing"`. [closed] supports nothing:
 * an older plugin, or one the dashboard hasn't loaded since it was installed. Read per screen,
 * never cached, so one server's answer can't gate another's.
 */
data class HermexCompanionRoutes(val pluginVersion: HermexPushPluginVersion?, val routes: Set<String>) {
    fun supports(route: String): Boolean = route in routes

    companion object {
        val closed = HermexCompanionRoutes(pluginVersion = null, routes = emptySet())

        /** Fields the plugin may add later are ignored, and an entry that isn't a string is skipped. */
        fun from(body: BotJSON): HermexCompanionRoutes = HermexCompanionRoutes(
            pluginVersion = body["plugin_version"].text?.let { HermexPushPluginVersion.parse(it) },
            routes = (body["routes"].list ?: emptyList()).mapNotNull { it.text }.toSet(),
        )
    }
}

/**
 * Port of `HermexPushPluginVersion`: a hermex-push version, compared number by number so
 * 0.10.0 is newer than 0.9.0, and 0.3 equals 0.3.0. Only dot-separated whole numbers parse.
 */
class HermexPushPluginVersion private constructor(val description: String, private val numbers: List<Int>) :
    Comparable<HermexPushPluginVersion> {

    override fun compareTo(other: HermexPushPluginVersion): Int {
        val a = padded(other)
        val b = other.padded(this)
        for (index in a.indices) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is HermexPushPluginVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = description

    private fun padded(other: HermexPushPluginVersion): List<Int> =
        numbers + List(maxOf(0, other.numbers.size - numbers.size)) { 0 }

    companion object {
        fun parse(text: String): HermexPushPluginVersion? {
            val numbers = text.split(".").map { part ->
                if (part.isNotEmpty() && part.all { it in '0'..'9' }) part.toIntOrNull() else null
            }
            if (numbers.any { it == null }) return null
            return HermexPushPluginVersion(text, numbers.filterNotNull())
        }
    }
}

/**
 * Port of `BotDashboardClient` (`Networking/Bots/BotDashboardClient.swift`): the Hermes
 * host's dashboard REST surface, kept apart from [BotClient] because it needs no gateway
 * socket: it mutates the host's plugins and environment and reads the companion plugin's
 * routes, over the sign-in and cookie jar the server's Bot screens share.
 */
class BotDashboardClient(private val http: HermesConnection) {

    /** Signs in with the provisioning deadline; the install identity is checked before the password goes out. */
    suspend fun signIn() = http.signIn(HermesConnection.Deadline.PROVISIONING)

    /** Writes one managed environment value at the host root. */
    suspend fun setEnvironmentValue(key: String, value: String) {
        send(HermesREST.SetEnvironment(key, value))
    }

    /** Installs an agent plugin from its identifier, reinstalling over an existing copy. */
    suspend fun installPlugin(identifier: String) {
        send(HermesREST.InstallPlugin(identifier))
    }

    /** Enables or disables an installed agent plugin; `validateDispatch` runs just before each send. */
    suspend fun setPlugin(name: String, enabled: Boolean, validateDispatch: (() -> Unit)? = null) {
        send(HermesREST.SetPlugin(name, enabled), validateDispatch)
    }

    /** Restarts the agent gateway so a newly installed plugin is loaded. */
    suspend fun restartGateway() {
        send(HermesREST.RestartGateway)
    }

    /** Asks hermex-push 0.4.0 or newer to restart the dashboard process it runs in (#934). */
    suspend fun restartHermes() {
        send(HermesREST.RestartDashboard)
    }

    /** Whether the host answers its public status route, read without signing in. */
    suspend fun answersStatus(): Boolean = http.answersStatus()

    /**
     * The hermex-push routes the dashboard has mounted (#1235). Any 404 is
     * [HermexCompanionRoutes.closed], whatever its body says. Other refusals throw.
     */
    suspend fun companionRoutes(): HermexCompanionRoutes {
        val (data, status) = http.reply(HermesREST.PluginCapabilities)
        if (status == 404) return HermexCompanionRoutes.closed
        if (status !in 200..299) throw BotFailure.Rejected(status)
        return HermexCompanionRoutes.from(BotJSON.parseOrNull(data) ?: BotJSON.NULL)
    }

    /** The plugins hub: every agent plugin with its on-disk version, or JSON null when unreadable. */
    suspend fun pluginsHub(): BotJSON = send(HermesREST.PluginsHub)

    /** The pairing route's body on the provisioning deadline; a 404 or 409 throws [BotFailure.Rejected] with its status. */
    suspend fun pairing(): BotJSON {
        val (data, status) = http.reply(HermesREST.PushPairing, HermesConnection.Deadline.PROVISIONING)
        if (status !in 200..299) throw BotFailure.Rejected(status)
        return BotJSON.parseOrNull(data) ?: BotJSON.NULL
    }

    /** Any non-2xx is the step's failure, carrying the status. Each step gets the provisioning deadline. */
    private suspend fun send(rest: HermesREST, validateDispatch: (() -> Unit)? = null): BotJSON {
        val data = http.data(rest, HermesConnection.Deadline.PROVISIONING, accepting = 200..299, validateDispatch = validateDispatch)
        return BotJSON.parseOrNull(data) ?: BotJSON.NULL
    }
}
