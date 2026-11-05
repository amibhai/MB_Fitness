package com.swastik.mbfitness.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swastik.mbfitness.R
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.screens.home.HomeScreen
import com.swastik.mbfitness.ui.screens.lock.SetPinScreen
import com.swastik.mbfitness.ui.screens.lock.SetPinStage
import com.swastik.mbfitness.ui.screens.lock.SetPinViewModel
import com.swastik.mbfitness.ui.screens.settings.SettingsScreen
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object MembersRoute

@Serializable
data object TodayRoute

@Serializable
data object MessagesRoute

@Serializable
data object ReportsRoute

@Serializable
data object SettingsRoute

@Serializable
data object SetPinRoute

private data class TopLevel(val route: Any, val type: KClass<*>, val label: String, @DrawableRes val icon: Int)

private val topLevels = listOf(
    TopLevel(MembersRoute, MembersRoute::class, "Home", R.drawable.ic_group),
    TopLevel(TodayRoute, TodayRoute::class, "Today", R.drawable.ic_today),
    TopLevel(MessagesRoute, MessagesRoute::class, "SMS", R.drawable.ic_sms),
    TopLevel(ReportsRoute, ReportsRoute::class, "Reports", R.drawable.ic_bar_chart),
    TopLevel(SettingsRoute, SettingsRoute::class, "Settings", R.drawable.ic_settings),
)

/** Everything behind the lock screen. */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val colors = MbTheme.colors

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            val isTopLevel = topLevels.any { destination?.hasRoute(it.type) == true }
            if (isTopLevel) {
                NavigationBar(containerColor = colors.surface) {
                    topLevels.forEach { item ->
                        NavigationBarItem(
                            selected = destination?.hasRoute(item.type) == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = {
                                // Shrinks to fit rather than clipping at a large font (SPEC 10.1).
                                Text(
                                    item.label,
                                    style = MbType.Caption,
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MbType.Caption.fontSize),
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.avatarText,
                                selectedTextColor = colors.textPrimary,
                                indicatorColor = colors.avatarBackground,
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = MembersRoute, Modifier.padding(padding)) {
            composable<MembersRoute> { HomeScreen() }
            composable<TodayRoute> { Placeholder("Today's closing") }
            composable<MessagesRoute> { Placeholder("Messages") }
            composable<ReportsRoute> { Placeholder("Reports") }
            composable<SettingsRoute> { SettingsScreen(onSetPin = { navController.navigate(SetPinRoute) }) }
            composable<SetPinRoute> {
                val viewModel = appViewModel { app -> SetPinViewModel(app.appLock, app.settings) }
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                if (state.stage == SetPinStage.DONE) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    SetPinScreen(state, viewModel, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/** Replaced screen by screen in the later phases. */
@Composable
private fun Placeholder(title: String) {
    Column(Modifier.fillMaxSize()) {
        MbTopBar(title)
        Box(Modifier.fillMaxSize()) {
            EmptyState("Nothing here yet.")
        }
    }
}
