package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronJob

/**
 * One row of Task Detail's Configuration card.
 * Port of `Features/Tasks/TaskConfigurationFields.swift`.
 */
data class TaskConfigurationField(
    /** The label. Unique within a card, so it is also the row's identity. */
    val title: String,
    val value: String,
    /** A dimmer second line under the value — the raw cron expression under its English sentence. */
    val detail: String? = null,
) {
    companion object {
        /**
         * The Configuration card's rows, in reading order.
         *
         * A field the job does not have does not get a row. Schedule and Deliver
         * are the exceptions, because every job has both: Deliver falls back to
         * `local`, which is the server's own default rather than a stand-in for
         * a missing value.
         */
        fun fields(job: CronJob): List<TaskConfigurationField> {
            val fields = mutableListOf<TaskConfigurationField>()

            val schedule = job.scheduleDescription?.sentence
                ?: job.scheduleText
                ?: "Not available"
            fields += TaskConfigurationField(
                title = "Schedule",
                value = schedule,
                // Only worth a second line when it is not already the value.
                detail = job.editableScheduleText?.takeIf { it != schedule },
            )

            fields += TaskConfigurationField(
                title = "Deliver",
                value = trimmed(job.deliver) ?: "local",
            )

            trimmed(job.model)?.let {
                fields += TaskConfigurationField(title = "Model", value = it)
            }

            trimmed(job.provider)?.let {
                fields += TaskConfigurationField(title = "Provider", value = it)
            }

            trimmed(job.profile)?.let {
                fields += TaskConfigurationField(title = "Profile", value = it)
            }

            val skills = (job.skills ?: emptyList()).mapNotNull(::trimmed)
            if (skills.isNotEmpty()) {
                fields += TaskConfigurationField(
                    title = "Skills",
                    value = skills.joinToString(", "),
                )
            }

            job.toastNotifications?.let {
                fields += TaskConfigurationField(
                    title = "Notifications",
                    value = if (it) "On" else "Off",
                )
            }

            return fields
        }

        private fun trimmed(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }
    }
}
