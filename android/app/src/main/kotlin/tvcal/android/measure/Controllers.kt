package tvcal.android.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tvcal.android.app.AppModel
import tvcal.android.storage.FinderData
import tvcal.android.storage.GuideProgress
import tvcal.android.storage.GuideState
import tvcal.android.storage.GuideStep
import tvcal.android.storage.GuidedSession
import tvcal.android.storage.TVRecord
import tvcal.android.storage.upper
import tvcal.kit.Analysis
import tvcal.kit.FinderPoint
import tvcal.kit.GuidePlan
import tvcal.kit.Measurements
import tvcal.kit.Patterns
import tvcal.kit.PointFinder
import tvcal.kit.Report
import tvcal.kit.ShownPatch
import tvcal.kit.SignalMode
import tvcal.kit.cfmt
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class QuickWhich { BEFORE, AFTER, CHECK }

/**
 * One TV's guided calibration in one signal mode (Swift GuidedController). Create one per TV screen
 * (`remember(tv.id) { GuidedController(app, tv, savedMode) }`); switch modes with [reload]. All state is
 * Compose snapshot state; [runner] and [live] are observable too (no forwarding needed). Main thread only.
 * Long work: call [startQuick] / [startFinder] from click handlers (they run in the app scope, like Swift's
 * unstructured Task), or the suspend versions from your own coroutine.
 */
class GuidedController(val app: AppModel, tv: TVRecord, mode: SignalMode) {
    var tv: TVRecord by mutableStateOf(tv)
        private set
    var mode: SignalMode by mutableStateOf(mode)
        private set
    /** Loaded from app.store.guideState(tv, mode); every change is saved. */
    var state: GuideState by mutableStateOf(app.store.guideState(tv, mode))
        private set
    var message: String? by mutableStateOf(null)
    /** The latest Check step result (Analysis.analyse(state.check)); not kept between launches. */
    var checkReport: Report? by mutableStateOf(state.check?.let { Analysis.analyse(it, hdr = mode.isHDR) })
        private set

    val live = LiveSession(app.scope)
    val runner = SweepRunner(app.scope)
    val shown = ShownPatch()

    val hdr: Boolean get() = mode.isHDR
    val plan: GuidePlan get() = GuidePlan.forHDR(hdr)
    val progress: GuideProgress get() = state.progress
    val lastSession: GuidedSession? get() = state.sessions.lastOrNull()
    /** The latest session's Before measurement, if it has one. */
    val latestBefore: Measurements? get() = state.sessions.lastOrNull()?.before

    /** Stops live readings, resets the runner, loads the other TV/mode's state, clears the message. */
    fun reload(tv: TVRecord, mode: SignalMode) {
        stopLive()
        runner.reset()
        this.tv = tv
        this.mode = mode
        state = app.store.guideState(tv, mode)
        checkReport = state.check?.let { Analysis.analyse(it, hdr = mode.isHDR) }
        message = null
        finderHolding = false
        finderShowingResult = false
    }

    private fun save() = app.store.save(state, tv, mode)

    /** Swift `state.progress.x = ...`: replaces the progress (a value) inside the state (a value). */
    private inline fun editProgress(change: (GuideProgress) -> GuideProgress) {
        state = state.copy(progress = change(state.progress))
    }

    /** Marks [markDone] done, moves to [step], clears the message; stops live readings unless the step is BRIGHT/DARK/COLOURS/FINE. */
    fun go(step: GuideStep, markDone: GuideStep? = null) {
        if (markDone != null && markDone !in state.progress.done) editProgress { it.copy(done = it.done + markDone) }
        editProgress { it.copy(step = step) }
        message = null
        if (step !in LIVE_STEPS) stopLive()
        save()
    }

    fun markLimit(key: String) {
        if (key !in state.progress.limits) editProgress { it.copy(limits = it.limits + key) }
        save()
    }

    fun setColour(c: String?) {
        editProgress { it.copy(colour = c) }
        save()
    }

