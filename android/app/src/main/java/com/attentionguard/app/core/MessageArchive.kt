package com.attentionguard.app.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.attentionguard.app.capture.HistoryRange
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Align neighboring visible screens. Repeated text is not a global message ID. */
object ScreenOverlap {
    fun match(previous: List<Msg>, next: List<Msg>): Map<Int, Int> {
        fun same(a: Msg, b: Msg) = a.side == b.side && a.sender == b.sender && a.text == b.text && a.type == b.type &&
            (a.date == null || b.date == null || a.date == b.date) &&
            (a.timeLabel == null || b.timeLabel == null || a.timeLabel == b.timeLabel) &&
            (a.timestamp == null || b.timestamp == null || a.timestamp == b.timestamp)
        if (previous.isEmpty() || next.isEmpty()) return emptyMap()
        if (previous.size == next.size && previous.indices.all { same(previous[it], next[it]) }) return next.indices.associateWith { it }
        val alignments = (-next.lastIndex..previous.lastIndex).mapNotNull { offset ->
            val indices = next.indices.filter { it + offset in previous.indices }
            if (indices.isEmpty() || !indices.all { same(previous[it + offset], next[it]) }) null
            else indices.associateWith { it + offset }
        }
        val bestSize = alignments.maxOfOrNull { it.size } ?: 0
        val best = alignments.filter { it.size == bestSize }
        // One generic "OK" on both screens is not proof of identity.
        return if (bestSize >= 2 && best.size == 1) best.single() else emptyMap()
    }
}

data class ArchivedMessage(val id: Long, val group: String, val message: Msg, val capturedAt: Long, val recordingId: String? = null)
data class ArchiveCount(val total: Int, val undated: Int)
data class ArchiveWrite(val added: Int, val gap: Boolean)

data class ArchiveReview(val duplicateCount: Int, val uncertainCount: Int)

