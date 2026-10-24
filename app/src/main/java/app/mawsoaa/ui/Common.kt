@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mawsoaa.data.Mark
import app.mawsoaa.data.UserDb
import kotlinx.coroutines.launch

@Composable
fun Screen(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White
                )
            )
        },
        content = content
    )
}

@Composable
fun Empty(msg: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(msg, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

const val NOT_BUNDLED = "هذا القسم غير مضمَّن في هذه النسخة بعد.\nشغّل سكربتات scripts/ على جهاز متصل ثم أعد بناء التطبيق (انظر README)."

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.2f غ.ب".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f م.ب".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f ك.ب".format(n / 1024.0)
    else -> "$n بايت"
}

/** Favorite / bookmark / note buttons shared by ayat, adhkar and hadith. [marks] = marks of the same type already loaded by the screen. */
@Composable
fun MarkBar(type: String, ref: String, title: String, preview: String, marks: List<Mark>) {
    val dao = remember { UserDb.get(LocalContext.current).dao() }
    val scope = rememberCoroutineScope()
    val mine = marks.filter { it.refKey == ref }
    fun has(k: String) = mine.any { it.kind == k }
    var noteOpen by remember { mutableStateOf(false) }

    fun toggle(kind: String) = scope.launch {
        if (has(kind)) dao.remove(type, ref, kind) else dao.upsert(Mark(type = type, refKey = ref, kind = kind, title = title, preview = preview.take(160)))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { toggle("fav") }) { Icon(if (has("fav")) Icons.Filled.Star else Icons.Outlined.StarBorder, "مفضلة", tint = MaterialTheme.colorScheme.secondary) }
        IconButton(onClick = { toggle("bookmark") }) { Icon(if (has("bookmark")) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "علامة", tint = MaterialTheme.colorScheme.primary) }
        IconButton(onClick = { noteOpen = true }) { Icon(if (has("note")) Icons.Filled.Edit else Icons.Outlined.Edit, "ملاحظة", tint = MaterialTheme.colorScheme.primary) }
    }
    if (noteOpen) {
        var text by remember { mutableStateOf(mine.firstOrNull { it.kind == "note" }?.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("ملاحظة") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (text.isBlank()) dao.remove(type, ref, "note")
                        else dao.upsert(Mark(type = type, refKey = ref, kind = "note", title = title, preview = preview.take(160), note = text))
                    }
                    noteOpen = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("إلغاء") } }
        )
    }
}
