package tvcal.android.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import tvcal.android.LocalApp
import tvcal.android.TVCalApp
import tvcal.android.measure.GuidedController
import tvcal.android.storage.TVRecord
import tvcal.android.storage.upper
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.screens.CameraSetupView
import tvcal.android.ui.screens.GuidedScreen
import tvcal.android.ui.screens.IntroView
import tvcal.android.ui.screens.LabEntry
import tvcal.android.ui.screens.LiveMeterView
import tvcal.android.ui.screens.ResultsChartsView
import tvcal.android.ui.screens.SettingsView
import tvcal.android.ui.screens.TVHomeView
import tvcal.android.ui.screens.TVListView
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.secondary
import tvcal.kit.Analysis
import java.util.UUID

// Port of App/TVCalibratorApp.swift (the intro overlay and the launch tasks) and Views/RootView.swift's
// RootView (the NavigationStack and its routes).

/**
 * Navigation for screens deep in the tree that don't get a callback (e.g. ResultsView's "Grey by grey",
 * the live meter's "Set up the camera"). Top-level screens get explicit callbacks from [RootView].
 */
class AppNav(private val nav: NavHostController) {
    fun openTV(id: UUID) = nav.navigate(TVRoute(id.upper)) { launchSingleTop = true }
    fun openSteps(tvID: UUID) = nav.navigate(GuidedRoute(tvID.upper)) { launchSingleTop = true }
    fun openResultsCharts(tvID: UUID) = nav.navigate(ResultsChartsRoute(tvID.upper)) { launchSingleTop = true }
    fun openCamera() = nav.navigate(CameraRoute) { launchSingleTop = true }
    fun openLive() = nav.navigate(LiveRoute) { launchSingleTop = true }
    fun openSettings() = nav.navigate(SettingsRoute) { launchSingleTop = true }
    fun openLab() = nav.navigate(LabRoute) { launchSingleTop = true }

    /**
     * Pops the top screen (Swift dismiss() on a pushed view). Ignored while a transition runs (the top entry
     * isn't resumed yet, e.g. a second tap on the exiting screen's back arrow) and on Home, so the start
     * destination is never popped.
     */
    fun back() {
        val top = nav.currentBackStackEntry ?: return
        if (nav.previousBackStackEntry == null) return
        if (top.lifecycle.currentState != Lifecycle.State.RESUMED) return
        nav.popBackStack()
    }
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("LocalNav is not provided") }

/**
 * The app's root (Swift TVCalibratorApp body): RootView with the Intro overlay on top for 1.7 s, then a
 * 0.6 s ease-in-out fade; the launch tasks (notifications.start(), then model.refreshAll(): profiles,
 * then links). Call inside TVCalTheme with LocalApp provided.
 */
@Composable
fun AppRoot() {
    val app = LocalApp.current
    var intro by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1_700)
        intro = false
    }
    LaunchedEffect(Unit) {
        app.notifications.start()
        app.model.refreshAll()
    }
    // The window app-scope chimes give their haptic to (held weakly, cleared with this composition).
    val view = LocalView.current
    DisposableEffect(view) {
        app.chime.attach(view)
        onDispose { app.chime.detach(view) }
    }
    Box(Modifier.fillMaxSize().background(Theme.background)) {
        // Hidden from TalkBack while the intro covers it (the overlay also takes every tap).
        Box(Modifier.fillMaxSize().then(if (intro) Modifier.clearAndSetSemantics {} else Modifier)) {
            RootView()
        }
        AnimatedVisibility(
            visible = intro,
            enter = EnterTransition.None,
            exit = fadeOut(tween(600, easing = FastOutSlowInEasing)),
        ) {
            IntroView(Modifier.fillMaxSize())
        }
    }
}

