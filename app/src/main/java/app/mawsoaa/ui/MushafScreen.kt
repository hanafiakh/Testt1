@file:OptIn(ExperimentalMaterial3Api::class)

package app.mawsoaa.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.webkit.WebViewAssetLoader
import app.mawsoaa.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

private fun mushafDir(c: android.content.Context) = File(c.filesDir, "mushaf")
private fun mushafInstalled(c: android.content.Context) = File(mushafDir(c), "index.html").exists() && File(mushafDir(c), "pages/001.json").exists()

/** Unzips mushaf_pack.zip (built by scripts/build_mushaf_pages.py --package) into filesDir/mushaf. Guards against zip-slip. */
private fun installPack(c: android.content.Context, uri: android.net.Uri): String? {
    val tmp = File(c.filesDir, "mushaf.tmp").apply { deleteRecursively() }
    try {
        val base = tmp.canonicalPath + File.separator
        c.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(base)) return "ملف مضغوط غير آمن"
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                    e = z.nextEntry
                }
            }
        } ?: return "تعذّر فتح الملف"
        if (!File(tmp, "index.html").exists() || !File(tmp, "pages/001.json").exists()) return "الحزمة ليست حزمة مصحف صالحة"
        mushafDir(c).deleteRecursively(); tmp.renameTo(mushafDir(c)); return null
    } catch (e: Exception) { return "فشل الاستيراد: ${e.message}" } finally { tmp.deleteRecursively() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MushafScreen(nav: NavController, page: Int) {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf(mushafInstalled(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            Thread {
                val err = installPack(ctx, uri)
                android.os.Handler(android.os.Looper.getMainLooper()).post { msg = err ?: "تم تثبيت المصحف"; installed = mushafInstalled(ctx); busy = false }
            }.start()
        }
    }
    Screen("المصحف", { nav.popBackStack() }) { pad ->
        if (!installed) {
            Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("عرض المصحف المطبوع يحتاج حزمة خطوط وبيانات الصفحات (mushaf_pack.zip).\nأنشئها بالسكربت scripts/build_mushaf_pages.py --package على جهاز متصل، وراجع ترخيص الخطوط قبل أي نشر، ثم استوردها هنا.")
                Button({ picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text(if (busy) "جارٍ التثبيت…" else "استيراد حزمة المصحف") }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            return@Screen
        }
        val dark = when (Prefs.nightMode) { 1 -> false; 2 -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
        AndroidView(modifier = Modifier.padding(pad).fillMaxSize(), factory = { c ->
            // Serves filesDir/mushaf at https://appassets.androidplatform.net/mushaf/  (no file:// access, no internet needed)
            val loader = WebViewAssetLoader.Builder().addPathHandler("/mushaf/", WebViewAssetLoader.InternalStoragePathHandler(c, mushafDir(c))).build()
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                }
                loadUrl("https://appassets.androidplatform.net/mushaf/index.html?theme=${if (dark) "dark" else "light"}#p$page")
            }
        })
    }
}
