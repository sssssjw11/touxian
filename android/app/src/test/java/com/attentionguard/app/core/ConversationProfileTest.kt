package com.attentionguard.app.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConversationProfileTest {
    private val context = RuntimeEnvironment.getApplication()
    private val archive = MessageArchive(context)
    @After fun close() { archive.close() }
    private fun seed(id: String = "r1", title: String = "同名聊天") {
        archive.createRecording(ChatRecording(id, title, 1))
        archive.append(ChatSnapshot(title, listOf(Msg("me", "资料已收到"), Msg("other", "谢谢你", "甲"))), id, requireRecording = true)
    }
    private fun create(id: String = "p1", record: String? = "r1") =
        archive.createProfile(ConversationProfile(id, "同名对象", kind = ProfileKind.PERSON), record)
    private fun snapshot(id: String = "p1") = requireNotNull(archive.profileSnapshot(id))
    private fun analyze(snapshot: ProfileSnapshot) = RelationshipAnalysis.analyze(snapshot.messages,
        snapshot.profile.name, snapshot.profile.scene, snapshot.profile)

    @Test fun sameNameProfilesAndRecordingsStaySeparateUntilExplicitAssociation() {
        seed(); seed("r2")
        create(); create("p2", "r2")
        assertEquals("p1", archive.profileForRecording("r1")!!.id)
        assertEquals("p2", archive.profileForRecording("r2")!!.id)
        assertEquals(2, snapshot().messages.size)
        assertEquals(setOf("r1"), snapshot().messages.map { it.recordingId }.toSet())
        assertEquals(2, archive.profiles().size)
    }
    @Test fun multipleRecordingsCanBeLinkedAcrossChangedDisplayNames() {
        seed(); seed("r2", "改过的名称"); create()
        archive.linkRecording("p1", "r2")
        val snapshot = snapshot()
        assertEquals(4, snapshot.messages.size)
        assertEquals(4, analyze(snapshot).messageCount)
        assertEquals(4, AnalysisInput.deep(snapshot.messages, analyze(snapshot), snapshot.profile).messages.size)
        assertEquals("未计算", analyze(snapshot).metrics.single { it.label == "异方消息间隔" }.value)
    }
    @Test fun cloudReportPreservesLocalAndReopeningRetainsBoth() {
        seed(); create()
        val snapshot = snapshot(); val local = analyze(snapshot)
        assertTrue(archive.saveProfileAnalysis("p1", snapshot.fingerprint, local))
        val enhanced = local.copy(source = "DeepSeek", summary = "局部语境补充")
        assertTrue(archive.saveProfileAnalysis("p1", snapshot.fingerprint, enhanced, cloud = true))
        MessageArchive(context).use { reopened ->
            val saved = reopened.profileSnapshot("p1")!!
            assertEquals(local, saved.freshLocalReport)
            assertEquals(enhanced, saved.freshCloudReport)
            assertEquals(enhanced, saved.freshReport)
        }
    }
    @Test fun addedMessagesAndUpdatedMetadataMakeOldPortraitStale() {
        seed(); create()
        val old = snapshot()
        assertTrue(archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old)))
        archive.append(ChatSnapshot("同名聊天", listOf(Msg("me", "资料已收到"), Msg("other", "谢谢你", "甲"),
            Msg("other", "新信息", "甲"))), "r1", requireRecording = true)
        assertNull(snapshot().freshReport)
        assertFalse(archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old)))
        val newer = snapshot()
        assertTrue(archive.saveProfileAnalysis("p1", newer.fingerprint, analyze(newer)))
        archive.append(ChatSnapshot("同名聊天", newer.messages.map { it.message.copy(date = "2026-10-05") }),
            "r1", requireRecording = true)
        assertEquals(newer.messages.size, snapshot().messages.size)
        assertNull(snapshot().freshReport)
    }
    @Test fun editedOrDeletedSourceInvalidatesEvenIfRevisionWasNotUpdated() {
        seed(); create(); val old = snapshot()
        assertTrue(archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old)))
        archive.writableDatabase.execSQL("UPDATE messages SET body='修正原话' WHERE _id=?", arrayOf(old.messages.first().id))
        assertNull(snapshot().freshReport)
        assertFalse(archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old)))
        val updated = snapshot()
        assertTrue(archive.saveProfileAnalysis("p1", updated.fingerprint, analyze(updated)))
        archive.writableDatabase.execSQL("DELETE FROM messages WHERE _id=?", arrayOf(updated.messages.first().id))
        assertNull(snapshot().freshReport)
    }
    @Test fun sceneChangeUnlinkAndMoveInvalidateAllAffectedProfiles() {
        seed(); seed("r2"); create(); create("p2", "r2")
        val old = snapshot()
        archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old))
        archive.updateProfile("p1", "新名称", AnalysisScene.WORK, ProfileKind.PERSON)
        assertNull(snapshot().freshReport)
        archive.linkRecording("p2", "r1")
        assertTrue(snapshot().messages.isEmpty())
        assertEquals(4, snapshot("p2").messages.size)
        val second = snapshot("p2"); archive.saveProfileAnalysis("p2", second.fingerprint, analyze(second))
        archive.unlinkRecording("p2", "r1")
        assertNull(snapshot("p2").freshReport)
        assertNull(archive.profileForRecording("r1"))
        assertEquals(2, archive.recordingMessages("r1").size)
    }
    @Test fun deletingProfilePreservesRawRecordingAndDeletingRecordingInvalidatesProfile() {
        seed(); create()
        archive.deleteProfile("p1")
        assertNull(archive.profile("p1")); assertNull(archive.profileForRecording("r1"))
        assertNotNull(archive.recording("r1")); assertEquals(2, archive.recordingMessages("r1").size)
        create(); val old = snapshot(); archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old))
        archive.deleteRecording("r1")
        assertNull(snapshot().freshReport)
        assertTrue(snapshot().recordings.isEmpty())
    }
    @Test fun cancellationCannotWriteAndRecordingCacheIncludesProfileVersion() {
        seed(); create(); val old = snapshot()
        assertFalse(archive.saveProfileAnalysis("p1", old.fingerprint, analyze(old), canWrite = { false }))
        assertNull(snapshot().freshReport)
        val key = AnalysisFingerprint.messages(archive.recordingMessages("r1"))
        assertTrue(archive.saveRecordingAnalysisIfCurrent("r1", null, true, key, old.profile.cacheKey, "local"))
        assertFalse(archive.saveRecordingAnalysisIfCurrent("r1", null, true, key, old.profile.cacheKey, "cancelled", canWrite = { false }))
        assertEquals("local", archive.recordingAnalysis("r1"))
        archive.unlinkRecording("p1", "r1")
        assertFalse(archive.saveRecordingAnalysisIfCurrent("r1", null, true, key, old.profile.cacheKey, "stale"))
    }
    @Test fun versionThreeUpgradePreservesRecordsMessagesAndLegacyReports() {
        seed()
        val old = JSONObject(RelationshipAnalysis.analyze(archive.recordingMessages("r1"), "同名聊天").toJson())
            .apply { remove("context"); remove("scene"); remove("analysisVersion") }.toString()
        archive.saveRecordingAnalysis("r1", old)
        archive.writableDatabase.apply {
            execSQL("DROP TABLE profile_recordings"); execSQL("DROP TABLE conversation_profiles"); version = 3
        }
        archive.close()
        MessageArchive(context).use { upgraded ->
            assertEquals(4, upgraded.readableDatabase.version)
            assertEquals(2, upgraded.recordingMessages("r1").size)
            assertNotNull(upgraded.recording("r1"))
            assertEquals(2, RelationshipReport.fromJson(upgraded.recordingAnalysis("r1")!!).messageCount)
            assertEquals(1, RelationshipReport.fromJson(old).analysisVersion)
            assertTrue(upgraded.profiles().isEmpty())
            upgraded.createProfile(ConversationProfile("new", "新档案"), "r1")
            assertEquals(2, upgraded.profileSnapshot("new")!!.messages.size)
        }
    }
}
