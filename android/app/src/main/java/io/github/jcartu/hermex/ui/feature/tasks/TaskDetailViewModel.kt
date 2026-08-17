package io.github.jcartu.hermex.ui.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.CronOutputItem
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Detail state for a single cron job. Port of
 * `Features/Tasks/TaskDetailViewModel.swift`.
 *
 * The job header/metadata render from the already-loaded
 * [io.github.jcartu.hermex.model.CronJob] held by the list; mutations flow
 * back through [TaskDetailViewModel.onMutation] (the Compose equivalent of the
 * Swift `lastMutation` → `onMutation` forwarding) so the list stays the single
 * source of truth across panes.
 */
data class TaskDetailUiState(
    val outputs: List<CronOutputItem> = emptyList(),
    /** Server-provided deliver targets; `null` keeps the editor's free-text fallback. */
    val deliveryOptions: List<CronDeliveryOption>? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
)

class TaskDetailViewModel(
    private val api: HermesApi,
    private val jobId: String?,
    private val onMutation: (CronJobListMutation) -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Loads the recent output (limit 5) and refreshes the delivery options.
     * Options failure never breaks the detail view, mirroring the Swift `try?`.
     */
    fun load() {
        val id = jobId
        if (id == null) {
            // Mirrors the Swift guard: jobs without an id cannot fetch output.
            _uiState.update { it.copy(errorMessage = "Missing job identifier.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            coroutineScope {
                val optionsDeferred = async {
                    try {
                        api.cronDeliveryOptions()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                }
                try {
                    val response = api.cronOutput(id, limit = 5)
                    _uiState.update { it.copy(outputs = response.outputs ?: emptyList()) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Could not load output.")
                    }
                }
                val options = optionsDeferred.await()?.platforms
                _uiState.update { it.copy(deliveryOptions = options, isLoading = false) }
            }
        }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    /** Runs the job immediately. Caller marks the job as running in the list on success. */
    suspend fun runNow(): Boolean = mutateJob { api.runCron(it) }

    suspend fun pause(reason: String? = null): Boolean = mutateJob { api.pauseCron(it, reason) }

    suspend fun resume(): Boolean = mutateJob { api.resumeCron(it) }

    /**
     * Saves the edited draft. Port of Swift `update(from:)` — note the Swift
     * update path sends trimmed-but-possibly-empty strings (unlike create,
     * which nils empties); ported exactly.
     */
    suspend fun update(draft: CronJobEditorDraft): Boolean {
        draft.validationMessage?.let { message ->
            _uiState.update { it.copy(actionErrorMessage = message) }
            return false
        }

        return mutateJob { id ->
            api.updateCron(
                jobId = id,
                prompt = draft.trimmedPrompt,
                schedule = draft.trimmedSchedule,
                name = draft.name.trim(),
                deliver = draft.deliver.trim(),
                skills = draft.skills,
                model = draft.model.trim(),
                provider = draft.provider.trim(),
                profile = draft.profile.trim(),
                toastNotifications = draft.toastNotifications,
            )
        }
    }

    /** Deletes the job; forwards [CronJobListMutation.Delete] on success. */
    suspend fun delete(): Boolean {
        val id = jobId
        if (id == null) {
            _uiState.update { it.copy(actionErrorMessage = "Missing job identifier.") }
            return false
        }

        _uiState.update { it.copy(isMutating = true, actionErrorMessage = null) }
        return try {
            val response = api.deleteCron(id)
            if (response.ok == false) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        actionErrorMessage = response.error ?: "Could not delete task.",
                    )
                }
                return false
            }
            _uiState.update { it.copy(isMutating = false) }
            onMutation(CronJobListMutation.Delete(id))
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isMutating = false,
                    actionErrorMessage = error.message ?: "Could not delete task.",
                )
            }
            false
        }
    }

    /** Port of the Swift `mutateJob` helper shared by run/pause/resume/update. */
    private suspend fun mutateJob(
        action: suspend (String) -> CronMutationResponse,
    ): Boolean {
        val id = jobId
        if (id == null) {
            _uiState.update { it.copy(actionErrorMessage = "Missing job identifier.") }
            return false
        }

        _uiState.update { it.copy(isMutating = true, actionErrorMessage = null) }
        return try {
            val response = action(id)
            if (response.ok == false) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        actionErrorMessage = response.error ?: "Could not update task.",
                    )
                }
                return false
            }
            _uiState.update { it.copy(isMutating = false) }
            response.job?.let { onMutation(CronJobListMutation.Upsert(it)) }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isMutating = false,
                    actionErrorMessage = error.message ?: "Could not update task.",
                )
            }
            false
        }
    }
}
