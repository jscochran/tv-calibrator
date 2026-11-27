package tvcal.android.camera

import tvcal.kit.cfmt

/**
 * The camera log's lines, the last [limit] (Swift keeps the last 200), each "HH:mm:ss  line". Android: a line
 * that repeats the one just before it doesn't add another: the earlier copy becomes
 * "HH:mm:ss  line (n times, the last at HH:mm:ss)". A failure logged on every missed reading (about every 0.3 s
 * while the camera is interrupted or the app is in the background) then can't push the setup lines and the line
 * saying why the camera stopped out of the log before it is shared.
 */
class CameraLog(private val limit: Int = LIMIT) {
    companion object {
        const val LIMIT = 200
    }

    var lines: List<String> = emptyList()
        private set

    private var last: String? = null
    private var firstAt = ""
    private var times = 0

    /** Adds [line] logged at [time] ("HH:mm:ss") and returns the new [lines]. */
    fun add(time: String, line: String): List<String> {
        if (line == last && lines.isNotEmpty()) {
            times++
            lines = lines.dropLast(1) + "$firstAt  $line (${cfmt(times.toDouble(), 0)} times, the last at $time)"
        } else {
            last = line
            firstAt = time
            times = 1
            lines = (lines + "$time  $line").takeLast(limit)
        }
        return lines
    }
}
