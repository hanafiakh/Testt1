package app.mawsoaa.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small settings store. Compose-observable; persisted in SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences

    /** 0 = follow system, 1 = light, 2 = dark */
    var nightMode by mutableIntStateOf(0); private set
    var quranFontSp by mutableFloatStateOf(26f); private set
    var wifiOnly by mutableStateOf(true); private set

    fun init(c: Context) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        nightMode = sp.getInt("night", 0)
        quranFontSp = sp.getFloat("fontSp", 26f)
        wifiOnly = sp.getBoolean("wifiOnly", true)
    }

    fun setNight(v: Int) { nightMode = v; sp.edit().putInt("night", v).apply() }
    fun setFont(v: Float) { quranFontSp = v; sp.edit().putFloat("fontSp", v).apply() }
    fun setWifiOnly(v: Boolean) { wifiOnly = v; sp.edit().putBoolean("wifiOnly", v).apply() }

    // Last reading position (Quran)
    fun lastRead(): Pair<Int, Int>? =
        if (sp.contains("lrS")) sp.getInt("lrS", 1) to sp.getInt("lrA", 1) else null
    fun saveLastRead(surah: Int, ayah: Int) { sp.edit().putInt("lrS", surah).putInt("lrA", ayah).apply() }

    // Last listening position
    data class Listen(val moshafId: Int, val surah: Int, val posMs: Long)
    fun lastListen(): Listen? =
        if (sp.contains("llM")) Listen(sp.getInt("llM", 0), sp.getInt("llS", 1), sp.getLong("llP", 0)) else null
    fun saveListen(moshafId: Int, surah: Int, posMs: Long) {
        sp.edit().putInt("llM", moshafId).putInt("llS", surah).putLong("llP", posMs).apply()
    }
}
