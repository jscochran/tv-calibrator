package tvcal.android.ui.nav

import androidx.lifecycle.ViewModel
import tvcal.android.app.AppModel
import tvcal.android.app.PrefKeys
import tvcal.android.app.Prefs
import tvcal.android.measure.GuidedController
import tvcal.android.storage.TVRecord
import tvcal.kit.SignalMode

/**
 * Keeps one TV's GuidedController for as long as its screen home (TVRoute) is on the back stack, so the
 * screen home, the guided steps (GuidedRoute) and "Grey by grey" (ResultsChartsRoute) share it, like the
 * Swift @StateObject in TVHomeView that GuidedScreen observes. Scoped to the TVRoute NavBackStackEntry.
 */
class GuidedHolder : ViewModel() {
    private var controller: GuidedController? = null

    /** The TV's controller, created on first use in the mode last used on this TV ("mode-<UUID>"). */
    fun controller(app: AppModel, tv: TVRecord): GuidedController =
        controller ?: GuidedController(app, tv, savedMode(app.prefs, tv)).also { controller = it }

    /**
     * The screen home left the back stack (back to the home screen): nothing should keep measuring behind
     * the user's back (Swift TVHomeView.onDisappear when not going into its steps).
     */
    override fun onCleared() {
        controller?.let {
            it.runner.cancel()
            it.stopLive()
        }
        controller = null
    }
}

/** The signal mode last used on this TV, SDR by default. */
fun savedMode(prefs: Prefs, tv: TVRecord): SignalMode =
    prefs.string(PrefKeys.mode(tv.id))?.let { SignalMode.from(it) } ?: SignalMode.SDR
