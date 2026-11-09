package com.sgexpense

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sgexpense.ui.components.appViewModel
import com.sgexpense.ui.edit.EditTransactionScreen
import com.sgexpense.ui.home.HomeScreen
import com.sgexpense.ui.importer.ImportScreen
import com.sgexpense.ui.importer.ImportViewModel
import com.sgexpense.ui.importer.displayName
import com.sgexpense.ui.list.TransactionListScreen
import com.sgexpense.ui.settings.CaptureLogScreen
import com.sgexpense.ui.settings.CategoriesScreen
import com.sgexpense.ui.settings.SettingsScreen
import com.sgexpense.ui.settings.WatchedAppsScreen
import com.sgexpense.ui.stats.StatsScreen
import com.sgexpense.ui.theme.SgExpenseTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("list", "History", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("stats", "Stats", Icons.Filled.PieChart),
    Tab("import", "Import", Icons.Filled.FileUpload),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

/** Files/images shared into the app, consumed by the Import screen. */
private sealed interface Shared {
    data class Images(val uris: List<Uri>) : Shared
    data class File(val uri: Uri) : Shared
}

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<Shared?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { SgExpenseTheme { AppRoot(shared) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val type = intent.type.orEmpty()
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        shared.value = if (type.startsWith("image/")) Shared.Images(uris) else Shared.File(uris.first())
    }
}

@Composable
private fun AppRoot(shared: androidx.compose.runtime.MutableState<Shared?>) {
    val nav = rememberNavController()
    // Activity-scoped so review items survive tab switches.
    val importVm = appViewModel { ImportViewModel(it) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pending by shared

    LaunchedEffect(pending) {
        when (val s = pending) {
            is Shared.Images -> importVm.addImages(s.uris)
            is Shared.File -> importVm.addFile(s.uri, displayName(context, s.uri))
            null -> return@LaunchedEffect
        }
        shared.value = null
        nav.navigateTab("import")
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        // Each screen's own Scaffold handles the status bar; this one only reserves room for the tab bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigateTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        val bottom = PaddingValues(bottom = pad.calculateBottomPadding())
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(bottom).consumeWindowInsets(bottom)) {
            composable("home") {
                HomeScreen(
                    onAdd = { nav.navigate("edit/0") },
                    onEdit = { nav.navigate("edit/$it") },
                    onSetupCapture = { nav.navigateTab("settings") },
                    onSeeAll = { nav.navigateTab("list") },
                )
            }
            composable("list") { TransactionListScreen(onEdit = { nav.navigate("edit/$it") }) }
            composable("stats") { StatsScreen() }
            composable("import") { ImportScreen(importVm) }
            composable("settings") {
                SettingsScreen(
                    onCategories = { nav.navigate("categories") },
                    onWatchedApps = { nav.navigate("watched") },
                    onCaptureLog = { nav.navigate("capturelog") },
                )
            }
            composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                EditTransactionScreen(entry.arguments?.getLong("id") ?: 0L, onDone = { nav.popBackStack() })
            }
            composable("categories") { CategoriesScreen(onBack = { nav.popBackStack() }) }
            composable("watched") { WatchedAppsScreen(onBack = { nav.popBackStack() }) }
            composable("capturelog") { CaptureLogScreen(onBack = { nav.popBackStack() }, onOpenTransaction = { nav.navigate("edit/$it") }) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
