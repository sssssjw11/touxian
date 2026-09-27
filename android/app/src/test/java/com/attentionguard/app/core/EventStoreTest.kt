package com.attentionguard.app.core

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EventStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = EventStore(context)
    private val file get() = File(context.filesDir, EventStore.FILE_NAME)
    private val legacy get() = context.getSharedPreferences("attention_guard_events", Context.MODE_PRIVATE)
    private val event = DemoAttentionData.events[2].copy(id = "test-live-event",
        captureOrigin = CaptureOrigin.WECHAT_MANUAL, sourceCapturedAt = 1_700_000_000_000L)

    @Test fun freshInstallHasNoImplicitDemoRecords() {
        assertTrue(store.load().isEmpty())
        assertFalse(store.readFailed)
        assertFalse(file.exists())
    }

    @Test fun saveAndOverwriteRoundTripEveryEventField() {
        assertEquals(listOf(event), store.upsert(event))
        assertEquals(listOf(event), EventStore(context).load())
        val updated = event.copy(title = "Updated title", dueLabel = null, consequence = "Changed")
        store.upsert(updated)
        assertEquals(listOf(updated), EventStore(context).load())
        assertFalse(File(file.parentFile, "${file.name}.pending").exists())
    }

    @Test fun completionSurvivesNewObservationsAndRestoresPriorStatus() {
        store.upsert(event)
        store.setCompleted(event.id, true)
        store.upsert(event.copy(status = EventStatus.ACTION_REQUIRED))
        val completed = EventStore(context).load().single()
        assertEquals(EventStatus.COMPLETED, completed.status)
        assertEquals(EventStatus.MONITORING, completed.previousStatus)
        assertEquals(EventStatus.MONITORING, store.setCompleted(event.id, false).single().status)
        assertNull(EventStore(context).load().single().previousStatus)
    }

    @Test fun archiveSurvivesObservationAndCanBeRestoredWithoutChangingCompletion() {
        store.upsert(event)
        store.setCompleted(event.id, true)
        store.setArchived(event.id, true)
        store.upsert(event.copy(captureOrigin = CaptureOrigin.WECHAT_AUTO))
        val archived = EventStore(context).load().single()
        assertTrue(archived.archived)
        assertEquals(EventStatus.COMPLETED, archived.status)
        assertEquals(CaptureOrigin.WECHAT_AUTO, archived.captureOrigin)
        val restored = store.setArchived(event.id, false).single()
        assertFalse(restored.archived)
        assertEquals(EventStatus.COMPLETED, restored.status)
    }

    @Test fun batchArchiveWritesSelectedEventsTogetherAndLeavesOthersUntouched() {
        val first = event.copy(id = "batch-first")
        val second = event.copy(id = "batch-second")
        val third = event.copy(id = "batch-third", archived = true)
        store.upsert(first)
        store.upsert(second)
        store.upsert(third)
        val archived = store.setArchived(listOf(first.id, second.id), true)
        assertTrue(archived.first { it.id == first.id }.archived)
        assertTrue(archived.first { it.id == second.id }.archived)
        assertTrue(archived.first { it.id == third.id }.archived)
        val restored = store.setArchived(listOf(first.id, third.id), false)
        assertFalse(restored.first { it.id == first.id }.archived)
        assertTrue(restored.first { it.id == second.id }.archived)
        assertFalse(restored.first { it.id == third.id }.archived)
    }

    @Test fun oldRecordsWithoutOriginOrArchiveRemainReadable() {
        store.upsert(event)
        val old = JSONArray(file.readText()).getJSONObject(0)
        old.remove("captureOrigin")
        old.remove("sourceCapturedAt")
        old.remove("archived")
        file.writeText(JSONArray().put(old).toString())
        val loaded = EventStore(context).load().single()
        assertEquals(CaptureOrigin.UNKNOWN, loaded.captureOrigin)
        assertNull(loaded.sourceCapturedAt)
        assertFalse(loaded.archived)
    }

    @Test fun legacyBlankTitleDoesNotHideTheWholeArchiveOrRewriteOnRead() {
        store.upsert(event)
        val first = JSONArray(file.readText()).getJSONObject(0)
        val blank = org.json.JSONObject(first.toString()).put("id", "blank-title").put("title", "")
        val raw = JSONArray().put(blank).put(first).toString()
        file.writeText(raw)
        val loaded = store.load()
        assertFalse(store.readFailed)
        assertEquals(2, loaded.size)
        assertEquals("未命名事项 · 请核对原文", loaded.first().title)
        assertEquals(event.evidence, loaded.first().evidence)
        assertEquals(raw, file.readText())
        assertEquals(event, loaded.last())
    }

    @Test fun mergingKeepsRecentDistinctEvidenceAndUpdates() {
        store.upsert(event.copy(updates = emptyList(), evidence = emptyList()))
        repeat(12) { index ->
            store.upsert(event.copy(
                updates = listOf(EventUpdate("10:00", "Update $index", "Detail $index")),
                evidence = listOf("Evidence $index")
            ))
        }
        val merged = store.load().single()
        assertEquals((4..11).map { "Update $it" }, merged.updates.map { it.title })
        assertEquals((6..11).map { "Evidence $it" }, merged.evidence)
        store.upsert(merged)
        assertEquals(merged, store.load().single())
    }

    @Test fun legacyPreferencesAreReadAndMigratedOnFirstWrite() {
        store.upsert(event)
        val legacyJson = file.readText()
        check(file.delete())
        check(legacy.edit().putString("events_json", legacyJson).commit())
        assertEquals(listOf(event), store.load())
        assertFalse(file.exists())
        store.setCompleted(event.id, true)
        assertTrue(file.exists())
        assertEquals(EventStatus.COMPLETED, EventStore(context).load().single().status)
        assertEquals(legacyJson, legacy.getString("events_json", null))
    }

    @Test fun legacyDemoIdsAreNotPresentedAsRealObservations() {
        store.upsert(event)
        val liveJson = JSONArray(file.readText()).getJSONObject(0)
        val demoJson = JSONArray(file.readText()).getJSONObject(0).put("id", "assessment")
        check(file.delete())
        check(legacy.edit().putString("events_json", JSONArray().put(demoJson).put(liveJson).toString()).commit())
        assertEquals(listOf(event), store.load())
    }

    @Test fun corruptFileDoesNotFallBackToOutdatedPreferencesOrOverwrite() {
        store.upsert(event)
        check(legacy.edit().putString("events_json", file.readText()).commit())
        file.writeText("corrupt-record")
        assertTrue(store.load().isEmpty())
        assertTrue(store.readFailed)
        assertThrows(IllegalStateException::class.java) { store.upsert(event) }
        assertThrows(IllegalStateException::class.java) { store.setCompleted(event.id, true) }
        assertThrows(IllegalStateException::class.java) { store.setArchived(event.id, true) }
        assertEquals("corrupt-record", file.readText())
    }

    @Test fun corruptLegacyPreferencesAreNotSilentlyReplaced() {
        check(legacy.edit().putString("events_json", "broken-json").commit())
        assertThrows(IllegalStateException::class.java) { store.upsert(event) }
        assertFalse(file.exists())
        assertEquals("broken-json", legacy.getString("events_json", null))
    }

    @Test fun structurallyInvalidRecordsAreNotSilentlyOverwritten() {
        for (raw in listOf("[{}]", "[{\"id\":\"x\",\"title\":7,\"sourceGroup\":\"group\"}]")) {
            file.writeText(raw)
            assertThrows(IllegalStateException::class.java) { store.upsert(event) }
            assertEquals(raw, file.readText())
        }
    }

    @Test fun failedWritePreservesPreviousBytesAndCanBeRetried() {
        store.upsert(event)
        val original = file.readBytes()
        val blocker = File(file.parentFile, "${file.name}.pending")
        check(blocker.mkdir())
        assertThrows(java.io.IOException::class.java) { store.setCompleted(event.id, true) }
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(event), store.load())
        check(blocker.delete())
        assertEquals(EventStatus.COMPLETED, store.setCompleted(event.id, true).single().status)
    }
}
