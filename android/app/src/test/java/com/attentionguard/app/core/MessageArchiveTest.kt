package com.attentionguard.app.core

import com.attentionguard.app.capture.HistoryRange
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MessageArchiveTest {
    private val context = RuntimeEnvironment.getApplication()
    private val archive = MessageArchive(context)
    private fun messages(vararg text: String) = text.map { Msg("other", it) }
    private fun screen(vararg text: String) = ChatSnapshot("group", messages(*text), "com.tencent.mm")
    @After fun cleanup() { archive.close() }

    @Test fun ordinaryMessagesPersistWithoutProducingEvents() {
        archive.append(screen("hello"), "live")
        assertEquals(1, archive.count().total); assertEquals("hello", archive.recent().single().message.text)
        assertTrue(EventStore(context).load().isEmpty())
    }
    @Test fun overlapWorksForwardAndBackwardWithoutGlobalTextDeduplication() {
        archive.append(screen("a", "b", "c"), "live")
        assertEquals(1, archive.append(screen("b", "c", "d"), "live").added)
        assertEquals(4, archive.count().total)
        assertEquals(1, archive.append(screen("a", "b", "c"), "live").added)
        // Only neighboring screens carry proven identity, not all historical text.
        assertEquals(5, archive.count().total)
        val history = "backwards"
        archive.append(screen("c", "d", "e"), history)
        assertEquals(2, archive.append(screen("a", "b", "c", "d"), history).added)
        assertEquals(5, archive.count(history).total)
    }
    @Test fun repeatedTextWithinOneScreenIsRetained() {
        archive.append(screen("OK", "OK", "new"), "live")
        assertEquals(3, archive.count().total)
        assertEquals(0, archive.append(screen("OK", "OK", "new"), "live").added)
        assertEquals(2, archive.recent().count { it.message.text == "OK" })
    }
    @Test fun ambiguousSingleTextOverlapRetainsRatherThanDropsMessages() {
        val result = ScreenOverlap.match(messages("old", "OK"), messages("OK", "new"))
        assertTrue(result.isEmpty())
        archive.append(screen("old", "OK"), "live")
        assertTrue(archive.append(screen("OK", "new"), "live").gap)
        assertEquals(4, archive.count().total)
    }
    @Test fun rangeFiltersConfirmedOutsideDaysAndKeepsUnknownSeparate() {
        val range = HistoryRange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20))
        val msgs = listOf(Msg("other", "before", date = "2026-09-09"), Msg("other", "inside", date = "2026-09-15"), Msg("other", "unknown"), Msg("other", "after", date = "2026-09-21"))
        archive.append(ChatSnapshot("group", msgs), "history", range)
        assertEquals(ArchiveCount(2, 1), archive.count("history"))
        assertEquals(setOf("inside", "unknown"), archive.recent().map { it.message.text }.toSet())
    }
    @Test fun laterDateEvidenceCanRemoveOutOfRangeUnknownWithoutASecondCopy() {
        val range = HistoryRange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20))
        archive.append(screen("a", "b"), "history", range)
        archive.append(ChatSnapshot("group", messages("a", "b").map { it.copy(date = "2026-09-09") }), "history", range)
        assertEquals(0, archive.count().total)
    }
    @Test fun sessionsAndGroupsRemainIsolatedAndReopenIsDurable() {
        archive.append(screen("a"), "run1")
        archive.append(ChatSnapshot("other group", messages("b")), "run2")
        MessageArchive(context).use { reopened ->
            assertEquals(2, reopened.count().total)
            assertEquals("a", reopened.recent(stream = "run1").single().message.text)
            assertEquals("b", reopened.recent(group = "other group").single().message.text)
        }
    }
    @Test fun pagingNeverRepeatsIdsAndOcrIsMarked() {
        archive.append(ChatSnapshot("group", (1..65).map { Msg("other", "text $it", captureMethod = "ocr") }), "live")
        val first = archive.recent()
        val second = archive.recent(beforeId = first.last().id)
        assertEquals(50, first.size); assertEquals(15, second.size)
        assertEquals(65, (first + second).map { it.id }.distinct().size)
        assertTrue((first + second).all { it.message.captureMethod == "ocr" })
    }
    @Test fun clearingFromAnotherOwnerInvalidatesTheLiveScreenCache() {
        archive.append(screen("a", "b"), "live")
        MessageArchive(context).use { it.clear() }
        assertEquals(2, archive.append(screen("a", "b"), "live").added)
        assertEquals(2, archive.count().total)
    }

    @Test fun cancelledCaptureCannotRepopulateClearedMessages() {
        archive.append(screen("old"), "live")
        MessageArchive(context).use { it.clear() }
        assertEquals(0, archive.append(screen("stale"), "live", canWrite = { false }).added)
        assertEquals(0, archive.count().total)
        assertEquals(1, archive.append(screen("new"), "live").added)
    }

    @Test fun exactRecentViewportReplaySurvivesReconnectionWithoutDuplicatingRows() {
        val page = screen("notice a", "notice b")
        archive.append(page, "live")
        MessageArchive(context).use { reopened ->
            assertEquals(0, reopened.append(page.copy(capturedAt = page.capturedAt + 1500), "live").added)
            assertEquals(2, reopened.count().total)
        }
    }

    @Test fun expiredReplayDoesNotDiscardLaterIdenticalNotices() {
        val page = screen("same notice")
        archive.append(page, "live")
        MessageArchive(context).use { reopened ->
            assertEquals(1, reopened.append(page.copy(capturedAt = page.capturedAt + 120_001), "live").added)
        }
    }

    @Test fun cancellationBeforeCommitRollsBackMessagesAndCheckpoint() {
        var checks = 0
        val page = screen("new a", "new b")
        assertEquals(0, archive.append(page, "live", canWrite = { ++checks == 1 }).added)
        assertEquals(0, archive.count().total)
        assertEquals(2, archive.append(page, "live").added)
    }

    @Test fun recordingIsScopedAndMessagesUseTheirDateNotCaptureTime() {
        archive.createRecording(ChatRecording("recording", "group", 1))
        val precise = 1_780_000_000_000L
        archive.append(ChatSnapshot("group", listOf(Msg("me", "inside", timestamp = precise, date = "2026-09-15"),
            Msg("other", "unknown"), Msg("other", "outside", date = "2026-09-01"))), "recording", requireRecording = true)
        val range = HistoryRange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20))
        assertEquals(listOf("inside"), archive.recordingMessages("recording", range, false).map { it.message.text })
        assertEquals(2, archive.recordingMessages("recording", range, true).size)
        assertEquals(precise, archive.recordingMessages("recording").first().message.timestamp)
        assertEquals(0, archive.append(ChatSnapshot("another", messages("bad")), "recording", requireRecording = true).added)
    }

    @Test fun stoppingDeletingAndReopeningCannotResurrectARecording() {
        val recording = ChatRecording("recording", "group", 1)
        archive.createRecording(recording)
        archive.append(screen("one"), recording.id, requireRecording = true)
        archive.updateRecording(recording.copy(state = RecordingState.FINISHED))
        assertEquals(0, archive.append(screen("two"), recording.id, requireRecording = true).added)
        archive.deleteRecording(recording.id)
        archive.updateRecording(recording.copy(state = RecordingState.ACTIVE))
        assertNull(archive.recording(recording.id))
        assertEquals(0, archive.append(screen("three"), recording.id, requireRecording = true).added)
        assertEquals(0, archive.count(recording.id).total)
    }

    @Test fun equalTextAtDifferentKnownTimesIsNotTheSameMessage() {
        val early = messages("到了吗", "到了").map { it.copy(date = "2026-09-30", timeLabel = "10:00") }
        val late = early.map { it.copy(timeLabel = "11:00") }
        archive.append(ChatSnapshot("group", early), "live")
        assertEquals(2, archive.append(ChatSnapshot("group", late), "live").added)
        assertEquals(4, archive.count("live").total)
    }

    @Test fun recordingIdentityDoesNotUseLooseKeywordNormalization() {
        archive.createRecording(ChatRecording("recording", "A-B", 1))
        assertEquals(0, archive.append(ChatSnapshot("AB", messages("not this chat")), "recording", requireRecording = true).added)
        assertFalse(ConversationIdentity.sameTitle("张 三", "张三"))
        assertFalse(ConversationIdentity.sameTitle("AB", "ab"))
        assertTrue(ConversationIdentity.sameTitle("讨论组（30）", "讨论组(31)"))
    }

    @Test fun newMessagesInvalidateAStoredAnalysisAndReconnectionRequiresConfirmation() {
        val recording = ChatRecording("recording", "group", 1)
        archive.createRecording(recording)
        archive.append(screen("one", "two"), recording.id, requireRecording = true)
        archive.saveRecordingAnalysis(recording.id, "report")
        archive.append(screen("one", "two", "three"), recording.id, requireRecording = true)
        assertNull(archive.recordingAnalysis(recording.id))
        archive.interruptRecordings()
        assertEquals(RecordingState.PAUSED, archive.recording(recording.id)?.state)
        assertEquals(3, archive.count(recording.id).total)
    }

    @Test fun versionOneDatabaseUpgradePreservesMessages() {
        val path = context.getDatabasePath("message_archive.db")
        path.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, stream TEXT NOT NULL, group_title TEXT NOT NULL, body TEXT NOT NULL, side TEXT NOT NULL, sender TEXT, day TEXT, time_label TEXT, kind TEXT NOT NULL, capture_method TEXT NOT NULL, captured_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO messages(stream,group_title,body,side,kind,capture_method,captured_at) VALUES ('live','group','preserved','other','TEXT','nodes',1)")
            db.version = 1
        }
        assertEquals("preserved", archive.recent().single().message.text)
        assertEquals(1, archive.append(screen("new"), "live").added)
        assertEquals(2, archive.count().total)
    }
}