/** App-private SQLite archive. It is independent of event selection and cloud calls. */
class MessageArchive(context: Context) : SQLiteOpenHelper(context.applicationContext, "message_archive.db", null, 4) {
    private data class Seen(val message: Msg, val id: Long?)
    private var screenEpoch = EPOCH.get()
    private val screens = object : LinkedHashMap<String, List<Seen>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Seen>>?) = size > 16
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, stream TEXT NOT NULL, group_title TEXT NOT NULL, body TEXT NOT NULL, side TEXT NOT NULL, sender TEXT, day TEXT, time_label TEXT, kind TEXT NOT NULL, capture_method TEXT NOT NULL, captured_at INTEGER NOT NULL, message_time INTEGER)")
        db.execSQL("CREATE INDEX messages_stream_day ON messages(stream,day)")
        db.execSQL("CREATE INDEX messages_group ON messages(group_title,_id)")
        createCheckpoints(db)
        createRecordings(db)
        createProfiles(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCheckpoints(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN message_time INTEGER")
            createRecordings(db)
        }
        if (oldVersion < 4) createProfiles(db)
    }
    private fun createCheckpoints(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE screen_checkpoints (stream TEXT PRIMARY KEY, signature TEXT NOT NULL, ids TEXT NOT NULL, captured_at INTEGER NOT NULL)")

    private fun createRecordings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, ended_at INTEGER, state TEXT NOT NULL, reason TEXT NOT NULL, gaps INTEGER NOT NULL DEFAULT 0, analysis TEXT)")
    }

    private fun createProfiles(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversation_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, scene TEXT NOT NULL, kind TEXT NOT NULL, created_at INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, analysis TEXT, analysis_fingerprint TEXT)")
        db.execSQL("CREATE TABLE profile_recordings (recording_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX profile_recordings_profile ON profile_recordings(profile_id)")
    }

    private fun invalidateProfile(db: SQLiteDatabase, recordingId: String) {
        db.execSQL("UPDATE conversation_profiles SET revision=revision+1 WHERE id IN (SELECT profile_id FROM profile_recordings WHERE recording_id=?)", arrayOf(recordingId))
    }

    // Only an exact, recent viewport replay survives service reconnection. This
    // is not a global text identity and must not erase later repeated notices.
    private fun replay(db: SQLiteDatabase, stream: String, snapshot: ChatSnapshot): List<Seen> =
        db.query("screen_checkpoints", null, "stream=?", arrayOf(stream), null, null, null).use { c ->
            if (!c.moveToFirst() || c.getString(c.getColumnIndexOrThrow("signature")) != snapshot.signature() ||
                snapshot.capturedAt - c.getLong(c.getColumnIndexOrThrow("captured_at")) !in 0..120_000) return@use emptyList()
            val ids = JSONArray(c.getString(c.getColumnIndexOrThrow("ids")))
            if (ids.length() != snapshot.messages.size) return@use emptyList()
            snapshot.messages.mapIndexed { i, message -> Seen(message, if (ids.isNull(i)) null else ids.getLong(i)) }
        }

    fun append(snapshot: ChatSnapshot, stream: String, range: HistoryRange? = null, requireRecording: Boolean = false,
               canWrite: () -> Boolean = { true }): ArchiveWrite = synchronized(WRITE_LOCK) {
        // A clear and a queued capture can originate from different helper instances.
        if (!canWrite()) return@synchronized ArchiveWrite(0, false)
        if (screenEpoch != EPOCH.get()) { screens.clear(); screenEpoch = EPOCH.get() }
        val title = requireNotNull(snapshot.title).also { require(it.isNotBlank()) }
        val db = writableDatabase
        val recording = recording(stream)
        if (requireRecording && recording == null) return@synchronized ArchiveWrite(0, false)
        if (recording != null && (recording.state != RecordingState.ACTIVE ||
                !ConversationIdentity.sameTitle(recording.title, title))) return@synchronized ArchiveWrite(0, false)
        val previous = screens[stream] ?: replay(db, stream, snapshot)
        val matches = ScreenOverlap.match(previous.map { it.message }, snapshot.messages)
        val next = ArrayList<Seen>()
        var added = 0
        var changed = false
        db.beginTransaction()
        try {
            snapshot.messages.forEachIndexed { index, incoming ->
                val old = matches[index]?.let { previous[it] }
                val message = if (old != null) incoming.copy(date = incoming.date ?: old.message.date,
                    timeLabel = incoming.timeLabel ?: old.message.timeLabel, timestamp = incoming.timestamp ?: old.message.timestamp) else incoming
                var id = old?.id
                if (range == null || range.includes(message.date)) {
                    val values = ContentValues().apply {
                        put("stream", stream); put("group_title", title); put("body", message.text); put("side", message.side)
                        put("sender", message.sender); put("day", message.date); put("time_label", message.timeLabel)
                        put("kind", message.type.name); put("capture_method", message.captureMethod); put("captured_at", snapshot.capturedAt)
                        put("message_time", message.timestamp)
                    }
                    if (id == null || db.update("messages", values, "_id=?", arrayOf(id.toString())) == 0) {
                        id = db.insertOrThrow("messages", null, values); added++
                        changed = true
                    }
                    if (old != null && old.message != message) changed = true
                } else {
                    if (id != null) { db.delete("messages", "_id=?", arrayOf(id.toString())); changed = true }
                    id = null
                }
                next.add(Seen(message, id))
            }
            if (!canWrite()) return@synchronized ArchiveWrite(0, false)
            val ids = JSONArray().apply { next.forEach { put(it.id ?: org.json.JSONObject.NULL) } }
            db.insertWithOnConflict("screen_checkpoints", null, ContentValues().apply {
                put("stream", stream); put("signature", snapshot.signature()); put("ids", ids.toString()); put("captured_at", snapshot.capturedAt)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM screen_checkpoints WHERE stream NOT IN (SELECT stream FROM screen_checkpoints ORDER BY captured_at DESC LIMIT 16)")
            if (changed) {
                if (recording != null) db.execSQL("UPDATE recordings SET analysis=NULL WHERE id=?", arrayOf(stream))
                invalidateProfile(db, stream)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        screens[stream] = next
        ArchiveWrite(added, previous.isNotEmpty() && matches.isEmpty())
    }

    /** Cross-screen duplicate check for callers that need an audit signal. */
    fun review(stream: String? = null): ArchiveReview = synchronized(WRITE_LOCK) {
        val where = if (stream == null) "" else " WHERE stream=?"
        val args = stream?.let { arrayOf(it) } ?: emptyArray()
        val cursor = readableDatabase.rawQuery(
            "SELECT body, side, sender, day, time_label, COUNT(*) c FROM messages$where GROUP BY body, side, sender, day, time_label HAVING c > 1",
            args
        )
        var duplicates = 0
        cursor.use { while (it.moveToNext()) duplicates += (it.getInt(5) - 1).coerceAtLeast(0) }
        val uncertainWhere = if (stream == null) " WHERE day IS NULL" else " WHERE stream=? AND day IS NULL"
        val uncertain = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages$uncertainWhere", args
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return ArchiveReview(duplicates, uncertain)
    }

    fun count(stream: String? = null): ArchiveCount {
        val where = if (stream == null) "" else " WHERE stream=?"
        readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN day IS NULL THEN 1 ELSE 0 END),0) FROM messages$where", stream?.let { arrayOf(it) }).use {
            it.moveToFirst(); return ArchiveCount(it.getInt(0), it.getInt(1))
        }
    }

    fun recent(group: String? = null, stream: String? = null, limit: Int = 50, beforeId: Long? = null): List<ArchivedMessage> {
        val terms = mutableListOf<String>(); val args = mutableListOf<String>()
        if (group != null) { terms.add("group_title=?"); args.add(group) }
        if (stream != null) { terms.add("stream=?"); args.add(stream) }
        if (beforeId != null) { terms.add("_id<?"); args.add(beforeId.toString()) }
        return readableDatabase.query("messages", null, terms.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.toTypedArray(), null, null, "_id DESC", limit.coerceIn(1, 100).toString()).use { c ->
            fun str(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            buildList {
                while (c.moveToNext()) add(ArchivedMessage(c.getLong(c.getColumnIndexOrThrow("_id")), str("group_title"),
                    Msg(str("side"), str("body"), str("sender"), timestamp = c.getColumnIndexOrThrow("message_time").let { if (c.isNull(it)) null else c.getLong(it) }, type = MessageType.valueOf(str("kind")), date = str("day"), timeLabel = str("time_label"), captureMethod = str("capture_method")),
                    c.getLong(c.getColumnIndexOrThrow("captured_at")), str("stream")))
            }
        }
    }

    fun createRecording(recording: ChatRecording) = synchronized(WRITE_LOCK) {
        require(recording.id.isNotBlank() && recording.title.isNotBlank())
        writableDatabase.insertOrThrow("recordings", null, recordingValues(recording))
        Unit
    }

    fun updateRecording(recording: ChatRecording) = synchronized(WRITE_LOCK) {
        writableDatabase.update("recordings", recordingValues(recording), "id=?", arrayOf(recording.id))
        Unit
    }

    private fun recordingValues(recording: ChatRecording) = ContentValues().apply {
        put("id", recording.id); put("title", recording.title); put("created_at", recording.createdAt)
        put("ended_at", recording.endedAt); put("state", recording.state.name)
        put("reason", recording.reason); put("gaps", recording.gaps)
    }

    fun recordings(): List<ChatRecording> = readableDatabase.query("recordings", null, null, null, null, null, "created_at DESC").use { c ->
        buildList {
            while (c.moveToNext()) add(ChatRecording(c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("title")), c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getLong(it) },
                RecordingState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
                c.getString(c.getColumnIndexOrThrow("reason")), c.getInt(c.getColumnIndexOrThrow("gaps"))))
        }
    }

    fun recording(id: String): ChatRecording? = readableDatabase.query("recordings", null, "id=?", arrayOf(id), null, null, null).use { c ->
        if (!c.moveToFirst()) null else ChatRecording(c.getString(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("title")), c.getLong(c.getColumnIndexOrThrow("created_at")),
            c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getLong(it) },
            RecordingState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
            c.getString(c.getColumnIndexOrThrow("reason")), c.getInt(c.getColumnIndexOrThrow("gaps")))
    }

    fun recordingMessages(id: String, range: HistoryRange? = null, includeUndated: Boolean = true): List<ArchivedMessage> = synchronized(WRITE_LOCK) {
        val rows = ArrayList<ArchivedMessage>()
        var cursor: Long? = null
        while (true) {
            val page = recent(stream = id, beforeId = cursor, limit = 100)
            rows.addAll(page.filter { (includeUndated || it.message.date != null) && (range == null || range.includes(it.message.date)) })
            if (page.size < 100) break
            cursor = page.last().id
        }
        rows.reversed()
    }

    fun saveRecordingAnalysis(id: String, raw: String) = synchronized(WRITE_LOCK) {
        check(writableDatabase.update("recordings", ContentValues().apply { put("analysis", raw) }, "id=?", arrayOf(id)) == 1)
    }

    fun recordingAnalysis(id: String): String? = readableDatabase.query("recordings", arrayOf("analysis"), "id=?", arrayOf(id), null, null, null).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }

    fun saveRecordingAnalysisIfCurrent(id: String, range: HistoryRange?, includeUndated: Boolean,
                                      fingerprint: String, profileKey: String, raw: String,
                                      canWrite: () -> Boolean = { true }): Boolean = synchronized(WRITE_LOCK) {
        if (recording(id) == null || profileForRecording(id)?.cacheKey.orEmpty() != profileKey ||
            AnalysisFingerprint.messages(recordingMessages(id, range, includeUndated)) != fingerprint) return@synchronized false
        if (!canWrite()) return@synchronized false
        saveRecordingAnalysis(id, raw)
        true
    }

    fun profiles(): List<ConversationProfile> = synchronized(WRITE_LOCK) {
        readableDatabase.query("conversation_profiles", null, null, null, null, null, "created_at DESC, id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    fun str(name: String) = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
                    add(ConversationProfile(str("id")!!, str("name")!!, AnalysisScene.read(str("scene")),
                        runCatching { ProfileKind.valueOf(str("kind")!!) }.getOrDefault(ProfileKind.UNKNOWN),
                        c.getLong(c.getColumnIndexOrThrow("created_at")), c.getLong(c.getColumnIndexOrThrow("revision")),
                        str("analysis"), str("analysis_fingerprint")))
                }
            }
        }
    }

    fun profile(id: String): ConversationProfile? = profiles().firstOrNull { it.id == id }
    fun profileForRecording(recordingId: String): ConversationProfile? = synchronized(WRITE_LOCK) {
        readableDatabase.query("profile_recordings", arrayOf("profile_id"), "recording_id=?", arrayOf(recordingId), null, null, null).use {
            if (it.moveToFirst()) profile(it.getString(0)) else null
        }
    }

    fun createProfile(profile: ConversationProfile, recordingId: String? = null) = synchronized(WRITE_LOCK) {
        require(profile.id.isNotBlank() && profile.name.isNotBlank() && profile.name.length <= 80)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("conversation_profiles", null, ContentValues().apply {
                put("id", profile.id); put("name", profile.name); put("scene", profile.scene.name)
                put("kind", profile.kind.name); put("created_at", profile.createdAt); put("revision", 0)
            })
            if (recordingId != null) linkRecording(profile.id, recordingId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateProfile(id: String, name: String, scene: AnalysisScene, kind: ProfileKind) = synchronized(WRITE_LOCK) {
        require(name.isNotBlank() && name.length <= 80)
        check(writableDatabase.update("conversation_profiles", ContentValues().apply {
            put("name", name); put("scene", scene.name); put("kind", kind.name)
        }, "id=?", arrayOf(id)) == 1)
        writableDatabase.execSQL("UPDATE conversation_profiles SET revision=revision+1 WHERE id=?", arrayOf(id))
    }

    fun linkRecording(profileId: String, recordingId: String) = synchronized(WRITE_LOCK) {
        require(profile(profileId) != null && recording(recordingId) != null)
        val previous = profileForRecording(recordingId)
        if (previous?.id == profileId) return@synchronized
        val db = writableDatabase
        db.beginTransaction()
        try {
            invalidateProfile(db, recordingId)
            db.insertWithOnConflict("profile_recordings", null, ContentValues().apply {
                put("recording_id", recordingId); put("profile_id", profileId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            invalidateProfile(db, recordingId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun unlinkRecording(profileId: String, recordingId: String) = synchronized(WRITE_LOCK) {
        if (profileForRecording(recordingId)?.id != profileId) return@synchronized
        val db = writableDatabase
        db.beginTransaction()
        try {
            invalidateProfile(db, recordingId)
            db.delete("profile_recordings", "profile_id=? AND recording_id=?", arrayOf(profileId, recordingId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun profileSnapshot(id: String): ProfileSnapshot? = synchronized(WRITE_LOCK) {
        val profile = profile(id) ?: return@synchronized null
        val ids = readableDatabase.query("profile_recordings", arrayOf("recording_id"), "profile_id=?", arrayOf(id), null, null, "recording_id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val records = ids.mapNotNull(::recording)
        ProfileSnapshot(profile, records, records.flatMap { recordingMessages(it.id) }.sortedBy { it.id })
    }

    /** Compare source contents as well as revision, so stale or deleted sources never receive a report. */
    fun saveProfileAnalysis(id: String, fingerprint: String, report: RelationshipReport, cloud: Boolean = false,
                            canWrite: () -> Boolean = { true }): Boolean = synchronized(WRITE_LOCK) {
        val current = profileSnapshot(id) ?: return@synchronized false
        if (current.fingerprint != fingerprint) return@synchronized false
        val local = if (cloud) current.freshLocalReport ?: return@synchronized false else report
        val envelope = JSONObject().put("version", ContextInsight.VERSION).put("localReport", JSONObject(local.toJson()))
            .put("cloudReport", if (cloud) JSONObject(report.toJson()) else JSONObject.NULL)
            .put("analyzedAt", System.currentTimeMillis()).put("sourceMessageCount", current.messages.size)
        if (!canWrite()) return@synchronized false
        writableDatabase.update("conversation_profiles", ContentValues().apply {
            put("analysis", envelope.toString()); put("analysis_fingerprint", fingerprint)
        }, "id=?", arrayOf(id)) == 1
    }

    fun deleteProfile(id: String) = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("profile_recordings", "profile_id=?", arrayOf(id))
            db.delete("conversation_profiles", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun interruptRecordings() = synchronized(WRITE_LOCK) {
        writableDatabase.update("recordings", ContentValues().apply {
            put("state", RecordingState.PAUSED.name); put("reason", "服务已断开，请回到目标微信会话后确认继续")
        }, "state=?", arrayOf(RecordingState.ACTIVE.name))
        Unit
    }

    fun deleteRecording(id: String) = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            invalidateProfile(db, id)
            db.delete("profile_recordings", "recording_id=?", arrayOf(id))
            db.delete("messages", "stream=?", arrayOf(id)); db.delete("screen_checkpoints", "stream=?", arrayOf(id))
            db.delete("recordings", "id=?", arrayOf(id)); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        screens.remove(id); screenEpoch = EPOCH.incrementAndGet()
    }

    fun clear() = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("screen_checkpoints", null, null)
            db.delete("recordings", null, null)
            db.delete("profile_recordings", null, null)
            db.delete("conversation_profiles", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        screens.clear(); screenEpoch = EPOCH.incrementAndGet()
    }
    companion object {
        private val EPOCH = AtomicLong()
        private val WRITE_LOCK = Any()
    }
}
