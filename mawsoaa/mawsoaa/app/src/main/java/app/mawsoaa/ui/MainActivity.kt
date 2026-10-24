package app.mawsoaa.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mawsoaa.audio.AudioRepo
import app.mawsoaa.audio.PlayerController
import app.mawsoaa.data.*

val LocalPlayer = staticCompositionLocalOf<PlayerController> { error("no player") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MawsoaaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val player = remember { PlayerController(ctx) }
    val nav = rememberNavController()
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    CompositionLocalProvider(LocalPlayer provides player) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                    composable("home") { HomeScreen(nav) }
                    composable("quran") { QuranIndexScreen(nav) }
                    composable("quran/{surah}?ayah={ayah}", listOf(navArgument("surah") { type = NavType.IntType }, navArgument("ayah") { type = NavType.IntType; defaultValue = 1 })) {
                        QuranReaderScreen(nav, it.arguments!!.getInt("surah"), it.arguments!!.getInt("ayah"))
                    }
                    composable("mushaf?page={page}", listOf(navArgument("page") { type = NavType.IntType; defaultValue = 1 })) { MushafScreen(nav, it.arguments!!.getInt("page")) }
                    composable("adhkar") { AdhkarHomeScreen(nav) }
                    composable("adhkar/{cat}", listOf(navArgument("cat") { type = NavType.IntType })) { AdhkarCategoryScreen(nav, it.arguments!!.getInt("cat")) }
                    composable("hadith") { HadithHomeScreen(nav) }
                    composable("hadith/{slug}") { HadithBookScreen(nav, it.arguments!!.getString("slug")!!) }
                    composable("reciters") { ReciterListScreen(nav) }
                    composable("reciter/{id}", listOf(navArgument("id") { type = NavType.IntType })) { ReciterScreen(nav, it.arguments!!.getInt("id")) }
                    composable("library?type={type}", listOf(navArgument("type") { type = NavType.StringType; defaultValue = "" })) { LibraryScreen(nav, it.arguments!!.getString("type").orEmpty()) }
                    composable("pack/{file}") { PackScreen(nav, it.arguments!!.getString("file")!!) }
                    composable("downloads") { DownloadsScreen(nav) }
                    composable("favorites") { FavoritesScreen(nav) }
                    composable("storage") { StorageScreen(nav) }
                    composable("settings") { SettingsScreen(nav) }
                }
                PlayerBar(player)
            }
        }
    }
}

private data class Tile(val title: String, val icon: ImageVector, val route: String)

@Composable
fun HomeScreen(nav: NavController) {
    val tiles = listOf(
        Tile("القرآن الكريم", Icons.Filled.MenuBook, "quran"),
        Tile("المصحف (صفحات مطبوعة)", Icons.Filled.AutoStories, "mushaf?page=1"),
        Tile("القراء والتلاوات", Icons.Filled.Headphones, "reciters"),
        Tile("السنة والحديث", Icons.Filled.AutoStories, "hadith"),
        Tile("الأذكار والأدعية", Icons.Filled.WbSunny, "adhkar"),
        Tile("التفسير وعلوم القرآن", Icons.Filled.Translate, "library?type=tafsir"),
        Tile("المكتبة الإسلامية", Icons.Filled.LocalLibrary, "library?type="),
        Tile("التنزيلات", Icons.Filled.Download, "downloads"),
        Tile("المفضلة والعلامات", Icons.Filled.Star, "favorites"),
        Tile("التخزين", Icons.Filled.Storage, "storage"),
        Tile("الإعدادات", Icons.Filled.Settings, "settings")
    )
    val last = remember { Prefs.lastRead() }
    val status = remember {
        listOf("القرآن" to QuranRepo.available(), "الأذكار" to AdhkarRepo.available(), "الحديث" to HadithRepo.books().isNotEmpty(), "كتالوج القراء" to AudioRepo.available())
    }
    Screen("الموسوعة الإسلامية", null) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            if (last != null && QuranRepo.available()) {
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("quran/${last.first}?ayah=${last.second}") }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MenuBook, null); Spacer(Modifier.width(12.dp))
                        Text("متابعة القراءة: سورة ${last.first} — آية ${last.second}", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tiles) { t ->
                    Card(Modifier.fillMaxWidth().height(104.dp).clickable { nav.navigate(t.route) }, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(t.title, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            // Honest status: what is actually inside this build.
            Text(status.joinToString("  •  ") { (n, ok) -> "$n: ${if (ok) "✓" else "غير مضمَّن"}" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