    /** Next unfinished colour (Patterns.colourIDs order), or go(AFTER, markDone = COLOURS). */
    fun finishColour(c: String, done: Boolean) {
        if (done && c !in state.progress.colours) editProgress { it.copy(colours = it.colours + c) }
        val finished = state.progress.colours
        val next = Patterns.colourIDs.firstOrNull { it !in finished && it != c }
        if (next != null) {
            editProgress { it.copy(colour = next) }
            save()
        } else {
            editProgress { it.copy(colour = null) }
            go(GuideStep.AFTER, markDone = GuideStep.COLOURS)
        }
    }

    fun setFinePid(pid: String) {
        editProgress { it.copy(finePid = pid) }
        save()
    }

    /** Starts again from Before (progress = step BEFORE, done [SETUP]; a new empty session; check cleared). */
    fun newSession() {
        state = state.copy(
            progress = GuideProgress(step = GuideStep.BEFORE, done = listOf(GuideStep.SETUP)),
            sessions = state.sessions + GuidedSession(before = null),
            check = null,
        )
        checkReport = null
        save()
    }

    // the file to play

    fun file(pid: String): String? = Patterns.packFile(mode = mode, id = pid)
    val quickFile: String get() = file("QUICK") ?: ""
    val finderFile: String get() = Patterns.finderFile(mode = mode)

    // quick checks (before / after / check)

    /**
     * Measures the quick video and files the result (session bookkeeping as the Swift; After schedules the
     * re-check reminder for real TVs; appends to log.md). Sets [message] = app.cameraBlocker when no meter.
     */
    suspend fun measureQuick(which: QuickWhich) {
        stopLive()
        val seq = Patterns.quickSequence(hdr = hdr, ids = plan.quickWithColours)
        val meter = app.videoMeter(tv, hdr = hdr, sequence = seq)
        if (meter == null) {
            message = app.cameraBlocker
            return
        }
        val meas = runner.run(meter, seq, checkOrder = true) ?: return
        val rep = Analysis.analyse(meas, hdr = hdr)
        when (which) {
            QuickWhich.CHECK -> {
                checkReport = rep
                state = state.copy(check = meas)
                save()
                return
            }
            QuickWhich.BEFORE -> {
                // Fill a fresh session (from Start a new session), or start one.
                val last = state.sessions.lastOrNull()
                state = if (last != null && last.before == null && last.after == null) {
                    state.copy(sessions = state.sessions.dropLast(1) + last.copy(before = meas, started = Instant.now()))
                } else {
                    state.copy(sessions = state.sessions + GuidedSession(before = meas))
                }
            }
            QuickWhich.AFTER -> {
                var sessions = state.sessions
                if (sessions.isEmpty() || sessions.last().after != null) {
                    // Measuring "after" again: keep comparing with the same starting point.
                    sessions = sessions + GuidedSession(before = sessions.lastOrNull()?.before)
                }
                sessions = sessions.dropLast(1) + sessions.last().copy(after = meas, finished = Instant.now())
                state = state.copy(sessions = sessions)
                if (GuideStep.AFTER !in state.progress.done) editProgress { it.copy(done = it.done + GuideStep.AFTER) }
                if (!tv.isDemo) app.reminders.scheduleRecheck(tvID = tv.id.upper, tvName = tv.name)
            }
        }
        save()
        app.store.appendLog(tv, Log.quick(mode = mode, which = which, report = rep))
    }

    /**
     * Counts quick measurements (startQuick) that ended with the runner Done or Cancelled. QuickStep compares it
     * with the value it saw when it started measuring, so its "back to the results" survives the composition
     * being recreated mid-sweep (nothing composition-bound is written from the app scope).
     */
    var quickEnded: Int by mutableIntStateOf(0)
        private set

    /** measureQuick in the app scope. */
    fun startQuick(which: QuickWhich): Job = app.scope.launch {
        try {
            measureQuick(which)
        } finally {
            val p = runner.phase
            if (p == SweepRunner.Phase.Done || p == SweepRunner.Phase.Cancelled) quickEnded += 1
        }
    }

