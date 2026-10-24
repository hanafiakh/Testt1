@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.mawsoaa.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.mawsoaa.audio.*
import app.mawsoaa.data.*
import kotlinx.coroutines.launch

@Composable
fun ReciterListScreen(nav: NavController) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val all = remember { AudioRepo.reciters() }
    val selected = remember { mutableStateListOf<Int>() }
    var confirm by remember { mutableStateOf(false) }
    val list = remember(q, all) { all.filter { q.isBlank() || normalizeAr(it.name).contains(normalizeAr(q)) } }

    Screen("القراء والتلاوات", { nav.popBackStack() }) { pad ->
        if (!AudioRepo.available()) { Empty(NOT_BUNDLED, Modifier.padding(pad)); return@Screen }
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, label = { Text("ابحث عن قارئ  (اضغط مطولًا لتحديد عدة قراء)") })
            if (selected.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ confirm = true }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("تنزيل ${selected.size} مصحف") }
                TextButton({ selected.clear() }) { Text("إلغاء التحديد") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { r ->
                    val on = r.id in selected
                    ListItem(
                        headlineContent = { Text(r.name) },
                        leadingContent = if (selected.isNotEmpty()) ({ Checkbox(on, { if (on) selected.remove(r.id) else selected.add(r.id) }) }) else null,
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selected.isNotEmpty()) { if (on) selected.remove(r.id) else selected.add(r.id) } else nav.navigate("reciter/${r.id}") },
                            onLongClick = { if (on) selected.remove(r.id) else selected.add(r.id) })
                    )
                    HorizontalDivider()
                }
            }
        }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("تنزيل المصاحف الكاملة؟") },
            text = { Text("سيبدأ تنزيل الرواية الأولى المتاحة لكل قارئ محدد بكامل سورها. المصحف الواحد قد يبلغ مئات الميغابايت؛ افتح صفحة القارئ إن أردت قياس الحجم قبل التنزيل.\n" + if (Prefs.wifiOnly) "التنزيل عبر Wi-Fi فقط." else "تنبيه: التنزيل عبر بيانات الجوال مسموح في الإعدادات.") },
            confirmButton = {
                TextButton({
                    selected.forEach { id -> AudioRepo.moshafs(id).firstOrNull()?.let { m -> DownloadWorker.enqueue(ctx, m, m.surahs, AudioRepo.reciterName(id), Prefs.wifiOnly) } }
                    selected.clear(); confirm = false; nav.navigate("downloads")
                }) { Text("ابدأ") }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ReciterScreen(nav: NavController, reciterId: Int) {
    val ctx = LocalContext.current
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val reciter = remember { AudioRepo.reciterName(reciterId) }
    val moshafs = remember { AudioRepo.moshafs(reciterId) }
    var sel by remember { mutableIntStateOf(0) }
    val m = moshafs.getOrNull(sel)
    val names = remember { QuranRepo.surahs().associate { it.id to it.name } }
    fun nameOf(s: Int) = names[s] ?: "سورة $s"
    var tick by remember { mutableIntStateOf(0) }       // bump to refresh disk state
    var sizeText by remember(m?.id) { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }

    val work = if (m != null) WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(DownloadWorker.workName(m.id)).collectAsStateWithLifecycle(emptyList()).value.firstOrNull() else null
    val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED
    LaunchedEffect(work?.state, work?.progress?.getInt(DownloadWorker.P_DONE, 0)) { tick++ }

    Screen(reciter, { nav.popBackStack() }) { pad ->
        if (m == null) { Empty("لا توجد تسجيلات لهذا القارئ في الكتالوج.", Modifier.padding(pad)); return@Screen }
        val have = remember(tick, m.id) { AudioRepo.downloadedSurahs(ctx, m.id).toSet() }
        val missing = m.surahs.filter { it !in have }
        Column(Modifier.padding(pad)) {
            if (moshafs.size > 1) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                moshafs.forEachIndexed { i, x -> FilterChip(sel == i, { sel = i }, { Text(x.name.ifBlank { "مصحف ${i + 1}" }, maxLines = 1) }) }
            }
            Text("الرواية: ${m.name.ifBlank { m.riwaya ?: "غير محددة في المصدر" }} • المتاح في الكتالوج: ${m.surahs.size} سورة • المنزَّل: ${have.size}", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    OutlinedButton({ DownloadWorker.pause(ctx, m.id) }) { Icon(Icons.Filled.Pause, null); Text(" إيقاف") }
                    TextButton({ DownloadWorker.cancel(ctx, m.id); tick++ }) { Text("إلغاء") }
                } else if (missing.isNotEmpty()) {
                    Button({ DownloadWorker.enqueue(ctx, m, missing, reciter, Prefs.wifiOnly) }) { Icon(Icons.Filled.Download, null); Text(if (have.isEmpty()) " تنزيل الكل" else " متابعة/تنزيل المتبقي") }
                }
                OutlinedButton({
                    probing = true
                    scope.launch {
                        var total = 0L; var known = 0
                        for (s in missing) AudioRepo.remoteSize(m.url(s))?.let { total += it; known++ }
                        sizeText = if (missing.isEmpty()) "لا شيء متبقٍ" else if (known == 0) "تعذّر قياس الحجم (لا اتصال؟)" else "${formatBytes(total)} (قيس $known من ${missing.size} ملف)"
                        probing = false
                    }
                }, enabled = !probing && missing.isNotEmpty()) { Text(if (probing) "جارٍ القياس…" else "قياس حجم المتبقي") }
            }
            sizeText?.let { Text("الحجم المتبقي: $it", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            if (running) work?.progress?.let { p ->
                val d = p.getInt(DownloadWorker.P_DONE, 0); val t = p.getInt(DownloadWorker.P_TOTAL, 0)
                val b = p.getLong(DownloadWorker.P_BYTES, 0); val sz = p.getLong(DownloadWorker.P_SIZE, 0)
                Column(Modifier.padding(12.dp)) {
                    LinearProgressIndicator(progress = { if (sz > 0) b / sz.toFloat() else 0f }, Modifier.fillMaxWidth())
                    Text(if (work?.state == WorkInfo.State.ENQUEUED) "بانتظار الشبكة${if (Prefs.wifiOnly) " (Wi-Fi فقط)" else ""}…" else "سورة ${nameOf(p.getInt(DownloadWorker.P_SURAH, 0))} — $d/$t • ${formatBytes(b)}${if (sz > 0) " من ${formatBytes(sz)}" else ""}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Prefs.lastListen()?.takeIf { it.moshafId == m.id && it.surah in have }?.let { l ->
                TextButton({ player.play(m, reciter, ::nameOf, l.surah, l.posMs) }, Modifier.padding(horizontal = 4.dp)) { Icon(Icons.Filled.PlayArrow, null); Text(" متابعة الاستماع: ${nameOf(l.surah)}") }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(m.surahs) { s ->
                    val ok = s in have
                    ListItem(
                        headlineContent = { Text(nameOf(s)) },
                        supportingContent = { Text(if (ok) "منزَّلة • ${formatBytes(AudioRepo.file(ctx, m.id, s).length())}" else "غير منزَّلة") },
                        leadingContent = { Text("$s") },
                        trailingContent = {
                            Row {
                                if (ok) {
                                    IconButton({ player.play(m, reciter, ::nameOf, s) }) { Icon(Icons.Filled.PlayArrow, "تشغيل", tint = MaterialTheme.colorScheme.primary) }
                                    IconButton({ AudioRepo.deleteSurah(ctx, m.id, s); tick++ }) { Icon(Icons.Filled.Delete, "حذف") }
                                } else IconButton({ DownloadWorker.enqueue(ctx, m, listOf(s), reciter, Prefs.wifiOnly) }) { Icon(Icons.Filled.Download, "تنزيل") }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Mini player: shown under every screen while something is queued. Works with the app in the background via MediaSessionService. */
@Composable
fun PlayerBar(p: PlayerController) {
    if (!p.hasMedia) return
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 6.dp, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
                p.sleepMinutesLeft?.let { Text("⏲ $it د", style = MaterialTheme.typography.labelSmall) }
                IconButton({ p.stop() }) { Icon(Icons.Filled.Close, "إغلاق") }
            }
            Slider(value = if (p.durationMs > 0) p.positionMs / p.durationMs.toFloat() else 0f, onValueChange = { if (p.durationMs > 0) p.seekTo((it * p.durationMs).toLong()) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                IconButton({ p.cycleRepeat() }) {
                    Icon(if (p.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "تكرار",
                        tint = if (p.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
                IconButton({ p.prev() }) { Icon(Icons.Filled.SkipPrevious, "السابق") }
                FilledIconButton({ p.toggle() }) { Icon(if (p.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل/إيقاف") }
                IconButton({ p.next() }) { Icon(Icons.Filled.SkipNext, "التالي") }
                Box {
                    TextButton({ speedMenu = true }) { Text("${p.speed}×") }
                    DropdownMenu(speedMenu, { speedMenu = false }) { listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> DropdownMenuItem({ Text("$s×") }, { p.setSpeed(s); speedMenu = false }) } }
                }
                Box {
                    IconButton({ sleepMenu = true }) { Icon(Icons.Filled.Bedtime, "مؤقت النوم") }
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(15, 30, 45, 60).forEach { mnt -> DropdownMenuItem({ Text("بعد $mnt دقيقة") }, { p.sleepIn(mnt); sleepMenu = false }) }
                        DropdownMenuItem({ Text("إلغاء المؤقت") }, { p.sleepIn(null); sleepMenu = false })
                    }
                }
            }
        }
    }
}