/** Swift RootView: the navigation stack, Home first. */
@Composable
fun RootView() {
    val app = LocalApp.current
    val nav = rememberNavController()
    val appNav = remember(nav) { AppNav(nav) }
    CompositionLocalProvider(LocalNav provides appNav) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier.fillMaxSize().background(Theme.background),
            enterTransition = { slideInHorizontally(tween(300)) { it } },
            exitTransition = { slideOutHorizontally(tween(300)) { -it / 3 } },
            popEnterTransition = { slideInHorizontally(tween(300)) { -it / 3 } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } },
        ) {
            composable<HomeRoute> {
                TVListView(
                    onOpenTV = appNav::openTV,
                    onOpenCamera = appNav::openCamera,
                    onOpenLive = appNav::openLive,
                    onOpenSettings = appNav::openSettings,
                    onOpenLab = appNav::openLab,
                )
            }
            composable<TVRoute> { entry ->
                val id = entry.toRoute<TVRoute>().id
                val tv = app.model.tvs.firstOrNull { it.id.upper == id }
                if (tv == null) {
                    RemovedScreen(onBack = appNav::back)
                } else {
                    val guided = guidedFor(app, entry, tv)
                    TVHomeView(
                        tv = tv,
                        guided = guided,
                        onOpenSteps = { appNav.openSteps(tv.id) },
                        onBack = appNav::back,
                    )
                }
            }
            composable<GuidedRoute> { entry ->
                val id = entry.toRoute<GuidedRoute>().id
                val parent = remember(entry) { tvEntry(nav, id) }
                val tv = app.model.tvs.firstOrNull { it.id.upper == id }
                if (parent == null || tv == null) {
                    RemovedScreen(onBack = appNav::back)
                } else {
                    GuidedScreen(guided = guidedFor(app, parent, tv), onBack = appNav::back)
                }
            }
            composable<ResultsChartsRoute> { entry ->
                val id = entry.toRoute<ResultsChartsRoute>().id
                val parent = remember(entry) { tvEntry(nav, id) }
                val tv = app.model.tvs.firstOrNull { it.id.upper == id }
                val guided = if (parent != null && tv != null) guidedFor(app, parent, tv) else null
                val session = guided?.lastSession
                val after = session?.after
                if (guided == null || after == null) {
                    RemovedScreen(onBack = appNav::back)
                } else {
                    val hdr = guided.hdr
                    val report = remember(after, hdr) { Analysis.analyse(after, hdr) }
                    val before = remember(session.before, hdr) { session.before?.let { Analysis.analyse(it, hdr) } }
                    ResultsChartsView(
                        report = report,
                        hdr = hdr,
                        mode = guided.mode,
                        tvName = guided.tv.name,
                        onBack = appNav::back,
                        before = before,
                    )
                }
            }
            composable<CameraRoute> {
                CameraSetupView(camera = app.camera, profiles = app.profiles, onDone = appNav::back)
            }
            composable<LiveRoute> {
                LiveMeterView(onBack = appNav::back, onOpenCamera = appNav::openCamera)
            }
            composable<SettingsRoute> {
                SettingsView(profiles = app.profiles, onBack = appNav::back, onOpenLab = appNav::openLab)
            }
            composable<LabRoute> {
                LabEntry(onBack = appNav::back)
            }
        }
    }
}

/**
 * The TV's shared controller, kept by the TVRoute entry's [GuidedHolder]. Follows changes to the TV record
 * wherever it's shown (Swift TVHomeView .onChange(of: tv) { guided.reload(tv: t, mode: mode) }, which also
 * fires while GuidedScreen is pushed on top): a menu question answered or Screen settings saved inside the
 * steps reach guided.tv at once.
 */
@Composable
private fun guidedFor(app: TVCalApp, tvEntry: NavBackStackEntry, tv: TVRecord): GuidedController {
    val holder: GuidedHolder = viewModel(viewModelStoreOwner = tvEntry)
    val controller = holder.controller(app.model, tv)
    LaunchedEffect(controller, tv) {
        if (controller.tv != tv) controller.reload(tv, controller.mode)
    }
    return controller
}

/** The TVRoute entry for this TV id on the back stack, or null. */
private fun tvEntry(nav: NavHostController, id: String): NavBackStackEntry? =
    runCatching { nav.getBackStackEntry(TVRoute(id)) }.getOrNull()

/** Swift: "This screen was removed." (the TV was deleted while its route was still on the stack). */
@Composable
private fun RemovedScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "", onLeading = onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This screen was removed.", style = Fonts.bodyBrand.secondary())
        }
    }
}
