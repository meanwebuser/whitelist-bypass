package bypass.whitelist.util

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogWriter(cacheDir: File, private val maxDisplayLines: Int = 2000) {

    private val logFile = File(cacheDir, "relay.log")
    private var writer: FileWriter? = null
    private val displayLines = ArrayDeque<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var revisionCounter: Long = 0L

    val file: File get() = logFile

    @Synchronized
    fun reset() {
        writer?.close()
        writer = FileWriter(logFile, false)
        displayLines.clear()
        revisionCounter++
    }

    @Synchronized
    fun append(msg: String) {
        val ts = dateFormat.format(Date())
        val line = "$ts $msg"
        writer?.apply { write("$line\n"); flush() }
        displayLines.addLast(line)
        if (displayLines.size > maxDisplayLines) displayLines.removeFirst()
        revisionCounter++
    }

    @Synchronized
    fun revision(): Long = revisionCounter

    @Synchronized
    fun displayText(): String = displayLines.joinToString("\n")

    @Synchronized
    fun close() {
        writer?.close()
        writer = null
    }
}
