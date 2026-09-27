package com.attentionguard.app.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.attentionguard.app.capture.HistoryRange
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray

/** Align neighboring visible screens. Repeated text is not a global message ID. */
object ScreenOverlap {
    fun match(previous: List<Msg>, next: List<Msg>): Map<Int, Int> {
        fun same(a: Msg, b: Msg) = a.side == b.side && a.sender == b.sender && a.text == b.text && a.type == b.type &&
            (a.date == null || b.date == null || a.date == b.date)
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

data class ArchivedMessage(val id: Long, val group: String, val message: Msg, val capturedAt: Long)
data class ArchiveCount(val total: Int, val undated: Int)
data class ArchiveWrite(val added: Int, val gap: Boolean)

data class ArchiveReview(val duplicateCount: Int, val uncertainCount: Int)

/** App-private SQLite archive. It is independent of event selection and cloud calls. */
class MessageArchive(context: Context) : SQLiteOpenHelper(context.applicationContext, "message_archive.db", null, 2) {
    private data class Seen(val message: Msg, val id: Long?)
    private var screenEpoch = EPOCH.get()
    private val screens = object : LinkedHashMap<String, List<Seen>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Seen>>?) = size > 16
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT, stream TEXT NOT NULL, group_title TEXT NOT NULL, body TEXT NOT NULL, side TEXT NOT NULL, sender TEXT, day TEXT, time_label TEXT, kind TEXT NOT NULL, capture_method TEXT NOT NULL, captured_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_stream_day ON messages(stream,day)")
        db.execSQL("CREATE INDEX messages_group ON messages(group_title,_id)")
        createCheckpoints(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCheckpoints(db)
    }
    private fun createCheckpoints(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE screen_checkpoints (stream TEXT PRIMARY KEY, signature TEXT NOT NULL, ids TEXT NOT NULL, captured_at INTEGER NOT NULL)")

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

    fun append(snapshot: ChatSnapshot, stream: String, range: HistoryRange? = null,
               canWrite: () -> Boolean = { true }): ArchiveWrite = synchronized(WRITE_LOCK) {
        // A clear and a queued capture can originate from different helper instances.
        if (!canWrite()) return@synchronized ArchiveWrite(0, false)
        if (screenEpoch != EPOCH.get()) { screens.clear(); screenEpoch = EPOCH.get() }
        val title = requireNotNull(snapshot.title).also { require(it.isNotBlank()) }
        val db = writableDatabase
        val previous = screens[stream] ?: replay(db, stream, snapshot)
        val matches = ScreenOverlap.match(previous.map { it.message }, snapshot.messages)
        val next = ArrayList<Seen>()
        var added = 0
        db.beginTransaction()
        try {
            snapshot.messages.forEachIndexed { index, incoming ->
                val old = matches[index]?.let { previous[it] }
                val message = if (incoming.date == null && old?.message?.date != null)
                    incoming.copy(date = old.message.date, timeLabel = old.message.timeLabel, timestamp = old.message.timestamp) else incoming
                var id = old?.id
                if (range == null || range.includes(message.date)) {
                    val values = ContentValues().apply {
                        put("stream", stream); put("group_title", title); put("body", message.text); put("side", message.side)
                        put("sender", message.sender); put("day", message.date); put("time_label", message.timeLabel)
                        put("kind", message.type.name); put("capture_method", message.captureMethod); put("captured_at", snapshot.capturedAt)
                    }
                    if (id == null || db.update("messages", values, "_id=?", arrayOf(id.toString())) == 0) {
                        id = db.insertOrThrow("messages", null, values); added++
                    }
                } else {
                    if (id != null) db.delete("messages", "_id=?", arrayOf(id.toString()))
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
                    Msg(str("side"), str("body"), str("sender"), type = MessageType.valueOf(str("kind")), date = str("day"), timeLabel = str("time_label"), captureMethod = str("capture_method")),
                    c.getLong(c.getColumnIndexOrThrow("captured_at"))))
            }
        }
    }

    fun clear() = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("screen_checkpoints", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        screens.clear(); screenEpoch = EPOCH.incrementAndGet()
    }
    companion object {
        private val EPOCH = AtomicLong()
        private val WRITE_LOCK = Any()
    }
}
