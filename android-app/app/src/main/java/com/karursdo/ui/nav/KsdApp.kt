package com.karursdo.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Villa
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.karursdo.ui.directory.DirectoryScreen
import com.karursdo.ui.directory.EmployeeDetailScreen
import com.karursdo.ui.directory.OfficeDetailScreen
import com.karursdo.ui.directory.OutsiderDetailScreen
import com.karursdo.ui.home.HomeScreen
import com.karursdo.ui.ingest.ImportScreen
import com.karursdo.ui.mo.MoBeatListScreen
import com.karursdo.ui.mo.MoLandingScreen
import com.karursdo.ui.mo.MoOfficeDetailScreen
import com.karursdo.ui.offices.OfficeManagementScreen
import com.karursdo.ui.offices.OfficeMasterDetailScreen
import com.karursdo.data.repo.UserDataRepository
import com.karursdo.ui.chat.ChatScreen
import com.karursdo.ui.scaffolds.ArrangementsScreen
import com.karursdo.ui.theme.Brand
import com.karursdo.ui.user.ProfileScreen
import com.karursdo.ui.user.UserAdminScreen

private data class BottomDest(
    val route: Any,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun KsdApp(onLogout: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()

    val bottomDests = listOf(
        BottomDest(HomeRoute, "Home", Icons.Outlined.Home),
        BottomDest(MessageRoute, "Message", Icons.Outlined.ChatBubbleOutline),
        BottomDest(OfficeManagementRoute, "Offices", Icons.Outlined.Villa),
        BottomDest(MoRoute, "MO", Icons.Outlined.Route),
        BottomDest(ProfileRoute, "Profile", Icons.Outlined.AccountCircle)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomDests.forEach { dest ->
                    val selected = backStack?.destination?.route
                        ?.substringBefore("?")?.substringBefore("/")
                        ?.endsWith(dest.route::class.simpleName ?: "") == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brand.Indigo,
                            selectedTextColor = Brand.Indigo,
                            indicatorColor = Brand.BadgeDsBg
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            enterTransition = {
                fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
            },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = {
                fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 12 }
            },
            // Consume the Scaffold's inset padding so screens that pad by WindowInsets.ime
            // (the chat input bar) measure the keyboard relative to the content area rather
            // than the whole screen — without this the bar floats a bottom-bar's height above
            // the keyboard.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenImport = { navController.navigate(ImportRoute) },
                    onOpenDirectory = { navController.navigate(DirectoryRoute) },
                    onOpenArrangements = { navController.navigate(ArrangementsRoute) },
                    onOpenMoBeat = { beat -> navController.navigate(MoBeatListRoute(beat)) }
                )
            }
            composable<DirectoryRoute> {
                DirectoryScreen(
                    onOpenOffice = { o ->
                        navController.navigate(
                            OfficeDetailRoute(o.officeId, o.officeName ?: o.officeId)
                        )
                    },
                    onOpenEmployee = { type, id ->
                        navController.navigate(EmployeeDetailRoute(type, id))
                    },
                    onOpenOutsider = { id -> navController.navigate(OutsiderDetailRoute(id)) }
                )
            }
            composable<OfficeDetailRoute> { entry ->
                val r = entry.toRoute<OfficeDetailRoute>()
                OfficeDetailScreen(
                    officeId = r.officeId,
                    officeName = r.officeName,
                    onOpenEmployee = { type, id ->
                        navController.navigate(EmployeeDetailRoute(type, id))
                    }
                )
            }
            composable<EmployeeDetailRoute> { entry ->
                val r = entry.toRoute<EmployeeDetailRoute>()
                EmployeeDetailScreen(
                    type = r.type,
                    employeeId = r.employeeId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable<OutsiderDetailRoute> { entry ->
                val r = entry.toRoute<OutsiderDetailRoute>()
                OutsiderDetailScreen(
                    resourceId = r.resourceId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable<OfficeManagementRoute> {
                OfficeManagementScreen(
                    onOpenOffice = { id -> navController.navigate(OfficeMasterDetailRoute(id)) }
                )
            }
            composable<OfficeMasterDetailRoute> { entry ->
                val r = entry.toRoute<OfficeMasterDetailRoute>()
                OfficeMasterDetailScreen(
                    officeId = r.officeId,
                    onBack = { navController.popBackStack() },
                    onOpenEmployee = { type, id ->
                        navController.navigate(EmployeeDetailRoute(type, id))
                    },
                    onOpenOffice = { id -> navController.navigate(OfficeMasterDetailRoute(id)) }
                )
            }
            composable<MoRoute> {
                MoLandingScreen(
                    onOpenBeat = { beat -> navController.navigate(MoBeatListRoute(beat)) }
                )
            }
            composable<MoBeatListRoute> { entry ->
                val r = entry.toRoute<MoBeatListRoute>()
                MoBeatListScreen(
                    beat = r.beat,
                    onBack = { navController.popBackStack() },
                    onOpenOffice = { id -> navController.navigate(MoOfficeDetailRoute(id)) }
                )
            }
            composable<MoOfficeDetailRoute> { entry ->
                val r = entry.toRoute<MoOfficeDetailRoute>()
                MoOfficeDetailScreen(
                    moOfficeId = r.moOfficeId,
                    onBack = { navController.popBackStack() },
                    onOpenFullOffice = { officeId ->
                        navController.navigate(OfficeMasterDetailRoute(officeId))
                    }
                )
            }
            composable<ArrangementsRoute> {
                ArrangementsScreen(onOpenImport = { navController.navigate(ImportRoute) })
            }
            composable<MessageRoute> { ChatScreen() }
            composable<ImportRoute> { ImportScreen() }
            composable<ProfileRoute> {
                ProfileScreen(
                    onOpenFavorite = { itemType, itemId ->
                        when (itemType) {
                            UserDataRepository.KIND_EMPLOYEE -> {
                                val parts = itemId.split(":", limit = 2)
                                if (parts.size == 2) {
                                    navController.navigate(EmployeeDetailRoute(parts[0], parts[1]))
                                }
                            }
                            UserDataRepository.KIND_OUTSIDER ->
                                navController.navigate(OutsiderDetailRoute(itemId))
                            UserDataRepository.KIND_OFFICE ->
                                navController.navigate(OfficeMasterDetailRoute(itemId))
                        }
                    },
                    onOpenUserAdmin = { navController.navigate(UserAdminRoute) },
                    onLogout = onLogout
                )
            }
            composable<UserAdminRoute> {
                UserAdminScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
