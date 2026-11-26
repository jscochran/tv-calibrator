package tvcal.android.app

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import tvcal.android.storage.upper
import java.util.UUID

/** The iPhone app's UserDefaults keys, unchanged. */
object PrefKeys {
    /** JSON `{"short":[r,g,b],"long":[r,g,b]}` (camera.DarkLevel), stored as a String. */
    const val DARK_LEVEL = "darkLevel"
    /** Bool, default true (Settings › Sounds). */
    const val SOUNDS = "feedback.sounds"
    /** Bool, default true. Video pushes are iPhone-only; kept for the toggle state only. */
    const val NOTIFY_VIDEOS = "notify.videos"
    /** Bool, default true. */
    const val NOTIFY_RECHECK = "notify.recheck"
    /** Bool, default false. */
    const val NOTIFY_CARD_DISMISSED = "notify.cardDismissed"
    /** Push token hex (unused on Android). */
    const val NOTIFY_TOKEN = "notify.token"
    /** Android only: the POST_NOTIFICATIONS permission has been asked for once (tells "denied" from "not asked"). */
    const val NOTIFY_ASKED = "notify.asked"
    /** Bool, default false. */
    const val WELCOME_SEEN = "welcome.seen"
    /** Bool, default false: the newsletter link was opened. */
    const val UNTANGLED_OPENED = "untangled.opened"
    const val LAB_HOST = "lab.host"
    /** Int, 0 means 8765. */
    const val LAB_PORT = "lab.port"
    const val LAB_TOKEN = "lab.token"
    const val LAB_MAC_NAME = "lab.macName"

    /** SignalMode raw value of the mode last used on this TV ("mode-<UUID uppercase>"). */
    fun mode(tvID: UUID): String = "mode-${tvID.upper}"

    /** Bool: the user accepted the HDR10+/DV advanced-format sheet for this TV. */
    fun advancedOk(tvID: UUID): String = "advanced-ok-${tvID.upper}"
}

/** The key-value store behind [Prefs] (SharedPreferences on the phone, a map in JVM tests). */
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun remove(key: String)
}

class SharedPrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun contains(key: String): Boolean = sp.contains(key)
    override fun getBoolean(key: String, default: Boolean): Boolean = sp.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String?) = sp.edit().putString(key, value).apply()
    override fun getInt(key: String, default: Int): Int = sp.getInt(key, default)
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun remove(key: String) = sp.edit().remove(key).apply()
}

class MemoryStore : KeyValueStore {
    private val map = HashMap<String, Any>()
    override fun contains(key: String): Boolean = key in map
    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getString(key: String): String? = map[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * UserDefaults → SharedPreferences (file [FILE]). Plain getters/setters plus Compose-observable states
 * for the @AppStorage keys: `val seen by app.prefs.boolState(PrefKeys.WELCOME_SEEN, false)` in a
 * composable reads and writes through (one shared MutableState per key, so every screen sees changes).
 */
class Prefs(val store: KeyValueStore) {
    companion object {
        /** SharedPreferences file name. */
        const val FILE = "tvcal"
    }

    fun bool(key: String, default: Boolean): Boolean = store.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) {
        store.putBoolean(key, value)
        boolStates[key]?.sync(value)
    }
    fun string(key: String): String? = store.getString(key)
    fun setString(key: String, value: String?) {
        store.putString(key, value)
        stringStates[key]?.sync(value)
    }
    fun int(key: String, default: Int): Int = store.getInt(key, default)
    fun setInt(key: String, value: Int) = store.putInt(key, value)
    fun remove(key: String) {
        store.remove(key)
        boolStates[key]?.let { it.sync(it.default) }
        stringStates[key]?.sync(null)
    }

    private val boolStates = HashMap<String, WriteThrough<Boolean>>()
    private val stringStates = HashMap<String, WriteThrough<String?>>()

    /** Swift @AppStorage(Bool): a snapshot MutableState that writes through to the store (cached per key). */
    fun boolState(key: String, default: Boolean): MutableState<Boolean> =
        synchronized(boolStates) {
            boolStates.getOrPut(key) { WriteThrough(bool(key, default), default) { store.putBoolean(key, it) } }
        }

    /** Swift @AppStorage(String?): as [boolState]. */
    fun stringState(key: String): MutableState<String?> =
        synchronized(stringStates) {
            stringStates.getOrPut(key) { WriteThrough(string(key), null) { store.putString(key, it) } }
        }

    /** A MutableState whose writes also go to the store; [sync] updates it without writing (the store already has it). */
    private class WriteThrough<T>(initial: T, val default: T, private val write: (T) -> Unit) : MutableState<T> {
        private val inner = mutableStateOf(initial)

        override var value: T
            get() = inner.value
            set(v) {
                inner.value = v
                write(v)
            }

        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }

        fun sync(v: T) {
            inner.value = v
        }
    }
}
