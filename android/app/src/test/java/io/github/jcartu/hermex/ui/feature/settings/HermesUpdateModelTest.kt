package io.github.jcartu.hermex.ui.feature.settings

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.HermesUpdateCheck
import io.github.jcartu.hermex.model.hermes.HermesUpdateReceipt
import io.github.jcartu.hermex.model.hermes.HermesUpdateStart
import io.github.jcartu.hermex.model.hermes.HermesUpdateStatus
import io.github.jcartu.hermex.net.hermes.HermesUpdateClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesUpdateModelTest {
    private fun receipt(start: String, version: String = "0.22.3") = HermesUpdateReceipt(HermesUpdateReceipt.Outcome.Success, start, version)

    @Test fun `old receipt and other process exit never finish this update`() {
        val old = receipt("old")
        var machine = HermesUpdateMachine.started(old, 42, 0)
        machine = machine.handle(HermesUpdateMachine.Event.Status(HermesUpdateStatus(false, 0, 13, receipt = old)), 3_000)
        assertEquals(HermesUpdateMachine.Read.STATUS, machine.nextRead)
        assertFalse(machine.isSettled)
        machine = machine.handle(HermesUpdateMachine.Event.Status(HermesUpdateStatus(false, 1, 42, receipt = receipt("new"))), 6_000)
        assertEquals(HermesUpdateMachine.Read.HEALTH, machine.nextRead)
        machine = machine.handle(HermesUpdateMachine.Event.Health("0.22.3"), 9_000)
        assertEquals(HermesUpdateMachine.State.Done("0.22.3"), machine.state)
    }

    @Test fun `restart wait excludes unobserved background time and uses explicit receipt fallback`() {
        var machine = HermesUpdateMachine.started(null, 42, 0)
        machine = machine.handle(HermesUpdateMachine.Event.StatusMissing, 3_000)
        assertEquals(HermesUpdateMachine.Read.RECEIPT, machine.nextRead)
        machine = machine.handle(HermesUpdateMachine.Event.Unreachable, 3_600_000)
        assertEquals(HermesUpdateMachine.State.Recovering, machine.state)
        machine = machine.handle(HermesUpdateMachine.Event.Receipt(receipt("new")), 3_603_000)
        assertEquals(HermesUpdateMachine.Read.HEALTH, machine.nextRead)
        machine = machine.handle(HermesUpdateMachine.Event.Health("0.22.2"), 3_606_000)
        assertFalse(machine.isSettled)
        machine = machine.handle(HermesUpdateMachine.Event.Health("0.22.3"), 3_609_000)
        assertTrue(machine.isSettled)
    }

    @Test fun `check start restart wait receipt health updates version and reconnects once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val client = mockk<HermesUpdateClient>()
            val calls = mutableListOf<String>()
            var statusCalls = 0
            coEvery { client.check(false) } coAnswers { calls += "check"; HermesUpdateCheck(behind = 2, updateAvailable = true) }
            coEvery { client.start() } coAnswers { calls += "start"; HermesUpdateStart.Started(42) }
            coEvery { client.status() } coAnswers {
                calls += "status"
                when (++statusCalls) {
                    1, 2 -> HermesUpdateStatus(false, receipt = receipt("old", "0.22.2"))
                    3 -> throw BotFailure.Transport
                    else -> throw BotFailure.Rejected(404)
                }
            }
            coEvery { client.receipt() } coAnswers { calls += "receipt"; receipt("new") }
            coEvery { client.health() } coAnswers { calls += "health"; "0.22.3" }
            val versions = mutableListOf<String>()
            var reconnects = 0
            val delays = mutableListOf<Long>()
            var time = 0L
            val model = HermesUpdateModel("https://hermes.example", this, { client }, { versions += it }, { reconnects++ },
                sleep = { delays += it; time += it }, now = { time })
            model.appear()
            model.apply()
            advanceUntilIdle()
            assertEquals(listOf("0.22.3"), versions)
            assertEquals(1, reconnects)
            assertTrue(delays.isNotEmpty())
            assertTrue(calls.indexOf("check") < calls.indexOf("start"))
            assertTrue(calls.indexOf("start") < calls.indexOf("receipt"))
            assertEquals("health", calls.last())
            coVerify(exactly = 1) { client.start() }
            assertEquals(HermesUpdateMachine.State.Done("0.22.3"), (model.state.value.run as HermesUpdateUiState.Run.Following).machine.state)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `ambiguous start is reconciled rather than posted twice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val client = mockk<HermesUpdateClient>()
            var reads = 0
            coEvery { client.status() } coAnswers {
                if (++reads == 1) HermesUpdateStatus(false)
                else HermesUpdateStatus(false, pid = 42, receipt = receipt("new"))
            }
            coEvery { client.start() } throws BotFailure.Transport
            coEvery { client.health() } returns "0.22.3"
            var time = 0L
            val model = HermesUpdateModel("https://hermes.example", this, { client }, {}, {}, sleep = { time += it }, now = { time })
            model.apply(); advanceUntilIdle()
            coVerify(exactly = 1) { client.start() }
            assertTrue((model.state.value.run as HermesUpdateUiState.Run.Following).machine.state is HermesUpdateMachine.State.Done)
        } finally { Dispatchers.resetMain() }
    }
}
