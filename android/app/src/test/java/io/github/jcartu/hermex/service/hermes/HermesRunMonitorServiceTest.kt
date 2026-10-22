package io.github.jcartu.hermex.service.hermes

import io.github.jcartu.hermex.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HermesRunMonitorServiceTest {
    private val target = HermesRunTarget(
        serverId = "https://source.example",
        connectionId = "connection-a",
        profile = "Research Profile",
        storedKey = "stored-session",
        title = "Investigate the build",
    )

    @Test
    fun `ownership key binds server connection profile and stored session but not presentation title`() {
        val key = target.ownershipKey()

        assertEquals(key, target.copy(title = "Renamed while running").ownershipKey())
        assertNotEquals(key, target.copy(serverId = "https://other.example").ownershipKey())
        assertNotEquals(key, target.copy(connectionId = "connection-b").ownershipKey())
        assertNotEquals(key, target.copy(profile = "Default").ownershipKey())
        assertNotEquals(key, target.copy(storedKey = "other-session").ownershipKey())
    }

    @Test
    fun `background handoff requires an accepted keyed owner with work or a prompt`() {
        assertNull(
            hermesRunTargetForBackground(
                serverId = target.serverId,
                connectionId = target.connectionId,
                profile = target.profile,
                storedKey = null,
                title = target.title,
                hasActiveRun = true,
                hasPendingPrompt = false,
            ),
        )
        assertNull(
            hermesRunTargetForBackground(
                serverId = target.serverId,
                connectionId = target.connectionId,
                profile = target.profile,
                storedKey = target.storedKey,
                title = target.title,
                hasActiveRun = false,
                hasPendingPrompt = false,
            ),
        )

        val running = hermesRunTargetForBackground(
            serverId = target.serverId,
            connectionId = target.connectionId,
            profile = target.profile,
            storedKey = target.storedKey,
            title = target.title,
            hasActiveRun = true,
            hasPendingPrompt = false,
        )
        val waiting = hermesRunTargetForBackground(
            serverId = target.serverId,
            connectionId = target.connectionId,
            profile = target.profile,
            storedKey = target.storedKey,
            title = target.title,
            hasActiveRun = false,
            hasPendingPrompt = true,
        )

        assertEquals(target, running)
        assertEquals(target, waiting)
        assertNotNull(running)
        assertNotNull(waiting)
    }

    @Test
    fun `foreground stop is observation only while cancel is the sole host interrupt action`() {
        assertEquals(
            HermesRunMonitorCommand.STOP_OBSERVING,
            HermesRunMonitorActions.command(HermesRunMonitorActions.STOP),
        )
        assertEquals(
            HermesRunMonitorCommand.INTERRUPT_HOST,
            HermesRunMonitorActions.command(HermesRunMonitorActions.CANCEL),
        )
        assertNotEquals(
            HermesRunMonitorActions.command(HermesRunMonitorActions.STOP),
            HermesRunMonitorActions.command(HermesRunMonitorActions.CANCEL),
        )
    }

    @Test
    fun `notification routing keeps captured source stored key and exact profile`() {
        val route = target.notificationTarget(ServerKind.HERMES)

        assertEquals(target.serverId, route.serverId)
        assertEquals(target.storedKey, route.sessionId)
        assertEquals("Research Profile", route.hermesProfile)
        assertEquals(ServerKind.HERMES, route.serverKind)

        val webuiRoute = target.notificationTarget(ServerKind.WEBUI)
        assertEquals("Research Profile", webuiRoute.hermesProfile)
        assertEquals(ServerKind.WEBUI, webuiRoute.serverKind)
        assertNotEquals(route, webuiRoute)
    }
}
