package tvcal.android.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import tvcal.android.R
import java.lang.ref.WeakReference

/** Plays the success sound. */
fun interface SoundPlayer {
    fun play()
}

/** res/raw/success.wav through SoundPool (USAGE_ASSISTANCE_SONIFICATION, volume 0.7), loaded on first use. */
class SuccessSound(context: Context) : SoundPlayer {
    private val context = context.applicationContext
    private var pool: SoundPool? = null
    private var soundID = 0
    private var loaded = false
    /** Played as soon as loading finishes (the first chime comes before the sound is loaded). */
    private var pending = false

    override fun play() {
        val p = pool ?: create()
        if (loaded) start(p) else pending = true
    }

    private fun create(): SoundPool {
        // Sonification: a short UI sound that mixes with whatever else is playing.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
        pool = p
        p.setOnLoadCompleteListener { sp, _, status ->
            if (status == 0) {
                loaded = true
                if (pending) {
                    pending = false
                    start(sp)
                }
            }
        }
        soundID = p.load(context, R.raw.success, 1)
        return p
    }

    /** From the start each time (one stream: a new chime replaces one still playing). */
    private fun start(p: SoundPool) {
        p.play(soundID, VOLUME, VOLUME, 1, 0, 1f)
    }

    private companion object {
        const val VOLUME = 0.7f
    }
}

/**
 * The little success sound (and a haptic tap) the first time a setting reaches its target. Each key
 * ("<tv>|<mode>|<kind>|<pid>|<chan>") chimes once while the app is open, after two on-target readings in a
 * row; a miss resets the streak. In memory only. One instance on TVCalApp (`LocalApp.current.chime`).
 */
class Chime(private val prefs: Prefs, private val sound: SoundPlayer) {
    companion object {
        /** Settings › Sounds. On unless turned off. */
        const val enabledKey = PrefKeys.SOUNDS
    }

    private val fired = HashSet<String>()
    private val streak = HashMap<String, Int>()

    /** prefs "feedback.sounds", default true. */
    val enabled: Boolean get() = prefs.bool(enabledKey, true)

    /** Call with every new reading of a setting. view: LocalView.current, for the haptic. */
    fun observe(key: String?, ok: Boolean, view: View? = null) {
        if (key == null) return
        if (!ok) {
            streak[key] = 0
            return
        }
        val n = (streak[key] ?: 0) + 1
        streak[key] = n
        if (n < 2 || key in fired) return
        fired.add(key)
        play(view)
    }

    /**
     * The window showing the app now (set by AppRoot, cleared when it's disposed), for chimes started from
     * the app scope with no view of their own. Weak, so a destroyed Activity is never kept alive.
     */
    private var attached: WeakReference<View>? = null

    fun attach(view: View) {
        attached = WeakReference(view)
    }

    fun detach(view: View) {
        if (attached?.get() === view) attached = null
    }

    /**
     * When [enabled]: the haptic (HapticFeedbackConstants.CONFIRM on API 30+, else VIRTUAL_KEY) on [view] (or
     * the attached window's view when null), and the sound.
     */
    fun play(view: View? = null) {
        if (!enabled) return
        val target = view ?: attached?.get()?.takeIf { it.isAttachedToWindow }
        target?.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
        sound.play()
    }
}
