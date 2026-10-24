package app.mawsoaa.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.IOException

fun <T> SQLiteDatabase.rows(sql: String, args: Array<String> = emptyArray(), f: (Cursor) -> T): List<T> {
    rawQuery(sql, args).use { c ->
        val out = ArrayList<T>()
        while (c.moveToNext()) out.add(f(c))
        return out
    }
}
fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

/**
 * Opens the prebuilt read-only content databases that ship in assets/db.
 * Each DB is copied once into filesDir/content (re-copied only when its sha256 in content_manifest.json changes).
 * A DB that was not built returns null -> the UI says the content is not bundled instead of faking it.
 */
object Content {
    private lateinit var app: Context
    private val open = HashMap<String, SQLiteDatabase?>()

    data class Entry(val name: String, val bytes: Long, val sha256: String)

    fun init(c: Context) { app = c.applicationContext }

    val manifest: List<Entry> by lazy {
        try {
            val j = JSONObject(app.assets.open("db/content_manifest.json").bufferedReader().readText())
            val a = j.getJSONArray("files")
            (0 until a.length()).map { val o = a.getJSONObject(it); Entry(o.getString("name"), o.getLong("bytes"), o.getString("sha256")) }
        } catch (e: Exception) { emptyList() }
    }

    fun shipped(prefix: String): List<String> = manifest.map { it.name }.filter { it.startsWith(prefix) }.sorted()

    fun db(name: String): SQLiteDatabase? = synchronized(open) {
        if (open.containsKey(name)) open[name] else openFromAssets(name).also { open[name] = it }
    }

    private fun openFromAssets(name: String): SQLiteDatabase? {
        val entry = manifest.firstOrNull { it.name == name } ?: return null
        val dir = File(app.filesDir, "content").apply { mkdirs() }
        val out = File(dir, name)
        val stamp = File(dir, "$name.sha")
        try {
            if (!out.exists() || !stamp.exists() || stamp.readText() != entry.sha256) {
                val tmp = File(dir, "$name.tmp")
                app.assets.open("db/$name").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
                stamp.writeText(entry.sha256)
            }
            return SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: IOException) { return null } catch (e: android.database.sqlite.SQLiteException) { return null }
    }

    fun meta(db: SQLiteDatabase, key: String): String? =
        db.rows("SELECT value FROM meta WHERE key=?", arrayOf(key)) { it.strOrNull(0) }.firstOrNull()
}

// ───────────────────────── Quran ─────────────────────────
data class Surah(val id: Int, val name: String, val ayahCount: Int, val type: String?)
data class Ayah(val surah: Int, val ayah: Int, val text: String, val juz: Int?, val page: Int?)

object QuranRepo {
    private val db get() = Content.db("quran.db")
    fun available() = db != null
    fun riwaya(): String? = db?.let { Content.meta(it, "riwaya_ar") }

    fun surahs(): List<Surah> = db?.rows("SELECT id,name,ayah_count,type FROM surah ORDER BY id") {
        Surah(it.getInt(0), it.strOrNull(1) ?: "سورة ${it.getInt(0)}", it.getInt(2), it.strOrNull(3))
    }.orEmpty()

    fun ayahs(surah: Int): List<Ayah> = db?.rows("SELECT surah,ayah,text,juz,page FROM ayah WHERE surah=? ORDER BY ayah", arrayOf("$surah")) {
        Ayah(it.getInt(0), it.getInt(1), it.getString(2), it.intOrNull(3), it.intOrNull(4))
    }.orEmpty()

    fun firstOfJuz(juz: Int): Pair<Int, Int>? = db?.rows("SELECT surah,ayah FROM ayah WHERE juz=? ORDER BY id LIMIT 1", arrayOf("$juz")) { it.getInt(0) to it.getInt(1) }?.firstOrNull()
    fun firstOfPage(page: Int): Pair<Int, Int>? = db?.rows("SELECT surah,ayah FROM ayah WHERE page=? ORDER BY id LIMIT 1", arrayOf("$page")) { it.getInt(0) to it.getInt(1) }?.firstOrNull()
    fun hasNavigation(): Boolean = db?.let { Content.meta(it, "has_navigation_metadata") == "True" } ?: false

    fun search(q: String, limit: Int = 100): List<Ayah> {
        val fq = ftsQuery(q) ?: return emptyList()
        return db?.rows(
            "SELECT a.surah,a.ayah,a.text,a.juz,a.page FROM ayah_fts f JOIN ayah a ON a.id=f.docid WHERE ayah_fts MATCH ? ORDER BY a.id LIMIT $limit",
            arrayOf(fq)
        ) { Ayah(it.getInt(0), it.getInt(1), it.getString(2), it.intOrNull(3), it.intOrNull(4)) }.orEmpty()
    }
}

