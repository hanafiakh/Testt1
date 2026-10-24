@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.mawsoaa.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun AdhkarHomeScreen(nav: NavController) {
    var q by remember { mutableStateOf("") }
    val cats = remember { AdhkarRepo.categories() }
    val found by produceState(emptyList<Dhikr>(), q) { value = if (q.trim().length >= 2) withContext(Dispatchers.IO) { AdhkarRepo.search(q) } else emptyList() }
    Screen("الأذكار والأدعية", { nav.popBackStack() }) { pad ->
        if (!AdhkarRepo.available()) { Empty(NOT_BUNDLED, Modifier.padding(pad)); return@Screen }
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, label = { Text("ابحث في الأذكار") })
            if (q.isNotBlank()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(found) { d ->
                        Card(Modifier.fillMaxWidth().clickable { nav.navigate("adhkar/${d.categoryId}") }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(AdhkarRepo.categoryTitle(d.categoryId), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(d.text, fontFamily = quranFont(), fontSize = 18.sp, lineHeight = 32.sp, maxLines = 4)
                            }
                        }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(cats) { c ->
                    ListItem(headlineContent = { Text(c.title) }, supportingContent = { Text("${c.count} ذكر") }, modifier = Modifier.clickable { nav.navigate("adhkar/${c.id}") })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun AdhkarCategoryScreen(nav: NavController, cat: Int) {
    val ctx = LocalContext.current
    val dao = remember { UserDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val title = remember(cat) { AdhkarRepo.categoryTitle(cat) }
    val items = remember(cat) { AdhkarRepo.items(cat) }
    val day = remember { LocalDate.now().toString() }
    val marks by dao.marks("dhikr", "$cat:").collectAsStateWithLifecycle(emptyList())
    val progress by dao.progress(day).collectAsStateWithLifecycle(emptyList())
    val font = quranFont()

    Screen(title, { nav.popBackStack() }, actions = {
        IconButton({ scope.launch { dao.resetProgress(day, "$cat:") } }) { Icon(Icons.Filled.Refresh, "تصفير عداد اليوم") }
    }) { pad ->
        val doneCount = items.count { d -> (d.count ?: 1) <= (progress.firstOrNull { it.itemKey == "$cat:${d.rowId}" }?.done ?: 0) }
        Column(Modifier.padding(pad)) {
            LinearProgressIndicator(progress = { if (items.isEmpty()) 0f else doneCount / items.size.toFloat() }, Modifier.fillMaxWidth())
            Text("اكتمل اليوم: $doneCount من ${items.size}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { d ->
                    val key = "$cat:${d.rowId}"
                    val target = d.count ?: 1
                    val done = progress.firstOrNull { it.itemKey == key }?.done ?: 0
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.text, fontFamily = font, fontSize = Prefs.quranFontSp.sp * 0.8f, lineHeight = (Prefs.quranFontSp * 1.5f).sp)
                            // Metadata is shown ONLY when the source provides it; nothing is invented.
                            listOfNotNull(d.source?.let { "المصدر: $it" }, d.takhrij?.let { "التخريج: $it" }, d.grade?.let { "الدرجة: $it" }).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton({ if (done < target) scope.launch { dao.setProgress(DailyProgress(day, key, done + 1)) } }, enabled = done < target) {
                                    Text(if (done >= target) "تم ✓" else "${done.toArabicDigits()} / ${target.toArabicDigits()}", fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.weight(1f))
                                MarkBar("dhikr", key, title, d.text, marks)
                            }
                        }
                    }
                }
            }
        }
    }
}
