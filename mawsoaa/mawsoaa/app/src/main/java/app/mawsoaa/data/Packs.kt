package app.mawsoaa.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File

data class PackInfo(val file: String, val id: String, val title: String, val type: String, val author: String?, val license: String?, val complete: Boolean, val entries: Int, val bytes: Long)
data class PackEntry(val id: Long, val ref: String?, val title: String?, val body: String)

/**
 * Library packs: SQLite files with tables pack_meta(key,value) and entry(id,ref,title,body,body_norm).
 * Built by scripts/make_pack.py; imported from storage (SAF) or placed by a downloader. Work fully offline once present.
 */
object PackRepo {
    private fun dir(c: Context) = File(c.filesDir, "packs").apply { mkdirs() }
    private val REQUIRED = listOf("id", "title", "type", "license", "complete")

    private fun readMeta(db: SQLiteDatabase): Map<String, String>? = try {
        db.rows("SELECT key,value FROM pack_meta") { it.getString(0) to (it.strOrNull(1) ?: "") }.toMap()
            .takeIf { m -> REQUIRED.all { it in m } }
    } catch (e: Exception) { null }

    fun list(c: Context): List<PackInfo> = dir(c).listFiles { f -> f.extension == "db" }.orEmpty().mapNotNull { f ->
        try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val m = readMeta(db) ?: return@use null
                val n = db.rows("SELECT COUNT(*) FROM entry") { it.getInt(0) }.firstOrNull() ?: 0
                PackInfo(f.name, m["id"]!!, m["title"]!!, m["type"]!!, m["author"], m["license"], m["complete"] == "true", n, f.length())
            }
        } catch (e: Exception) { null }
    }.sortedBy { it.title }

    /** Copies, validates, and only then installs. Returns an error message or null on success. */
    fun import(c: Context, uri: Uri): String? {
        val tmp = File(dir(c), "import.tmp")
        try {
            c.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: return "تعذّر فتح الملف"
            val id = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val m = readMeta(db) ?: return "الملف ليس حزمة صالحة (pack_meta ناقص)"
                if (db.rows("SELECT COUNT(*) FROM entry") { it.getInt(0) }.firstOrNull() == 0) return "الحزمة فارغة"
                m["id"]!!.filter { it.isLetterOrDigit() || it == '_' }
            }
            val dest = File(dir(c), "$id.db")
            dest.delete(); tmp.renameTo(dest)
            return null
        } catch (e: Exception) { return "ملف غير صالح: ${e.message}" } finally { tmp.delete() }
    }

    fun delete(c: Context, file: String) { File(dir(c), file).delete() }

    private fun open(c: Context, file: String) = SQLiteDatabase.openDatabase(File(dir(c), file).path, null, SQLiteDatabase.OPEN_READONLY)

    fun entries(c: Context, file: String, q: String, offset: Int, limit: Int = 40): List<PackEntry> = open(c, file).use { db ->
        val n = normalizeAr(q)
        if (n.isEmpty()) db.rows("SELECT id,ref,title,body FROM entry ORDER BY id LIMIT $limit OFFSET $offset") { PackEntry(it.getLong(0), it.strOrNull(1), it.strOrNull(2), it.getString(3)) }
        else db.rows("SELECT id,ref,title,body FROM entry WHERE body_norm LIKE ? ORDER BY id LIMIT $limit OFFSET $offset", arrayOf("%$n%")) { PackEntry(it.getLong(0), it.strOrNull(1), it.strOrNull(2), it.getString(3)) }
    }
}
