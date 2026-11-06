package com.swastik.mbfitness.ui.navigation

import com.swastik.mbfitness.launchSafely
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.swastik.mbfitness.App
import com.swastik.mbfitness.MainActivity
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.AppSettings
import com.swastik.mbfitness.data.PaymentSaved
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.PaymentMode
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.AppSnackbarHost
import com.swastik.mbfitness.ui.components.ConfirmDialog
import com.swastik.mbfitness.ui.components.LocalSnackbar
import com.swastik.mbfitness.ui.components.SnackAction
import com.swastik.mbfitness.ui.components.SnackbarController
import com.swastik.mbfitness.ui.screens.home.HomeScreen
import com.swastik.mbfitness.ui.screens.lock.SetPinScreen
import com.swastik.mbfitness.ui.screens.lock.SetPinStage
import com.swastik.mbfitness.ui.screens.lock.SetPinViewModel
import com.swastik.mbfitness.ui.screens.member.MemberDetailScreen
import com.swastik.mbfitness.ui.screens.member.MemberFormResult
import com.swastik.mbfitness.ui.screens.member.MemberFormScreen
import com.swastik.mbfitness.ui.screens.messages.BroadcastScreen
import com.swastik.mbfitness.ui.screens.messages.MessagesScreen
import com.swastik.mbfitness.ui.screens.payment.PaymentDetailScreen
import com.swastik.mbfitness.ui.screens.payment.RecordPaymentScreen
import com.swastik.mbfitness.ui.screens.payment.UpiQrScreen
import com.swastik.mbfitness.ui.screens.reports.ReportsScreen
import com.swastik.mbfitness.ui.screens.settings.AboutScreen
import com.swastik.mbfitness.ui.screens.settings.BackupScreen
import com.swastik.mbfitness.ui.screens.settings.ErrorLogScreen
import com.swastik.mbfitness.ui.screens.settings.HealthCheckScreen
import com.swastik.mbfitness.ui.screens.settings.HelpScreen
import com.swastik.mbfitness.ui.screens.settings.ImportScreen
import com.swastik.mbfitness.ui.screens.settings.PlansScreen
import com.swastik.mbfitness.ui.screens.settings.QuietDaysScreen
import com.swastik.mbfitness.ui.screens.settings.RecycleBinScreen
import com.swastik.mbfitness.ui.screens.settings.SecurityScreen
import com.swastik.mbfitness.ui.screens.settings.SettingsScreen
import com.swastik.mbfitness.ui.screens.settings.TemplatesScreen
import com.swastik.mbfitness.ui.screens.today.TodayClosingScreen
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

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

@Serializable
data object AddMemberRoute

@Serializable
data class EditMemberRoute(val memberId: Long)

@Serializable
data class MemberRoute(val memberId: Long)

@Serializable
data class RecordPaymentRoute(val memberId: Long)

@Serializable
data class PaymentRoute(val paymentId: Long)

@Serializable
data class UpiQrRoute(val memberName: String, val amount: Long)

@Serializable
data object PlansRoute

@Serializable
data object RecycleBinRoute

/** Hand-picked members, comma separated. Empty means choose an audience on the screen. */
@Serializable
data class BroadcastRoute(val pickedIds: String = "")

@Serializable
data object TemplatesRoute

@Serializable
data object QuietDaysRoute

@Serializable
data object SecurityRoute

@Serializable
data object ImportRoute

@Serializable
data object BackupRoute

@Serializable
data object HealthCheckRoute

@Serializable
data object ErrorLogRoute

@Serializable
data object HelpRoute

