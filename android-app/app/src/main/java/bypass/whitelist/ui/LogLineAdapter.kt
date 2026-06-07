package bypass.whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import bypass.whitelist.R

class LogLineAdapter(private val maxLines: Int = 500) : RecyclerView.Adapter<LogLineAdapter.LineViewHolder>() {

    private val lines = ArrayDeque<ParsedLine>()

    fun setLines(rawLines: List<String>) {
        lines.clear()
        rawLines.takeLast(maxLines).forEach { lines.addLast(parseLine(it)) }
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = lines.isEmpty()

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_log_line, parent, false)
        return LineViewHolder(row)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        holder.bind(lines.elementAt(position))
    }

    class LineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time = itemView.findViewById<TextView>(R.id.lineTime)
        private val component = itemView.findViewById<TextView>(R.id.lineComp)
        private val message = itemView.findViewById<TextView>(R.id.lineMessage)
        private val icon = itemView.findViewById<ImageView>(R.id.lineIcon)
        private val iconBox = itemView.findViewById<View>(R.id.lineIconBox)

        fun bind(parsed: ParsedLine) {
            val context = itemView.context
            time.text = parsed.time
            message.text = parsed.message
            if (parsed.component.isNotEmpty()) {
                component.text = parsed.component
                component.visibility = View.VISIBLE
            } else {
                component.visibility = View.GONE
            }

            icon.setImageResource(iconFor(parsed.component))
            val (boxBackground, iconColor, messageColor) = when (parsed.level) {
                Level.OK -> Triple(R.drawable.bg_log_box_ok, R.color.accent_emerald, R.color.ink)
                Level.WARN -> Triple(R.drawable.bg_log_box_warn, R.color.warn_amber, R.color.ink)
                Level.ERR -> Triple(R.drawable.bg_log_box_err, R.color.error_red, R.color.error_red)
                Level.INFO -> Triple(R.drawable.bg_settings_row_icon, R.color.ink_2, R.color.ink)
            }
            iconBox.setBackgroundResource(boxBackground)
            icon.setColorFilter(context.getColor(iconColor))
            message.setTextColor(context.getColor(messageColor))
        }
    }

    enum class Level { INFO, OK, WARN, ERR }

    data class ParsedLine(val time: String, val component: String, val level: Level, val message: String)

    companion object {
        private val timestampRegex = Regex("^\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?$")

        private fun parseLine(line: String): ParsedLine {
            var rest = line.trim()
            var time = ""
            val firstSpace = rest.indexOf(' ')
            if (firstSpace > 0 && rest.substring(0, firstSpace).matches(timestampRegex)) {
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
    }
}
