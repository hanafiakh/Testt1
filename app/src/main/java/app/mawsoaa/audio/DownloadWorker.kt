package app.mawsoaa.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.ensureActive

/**
 * Downloads whole-surah MP3s into app-private storage: resumable (Range + .part), verified, one surah at a time.
 * Pause = cancel the work (the .part stays). Resume = enqueue again. Cancel = cancel + delete .part files.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val moshafId = inputData.getInt(K_MOSHAF, -1)
        val server = inputData.getString(K_SERVER) ?: return Result.failure()
        val surahs = inputData.getIntArray(K_SURAHS) ?: return Result.failure()
        val label = inputData.getString(K_LABEL) ?: ""
        val moshaf = Moshaf(moshafId, 0, "", null, server, surahs.toList())
        setForeground(foreground(label, 0, surahs.size))
        var done = 0
        for (s in surahs) {
            if (AudioRepo.isDownloaded(applicationContext, moshafId, s)) { done++; continue }
            if (isStopped) return Result.success()
            try {
                fetch(moshaf, s) { got, total ->
                    setProgressAsync(workDataOf(P_DONE to done, P_TOTAL to surahs.size, P_SURAH to s, P_BYTES to got, P_SIZE to total))
                }
            } catch (e: IOException) {
                return if (runAttemptCount < 20) Result.retry() else Result.failure(workDataOf(P_ERROR to (e.message ?: "io")))
            }
            done++
            setForeground(foreground(label, done, surahs.size))
        }
        return Result.success()
    }

    private suspend fun fetch(m: Moshaf, surah: Int, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val out = AudioRepo.file(applicationContext, m.id, surah)
        val part = AudioRepo.partFile(applicationContext, m.id, surah)
        out.parentFile?.mkdirs()
        val have = if (part.exists()) part.length() else 0L
        val con = URL(m.url(surah)).openConnection() as HttpURLConnection
        con.connectTimeout = 20000; con.readTimeout = 30000
        if (have > 0) con.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = con.responseCode
            if (code == 416) { // already have everything
                verifyAndCommit(part, out, part.length()); return@withContext
            }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val resumed = code == 206 && have > 0
            val start = if (resumed) have else 0L
            val total = if (resumed) start + con.contentLengthLong else con.contentLengthLong   // -1 if unknown
            RandomAccessFile(part, "rw").use { raf ->
                if (!resumed) raf.setLength(0)
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var got = start
                con.inputStream.use { ins ->
                    while (true) {
                        ensureActive()   // blocking reads: this is what makes Pause/Cancel take effect
                        val n = ins.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); got += n
                        progress(got, total)
                    }
                }
            }
            verifyAndCommit(part, out, total)
        } finally { con.disconnect() }
    }

    /** Integrity: size must equal Content-Length when known, and the file must start like an MP3. */
    private fun verifyAndCommit(part: File, out: File, expected: Long) {
        if (expected > 0 && part.length() != expected) throw IOException("size mismatch ${part.length()} != $expected")
        val head = ByteArray(3).also { b -> part.inputStream().use { it.read(b) } }
        val id3 = head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        val sync = head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0
        if (part.length() < 1024 || !(id3 || sync)) { part.delete(); throw IOException("not an MP3 file") }
        if (!part.renameTo(out)) { out.delete(); part.renameTo(out) }
    }

    private fun foreground(label: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "التنزيلات", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("تنزيل التلاوات").setContentText("$label  ($done/$total)")
            .setProgress(total, done, false).setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val K_MOSHAF = "moshaf"; const val K_SERVER = "server"; const val K_SURAHS = "surahs"; const val K_LABEL = "label"
        const val P_DONE = "done"; const val P_TOTAL = "total"; const val P_SURAH = "surah"; const val P_BYTES = "bytes"; const val P_SIZE = "size"; const val P_ERROR = "error"
        private const val CHANNEL = "downloads"; private const val NOTIF_ID = 4101
        fun workName(moshafId: Int) = "dl_$moshafId"

        /** (Re)start a download. Safe to call again to resume after pause/failure. */
        fun enqueue(c: Context, m: Moshaf, surahs: List<Int>, label: String, wifiOnly: Boolean) {
            val todo = surahs.filter { !AudioRepo.isDownloaded(c, m.id, it) }
            if (todo.isEmpty()) return
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(workDataOf(K_MOSHAF to m.id, K_SERVER to m.server, K_SURAHS to todo.toIntArray(), K_LABEL to label)).build()
            WorkManager.getInstance(c).enqueueUniqueWork(workName(m.id), ExistingWorkPolicy.REPLACE, req)
        }
        fun pause(c: Context, moshafId: Int) { WorkManager.getInstance(c).cancelUniqueWork(workName(moshafId)) }
        fun cancel(c: Context, moshafId: Int) {
            WorkManager.getInstance(c).cancelUniqueWork(workName(moshafId))
            File(AudioRepo.root(c), "$moshafId").listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }
}
