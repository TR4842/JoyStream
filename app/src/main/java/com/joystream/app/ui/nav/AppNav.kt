package com.joystream.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.joystream.app.core.AppContainer
import com.joystream.app.data.model.MediaId
import com.joystream.app.ui.details.DetailsScreen
import com.joystream.app.ui.home.HomeScreen
import com.joystream.app.ui.library.LibraryScreen
import com.joystream.app.ui.livetv.LiveTvScreen
import com.joystream.app.ui.search.SearchScreen
import com.joystream.app.ui.settings.SettingsScreen
import com.joystream.app.ui.theme.JoyPurple

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIVE = "live"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    const val DETAILS = "details/{provider}/{idValue}?season={season}&episode={episode}&autoplay={autoplay}"

    fun details(id: MediaId, season: Int?, episode: Int?, autoplay: Boolean): String {
        val provider = Uri.encode(id.provider)
        val value = Uri.encode(id.value)
        return "details/$provider/$value?season=${season ?: -1}&episode=${episode ?: -1}&autoplay=$autoplay"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.SEARCH, "Search", Icons.Rounded.Search),
    Tab(Routes.LIVE, "Live", Icons.Rounded.LiveTv),
    Tab(Routes.LIBRARY, "Library", Icons.Rounded.BookmarkBorder),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun JoyStreamNav(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TABS.any { it.route == currentRoute }

    fun openDetails(id: MediaId, season: Int? = null, episode: Int? = null) {
        navController.navigate(Routes.details(id, season, episode, autoplay = false))
    }

    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = JoyPurple,
                                selectedTextColor = JoyPurple,
                                indicatorColor = JoyPurple.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenDetails = { id, season, episode -> openDetails(id, season, episode) },
                    onOpenSearch = { switchTab(Routes.SEARCH) },
                    onOpenSettings = { switchTab(Routes.SETTINGS) },
                    onOpenDownloads = { switchTab(Routes.LIBRARY) },
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    container = container,
                    onOpenDetails = { id -> openDetails(id) },
                )
            }

            composable(Routes.LIVE) {
                LiveTvScreen(
                    container = container,
                    onOpenSettings = { switchTab(Routes.SETTINGS) },
                )
            }

            composable(Routes.LIBRARY) {
                LibraryScreen(
                    container = container,
                    onOpenDetails = { id, season, episode -> openDetails(id, season, episode) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(container = container)
            }

            composable(
                route = Routes.DETAILS,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("idValue") { type = NavType.StringType },
                    navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("autoplay") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val provider = entry.arguments?.getString("provider").orEmpty()
                val idValue = entry.arguments?.getString("idValue").orEmpty()
                val season = entry.arguments?.getInt("season")?.takeIf { it > 0 }
                val episode = entry.arguments?.getInt("episode")?.takeIf { it > 0 }
                val autoplay = entry.arguments?.getBoolean("autoplay") ?: false
                DetailsScreen(
                    container = container,
                    mediaId = MediaId(provider, idValue),
                    season = season,
                    episode = episode,
                    autoplay = autoplay,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
