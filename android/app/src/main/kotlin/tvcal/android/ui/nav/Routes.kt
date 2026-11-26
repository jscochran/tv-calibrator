package tvcal.android.ui.nav

import kotlinx.serialization.Serializable

// The navigation routes (Swift `enum Route` plus the views pushed with navigationDestination /
// NavigationLink). TV ids are the UUID as an uppercase string (UUID.upper).

/** The home screen (TVListView). Start destination. */
@Serializable
data object HomeRoute

/** Swift Route.tv(UUID): the screen home (TVHomeView). Owns the TV's GuidedController (GuidedHolder). */
@Serializable
data class TVRoute(val id: String)

/** The guided steps (GuidedScreen), pushed from the screen home. Shares the TVRoute's GuidedController. */
@Serializable
data class GuidedRoute(val id: String)

/** "Grey by grey" (ResultsChartsView), pushed from the After results. Reads the TV's latest session. */
@Serializable
data class ResultsChartsRoute(val id: String)

/** Swift Route.camera: CameraSetupView. */
@Serializable
data object CameraRoute

/** Swift Route.live: LiveMeterView. */
@Serializable
data object LiveRoute

/** Swift Route.settings: SettingsView. */
@Serializable
data object SettingsRoute

/** Swift Route.lab: LabEntry (the lab build's placeholder until P5). */
@Serializable
data object LabRoute
