package bypass.whitelist.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.util.UiColors

class LogsFragment : Fragment(R.layout.fragment_logs_screen) {

    interface Host {
        fun activityLogLines(): List<String>
        fun copyLogs()
        fun shareLogs()
    }

    private var container: LinearLayout? = null
    private var scrollView: ScrollView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        container = view.findViewById(R.id.eventsContainer)
        scrollView = view.findViewById(R.id.activityScroll)
        renderLines(host()?.activityLogLines().orEmpty())
        view.findViewById<Button>(R.id.buttonCopyRaw).setOnClickListener { host()?.copyLogs() }
        view.findViewById<Button>(R.id.buttonShareFile).setOnClickListener { host()?.shareLogs() }
    }

    override fun onDestroyView() {
        container = null
        scrollView = null
        super.onDestroyView()
    }

    fun refresh() {
        renderLines(host()?.activityLogLines().orEmpty())
    }

    fun onLineAppended(line: String) {
        val parent = container ?: return
        if (parent.childCount == 1 && parent.getChildAt(0) is TextView && (parent.getChildAt(0) as TextView).text == getString(R.string.activity_empty)) {
            parent.removeAllViews()
        }
        appendRow(parent, line)
        scrollToBottom()
    }

    private fun renderLines(lines: List<String>) {
        val parent = container ?: return
        parent.removeAllViews()
        if (lines.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.activity_empty)
                setTextColor(requireContext().getColor(R.color.ink_3))
                setPadding(dp(20), dp(24), dp(20), dp(24))
                gravity = Gravity.CENTER
            }
            parent.addView(empty)
            return
        }
        lines.forEach { appendRow(parent, it) }
    }

    private fun appendRow(parent: LinearLayout, rawLine: String) {
        val inflater = LayoutInflater.from(parent.context)
        val row = inflater.inflate(R.layout.item_log_line, parent, false)
        bindRow(row, parseLine(rawLine))
        parent.addView(row)
    }

    private fun bindRow(row: View, parsed: ParsedLine) {
        val context = row.context
        val time = row.findViewById<TextView>(R.id.lineTime)
        val comp = row.findViewById<TextView>(R.id.lineComp)
        val message = row.findViewById<TextView>(R.id.lineMessage)
        val icon = row.findViewById<ImageView>(R.id.lineIcon)
        val iconBox = row.findViewById<View>(R.id.lineIconBox)

        time.text = parsed.time
        message.text = parsed.message
        if (parsed.component.isNotEmpty()) {
            comp.text = parsed.component
            comp.visibility = View.VISIBLE
        } else {
            comp.visibility = View.GONE
        }

        icon.setImageResource(iconFor(parsed.component))
        val (boxBg, iconColor, msgColor) = when (parsed.level) {
            Level.OK -> Triple(R.drawable.bg_log_box_ok, UiColors.accent(context), context.getColor(R.color.ink))
            Level.WARN -> Triple(R.drawable.bg_log_box_warn, context.getColor(R.color.warn_amber), context.getColor(R.color.ink))
            Level.ERR -> Triple(R.drawable.bg_log_box_err, context.getColor(R.color.error_red), context.getColor(R.color.error_red))
            Level.INFO -> Triple(R.drawable.bg_settings_row_icon, context.getColor(R.color.ink_2), context.getColor(R.color.ink))
        }
        iconBox.setBackgroundResource(boxBg)
        icon.setColorFilter(iconColor)
        message.setTextColor(msgColor)
    }

    private enum class Level { INFO, OK, WARN, ERR }
    private data class ParsedLine(val time: String, val component: String, val level: Level, val message: String)

    private fun parseLine(line: String): ParsedLine {
        var rest = line.trim()
        var time = ""
        val firstSpace = rest.indexOf(' ')
        if (firstSpace > 0 && rest.substring(0, firstSpace).matches(TS_REGEX)) {
            time = rest.substring(0, minOf(firstSpace, 8))
            rest = rest.substring(firstSpace + 1).trim()
        }

        var component = ""
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close > 0) {
                component = rest.substring(1, close).trim()
                rest = rest.substring(close + 1).trim()
            }
        }

        val lower = rest.lowercase()
        val level = when {
            lower.contains("error:") || lower.startsWith("error ") || lower.contains("failed") -> Level.ERR
            lower.contains("warn") || lower.contains("throttle") || lower.contains("drift") || lower.contains("late") || lower.contains("captcha") -> Level.WARN
            lower.contains("connected") || lower.contains("established") || lower.contains("ready") || lower.contains("active") -> Level.OK
            else -> Level.INFO
        }

        return ParsedLine(time = time, component = component, level = level, message = rest)
    }

    private fun iconFor(component: String): Int = when (component.lowercase()) {
        "boot" -> R.drawable.ic_log_boot
        "ws" -> R.drawable.ic_log_ws
        "sfu" -> R.drawable.ic_log_sfu
        "tunnel" -> R.drawable.ic_log_tunnel
        "vp8" -> R.drawable.ic_log_vp8
        "sctp" -> R.drawable.ic_log_sctp
        "relay" -> R.drawable.ic_log_relay
        "heartbeat" -> R.drawable.ic_log_heartbeat
        "captcha" -> R.drawable.ic_log_captcha
        "error" -> R.drawable.ic_log_error
        else -> R.drawable.ic_log_info
    }

    private fun scrollToBottom() {
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun host(): Host? = activity as? Host

    companion object {
        private val TS_REGEX = Regex("^\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?$")
    }
}
