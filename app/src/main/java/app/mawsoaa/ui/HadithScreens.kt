@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.mawsoaa.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun completenessLabel(s: String) = when (s) {
    "verified-complete" -> "مكتمل (تم التحقق)"
    "partial" -> "جزئي"
    else -> "لم يُتحقق من اكتماله"
}

@Composable
fun HadithHomeScreen(nav: NavController) {
    var q by remember { mutableStateOf("") }
    val books = remember { HadithRepo.books() }
    val found by produceState(emptyList<Pair<HadithBook, Hadith>>(), q) { value = if (q.trim().length >= 2) withContext(Dispatchers.IO) { HadithRepo.searchAll(q) } else emptyList() }
    Screen("السنة والحديث", { nav.popBackStack() }) { pad ->
        if (books.isEmpty()) { Empty(NOT_BUNDLED, Modifier.padding(pad)); return@Screen }
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, label = { Text("بحث شامل في كل الكتب") })
            if (q.isNotBlank()) LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found) { (b, h) ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("hadith/${b.slug}") }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${b.title}${h.number?.let { " • $it" } ?: ""}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(h.arabic, fontFamily = quranFont(), fontSize = 18.sp, lineHeight = 32.sp, maxLines = 6)
                        }
                    }
                }
            } else LazyColumn {
                items(books) { b ->
                    ListItem(
                        headlineContent = { Text(b.title, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("${b.count} حديث • ${completenessLabel(b.completeness)}") },
                        modifier = Modifier.clickable { nav.navigate("hadith/${b.slug}") }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun HadithBookScreen(nav: NavController, slug: String) {
    val dao = remember { UserDb.get(LocalContext.current).dao() }
    val book = remember(slug) { HadithRepo.books().firstOrNull { it.slug == slug } }
    val marks by dao.marks("hadith", "$slug:").collectAsStateWithLifecycle(emptyList())
    var q by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    val list = remember(slug) { mutableStateListOf<Hadith>() }
    var endReached by remember(slug) { mutableStateOf(false) }
    val state = rememberLazyListState()
    val font = quranFont()

    val mode = when { num.isNotBlank() -> "num"; q.trim().length >= 2 -> "search"; else -> "browse" }
    val special by produceState(emptyList<Hadith>(), q, num) {
        value = withContext(Dispatchers.IO) {
            when (mode) { "num" -> num.toIntOrNull()?.let { HadithRepo.byNumber(slug, it) }.orEmpty(); "search" -> HadithRepo.search(slug, q); else -> emptyList() }
        }
    }
    suspend fun more() { val p = withContext(Dispatchers.IO) { HadithRepo.page(slug, list.size) }; if (p.isEmpty()) endReached = true else list.addAll(p) }
    LaunchedEffect(slug) { more() }
    val nearEnd by remember { derivedStateOf { val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= list.size - 5 } }
    LaunchedEffect(nearEnd, mode) { if (mode == "browse" && nearEnd && !endReached && list.isNotEmpty()) more() }

    Screen(book?.title ?: slug, { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad)) {
            if (book != null && book.completeness != "verified-complete")
                Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth()) {
                    Text("${completenessLabel(book.completeness)}${book.note?.let { " — $it" } ?: ""}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text("بحث في الكتاب") })
                OutlinedTextField(num, { num = it.filter(Char::isDigit).take(6) }, Modifier.width(110.dp), singleLine = true, label = { Text("رقم") })
            }
            val shown = if (mode == "browse") list else special
            if (shown.isEmpty() && mode != "browse") Empty("لا نتائج")
            LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.rowId }) { h ->
                    val ref = "$slug:${h.rowId}"
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(listOfNotNull(h.number?.let { "رقم $it" }, h.chapterTitle).joinToString(" • "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(h.arabic, fontFamily = font, fontSize = Prefs.quranFontSp.sp * 0.8f, lineHeight = (Prefs.quranFontSp * 1.5f).sp)
                            h.grade?.let { Text("الحكم: $it", style = MaterialTheme.typography.bodySmall) }
                            Row { Spacer(Modifier.weight(1f)); MarkBar("hadith", ref, "${book?.title ?: slug}${h.number?.let { " • $it" } ?: ""}", h.arabic, marks) }
                        }
                    }
                }
            }
        }
    }
}
