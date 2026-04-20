package io.qent.broxy.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainWindowLogicTest {
    @Test
    fun `should redirect to servers for a new agentic install popup request id`() {
        assertTrue(
            shouldRedirectToServersForAgenticInstallPopup(
                popupRequestId = 7L,
                lastHandledPopupRequestId = 6L,
            ),
        )
    }

    @Test
    fun `should not redirect when there is no agentic install popup`() {
        assertFalse(
            shouldRedirectToServersForAgenticInstallPopup(
                popupRequestId = null,
                lastHandledPopupRequestId = 6L,
            ),
        )
    }

    @Test
    fun `should not redirect for already handled agentic install popup request id`() {
        assertFalse(
            shouldRedirectToServersForAgenticInstallPopup(
                popupRequestId = 7L,
                lastHandledPopupRequestId = 7L,
            ),
        )
    }
}
