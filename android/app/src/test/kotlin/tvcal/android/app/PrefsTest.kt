package tvcal.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device prefs file (left out of backups): routing and the one-time move from the main file. */
class PrefsTest {
    private val dark = """{"short":[1,2,3],"long":[4,5,6]}"""

    @Test fun deviceKeysMoveOutOfTheMainFile() {
        val main = MemoryStore().apply {
            putString(PrefKeys.DARK_LEVEL, dark)
            putString(PrefKeys.LAB_HOST, "10.0.1.67")
            putInt(PrefKeys.LAB_PORT, 8766)
            putString(PrefKeys.LAB_TOKEN, "0123456789abcdef0123456789abcdef")
            putString(PrefKeys.LAB_MAC_NAME, "JEFF-PC")
            putBoolean(PrefKeys.WELCOME_SEEN, true)
        }
        val device = MemoryStore()
        val prefs = Prefs(main, device)

        assertEquals(dark, device.getString(PrefKeys.DARK_LEVEL))
        assertEquals("10.0.1.67", device.getString(PrefKeys.LAB_HOST))
        assertEquals(8766, device.getInt(PrefKeys.LAB_PORT, 0))
        assertEquals("0123456789abcdef0123456789abcdef", device.getString(PrefKeys.LAB_TOKEN))
        assertEquals("JEFF-PC", device.getString(PrefKeys.LAB_MAC_NAME))
        for (key in Prefs.DEVICE_KEYS) assertFalse(key, main.contains(key))
        // Everything else stays in the main (backed-up) file.
        assertTrue(main.getBoolean(PrefKeys.WELCOME_SEEN, false))
        assertFalse(device.contains(PrefKeys.WELCOME_SEEN))

        // Reads go to the right file.
        assertEquals(dark, prefs.string(PrefKeys.DARK_LEVEL))
        assertEquals(8766, prefs.int(PrefKeys.LAB_PORT, 0))
        assertTrue(prefs.bool(PrefKeys.WELCOME_SEEN, false))
    }

    @Test fun writesGoToTheRightFile() {
        val main = MemoryStore()
        val device = MemoryStore()
        val prefs = Prefs(main, device)
        prefs.setString(PrefKeys.DARK_LEVEL, dark)
        prefs.setInt(PrefKeys.LAB_PORT, 9000)
        prefs.setBool(PrefKeys.SOUNDS, false)
        prefs.stringState(PrefKeys.LAB_TOKEN).value = "abc"
        assertEquals(dark, device.getString(PrefKeys.DARK_LEVEL))
        assertEquals(9000, device.getInt(PrefKeys.LAB_PORT, 0))
        assertEquals("abc", device.getString(PrefKeys.LAB_TOKEN))
        assertFalse(main.contains(PrefKeys.DARK_LEVEL))
        assertFalse(main.contains(PrefKeys.LAB_TOKEN))
        assertFalse(main.getBoolean(PrefKeys.SOUNDS, true))
        assertFalse(device.contains(PrefKeys.SOUNDS))

        prefs.remove(PrefKeys.LAB_PORT)
        assertFalse(device.contains(PrefKeys.LAB_PORT))
        assertFalse(prefs.contains(PrefKeys.LAB_PORT))
    }

    @Test fun whatTheDeviceFileHasWinsAndTheMoveRunsOnce() {
        val main = MemoryStore().apply { putString(PrefKeys.DARK_LEVEL, "old") }
        val device = MemoryStore().apply { putString(PrefKeys.DARK_LEVEL, dark) }
        Prefs(main, device)
        assertEquals(dark, device.getString(PrefKeys.DARK_LEVEL))
        assertFalse(main.contains(PrefKeys.DARK_LEVEL))

        // A second start finds nothing to move and changes nothing.
        Prefs(main, device)
        assertEquals(dark, device.getString(PrefKeys.DARK_LEVEL))
    }

    @Test fun aWrongTypedOldValueIsDropped() {
        val main = MemoryStore().apply { putString(PrefKeys.LAB_PORT, "8766") }
        val device = MemoryStore()
        Prefs(main, device)
        assertFalse(main.contains(PrefKeys.LAB_PORT))
        // MemoryStore reads a String as the default 0 (= 8765); SharedPreferences would throw, caught: nothing moved.
        assertEquals(0, device.getInt(PrefKeys.LAB_PORT, 0))
    }

    @Test fun oneStoreKeepsEverything() {
        val store = MemoryStore()
        val prefs = Prefs(store)
        prefs.setString(PrefKeys.LAB_HOST, "10.0.1.67")
        prefs.setString(PrefKeys.DARK_LEVEL, dark)
        assertEquals("10.0.1.67", store.getString(PrefKeys.LAB_HOST))
        assertEquals(dark, store.getString(PrefKeys.DARK_LEVEL))
        // Starting again doesn't remove them (the move only runs between two different stores).
        Prefs(store)
        assertEquals(dark, store.getString(PrefKeys.DARK_LEVEL))
        assertNull(store.getString(PrefKeys.LAB_TOKEN))
    }
}
