package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*

internal class MemoryListPreferences : HermesListPreferences {
    override var selected: String? = null
    override var showsAllProfiles = false
    override var lastListed: String? = null
}

internal class SessionTestWire : HermesSessionWire {
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null
    val calls = mutableListOf<HermesCall>()
    val pageCalls = mutableListOf<Triple<String, Int, Boolean>>()
    val writes = mutableListOf<Triple<String, String, HermesSessionChange>>()
    var connects = 0
    var closes = 0
    var profiles = listOf("default")
    var current = "default"
    var active = BotJSON.parse("""{"sessions":[]}""")
    var onConnect: suspend () -> Unit = {}
    var onPage: suspend (String, Int, Boolean, Boolean) -> HermesSessionPage = { _, _, _, _ -> HermesSessionPage(emptyList()) }
    var onSearch: suspend (String, String, Boolean) -> List<HermesSessionSearchResult> = { _, _, _ -> emptyList() }
    var onUpdate: suspend (HermesSessionChange, String, String) -> String? = { change, _, _ -> (change as? HermesSessionChange.Title)?.title }
    var onRow: suspend (String, String) -> BotJSON? = { _, _ -> null }
    var onCall: (suspend (HermesCall) -> BotJSON)? = null
    var deletion = HermesSessionDeletion.Outcome.DELETED
    override suspend fun connect() { connects++; onConnect() }
    override suspend fun close() { closes++ }
    override suspend fun currentProfile() = current
    override suspend fun call(call: HermesCall): BotJSON {
        calls += call
        onCall?.let { return it(call) }
        return when (call) {
            is HermesCall.ProfilesList -> BotJSON.objectOf("profiles" to BotJSON.array(profiles.mapIndexed { index, name ->
                BotJSON.objectOf("name" to BotJSON.string(name), "is_default" to BotJSON.bool(index == 0)) }))
            HermesCall.SessionActiveList -> active
            is HermesCall.ProjectsTree -> BotJSON.parse("""{"projects":[]}""")
            else -> BotJSON.parse("{}")
        }
    }
    override suspend fun page(profile: String, offset: Int, archived: Boolean, scheduled: Boolean): HermesSessionPage {
        pageCalls += Triple(profile, offset, scheduled)
        return onPage(profile, offset, archived, scheduled)
    }
    override suspend fun search(query: String, profile: String, scheduled: Boolean) = onSearch(query, profile, scheduled)
    override suspend fun row(key: String, profile: String) = onRow(key, profile)
    override suspend fun update(change: HermesSessionChange, key: String, profile: String): String? {
        writes += Triple(key, profile, change)
        return onUpdate(change, key, profile)
    }
    override suspend fun delete(key: String, profile: String) = deletion
    override suspend fun duplicate(key: String, profile: String, title: String) = HermesSessionDuplication.Outcome("copy", "$title (copy)")
    override suspend fun export(key: String, profile: String) = "{}".toByteArray()
}