// ───────────────────────── Adhkar ─────────────────────────
data class AdhkarCategory(val id: Int, val title: String, val count: Int)
data class Dhikr(val rowId: Long, val categoryId: Int, val text: String, val count: Int?, val source: String?, val takhrij: String?, val grade: String?)

object AdhkarRepo {
    private val db get() = Content.db("adhkar.db")
    fun available() = db != null

    fun categories(): List<AdhkarCategory> = db?.rows(
        "SELECT c.id,c.title,(SELECT COUNT(*) FROM dhikr d WHERE d.category_id=c.id) FROM category c ORDER BY c.rowid"
    ) { AdhkarCategory(it.getInt(0), it.getString(1), it.getInt(2)) }.orEmpty()

    private fun map(c: Cursor) = Dhikr(c.getLong(0), c.getInt(1), c.getString(2), c.intOrNull(3), c.strOrNull(4), c.strOrNull(5), c.strOrNull(6))
    private const val COLS = "d.id,d.category_id,d.text,d.count,d.source,d.takhrij,d.grade"

    fun items(cat: Int): List<Dhikr> = db?.rows("SELECT $COLS FROM dhikr d WHERE d.category_id=? ORDER BY d.ord,d.id", arrayOf("$cat"), ::map).orEmpty()
    fun categoryTitle(cat: Int): String = db?.rows("SELECT title FROM category WHERE id=?", arrayOf("$cat")) { it.getString(0) }?.firstOrNull() ?: ""
    fun search(q: String, limit: Int = 60): List<Dhikr> {
        val fq = ftsQuery(q) ?: return emptyList()
        return db?.rows("SELECT $COLS FROM dhikr_fts f JOIN dhikr d ON d.id=f.docid WHERE dhikr_fts MATCH ? LIMIT $limit", arrayOf(fq), ::map).orEmpty()
    }
}

// ───────────────────────── Hadith ─────────────────────────
data class HadithBook(val file: String, val slug: String, val title: String, val author: String?, val count: Int, val completeness: String, val note: String?)
data class Hadith(val rowId: Long, val number: Int?, val chapterTitle: String?, val arabic: String, val grade: String?)

object HadithRepo {
    fun books(): List<HadithBook> = Content.shipped("hadith_").mapNotNull { f ->
        val db = Content.db(f) ?: return@mapNotNull null
        val n = db.rows("SELECT COUNT(*) FROM hadith") { it.getInt(0) }.firstOrNull() ?: 0
        HadithBook(f, Content.meta(db, "slug") ?: f, Content.meta(db, "title_ar") ?: f, Content.meta(db, "author"), n,
            Content.meta(db, "completeness") ?: "unverified", Content.meta(db, "completeness_note"))
    }

    private fun dbFor(slug: String) = Content.db("hadith_$slug.db")
    private const val COLS = "h.rowid,h.number,c.title_ar,h.arabic,h.grade"
    private const val FROM = "FROM hadith h LEFT JOIN chapter c ON c.id=h.chapter_id"
    private fun map(c: Cursor) = Hadith(c.getLong(0), c.intOrNull(1), c.strOrNull(2), c.getString(3), c.strOrNull(4))

    fun page(slug: String, offset: Int, limit: Int = 40): List<Hadith> =
        dbFor(slug)?.rows("SELECT $COLS $FROM ORDER BY h.rowid LIMIT $limit OFFSET $offset", emptyArray(), ::map).orEmpty()

    fun byNumber(slug: String, n: Int): List<Hadith> =
        dbFor(slug)?.rows("SELECT $COLS $FROM WHERE h.number=? ORDER BY h.rowid", arrayOf("$n"), ::map).orEmpty()

    fun search(slug: String, q: String, limit: Int = 50): List<Hadith> {
        val fq = ftsQuery(q) ?: return emptyList()
        return dbFor(slug)?.rows("SELECT $COLS FROM hadith_fts f JOIN hadith h ON h.rowid=f.docid LEFT JOIN chapter c ON c.id=h.chapter_id WHERE hadith_fts MATCH ? LIMIT $limit", arrayOf(fq), ::map).orEmpty()
    }

    /** Search across every shipped book: (book, hadith) pairs. */
    fun searchAll(q: String, perBook: Int = 8): List<Pair<HadithBook, Hadith>> =
        books().flatMap { b -> search(b.slug, q, perBook).map { b to it } }
}
