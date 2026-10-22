package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import io.github.jcartu.hermex.net.hermes.HermesCall

/**
 * Port of `Features/Bots/BotRoomAction.swift`. The complete participant/task/generation/request
 * tuple is the identity revalidated at dispatch. Unknown or incomplete actions remain visible
 * Desktop attention and can never be partially addressed by Android.
 */
data class BotRoomAction(
    val id: Identity,
    val approval: BotApprovalRequest?,
) {
    data class Identity(
        val kind: String,
        val member: String?,
        val task: String?,
        val generation: Int?,
        val request: String?,
    )

    val isRetry: Boolean get() = id.kind == "retry" && id.task?.let(BotRoomRPC::validID) == true
    val isAnswerable: Boolean get() = approval != null || isRetry

    /** A retry without a choice, or an approval with exactly one host-offered room choice. */
    fun call(roomID: String, choice: BotApprovalChoice? = null): HermesCall? {
        val task = id.task ?: return null
        if (isRetry && choice == null) return HermesCall.GroupsRetry(roomID, task)
        val request = approval ?: return null
        if (choice == null || choice !in request.choices) return null
        val member = id.member ?: return null
        val generation = id.generation ?: return null
        val requestID = id.request ?: return null
        return HermesCall.GroupsApprove(
            roomID = roomID,
            memberID = member,
            taskID = task,
            executionGeneration = generation,
            requestID = requestID,
            choice = choice,
        )
    }

    companion object {
        fun from(value: BotJSON): BotRoomAction {
            val identity = Identity(
                kind = value["kind"].text.orEmpty(),
                member = value["member_id"].text,
                task = value["task_id"].text,
                generation = value["execution_generation"].integer,
                request = value["request_id"].text,
            )
            val approval = if (
                identity.kind == "approval" &&
                identity.member?.let(BotRoomRPC::validID) == true &&
                identity.task?.let(BotRoomRPC::validID) == true &&
                (identity.generation ?: 0) > 0 &&
                identity.request?.let(BotRoomRPC::validID) == true
            ) {
                value["approval"].fields?.let { received ->
                    val body = LinkedHashMap(received)
                    body["request_id"] = BotJSON.string(identity.request!!)
                    if (!received.containsKey("choices")) {
                        body["choices"] = BotJSON.array(
                            listOf(BotApprovalChoice.ONCE, BotApprovalChoice.DENY).map { BotJSON.string(it.rawValue) },
                        )
                    } else {
                        received["choices"]?.list?.let { offered ->
                            body["choices"] = BotJSON.array(
                                offered.filter { row -> row.text == BotApprovalChoice.ONCE.rawValue || row.text == BotApprovalChoice.DENY.rawValue },
                            )
                        }
                    }
                    BotApprovalRequest.from(BotJSON.objectOf(body))
                }
            } else {
                null
            }
            return BotRoomAction(identity, approval)
        }
    }
}
