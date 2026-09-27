package com.attentionguard.app.capture

import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.Msg
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HistorySessionTest {
    private val range = HistoryRange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20))
    private fun session(auto: Boolean = true) = HistorySession(HistoryConfig("group", range, auto))
    private fun page(text: String = "message", day: String? = "2026-09-18", title: String = "group") = ChatSnapshot(title, listOf(Msg("other", text, date = day)))

    @Test fun requiresExactConversationAndExplicitStart() {
        val s = session()
        assertFalse(s.canScroll(0)); assertFalse(s.start("other", 0)); assertTrue(s.start("group", 0))
        assertTrue(s.canScroll(4000)); assertFalse(s.start("group", 5000))
    }
    @Test fun manualModeNeverAutomaticallyScrolls() {
        val s = session(false); s.start("group", 0); s.observe(page())
        assertFalse(s.canScroll(4000)); assertEquals(1, s.screens)
    }
    @Test fun switchesPauseAndRequireAnotherExplicitConfirmation() {
        val s = session(); s.start("group", 0); s.observe(page(title = "another"))
        assertEquals(HistoryState.PAUSED, s.state); assertFalse(s.canScroll(4000))
        assertTrue(s.start("group", 5000)); s.cancel(); assertFalse(s.start("group", 6000))
    }
    @Test fun stallsStopAndNeverClaimCompleteHistory() {
        val s = session(); s.start("group", 0); s.observe(page())
        repeat(3) { assertTrue(s.canScroll((it + 1) * 4000L)) }
        assertFalse(s.canScroll(16000)); assertEquals(HistoryState.PAUSED, s.state)
        assertTrue(s.reason.contains("未发现新页面"))
    }
    @Test fun dateBoundaryRequiresEveryVisibleMessageToHaveAnOlderDay() {
        val s = session(); s.start("group", 0)
        s.observe(ChatSnapshot("group", listOf(Msg("other", "older", date = "2026-09-09"), Msg("other", "unknown"))))
        assertEquals(HistoryState.RUNNING, s.state)
        s.observe(page("known older", "2026-09-09")); assertEquals(HistoryState.FINISHED, s.state)
        assertFalse(s.canScroll(10000))
    }
    @Test fun unknownDatesRemainOutsideAnyClaimOfConfirmedRange() {
        assertTrue(range.includes(null)); assertTrue(range.includes("2026-09-10")); assertTrue(range.includes("2026-09-20"))
        assertFalse(range.includes("2026-09-21")); assertFalse(range.includes("2026-09-09"))
    }
    @Test fun identicalScreensDoNotInflateProgressAndGapsAreVisible() {
        val s = session(); s.start("group", 0); s.observe(page()); s.observe(page())
        assertEquals(1, s.screens)
        s.observe(page("disconnected"), true); assertEquals(1, s.gaps); assertTrue(s.summary().contains("可能有遗漏或重复"))
    }
    @Test fun sessionDurationAndScreenLimitsPause() {
        val timed = session(); timed.start("group", 0); timed.observe(page())
        assertFalse(timed.canScroll(HistorySession.MAX_DURATION)); assertEquals(HistoryState.PAUSED, timed.state)
        val capped = session(); capped.start("group", 0)
        repeat(200) { capped.observe(page("message $it")) }
        assertEquals(HistoryState.PAUSED, capped.state)
        assertFalse(capped.start("group", 3000))
        assertFalse(timed.start("group", HistorySession.MAX_DURATION + 1))
    }

    @Test fun pausingAndResumingDoesNotResetTheSessionTimeBudget() {
        val s = session(); s.start("group", 0); s.observe(page())
        s.pause("user"); assertTrue(s.start("group", 600_000))
        assertFalse(s.canScroll(HistorySession.MAX_DURATION))
    }
}