    // live readings

    /** Shows Patterns.patch(hdr, pid) and starts live readings (or sets [message] = app.cameraBlocker). */
    fun showLive(pid: String) {
        val p = Patterns.patch(hdr = hdr, id = pid) ?: return
        shown.patch = p
        val meter = app.liveMeter(tv, hdr = hdr, shown = shown)
        if (meter == null) {
            message = app.cameraBlocker
            return
        }
        message = null
        val ref = state.reference
        live.show(p, hdr = hdr, whiteY = ref.whiteY, basis = ref.basis, meter = meter)
    }

    fun stopLive() {
        live.stop()
        shown.patch = null
    }

    /** Demo only: press the "remote", then restart the live average. */
    fun demoPress(changes: Map<String, Int>) {
        app.demoApply(tv, hdr = hdr, changes = changes)
        live.restartAverage()
    }

    // point finder

    val finderMap: List<FinderPoint>
        get() {
            val b = state.finder.base ?: return emptyList()
            val n = state.finder.nudged ?: return emptyList()
            return PointFinder.map(base = b, nudged = n, hdr = hdr)
        }

    val grid: Int get() = PointFinder.grid(points = tv.multipoint.rawValue)

    val suggestions: Map<String, Int>
        get() = PointFinder.suggestions(map = finderMap, overrides = state.finder.overrides, hdr = hdr, grid = grid)

    val finderReady: Boolean get() = finderMap.isNotEmpty() || state.finder.overrides.isNotEmpty()

    /**
     * Base pass (nudged = false) or nudged pass. Demo + nudged: applies the PointFinder nudges
     * ("p20:<ch>:<pt>" to PointFinder.nudge) before and undoes them after (in finally). Clears meas.missed.
     */
    suspend fun measureFinder(nudged: Boolean) {
        stopLive()
        val seq = Patterns.finderSequence(hdr = hdr)
        val nudges = PointFinder.nudges.associate { "p20:${it.channel}:${it.point}" to PointFinder.NUDGE }
        // The TV and format the nudges went to: they're undone there, even if the screen switched meanwhile.
        val demoTV = tv.takeIf { it.isDemo && nudged }
        val demoHDR = hdr
        if (demoTV != null) app.demoApply(demoTV, hdr = demoHDR, changes = nudges)
        try {
            val meter = app.videoMeter(tv, hdr = hdr, sequence = seq)
            if (meter == null) {
                message = app.cameraBlocker
                return
            }
            val meas = (runner.run(meter, seq) ?: return).copy(missed = emptyMap())
            if (!nudged) {
                state = state.copy(finder = FinderData(base = meas, nudged = null, overrides = state.finder.overrides))
            } else {
                state = state.copy(finder = state.finder.copy(nudged = meas))
                val map = finderMap
                if (map.size < 2) {
                    message = "The nudges didn't show up clearly enough to find the controls. Check the four changes were " +
                        "made (+10 each), then measure the nudged pass again."
                } else {
                    app.store.appendLog(
                        tv,
                        "\n## Point finder (${mode.label}) ${Log.stamp()}\n" +
                            map.joinToString("") { "- TV ${it.point}% control (${it.channel}) acts on the ${cfmt(it.level, 1)}% grey\n" },
                    )
                }
            }
            save()
        } finally {
            if (demoTV != null) app.demoApply(demoTV, hdr = demoHDR, changes = nudges.mapValues { -it.value })
        }
    }

    /** measureFinder in the app scope. */
    fun startFinder(nudged: Boolean): Job = app.scope.launch { measureFinder(nudged) }

    /**
     * The point finder's page (Swift FineStep @State holdingFinder / PointFinderView @State showingResult), kept
     * here rather than in the composition: the nudged pass runs for minutes in the app scope and must land its
     * result page on whatever screen shows this controller then (after an Activity recreation, or after
     * leaving and coming back), so the user is always told to put the four controls back.
     */
    var finderHolding: Boolean by mutableStateOf(false)
        private set
    var finderShowingResult: Boolean by mutableStateOf(false)
        private set

