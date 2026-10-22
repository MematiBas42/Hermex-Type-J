package io.github.jcartu.hermex.ui.feature.hermes.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.data.HermesCacheStore
import io.github.jcartu.hermex.model.SessionExportFile
import io.github.jcartu.hermex.model.SessionExportFormat
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.sessions.SessionRowAttentionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl

/**
 * Port of Features/SessionList/SessionListViewModel.swift's Hermes path (#1046–1054/#1258).
 * Owns one BotClient consumer, not the host connection. Every callback hops from HermesActor to
 * viewModelScope's Main dispatcher. Reads are latest-wins; writes are never retried.
 */
class HermesSessionListViewModel(
    private val serverId: String,
    private val address: HttpUrl,
    private val makeWire: () -> HermesSessionWire,
    private val preferences: HermesListPreferences,
    private val cache: HermesCacheStore? = null,
    private val initialProfile: String? = null,
    private val archived: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val changeDebounceMillis: Long = 1_000,
    private val statusPollMillis: Long = 5_000,
) : ViewModel() {
    private val _state = MutableStateFlow(HermesSessionListState(profile = initialProfile, archived = archived,
        showsAllProfiles = !archived && preferences.showsAllProfiles))
    val uiState: StateFlow<HermesSessionListState> = _state.asStateFlow()
    private var wire: HermesSessionWire? = null
    private val source = HermesSessionListDataSource { wire ?: throw BotFailure.Stale }
    private var pages = HermesProfilePages()
    private var scheduled = HermesProfilePages()
    private var readSerial = 0
    private var projectsSerial = 0
    private var searchSerial = 0
    private var searchWrites = 0
    private var cacheEpoch = cache?.hermesCacheEpoch(serverId) ?: 0
    private var listening = false
    private var connecting = false
    private var readsStatus = true
    private var retriesStatus = false
    private var reconnectAttempt = 0
    private var reloadJob: Job? = null
    private var reloadWanted = false
    private var debounceJob: Job? = null
    private var statusJob: Job? = null
    private var reconnectJob: Job? = null
    private var searchJob: Job? = null
    private var searchAwaitsConnect = false
    private val returnedFrom = mutableSetOf<String>()
    private data class ReadMark(val sequence: Long, val unread: Boolean, var settledBefore: Int? = null)
    private val marks = mutableMapOf<String, ReadMark>()
    private val unreadWrites = mutableMapOf<String, Job>()
    private var markSequence = 0L
    private val listedProfiles: List<String> get() = _state.value.let { state ->
        if (state.showsAllProfiles && state.profiles.isNotEmpty()) state.profiles else listOfNotNull(state.profile)
    }

    /** Cache paints before connect (not after the failed request); a provisional copy is read-only without offline chrome. */
    suspend fun open() {
        listening = true
        reconnectJob?.cancel(); reconnectJob = null
        followSavedProfile()
        if (connecting) return
        val old = wire
        if (old != null) {
            if (_state.value.showsAllProfiles) readProfiles(old)
            watchProfiles(old)
            reload()
            return
        }
        connecting = true
        val current = makeWire()
        wire = current
        readsStatus = true
        cacheEpoch = cache?.hermesCacheEpoch(serverId) ?: 0
        if (_state.value.sessions.isEmpty()) {
            _state.update { it.copy(isLoading = true) }
            showCache(provisional = true)
        }
        current.onEvent = { event -> viewModelScope.launch {
            if (wire === current && listening && event["type"].text == "sessions.changed") noteChange()
        } }
        current.onDisconnect = { error -> viewModelScope.launch { if (wire === current) dropped(error) } }
        try {
            current.connect()
            if (wire !== current) return
            reconnectAttempt = 0
            _state.update { it.copy(isConnected = true) }
            readProfiles(current)
            if (_state.value.profile == null) resolveProfile(current)
            if (wire !== current) return
            if (watchProfiles(current)) moveOffRemovedProfile(current)
            reload()
        } catch (cancel: CancellationException) {
            if (wire === current) close()
            throw cancel
        } catch (error: Throwable) {
            if (wire === current) dropped(error)
        } finally { connecting = false }
    }

    fun refresh() { searchAwaitsConnect = _state.value.query.isNotEmpty(); viewModelScope.launch { open() } }
    fun pause() {
        listening = false
        debounceJob?.cancel(); debounceJob = null
        statusJob?.cancel(); statusJob = null
    }
    suspend fun close() {
        pause()
        reconnectJob?.cancel(); reconnectJob = null
        reloadJob?.cancel(); reloadJob = null; reloadWanted = false
        searchJob?.cancel(); searchJob = null
        readSerial++; searchSerial++
        val old = wire; wire = null
        old?.onEvent = null; old?.onDisconnect = null
        old?.close()
        connecting = false
        _state.update { it.copy(isConnected = false, isLive = false, isLoading = false, isLoadingMore = false, isLoadingMoreScheduled = false, attentionStates = emptyMap()) }
        searchAwaitsConnect = _state.value.query.isNotEmpty()
    }
    override fun onCleared() {
        val old = wire; wire = null
        old?.onEvent = null; old?.onDisconnect = null
        if (old != null) CoroutineScope(SupervisorJob() + HermesActor.dispatcher).launch { try { old.close() } finally { cancel() } }
        super.onCleared()
    }

    suspend fun selectProfile(profile: String) {
        if (_state.value.profile == profile && !_state.value.showsAllProfiles) return
        preferences.selected = profile; preferences.showsAllProfiles = false
        _state.update { it.copy(showsAllProfiles = false) }
        showProfile(profile)
    }
    suspend fun showAllProfiles() {
        if (archived || _state.value.showsAllProfiles) return
        val profile = _state.value.profile ?: return
        preferences.showsAllProfiles = true
        _state.update { it.copy(showsAllProfiles = true) }
        showProfile(profile)
    }
    private suspend fun showProfile(profile: String) {
        resetPages(profile)
        showCache(provisional = true)
        val current = wire ?: return open()
        watchProfiles(current)
        reload()
        search(_state.value.query, debounceMillis = 0)
    }
    private fun resetPages(profile: String) {
        readSerial++; projectsSerial++; searchSerial++
        pages = HermesProfilePages(); scheduled = HermesProfilePages()
        marks.clear(); returnedFrom.clear()
        _state.update { it.copy(profile = profile, sessions = emptyList(), projects = emptyList(), projectOwners = emptyMap(),
            unreadMarks = emptyMap(), attentionStates = emptyMap(), searchResults = emptyList(), searchSnippets = emptyMap(),
            hasMore = false, hasMoreScheduled = false, isLoadingMore = false, isLoadingMoreScheduled = false,
            isLoading = true, isLive = false, isViewingCachedData = false, isCachedCopyProvisional = false, errorMessage = null) }
    }
    private fun followSavedProfile() {
        if (archived) return
        val saved = preferences.selected ?: return
        if (saved == _state.value.profile || saved !in _state.value.profiles) return
        if (_state.value.showsAllProfiles) _state.update { it.copy(profile = saved) } else resetPages(saved)
    }
    private suspend fun readProfiles(current: HermesSessionWire) {
        try {
            val reply = current.call(HermesCall.ProfilesList(false))
            if (wire !== current) return
            val names = reply["profiles"].list?.mapNotNull { it["name"].text?.takeIf(String::isNotEmpty) } ?: return
            _state.update { it.copy(profiles = names) }
            if (_state.value.showsAllProfiles && names.isNotEmpty() && _state.value.profile !in names) resolveProfile(current)
        } catch (cancel: CancellationException) { throw cancel } catch (_: Throwable) { /* A failed roster preserves the last known names. */ }
    }
    private suspend fun resolveProfile(current: HermesSessionWire) {
        val profile = current.currentProfile()
        if (wire === current) _state.update { it.copy(profile = preferences.resolve(it.profiles, profile)) }
    }
    private suspend fun watchProfiles(current: HermesSessionWire): Boolean {
        var removed = false
        for (profile in listedProfiles) try { current.call(HermesCall.SessionMostRecent(profile)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: BotFailure.Rejected) { if (error.code == 4064 && profile == _state.value.profile) removed = true }
        catch (_: Throwable) { /* Rows still load if this older host cannot watch the store. */ }
        return removed
    }
    private suspend fun moveOffRemovedProfile(current: HermesSessionWire): Boolean {
        val removed = _state.value.profile ?: return false
        readProfiles(current)
        if (wire !== current || _state.value.profiles.isEmpty() || removed in _state.value.profiles) return false
        resolveProfile(current)
        val next = _state.value.profile ?: return false
        if (next == removed || next !in _state.value.profiles) return false
        resetPages(next)
        watchProfiles(current)
        return true
    }

    /** Reload exactly the pages already held; an in-flight mutation invalidates this read before it can undo local state. */
    suspend fun reload() {
        val current = wire ?: return
        if (listedProfiles.isEmpty()) return
        if (_state.value.mutating.isNotEmpty()) { reloadWanted = true; return }
        val profiles = listedProfiles
        val serial = ++readSerial
        val next = HermesProfilePages()
        val nextScheduled = HermesProfilePages()
        try {
            for (profile in profiles) {
                next[profile] = HermesSessionPages(archived)
                val wanted = maxOf(HermesREST.SESSION_PAGE_SIZE, pages[profile].nextOffset + if (_state.value.isLoadingMore) HermesREST.SESSION_PAGE_SIZE else 0)
                while (next[profile].hasMore && next[profile].nextOffset < wanted) {
                    val page = source.page(profile, next[profile].nextOffset, archived).hermesPage ?: throw BotFailure.Unsupported
                    if (serial != readSerial || wire !== current) return
                    next[profile].append(page)
                }
                if (!archived) {
                    val scheduledWanted = maxOf(HermesREST.SESSION_PAGE_SIZE, scheduled[profile].nextOffset)
                    while (nextScheduled[profile].hasMore && nextScheduled[profile].nextOffset < scheduledWanted) {
                        val page = current.page(profile, nextScheduled[profile].nextOffset, false, scheduled = true)
                        if (serial != readSerial || wire !== current) return
                        nextScheduled[profile].append(page)
                    }
                }
            }
            if (serial != readSerial || wire !== current) return
            pages = next; scheduled = nextScheduled
            marks.entries.removeAll { (_, mark) -> mark.settledBefore?.let { it <= serial } == true }
            publishPages(live = true)
            _state.update { it.copy(isLoading = false, isLoadingMore = false, isLoadingMoreScheduled = false, errorMessage = null) }
            val returned = returnedFrom.toSet(); returnedFrom.clear()
            for (row in _state.value.sessions) if (row.key in returned && row.row.unread == true) setUnread(row, false)
            preferences.lastListed = _state.value.profile
            cacheRows()
            startStatusRead(current)
            if (!_state.value.showsAllProfiles && !archived) readProjects(current, serial)
            if (searchAwaitsConnect) { searchAwaitsConnect = false; search(_state.value.query, 0) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) {
            if (serial != readSerial || wire !== current) return
            if (error == BotFailure.Stale) { dropped(error); return }
            if (error is BotFailure.Rejected && error.code == 404) {
                if (_state.value.showsAllProfiles) {
                    val before = _state.value.profiles; readProfiles(current)
                    if (before != _state.value.profiles) { requestReload(); return }
                } else if (moveOffRemovedProfile(current)) { requestReload(); return }
            }
            if (!fallback(error)) _state.update { it.copy(errorMessage = advice(error)) }
            _state.update { it.copy(isLive = false) }
        } finally {
            if (serial == readSerial) _state.update { it.copy(isLoading = false, isLoadingMore = false, isLoadingMoreScheduled = false) }
        }
    }

    suspend fun loadMore(scheduledRows: Boolean = false) {
        val state = _state.value
        if (!state.isLive || state.query.isNotBlank() || state.isLoadingMore || state.isLoadingMoreScheduled ||
            !(if (scheduledRows) state.hasMoreScheduled else state.hasMore)) return
        val current = wire ?: return
        val serial = readSerial
        _state.update { if (scheduledRows) it.copy(isLoadingMoreScheduled = true) else it.copy(isLoadingMore = true) }
        val next = copyPages(if (scheduledRows) scheduled else pages)
        try {
            for (profile in listedProfiles) if (next[profile].hasMore) {
                val page = current.page(profile, next[profile].nextOffset, archived, scheduledRows)
                if (serial != readSerial || wire !== current) return
                next[profile].append(page)
            }
            if (scheduledRows) scheduled = next else pages = next
            publishPages()
            cacheRows()
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { if (serial == readSerial) _state.update { it.copy(errorMessage = advice(error)) } }
        finally { if (serial == readSerial) _state.update { it.copy(isLoadingMore = false, isLoadingMoreScheduled = false) } }
    }
    private fun copyPages(from: HermesProfilePages) = HermesProfilePages().also { copy -> from.profiles.forEach { copy[it] = from[it].copy() } }
    private fun publishPages(live: Boolean = false) {
        val rows = (pages.rows + scheduled.rows).map { (profile, row) -> HermesListedSession(profile, row) }
        _state.update { it.copy(sessions = rows, hasMore = pages.hasMore, hasMoreScheduled = scheduled.hasMore,
            scheduledTotal = scheduled.totalCount, unreadMarks = marks.mapValues { mark -> mark.value.unread },
            isLive = live || it.isLive, isViewingCachedData = if (live) false else it.isViewingCachedData,
            isCachedCopyProvisional = if (live) false else it.isCachedCopyProvisional) }
    }
    private suspend fun cacheRows() {
        if (archived) return
        val cache = cache ?: return
        try {
            val snapshots = listedProfiles.map { Triple(it, (pages[it].rows + scheduled[it].rows).toList(), !pages[it].hasMore && !scheduled[it].hasMore) }
            withContext(ioDispatcher) { snapshots.forEach { (profile, rows, end) -> cache.cacheHermesSessions(rows, profile, end, serverId, expectedEpoch = cacheEpoch) } }
        } catch (cancel: CancellationException) { throw cancel } catch (error: Throwable) { _state.update { it.copy(cacheErrorMessage = error.message) } }
    }
    private suspend fun showCache(provisional: Boolean): Boolean {
        if (archived) return false
        val cache = cache ?: return false
        val picked = _state.value.profile ?: preferences.selected
        val profile = picked ?: preferences.lastListed
        if (!_state.value.showsAllProfiles && profile == null) return false
        val serial = readSerial
        val cached = withContext(ioDispatcher) { cache.cachedHermesSessions(serverId, if (_state.value.showsAllProfiles) null else profile) }
        if (cached.isEmpty() || serial != readSerial) return false
        _state.update { it.copy(sessions = cached.map { row -> HermesListedSession(row.first, row.second) }, profile = it.profile ?: picked,
            isLoading = false, isLive = false, isViewingCachedData = true, isCachedCopyProvisional = provisional,
            hasMore = false, hasMoreScheduled = false, attentionStates = emptyMap(), searchResults = emptyList(), searchSnippets = emptyMap(), errorMessage = null) }
        searchAwaitsConnect = _state.value.query.isNotEmpty()
        return true
    }
    private suspend fun fallback(error: Throwable): Boolean {
        if (hermesUsesOfflineCache(error) && showCache(false)) return true
        if (_state.value.isCachedCopyProvisional) _state.update { it.copy(sessions = emptyList(), isViewingCachedData = false, isCachedCopyProvisional = false) }
        return false
    }
    private suspend fun dropped(error: Throwable) {
        fallback(error)
        val wasListening = listening
        close()
        listening = wasListening
        val retry = BotConnectionAdvice.isRetryable(error)
        if (_state.value.sessions.isEmpty() || !retry) _state.update { it.copy(errorMessage = advice(error)) }
        if (listening && retry) reconnectJob = viewModelScope.launch {
            delay(HermesReconnectBackoff.delayMillis(reconnectAttempt++))
            reconnectJob = null
            if (listening) open()
        }
    }
    private fun noteChange() {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch { delay(changeDebounceMillis); requestReload() }
    }
    private fun requestReload() {
        reloadWanted = true
        if (reloadJob?.isActive == true || wire == null || _state.value.mutating.isNotEmpty()) return
        reloadJob = viewModelScope.launch {
            while (reloadWanted && wire != null && isActive && _state.value.mutating.isEmpty()) {
                reloadWanted = false
                reload()
            }
        }
    }
    private fun startStatusRead(current: HermesSessionWire) {
        statusJob?.cancel()
        if (!readsStatus || !listening || archived) return
        statusJob = viewModelScope.launch {
            do {
                try {
                    val items = current.call(HermesCall.SessionActiveList)["sessions"].list.orEmpty()
                    if (wire !== current || !listening) return@launch
                    val states = HermesSessionRowStates.from(items)
                    val rows = _state.value.sessions
                    val keyed = rows.mapNotNull { row ->
                        // Hosts that name a Profile let identical stored keys stay separate.
                        val matching = items.filter { it["session_key"].text == row.row.id && (it["profile"].text == null || it["profile"].text == row.profile) }
                        val value = if (matching.size == items.size) states[row.row.id] else HermesSessionRowStates.from(matching)[row.row.id]
                        value?.let { row.key to if (it == HermesSessionRowState.INPUT) SessionRowAttentionState.INPUT else SessionRowAttentionState.WORKING }
                    }.toMap()
                    _state.update { it.copy(attentionStates = keyed) }
                    retriesStatus = false
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Throwable) {
                    if (wire !== current) return@launch
                    if (error is BotFailure.Rejected && error.code == -32601) readsStatus = false
                    retriesStatus = retriesStatus || _state.value.attentionStates.isNotEmpty()
                    _state.update { it.copy(attentionStates = emptyMap()) }
                }
                if (!readsStatus || (_state.value.attentionStates.isEmpty() && !retriesStatus)) break
                delay(statusPollMillis)
            } while (listening && wire === current)
        }
    }

    fun search(query: String, debounceMillis: Long = 350) {
        val normalized = query.trim().lowercase()
        val repeated = normalized == _state.value.query
        searchJob?.cancel()
        val serial = ++searchSerial
        _state.update { it.copy(query = normalized, searchResults = if (repeated) it.searchResults else emptyList(),
            searchSnippets = if (repeated) it.searchSnippets else emptyMap(), searchErrorMessage = null, isSearching = false) }
        if (normalized.isEmpty()) { searchAwaitsConnect = false; return }
        if (!_state.value.isLive || _state.value.isViewingCachedData) { searchAwaitsConnect = true; return }
        searchJob = viewModelScope.launch {
            delay(debounceMillis)
            val current = wire ?: return@launch
            val profiles = listedProfiles
            _state.update { it.copy(isSearching = true) }
            try {
                do {
                    val writes = searchWrites
                    val results = mutableListOf<Pair<String, HermesSessionSearchResult>>()
                    for (profile in profiles) {
                        results += current.search(normalized, profile).map { profile to it }
                        if (!archived) results += current.search(normalized, profile, true).filter { it.row.archived != true }.map { profile to it }
                    }
                    if (serial != searchSerial || wire !== current || profiles != listedProfiles) return@launch
                    if (writes != searchWrites) continue
                    val kept = results.filter { !it.second.row.isRoomSession }
                    _state.update { it.copy(searchResults = kept.map { match -> HermesListedSession(match.first, match.second.row) },
                        searchSnippets = kept.mapNotNull { (profile, result) -> result.snippet?.let { "$profile|${result.row.identity}" to it } }.toMap()) }
                    break
                } while (isActive)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Throwable) { if (serial == searchSerial) {
                if (error == BotFailure.Stale) searchAwaitsConnect = true else _state.update { it.copy(searchErrorMessage = error.message) }
            } }
            finally { if (serial == searchSerial) _state.update { it.copy(isSearching = false) } }
        }
    }

    fun beginViewing(row: HermesListedSession) { setUnread(row, false) }
    fun noteReturn(row: HermesListedSession) { returnedFrom += row.key; if (listening) requestReload() }
    fun toggleUnread(row: HermesListedSession) { setUnread(row, !_state.value.isUnread(row)) }
    private fun setUnread(row: HermesListedSession, unread: Boolean) {
        if (!_state.value.isLive || _state.value.isViewingCachedData || row.row.isBotChat) return
        val current = wire ?: return
        val mark = ReadMark(++markSequence, unread)
        marks[row.key] = mark
        _state.update { it.copy(unreadMarks = marks.mapValues { mark -> mark.value.unread }) }
        val earlier = unreadWrites[row.key]
        unreadWrites[row.key] = viewModelScope.launch {
            earlier?.join()
            val written = try { current.update(HermesSessionChange.Unread(unread), row.row.id, row.profile); true }
            catch (cancel: CancellationException) { throw cancel } catch (_: Throwable) { false }
            if (marks[row.key]?.sequence != mark.sequence) return@launch
            if (written) marks[row.key]?.settledBefore = readSerial + 1 else marks.remove(row.key)
            _state.update { it.copy(unreadMarks = marks.mapValues { mark -> mark.value.unread }) }
        }
    }
    suspend fun setPinned(row: HermesListedSession, pinned: Boolean) = change(row, HermesSessionChange.Pinned(pinned))
    suspend fun archive(row: HermesListedSession) = change(row, HermesSessionChange.Archived(true))
    suspend fun unarchive(row: HermesListedSession) = change(row, HermesSessionChange.Archived(false))
    private suspend fun change(row: HermesListedSession, change: HermesSessionChange): Boolean {
        if (!beginMutation(row)) return false
        val current = wire
        val profiles = listedProfiles
        val target = if (row.summary().isCronSession && !archived) scheduled else pages
        val before = target[row.profile].apply(change, row.row.id)
        publishPages()
        return try {
            when (change) {
                is HermesSessionChange.Pinned -> source.setPinned(change.flag, row.summary())
                is HermesSessionChange.Archived -> source.setArchived(change.flag, row.summary())
                else -> error("Not a row toggle")
            }
            if (change == HermesSessionChange.Archived(true)) forgetCached(row)
            applySearchChange(row, change)
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) {
            if (error != BotFailure.Stale && profiles == listedProfiles && wire === current && before != null) {
                target[row.profile].restore(before.first, before.second); publishPages()
            }
            if (error != BotFailure.Stale) actionFailure(error)
            false
        } finally { endMutation(row); requestReload() }
    }
    suspend fun delete(row: HermesListedSession): Boolean {
        if (!beginMutation(row)) return false
        return try {
            val refusal = source.delete(row.summary())
            if (refusal != null) { _state.update { it.copy(actionErrorMessage = refusal) }; false }
            else {
                forgetCached(row)
                pages[row.profile].remove(row.row.id); scheduled[row.profile].remove(row.row.id)
                publishPages(); applySearchChange(row, null)
                true
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { actionFailure(error); false }
        finally { endMutation(row); requestReload() }
    }
    suspend fun rename(row: HermesListedSession, title: String): Boolean {
        if (!beginMutation(row)) return false
        _state.update { it.copy(isRenamingSession = true, renameErrorMessage = null) }
        return try {
            val response = source.rename(row.summary(), title.trim())
            val change = HermesSessionChange.Title(response.session?.title ?: title.trim())
            pages[row.profile].apply(change, row.row.id); scheduled[row.profile].apply(change, row.row.id)
            publishPages(); applySearchChange(row, change)
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { if (error != BotFailure.Stale) _state.update { it.copy(renameErrorMessage = advice(error)) }; false }
        finally { _state.update { it.copy(isRenamingSession = false) }; endMutation(row); requestReload() }
    }
    suspend fun duplicate(row: HermesListedSession): HermesListedSession? {
        if (!beginMutation(row)) return null
        return try {
            val result = source.duplicate(row.summary(), row.displayTitle)
            val copy = result.session ?: throw IllegalStateException(result.errorMessage ?: "The server did not return the duplicated session.")
            HermesListedSession(row.profile, HermesSessionRow(requireNotNull(copy.sessionId), title = copy.title, profile = row.profile))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { actionFailure(error); null }
        finally { endMutation(row); requestReload() }
    }
    suspend fun export(row: HermesListedSession): SessionExportFile? {
        if (!beginMutation(row, permitsBot = true)) return null
        return try { source.export(row.summary().copy(title = row.displayTitle), SessionExportFormat.json) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { actionFailure(error); null }
        finally { endMutation(row) }
    }
    private fun beginMutation(row: HermesListedSession, permitsBot: Boolean = false): Boolean {
        val state = _state.value
        if (!state.isLive || state.isViewingCachedData || row.key in state.mutating || (!permitsBot && (row.row.isBotChat || row.summary().isSessionReadOnly))) return false
        readSerial++
        _state.update { it.copy(mutating = it.mutating + row.key, actionErrorMessage = null) }
        return true
    }
    private fun endMutation(row: HermesListedSession) {
        _state.update { it.copy(mutating = it.mutating - row.key) }
        if (reloadWanted && _state.value.mutating.isEmpty()) requestReload()
    }
    private fun applySearchChange(row: HermesListedSession, change: HermesSessionChange?) {
        searchWrites++
        _state.update { state -> state.copy(searchResults = state.searchResults.mapNotNull { match ->
            if (match.key != row.key) match else when (change) {
                null -> null
                is HermesSessionChange.Pinned -> match.copy(row = match.row.copy(pinned = change.flag))
                is HermesSessionChange.Archived -> match.copy(row = match.row.copy(archived = change.flag))
                is HermesSessionChange.Title -> match.copy(row = match.row.copy(title = change.title))
                is HermesSessionChange.Unread -> match
            }
        }) }
    }
    private suspend fun forgetCached(row: HermesListedSession) {
        withContext(ioDispatcher) { cache?.removeHermesSession(row.row.identity, row.profile, serverId) }
    }

    suspend fun fillLane(projectId: String) {
        while (_state.value.hasMore && _state.value.isLive && _state.value.query.isBlank() && laneIsShort(projectId)) {
            val before = pages.profiles.sumOf { pages[it].nextOffset }
            loadMore()
            if (pages.profiles.sumOf { pages[it].nextOffset } <= before) return
        }
    }
    fun laneIsShort(projectId: String): Boolean {
        val claimed = _state.value.projects.firstOrNull { it.projectId == projectId }?.claimedCount ?: return false
        return _state.value.sessions.count { _state.value.projectOwners[it.row.id] == projectId } < claimed
    }
    suspend fun completeFolder(word: String): HermesFolderCompletion.Suggestions {
        val current = wire ?: return HermesFolderCompletion.Suggestions()
        val profile = _state.value.profile ?: return HermesFolderCompletion.Suggestions()
        if (!HermesFolderCompletion.completes(word)) return HermesFolderCompletion.Suggestions()
        return HermesFolderCompletion.suggestions(current.call(HermesCall.CompleteFolder(word, profile)), word)
    }
    suspend fun createProject(name: String, color: String, folder: String): String? {
        val normalizedFolder = folder.trim().let { if (it == "/") it else it.trimEnd('/') }
        return if (projectWrite { profile -> HermesCall.ProjectsCreate(profile, name.trim(), normalizedFolder, color) }) normalizedFolder else null
    }
    suspend fun renameProject(project: HermesProject, name: String, color: String?): Boolean =
        !project.isAutomatic && projectWrite { HermesCall.ProjectsUpdate(it, project.projectId, name.trim(), color?.takeIf(String::isNotEmpty)) }
    suspend fun deleteProject(project: HermesProject): Boolean =
        !project.isAutomatic && projectWrite { HermesCall.ProjectsDelete(it, project.projectId) }
    private suspend fun projectWrite(call: (String) -> HermesCall): Boolean {
        val current = wire ?: return false
        val profile = _state.value.profile ?: return false
        if (!_state.value.isLive || _state.value.isSavingProject) return false
        _state.update { it.copy(isSavingProject = true, projectErrorMessage = null) }
        return try { current.call(call(profile)); readProjects(current, readSerial); true }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { _state.update { it.copy(projectErrorMessage = advice(error)) }; false }
        finally { _state.update { it.copy(isSavingProject = false) } }
    }
    suspend fun refreshProjects() { wire?.let { readProjects(it, readSerial) } }
    private suspend fun readProjects(current: HermesSessionWire, serial: Int) {
        val profile = _state.value.profile ?: return
        val revision = ++projectsSerial
        _state.update { it.copy(isLoadingProjects = true) }
        try {
            val tree = HermesProjectTree(current.call(HermesCall.ProjectsTree(profile)))
            if (serial == readSerial && revision == projectsSerial && wire === current && profile == _state.value.profile) {
                _state.update { it.copy(projects = tree.projects, projectOwners = tree.owners) }
            }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Throwable) { /* A failed lane read keeps the last lanes. */ }
        finally { if (revision == projectsSerial) _state.update { it.copy(isLoadingProjects = false) } }
    }
    suspend fun move(row: HermesListedSession, folder: String): String? {
        if (!beginMutation(row)) return null
        val old = _state.value.sessions.firstOrNull { it.key == row.key }?.row?.cwd ?: row.row.cwd
        return try {
            wire!!.call(HermesCall.SessionWorkspaceMove(row.profile, row.row.id, folder))
            old?.takeIf { it.isNotEmpty() && it != folder }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) {
            if (error is BotSettingFailure.Rejected && error.code == 4017) _state.update { it.copy(actionErrorMessage = "Hermes can't find the folder $folder, so the session didn't move.") }
            else if (error != BotFailure.Stale) actionFailure(error)
            null
        } finally { endMutation(row); requestReload() }
    }
    fun clearActionError() { _state.update { it.copy(actionErrorMessage = null) } }
    fun clearRenameError() { _state.update { it.copy(renameErrorMessage = null) } }
    fun clearProjectError() { _state.update { it.copy(projectErrorMessage = null) } }
    private fun actionFailure(error: Throwable) { _state.update { it.copy(actionErrorMessage = advice(error)) } }
    private fun advice(error: Throwable): String = if (error is HermesSessionRefusal) error.message else BotConnectionAdvice.message(error, address)
}
