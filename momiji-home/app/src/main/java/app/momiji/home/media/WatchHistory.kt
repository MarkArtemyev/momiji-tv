package app.momiji.home.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import app.momiji.home.data.HomeStore
import java.security.MessageDigest

data class WatchRecord(val id: String, val profileId: String, val title: String, val page: String,
    val position: Long, val duration: Long, val updated: Long, val completed: Boolean)

/** History of actual native Momiji playback, separate from app launches and third-party feeds. */
class WatchHistory(context: Context, name: String = "watch-history.db") : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE progress (profile TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, page TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, updated INTEGER NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(profile,id))")
        db.execSQL("CREATE INDEX recent_progress ON progress(profile,updated DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun list(profileId: String, unfinishedOnly: Boolean = true, limit: Int = 12): List<WatchRecord> {
        val result=ArrayList<WatchRecord>()
        readableDatabase.query("progress",null,"profile=?" + if (unfinishedOnly) " AND completed=0" else "",
            arrayOf(profileId),null,null,"updated DESC",limit.coerceIn(1,200).toString()).use { cursor ->
            while (cursor.moveToNext()) result.add(record(cursor))
        }; return result
    }
    fun find(profileId: String, page: String): WatchRecord? = readableDatabase.query("progress",null,
        "profile=? AND id=?",arrayOf(profileId,key(page)),null,null,null,"1").use { if (it.moveToFirst()) record(it) else null }
    @Synchronized fun save(profileId: String, title: String, page: String, position: Long, duration: Long, ended: Boolean = false) {
        require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(validPage(page)) { "Неподдерживаемая страница" }
        require(duration in 1..1_209_600_000L && position in 0..duration) { "Некорректный прогресс" }
        // A short first play does not create a card. Rewinding an existing item to the
        // beginning must also remove its old position, otherwise the next open jumps back.
        if (position < 10_000 && !ended) { remove(profileId,key(page)); return }
        val completed=ended || (duration-position <= 30_000) || position.toDouble()/duration >= .97
        val values=ContentValues().apply {
            put("profile",profileId); put("id",key(page)); put("title",title.trim().filterNot(Char::isISOControl).take(200).ifBlank { Uri.parse(page).host ?: "Видео" })
            put("page",page); put("position",position); put("duration",duration); put("updated",System.currentTimeMillis()); put("completed",if (completed) 1 else 0)
        }
        val db=writableDatabase
        db.beginTransaction()
        try {
            check(db.insertWithOnConflict("progress",null,values,SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Не удалось записать прогресс" }
            db.execSQL("DELETE FROM progress WHERE profile=? AND id NOT IN (SELECT id FROM progress WHERE profile=? ORDER BY updated DESC LIMIT 200)",arrayOf(profileId,profileId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun remove(profileId: String, id: String) { writableDatabase.delete("progress","profile=? AND id=?",arrayOf(profileId,id)) }
    fun clear(profileId: String) { writableDatabase.delete("progress","profile=?",arrayOf(profileId)) }
    private fun record(c: Cursor) = WatchRecord(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("profile")),
        c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("page")),c.getLong(c.getColumnIndexOrThrow("position")),
        c.getLong(c.getColumnIndexOrThrow("duration")),c.getLong(c.getColumnIndexOrThrow("updated")),c.getInt(c.getColumnIndexOrThrow("completed")) != 0)
    companion object {
        fun validPage(page: String): Boolean = runCatching {
            val uri=Uri.parse(page)
            page.length in 1..8192 && page.none(Char::isISOControl) && uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
        private fun key(page: String)=MessageDigest.getInstance("SHA-256").digest(page.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Signature permission plus package allowlist: only our browser can exchange playback progress. */
class HomeHistoryProvider : ContentProvider() {
    private lateinit var store: HomeStore
    private lateinit var history: WatchHistory
    override fun onCreate(): Boolean {
        val app=context ?: return false; store=HomeStore(app); history=WatchHistory(app); return true
    }
    private fun enforceClient() {
        val app=context ?: throw SecurityException("No context")
        val uid=Binder.getCallingUid()
        if (uid == Process.myUid()) return
        app.enforceCallingOrSelfPermission("app.momiji.permission.HOME_HISTORY","Momiji signature required")
        val packages=app.packageManager.getPackagesForUid(uid).orEmpty()
        if ("app.momiji.tv" !in packages || app.packageManager.checkSignatures(uid,Process.myUid()) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Only Momiji Browser can access playback history")
    }
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceClient()
        if (method == "profile") return Bundle().apply {
            val active=store.activeProfile(); putString("id",active.id); putString("name",active.name); putInt("protocol",1)
        }
        val data=extras ?: throw IllegalArgumentException("Missing data")
        val profile=data.getString("profile").orEmpty()
        require(store.profiles().any { it.id == profile }) { "Профиль удалён" }
        val page=data.getString("page").orEmpty(); require(WatchHistory.validPage(page))
        return when (method) {
            "lookup" -> Bundle().apply { history.find(profile,page)?.let {
                putLong("position",if (it.completed) 0 else it.position); putLong("duration",it.duration)
            } }
            "progress" -> {
                history.save(profile,data.getString("title").orEmpty(),page,data.getLong("position"),data.getLong("duration"),data.getBoolean("ended"))
                if (store.profiles().none { it.id == profile }) { history.clear(profile); throw IllegalArgumentException("Профиль удалён") }
                Bundle().apply { putBoolean("ok",true) }
            }
            "forget" -> { history.find(profile,page)?.let { history.remove(profile,it.id) }; Bundle().apply { putBoolean("ok",true) } }
            else -> throw IllegalArgumentException("Unknown method")
        }
    }
    override fun getType(uri: Uri): String { enforceClient(); return "vnd.android.cursor.item/vnd.momiji.progress" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { enforceClient(); throw UnsupportedOperationException("Use versioned call API") }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { enforceClient(); throw UnsupportedOperationException() }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { enforceClient(); throw UnsupportedOperationException() }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { enforceClient(); throw UnsupportedOperationException() }
}