    /** "I've made the four changes - measure": holds the finder on screen, measures the nudged pass, then the result page (map of 2+) or back. */
    fun startNudgedFinder(): Job {
        finderHolding = true
        return app.scope.launch {
            try {
                measureFinder(nudged = true)
            } finally {
                if (finderMap.size >= 2) finderShowingResult = true else finderHolding = false
            }
        }
    }

    /** "Fine-tune with these points": leaves the result page for the fine-tune step. */
    fun finishFinderResult() {
        finderShowingResult = false
        finderHolding = false
        go(GuideStep.FINE)
    }

    /** point null removes the override. */
    fun setOverride(pid: String, point: Int?) {
        val o = state.finder.overrides
        state = state.copy(finder = state.finder.copy(overrides = if (point != null) o + (pid to point) else o - pid))
        save()
    }

    fun resetFinder() {
        state = state.copy(finder = FinderData(base = null, nudged = null, overrides = state.finder.overrides))
        save()
    }

    /** overrides["_skip"] = 0. */
    fun skipFinder() {
        state = state.copy(finder = state.finder.copy(overrides = state.finder.overrides + ("_skip" to 0)))
        save()
    }

    val finderSkipped: Boolean get() = state.finder.overrides["_skip"] != null

    private companion object {
        /** Steps that keep the live readings running. */
        val LIVE_STEPS = setOf(GuideStep.BRIGHT, GuideStep.DARK, GuideStep.COLOURS, GuideStep.FINE)
    }
}

/** The readable per-TV log (log.md). Every number through cfmt (never String.format). */
object Log {
    private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** "yyyy-MM-dd HH:mm" (local time). */
    fun stamp(now: LocalDateTime = LocalDateTime.now()): String = stampFormat.format(now)

    /** The greyscale and colour tables ("%.2f" → cfmt(x, 2), "%+.1f" → cfmt(x, 1, plus = true)). */
    fun table(rep: Report, hdr: Boolean): String {
        val out = StringBuilder()
        out.append("Greyscale dE ${cfmt(Analysis.greyAverage(rep, hdr = hdr), 2)}, colour dE ")
        out.append("${cfmt(Analysis.colourAverage(rep), 2)}\n\n| step | cd/m² | R | G | B | dE |\n|---|---|---|---|---|---|\n")
        for (g in rep.greys) {
            out.append("| ${g.name} | ${cfmt(g.Y, 2)} | ${cfmt(g.bal.x * 100, 1)} | ")
            out.append("${cfmt(g.bal.y * 100, 1)} | ${cfmt(g.bal.z * 100, 1)} | ${cfmt(g.de, 2)} |\n")
        }
        out.append("\n| colour | dE | saturation | hue | brightness |\n|---|---|---|---|---|\n")
        for (c in rep.colours) {
            out.append("| ${capitalized(c.name)} | ${cfmt(c.de, 2)} | ${cfmt(c.sat, 1, plus = true)}% | ")
            out.append("${cfmt(c.hue, 1, plus = true)}° | ${cfmt(c.lum, 1, plus = true)}% |\n")
        }
        for (w in rep.warnings) out.append("\nWarning: $w\n")
        return out.toString()
    }

    fun quick(mode: SignalMode, which: QuickWhich, report: Report): String {
        val label = when (which) {
            QuickWhich.BEFORE -> "before"
            QuickWhich.AFTER -> "after"
            QuickWhich.CHECK -> "check"
        }
        return "\n## Guided ${mode.label} - $label check (${stamp()})\n" + table(report, hdr = mode.isHDR)
    }

    /** Swift String.capitalized: each space-separated word's first letter upper case, the rest lower case. */
    internal fun capitalized(s: String): String =
        s.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w.substring(0, 1).uppercase(Locale.ROOT) + w.substring(1).lowercase(Locale.ROOT)
        }
}
