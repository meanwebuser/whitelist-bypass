package bypass.whitelist.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import bypass.whitelist.R

class LogsFragment : Fragment(R.layout.fragment_logs_screen) {

    interface Host {
        fun activityLogLines(): List<String>
        fun activityLogRevision(): Long
        fun copyLogs()
        fun shareLogs()
    }

    private var recyclerView: RecyclerView? = null
    private var emptyView: View? = null
    private val adapter = LogLineAdapter()
    private var lastRevision = -1L
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            syncIfChanged()
            tickHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.activityList)
        recyclerView = list
        emptyView = view.findViewById(R.id.activityEmpty)
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        list.adapter = adapter
        syncFromHost(forceScroll = true)
        view.findViewById<Button>(R.id.buttonCopyRaw).setOnClickListener { host()?.copyLogs() }
        view.findViewById<Button>(R.id.buttonShareFile).setOnClickListener { host()?.shareLogs() }
    }

    override fun onResume() {
        super.onResume()
        syncIfChanged()
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.postDelayed(tickRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        tickHandler.removeCallbacks(tickRunnable)
    }

    override fun onDestroyView() {
        tickHandler.removeCallbacks(tickRunnable)
        recyclerView = null
        emptyView = null
        super.onDestroyView()
    }

    fun refresh() {
        syncFromHost(forceScroll = true)
    }

    private fun syncIfChanged() {
        val revision = host()?.activityLogRevision() ?: return
        if (revision != lastRevision) syncFromHost(forceScroll = false)
    }

    private fun syncFromHost(forceScroll: Boolean) {
        val host = host() ?: return
        val revision = host.activityLogRevision()
        val lines = host.activityLogLines()
        val atBottom = isAtBottom()
        adapter.setLines(lines)
        lastRevision = revision
        updateEmptyState()
        if (forceScroll || atBottom) scrollToBottom()
    }

    private fun updateEmptyState() {
        val empty = adapter.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        recyclerView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun isAtBottom(): Boolean {
        val manager = recyclerView?.layoutManager as? LinearLayoutManager ?: return true
        val count = adapter.itemCount
        if (count == 0) return true
        return manager.findLastCompletelyVisibleItemPosition() >= count - 1
    }

    private fun scrollToBottom() {
        val list = recyclerView ?: return
        val count = adapter.itemCount
        if (count > 0) list.post { list.scrollToPosition(count - 1) }
    }

    private fun host(): Host? = activity as? Host

    companion object {
        private const val REFRESH_INTERVAL_MS = 400L
    }
}
