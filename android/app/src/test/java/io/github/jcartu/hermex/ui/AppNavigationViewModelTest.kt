package io.github.jcartu.hermex.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationViewModelTest {

    @Test
    fun `selected session survives ViewModel recreation`() {
        val handle = SavedStateHandle()
        AppNavigationViewModel(handle).selectSession("s1")

        val restored = AppNavigationViewModel(handle)
        assertEquals("s1", restored.selectedSessionId)
        assertFalse(restored.isNewChat)
    }

    @Test
    fun `destination survives recreation`() {
        val handle = SavedStateHandle()
        AppNavigationViewModel(handle).selectDestination(HermexDestination.FILES)

        assertEquals(HermexDestination.FILES, AppNavigationViewModel(handle).selectedDestination)
    }

    @Test
    fun `new chat and return to list are exclusive`() {
        val vm = AppNavigationViewModel(SavedStateHandle())
        vm.startNewChat()
        assertTrue(vm.isNewChat)
        assertNull(vm.selectedSessionId)

        vm.showSessionList()
        assertFalse(vm.isNewChat)
        assertNull(vm.selectedSessionId)
    }
}