@Serializable
data object AboutRoute

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
fun AppNavigation(openImport: Boolean = false, onImportOpened: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val colors = MbTheme.colors
    val snackbar = remember { SnackbarController() }
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    var suggestPin by remember { mutableStateOf(false) }
    val settings by app.settings.settings.collectAsStateWithLifecycle(AppSettings())

    /** Moving on to a new screen clears the snackbar, so it can't sit over that screen's buttons. */
    fun go(route: Any) {
        snackbar.dismiss()
        navController.navigate(route)
    }

    /** "₹1,200 saved · Receipt MBF-0042", with Show QR and Undo (SPEC 7.3). */
    fun showPaymentSaved(saved: PaymentSaved, memberName: String) {
        val text = listOfNotNull("${Money.format(saved.paid)} saved", saved.receipt?.let { "Receipt $it" }).joinToString(" · ")
        val actions = buildList {
            if (saved.mode == PaymentMode.UPI && saved.paid > 0 && settings.upiId.isNotBlank()) {
                add(SnackAction("Show QR") { navController.navigate(UpiQrRoute(memberName, saved.paid)) })
            }
            add(
                SnackAction("Undo") {
                    scope.launchSafely(app) {
                        app.members.undoPayment(saved)
                        snackbar.show("Payment undone")
                    }
                },
            )
        }
        snackbar.show(text, actions)
    }

    fun afterMemberSaved(saved: MemberFormResult.Saved) {
        when {
            saved.aadhaarFailed -> snackbar.show("Couldn't secure the Aadhaar number on this phone. Member saved without it.")
            saved.payment != null -> showPaymentSaved(saved.payment, saved.name)
            else -> snackbar.show("${saved.name} saved")
        }
        if (saved.suggestPin) suggestPin = true
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { AppSnackbarHost(snackbar) },
            bottomBar = {
                val isTopLevel = topLevels.any { destination?.hasRoute(it.type) == true }
                if (isTopLevel) BottomBar(navController, destination)
            },
        ) { padding ->
            NavHost(navController, startDestination = MembersRoute, Modifier.padding(padding)) {
                composable<MembersRoute> {
                    HomeScreen(
                        onMemberClick = { go(MemberRoute(it)) },
                        onAddMember = { go(AddMemberRoute) },
                        onSmsSelected = { ids -> go(BroadcastRoute(ids.joinToString(","))) },
                        onBackup = { go(BackupRoute) },
                        onSettings = { go(SettingsRoute) },
                    )
                }
                composable<TodayRoute> { TodayClosingScreen() }
                composable<MessagesRoute> { MessagesScreen(onBroadcast = { go(BroadcastRoute()) }) }
                composable<BroadcastRoute> { entry ->
                    val picked = entry.toRoute<BroadcastRoute>().pickedIds.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                    BroadcastScreen(
                        pickedIds = picked,
                        onBack = { navController.popBackStack() },
                        onSent = { plan ->
                            navController.popBackStack()
                            snackbar.show(plan)
                        },
                    )
                }
                composable<TemplatesRoute> { TemplatesScreen(onBack = { navController.popBackStack() }) }
                composable<QuietDaysRoute> { QuietDaysScreen(onBack = { navController.popBackStack() }) }
                composable<SecurityRoute> {
                    SecurityScreen(onBack = { navController.popBackStack() }, onChangePin = { go(SetPinRoute) })
                }
                composable<ImportRoute> {
                    ImportScreen(onBack = { navController.popBackStack() }, onAddOne = { go(AddMemberRoute) })
                }
                composable<BackupRoute> {
                    BackupScreen(onBack = { navController.popBackStack() })
                }
                composable<HealthCheckRoute> {
                    HealthCheckScreen(
                        onBack = { navController.popBackStack() },
                        onSetPin = { go(SetPinRoute) },
                        onBackup = { go(BackupRoute) },
                        onMessages = { go(MessagesRoute) },
                        // Back to Settings if it was opened from there. A notice can open it from anywhere.
                        onSettings = { if (!navController.popBackStack(SettingsRoute, inclusive = false)) go(SettingsRoute) },
                        onErrorLog = { go(ErrorLogRoute) },
                    )
                }
                composable<ErrorLogRoute> { ErrorLogScreen(onBack = { navController.popBackStack() }) }
                composable<HelpRoute> { HelpScreen(onBack = { navController.popBackStack() }) }
                composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
                composable<ReportsRoute> { ReportsScreen(onMemberClick = { go(MemberRoute(it)) }) }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onSetPin = { go(SetPinRoute) },
                        onSecurity = { go(SecurityRoute) },
                        onPlans = { go(PlansRoute) },
                        onRecycleBin = { go(RecycleBinRoute) },
                        onTemplates = { go(TemplatesRoute) },
                        onQuietDays = { go(QuietDaysRoute) },
                        onImport = { go(ImportRoute) },
                        onBackup = { go(BackupRoute) },
                        onHealthCheck = { go(HealthCheckRoute) },
                        onHelp = { go(HelpRoute) },
                        onAbout = { go(AboutRoute) },
                    )
                }
                composable<SetPinRoute> {
                    val viewModel = appViewModel { container -> SetPinViewModel(container.appLock, container.settings) }
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    if (state.stage == SetPinStage.DONE) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        SetPinScreen(state, viewModel, onBack = { navController.popBackStack() })
                    }
                }
                composable<AddMemberRoute> {
                    MemberFormScreen(
                        memberId = null,
                        onBack = { navController.popBackStack() },
                        onSaved = { saved ->
                            navController.popBackStack()
                            afterMemberSaved(saved)
                        },
                        onOpenMember = { id ->
                            navController.popBackStack()
                            go(MemberRoute(id))
                        },
                    )
                }
                composable<EditMemberRoute> { entry ->
                    MemberFormScreen(
                        memberId = entry.toRoute<EditMemberRoute>().memberId,
                        onBack = { navController.popBackStack() },
                        onSaved = { saved ->
                            navController.popBackStack()
                            afterMemberSaved(saved)
                        },
                        onOpenMember = { id ->
                            navController.popBackStack()
                            go(MemberRoute(id))
                        },
                    )
                }
                composable<MemberRoute> { entry ->
                    MemberDetailScreen(
                        memberId = entry.toRoute<MemberRoute>().memberId,
                        onBack = { navController.popBackStack() },
                        onRecordPayment = { go(RecordPaymentRoute(it)) },
                        onEdit = { go(EditMemberRoute(it)) },
                        onPaymentClick = { go(PaymentRoute(it)) },
                        onSetPin = { go(SetPinRoute) },
                    )
                }
                composable<RecordPaymentRoute> { entry ->
                    RecordPaymentScreen(
                        memberId = entry.toRoute<RecordPaymentRoute>().memberId,
                        onBack = { navController.popBackStack() },
                        onSaved = { saved ->
                            scope.launchSafely(app) {
                                val name = app.members.member(saved.memberId)?.name.orEmpty()
                                navController.popBackStack()
                                showPaymentSaved(saved, name)
                            }
                        },
                        onShowQr = { name, amount -> go(UpiQrRoute(name, amount)) },
                    )
                }
                composable<PaymentRoute> { entry ->
                    PaymentDetailScreen(entry.toRoute<PaymentRoute>().paymentId, onBack = { navController.popBackStack() })
                }
                composable<UpiQrRoute> { entry ->
                    val route = entry.toRoute<UpiQrRoute>()
                    UpiQrScreen(route.memberName, route.amount, onBack = { navController.popBackStack() })
                }
                composable<PlansRoute> { PlansScreen(onBack = { navController.popBackStack() }) }
                composable<RecycleBinRoute> { RecycleBinScreen(onBack = { navController.popBackStack() }) }
            }
        }

        if (openImport) {
            LaunchedEffect(Unit) {
                onImportOpened()
                navController.navigate(ImportRoute)
            }
        }

        // A notification asked for this (SPEC 8.10). It waits here until the app is unlocked.
        val openMessages = (LocalActivity.current as? MainActivity)?.openMessagesRequest
        if (openMessages?.value == true) {
            LaunchedEffect(Unit) {
                openMessages.value = false
                snackbar.dismiss()
                navController.navigate(MessagesRoute) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            }
        }

        val openHealth = (LocalActivity.current as? MainActivity)?.openHealthRequest
        if (openHealth?.value == true) {
            LaunchedEffect(Unit) {
                openHealth.value = false
                snackbar.dismiss()
                navController.navigate(HealthCheckRoute) { launchSingleTop = true }
            }
        }

        if (suggestPin) {
            ConfirmDialog(
                title = "Protect Aadhaar numbers",
                message = "Set an app PIN, so nobody holding this phone can see them.",
                confirmLabel = "Set PIN",
                onConfirm = {
                    suggestPin = false
                    go(SetPinRoute)
                },
                onDismiss = { suggestPin = false },
                dismissLabel = "Not now",
            )
        }
    }
}

@Composable
private fun BottomBar(navController: NavController, destination: NavDestination?) {
    val colors = MbTheme.colors
    val snackbar = LocalSnackbar.current
    NavigationBar(containerColor = colors.surface) {
        topLevels.forEach { item ->
            NavigationBarItem(
                selected = destination?.hasRoute(item.type) == true,
                onClick = {
                    snackbar.dismiss()
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
