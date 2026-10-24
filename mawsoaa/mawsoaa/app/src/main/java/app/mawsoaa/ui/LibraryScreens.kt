@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.mawsoaa.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TYPE_AR = mapOf("tafsir" to "تفسير", "hadith_sharh" to "شرح حديث", "seerah" to "سيرة", "aqeedah" to "عقيدة", "fiqh" to "فقه",
    "lexicon" to "معجم", "quran_sciences" to "علوم القرآن", "other" to "أخرى")

@Composable
fun LibraryScreen(nav: NavController, type: String) {
    val ctx = LocalContext.current
    var packs by remember { mutableStateOf(PackRepo.list(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { msg = PackRepo.import(ctx, uri) ?: "تم استيراد الحزمة"; packs = PackRepo.list(ctx) }
    }
    val shown = packs.filter { type.isBlank() || it.type == type }
    Screen(if (type == "tafsir") "التفسير وعلوم القرآن" else "المكتبة الإسلامية", { nav.popBackStack() }, actions = {
        IconButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Add, "استيراد حزمة") }
    }) { pad ->
        Column(Modifier.padding(pad)) {
            msg?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary) }
            if (shown.isEmpty()) Empty("لا توجد حزم مثبتة.\nاستورد حزمة (.db) بزر + أعلاه. أنشئ الحزم بالسكربت scripts/make_pack.py بعد التحقق من الترخيص والاكتمال.")
            else LazyColumn {
                items(shown) { p ->
                    ListItem(
                        headlineContent = { Text(p.title, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("${TYPE_AR[p.type] ?: p.type} • ${p.entries} مدخل • ${formatBytes(p.bytes)} • ${if (p.complete) "مكتملة" else "جزئية/غير موثّقة الاكتمال"}\nالترخيص: ${p.license ?: "غير مذكور"}") },
                        trailingContent = { IconButton({ PackRepo.delete(ctx, p.file); packs = PackRepo.list(ctx) }) { Icon(Icons.Filled.Delete, "حذف") } },
                        modifier = Modifier.clickable { nav.navigate("pack/${p.file}") }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun PackScreen(nav: NavController, file: String) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val list = remember(file, q) { mutableStateListOf<PackEntry>() }
    var end by remember(file, q) { mutableStateOf(false) }
    suspend fun more() { val p = withContext(Dispatchers.IO) { PackRepo.entries(ctx, file, q, list.size) }; if (p.isEmpty()) end = true else list.addAll(p) }
    LaunchedEffect(file, q) { more() }
    Screen(file.removeSuffix(".db"), { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, label = { Text("بحث") })
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(listOfNotNull(e.ref, e.title).joinToString(" • "), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(e.body, fontFamily = quranFont(), fontSize = 18.sp, lineHeight = 32.sp)
                        }
                    }
                }
                if (!end && list.isNotEmpty()) item { LaunchedEffect(list.size) { more() }; Spacer(Modifier.height(1.dp)) }
            }
        }
    }
}
