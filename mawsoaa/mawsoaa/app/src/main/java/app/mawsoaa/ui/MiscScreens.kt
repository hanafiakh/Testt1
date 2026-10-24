@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.mawsoaa.audio.AudioRepo
import app.mawsoaa.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable
fun FavoritesScreen(nav: NavController) {
    val dao = remember { UserDb.get(LocalContext.current).dao() }
    val scope = rememberCoroutineScope()
    val all by dao.allMarks().collectAsStateWithLifecycle(emptyList())
    var kind by remember { mutableStateOf("fav") }
    val shown = all.filter { it.kind == kind }
    Screen("المفضلة والعلامات", { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fav" to "المفضلة", "bookmark" to "العلامات", "note" to "الملاحظات").forEach { (k, t) -> FilterChip(kind == k, { kind = k }, { Text(t) }) }
            }
            if (shown.isEmpty()) Empty("لا شيء هنا بعد.")
            else LazyColumn {
                items(shown, key = { it.id }) { m ->
                    ListItem(
                        headlineContent = { Text(m.title, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(m.note ?: m.preview, maxLines = 3) },
                        trailingContent = { IconButton({ scope.launch { dao.removeId(m.id) } }) { Icon(Icons.Filled.Delete, "حذف") } },
                        modifier = Modifier.clickable {
                            when (m.type) {
                                "ayah" -> m.refKey.split(':').let { nav.navigate("quran/${it[0]}?ayah=${it[1]}") }
                                "dhikr" -> nav.navigate("adhkar/${m.refKey.substringBefore(':')}")
                                "hadith" -> nav.navigate("hadith/${m.refKey.substringBefore(':')}")
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(nav: NavController) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var verify by remember { mutableStateOf<String?>(null) }
    val ids = remember(tick) { AudioRepo.downloadedMoshafIds(ctx) }
    val scope = rememberCoroutineScope()
    Screen("التنزيلات", { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("التنزيل عبر Wi-Fi فقط", Modifier.weight(1f)); Switch(Prefs.wifiOnly, { Prefs.setWifiOnly(it) })
            }
            OutlinedButton({
                scope.launch {
                    verify = withContext(Dispatchers.IO) {
                        val files = AudioRepo.root(ctx).walkTopDown().filter { it.isFile }.toList()
                        val mp3 = files.filter { it.extension == "mp3" }
                        val bad = mp3.filter { f -> val h = ByteArray(3); f.inputStream().use { it.read(h) }
                            !(f.length() > 1024 && ((h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) || (h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0))) }
                        bad.forEach { it.delete() }
                        "فُحص ${mp3.size} ملفًا؛ تالف: ${bad.size}${if (bad.isNotEmpty()) " (حُذف، أعد تنزيله)" else ""}؛ تنزيلات غير مكتملة: ${files.count { it.name.endsWith(".part") }}"
                    }; tick++
                }
            }) { Text("التحقق من سلامة الملفات") }
            verify?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            if (ids.isEmpty()) Empty("لا توجد تلاوات منزَّلة.\nافتح «القراء والتلاوات» واختر قارئًا.")
            else LazyColumn {
                items(ids) { id ->
                    val m = remember(id) { AudioRepo.moshaf(id) }
                    ListItem(
                        headlineContent = { Text(m?.let { AudioRepo.reciterName(it.reciterId) } ?: "مصحف $id") },
                        supportingContent = { Text("${m?.name.orEmpty()} • ${AudioRepo.downloadedSurahs(ctx, id).size} سورة • ${formatBytes(AudioRepo.moshafBytes(ctx, id))}") },
                        trailingContent = { IconButton({ AudioRepo.deleteMoshaf(ctx, id); tick++ }) { Icon(Icons.Filled.Delete, "حذف الكل") } },
                        modifier = Modifier.clickable { m?.let { nav.navigate("reciter/${it.reciterId}") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun StorageScreen(nav: NavController) {
    val ctx = LocalContext.current
    val packs = remember { PackRepo.list(ctx) }
    val fs = remember { StatFs(ctx.filesDir.path) }
    Screen("إدارة المساحة", { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("قواعد البيانات المضمّنة", style = MaterialTheme.typography.titleMedium) }
            if (Content.manifest.isEmpty()) item { Text("لا توجد قواعد بيانات مضمّنة في هذه النسخة.") }
            items(Content.manifest) { Text("${it.name}: ${formatBytes(it.bytes)}") }
            item { Text("الحزم المستوردة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            if (packs.isEmpty()) item { Text("لا شيء") }
            items(packs) { Text("${it.title}: ${formatBytes(it.bytes)}") }
            item {
                Column {
                    Text("التلاوات المنزَّلة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(formatBytes(AudioRepo.totalBytes(ctx)))
                    Text("المساحة الحرة على الجهاز", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("${formatBytes(fs.availableBytes)} من ${formatBytes(fs.totalBytes)}")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val legal = remember {
        try {
            val j = JSONObject(ctx.assets.open("legal/sources.json").bufferedReader().readText()).getJSONArray("downloads")
            (0 until j.length()).map { j.getJSONObject(it).let { o -> "• ${o.getString("id")}: ${o.optString("license")}" } }
        } catch (e: Exception) { emptyList() }
    }
    Screen("الإعدادات", { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    Text("المظهر", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("حسب النظام", "نهاري", "ليلي").forEachIndexed { i, t -> FilterChip(Prefs.nightMode == i, { Prefs.setNight(i) }, { Text(t) }) }
                    }
                }
            }
            item {
                Column {
                    Text("حجم خط القرآن: ${Prefs.quranFontSp.toInt()}", style = MaterialTheme.typography.titleMedium)
                    Slider(Prefs.quranFontSp, { Prefs.setFont(it) }, valueRange = 18f..44f)
                }
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("تنزيل الحزم عبر Wi-Fi فقط", Modifier.weight(1f)); Switch(Prefs.wifiOnly, { Prefs.setWifiOnly(it) })
                }
            }
            item { Text("المصادر والتراخيص", style = MaterialTheme.typography.titleMedium) }
            if (legal.isEmpty()) item { Text("لا توجد بيانات مصادر (شغّل build_local_database.py).") }
            items(legal) { Text(it, style = MaterialTheme.typography.bodySmall) }
            item { Text("النصوص الشرعية تُنسخ من مصادرها دون تعديل، ولا يُنشأ أي نص أو تخريج أو درجة حديث دون مصدر. راجع أهل العلم عند الحاجة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
