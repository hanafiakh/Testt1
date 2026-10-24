@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.mawsoaa.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

@Composable
fun QuranIndexScreen(nav: NavController) {
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val surahs = remember { QuranRepo.surahs() }
    val results by produceState(emptyList<Ayah>(), q) { value = if (q.trim().length >= 2) withContext(Dispatchers.IO) { QuranRepo.search(q) } else emptyList() }
    val names = remember(surahs) { surahs.associate { it.id to it.name } }
    val goTo = Regex("^(\\d{1,3})\\s*[:/]\\s*(\\d{1,3})$").find(q.trim())

    Screen("القرآن الكريم", { nav.popBackStack() }) { pad ->
        if (!QuranRepo.available()) { Empty(NOT_BUNDLED, Modifier.padding(pad)); return@Screen }
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                label = { Text("ابحث في القرآن، أو اكتب رقم سورة:آية مثل 2:255") })
            goTo?.let { m ->
                val s = m.groupValues[1].toInt(); val a = m.groupValues[2].toInt()
                if (surahs.any { it.id == s && a in 1..it.ayahCount })
                    Button({ nav.navigate("quran/$s?ayah=$a") }, Modifier.padding(horizontal = 12.dp)) { Text("الانتقال إلى ${names[s]} — آية $a") }
            }
            if (q.isNotBlank() && goTo == null) {
                if (results.isEmpty()) Empty(if (q.trim().length < 2) "اكتب حرفين على الأقل" else "لا نتائج")
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { a ->
                        Card(Modifier.fillMaxWidth().clickable { nav.navigate("quran/${a.surah}?ayah=${a.ayah}") }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${names[a.surah]} • ${a.ayah}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(a.text, fontFamily = quranFont(), fontSize = 20.sp, lineHeight = 36.sp)
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("السور", "الأجزاء", "الصفحات").forEachIndexed { i, t -> FilterChip(tab == i, { tab = i }, { Text(t) }) }
                }
                when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize()) {
                        items(surahs) { s ->
                            ListItem(
                                headlineContent = { Text(s.name, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text("${s.ayahCount} آية" + (s.type?.let { " • ${if (it.equals("Meccan", true)) "مكية" else if (it.equals("Medinan", true)) "مدنية" else it}" } ?: "")) },
                                leadingContent = { Text(s.id.toArabicDigits(), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.clickable { nav.navigate("quran/${s.id}") }
                            )
                            HorizontalDivider()
                        }
                    }
                    1 -> if (!QuranRepo.hasNavigation()) Empty("بيانات الأجزاء غير متوفرة (quran-data.xml لم يُدمج).") else LazyColumn(Modifier.fillMaxSize()) {
                        items((1..30).toList()) { j ->
                            ListItem(headlineContent = { Text("الجزء ${j.toArabicDigits()}") }, modifier = Modifier.clickable { QuranRepo.firstOfJuz(j)?.let { nav.navigate("quran/${it.first}?ayah=${it.second}") } })
                            HorizontalDivider()
                        }
                    }
                    else -> if (!QuranRepo.hasNavigation()) Empty("بيانات الصفحات غير متوفرة (quran-data.xml لم يُدمج).") else LazyVerticalGrid(GridCells.Adaptive(64.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        gridItems((1..604).toList()) { p ->
                            OutlinedButton({ QuranRepo.firstOfPage(p)?.let { nav.navigate("quran/${it.first}?ayah=${it.second}") } }, contentPadding = PaddingValues(0.dp)) { Text("$p") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun QuranReaderScreen(nav: NavController, surahId: Int, startAyah: Int) {
    val ctx = LocalContext.current
    val dao = remember { UserDb.get(ctx).dao() }
    val surah = remember(surahId) { QuranRepo.surahs().firstOrNull { it.id == surahId } }
    val ayahs = remember(surahId) { QuranRepo.ayahs(surahId) }
    val marks by dao.marks("ayah", "$surahId:").collectAsStateWithLifecycle(emptyList())
    val font = quranFont()
    val state = rememberLazyListState()
    // item 0 = header, item n = ayah n
    LaunchedEffect(surahId, ayahs.size) { if (ayahs.isNotEmpty() && startAyah > 1) state.scrollToItem(startAyah.coerceAtMost(ayahs.size)) }
    LaunchedEffect(surahId) {
        snapshotFlow { state.firstVisibleItemIndex }.debounce(400).collect { Prefs.saveLastRead(surahId, it.coerceAtLeast(1)) }
    }
    Screen(surah?.name ?: "سورة $surahId", { nav.popBackStack() }) { pad ->
        if (ayahs.isEmpty()) { Empty(NOT_BUNDLED, Modifier.padding(pad)); return@Screen }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(surah?.name ?: "", style = MaterialTheme.typography.headlineSmall, fontFamily = font, fontWeight = FontWeight.Bold)
                        Text("${ayahs.size} آية" + (QuranRepo.riwaya()?.let { " • رواية $it" } ?: ""), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            itemsIndexed(ayahs) { _, a ->
                val ref = "${a.surah}:${a.ayah}"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(a.text, fontFamily = font, fontSize = Prefs.quranFontSp.sp, lineHeight = (Prefs.quranFontSp * 1.9f).sp, textAlign = TextAlign.Justify)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("﴿${a.ayah.toArabicDigits()}﴾", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            a.page?.let { Text("  ص ${it.toArabicDigits()}", style = MaterialTheme.typography.labelSmall) }
                            Spacer(Modifier.weight(1f))
                            MarkBar("ayah", ref, "${surah?.name ?: surahId} • ${a.ayah}", a.text, marks)
                        }
                        marks.firstOrNull { it.refKey == ref && it.kind == "note" }?.note?.let {
                            Text("📝 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ nav.navigate("quran/${surahId - 1}") { popUpTo("quran") } }, enabled = surahId > 1) { Text("السورة السابقة") }
                    TextButton({ nav.navigate("quran/${surahId + 1}") { popUpTo("quran") } }, enabled = surahId < 114) { Text("السورة التالية") }
                }
            }
        }
    }
}
