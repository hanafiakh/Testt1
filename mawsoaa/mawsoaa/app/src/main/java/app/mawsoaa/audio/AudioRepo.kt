package app.mawsoaa.audio

import android.content.Context
import app.mawsoaa.data.Content
import app.mawsoaa.data.rows
import app.mawsoaa.data.strOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Reciter(val id: Int, val name: String)
data class Moshaf(val id: Int, val reciterId: Int, val name: String, val riwaya: String?, val server: String, val surahs: List<Int>) {
    fun url(surah: Int) = server.trimEnd('/') + "/" + "%03d".format(surah) + ".mp3"
}

object AudioRepo {
    private val db get() = Content.db("audio_catalog.db")
    fun available() = db != null

    fun reciters(): List<Reciter> = db?.rows("SELECT id,name FROM reciter ORDER BY name") { Reciter(it.getInt(0), it.getString(1)) }.orEmpty()

    private fun moshaf(c: android.database.Cursor) = Moshaf(
        c.getInt(0), c.getInt(1), c.strOrNull(2) ?: "", c.strOrNull(3), c.getString(4),
        c.getString(5).split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..114 }.sorted()
    )

    fun moshafs(reciterId: Int): List<Moshaf> =
        db?.rows("SELECT id,reciter_id,name,riwaya,server,surah_list FROM moshaf WHERE reciter_id=? ORDER BY id", arrayOf("$reciterId"), ::moshaf).orEmpty()

    fun moshaf(id: Int): Moshaf? =
        db?.rows("SELECT id,reciter_id,name,riwaya,server,surah_list FROM moshaf WHERE id=?", arrayOf("$id"), ::moshaf)?.firstOrNull()

    fun reciterName(id: Int): String = db?.rows("SELECT name FROM reciter WHERE id=?", arrayOf("$id")) { it.getString(0) }?.firstOrNull() ?: ""

    // ── local files (the ONLY thing the player ever plays) ──
    fun root(c: Context) = File(c.filesDir, "audio").apply { mkdirs() }
    fun file(c: Context, moshafId: Int, surah: Int) = File(root(c), "$moshafId/${"%03d".format(surah)}.mp3")
    fun partFile(c: Context, moshafId: Int, surah: Int) = File(file(c, moshafId, surah).path + ".part")
    fun isDownloaded(c: Context, moshafId: Int, surah: Int) = file(c, moshafId, surah).let { it.exists() && it.length() > 0 }
    fun downloadedSurahs(c: Context, moshafId: Int): List<Int> =
        File(root(c), "$moshafId").listFiles { f -> f.extension == "mp3" }.orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()

    fun deleteSurah(c: Context, moshafId: Int, surah: Int) { file(c, moshafId, surah).delete(); partFile(c, moshafId, surah).delete() }
    fun deleteMoshaf(c: Context, moshafId: Int) { File(root(c), "$moshafId").deleteRecursively() }
    fun moshafBytes(c: Context, moshafId: Int): Long = File(root(c), "$moshafId").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun downloadedMoshafIds(c: Context): List<Int> = root(c).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { it.name.toIntOrNull() }.sorted()
    fun totalBytes(c: Context): Long = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private val sizeCache = HashMap<String, Long>()
    /** Remote size via HEAD; null when unknown/offline. Real measurement, never an estimate. */
    suspend fun remoteSize(url: String): Long? = sizeCache[url] ?: withContext(Dispatchers.IO) {
        try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.requestMethod = "HEAD"; con.connectTimeout = 15000; con.readTimeout = 15000
            val len = con.contentLengthLong
            con.disconnect()
            if (len > 0) len.also { sizeCache[url] = it } else null
        } catch (e: Exception) { null }
    }
}
