package app.mawsoaa.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.mawsoaa.data.Prefs
import kotlinx.coroutines.*

/** UI-side handle to the playback service. Only ever queues files that exist on disk. */
class PlayerController(private val ctx: Context) {
    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sleepJob: Job? = null

    var isPlaying by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set
    var hasMedia by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var speed by mutableStateOf(1f); private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var sleepMinutesLeft by mutableStateOf<Int?>(null); private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(p: Boolean) { isPlaying = p; if (!p) saveResume() }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) { sync() ; if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) saveResume() }
        override fun onPlaybackStateChanged(state: Int) = sync()
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            controller = f.get().also { it.addListener(listener) }
            sync(); pending?.invoke(); pending = null
        }, ContextCompat.getMainExecutor(ctx))
        scope.launch { while (isActive) { controller?.let { positionMs = it.currentPosition; durationMs = maxOf(0, it.duration) }; delay(500) } }
    }

    private fun sync() {
        val c = controller ?: return
        isPlaying = c.isPlaying; hasMedia = c.mediaItemCount > 0
        title = c.mediaMetadata.title?.toString().orEmpty()
        speed = c.playbackParameters.speed; repeatMode = c.repeatMode
    }

    private fun saveResume() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val (m, s) = id.split(':').let { it[0].toIntOrNull() to it.getOrNull(1)?.toIntOrNull() }
        if (m != null && s != null) Prefs.saveListen(m, s, c.currentPosition)
    }

    /** Queue = the surahs of this moshaf that are downloaded; starts at [startSurah]. */
    fun play(moshaf: Moshaf, reciterName: String, surahName: (Int) -> String, startSurah: Int, startPos: Long = 0) {
        val avail = AudioRepo.downloadedSurahs(ctx, moshaf.id)
        val idx = avail.indexOf(startSurah)
        if (idx < 0) return
        val items = avail.map { s ->
            MediaItem.Builder().setMediaId("${moshaf.id}:$s").setUri(Uri.fromFile(AudioRepo.file(ctx, moshaf.id, s)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(surahName(s)).setArtist(reciterName).build()).build()
        }
        val go = { controller?.let { it.setMediaItems(items, idx, startPos); it.prepare(); it.play() }; Unit }
        if (controller != null) go() else pending = go
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun setSpeed(v: Float) { controller?.setPlaybackSpeed(v); speed = v }
    fun cycleRepeat() {
        val n = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE; Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
        controller?.repeatMode = n; repeatMode = n
    }
    fun stop() { saveResume(); controller?.run { stop(); clearMediaItems() }; hasMedia = false }

    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel(); sleepMinutesLeft = minutes
        if (minutes == null) return
        sleepJob = scope.launch {
            var left = minutes
            while (left > 0) { delay(60_000); left--; sleepMinutesLeft = left }
            controller?.pause(); sleepMinutesLeft = null
        }
    }
}
