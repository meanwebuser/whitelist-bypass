package bypass.whitelist.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.util.Prefs

class MainFragment : Fragment(R.layout.fragment_main_screen) {

    private var content: MainFragmentView? = null
    private var pendingStatus: VpnStatus? = null
    private var connectedSinceMs: Long = 0L
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    interface Host {
        fun onConnectPressed(config: CallConfig)
        fun onDisconnectPressed()
        fun onPingPressed(callback: (success: Boolean, rttMs: Int) -> Unit)
        fun isTunnelActive(): Boolean
        fun currentStatus(): VpnStatus?
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        val container = MainFragmentView(rootView)
        content = container

        container.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
        container.bindHero(connected = isHostConnected(), status = hostStatus())

        container.onAddCallClicked = {
            AddDestinationSheet.show(parentFragmentManager)
        }
        container.onHeroPressed = {
            if (isHostConnected() || isHostConnecting()) {
                host()?.onDisconnectPressed()
            } else {
                val active = Prefs.activeDestination
                if (active != null) {
                    host()?.onConnectPressed(active)
                }
            }
        }
        container.onPingPressed = {
            container.showPingRunning()
            host()?.onPingPressed { success, rttMs ->
                container.showPingResult(success, rttMs)
            }
        }
        container.onCallSelected = { config ->
            Prefs.activeDestinationId = config.id
            if (Prefs.bindSettingsToProfiles) {
                if (config.tunnelMode == null) {
                    val updated = config.copy(tunnelMode = Prefs.tunnelMode, vp8Fps = Prefs.vp8Fps, vp8Batch = Prefs.vp8Batch)
                    Prefs.updateDestination(updated)
                } else {
                    Prefs.tunnelMode = config.tunnelMode
                    if (config.vp8Fps != null) Prefs.vp8Fps = config.vp8Fps
                    if (config.vp8Batch != null) Prefs.vp8Batch = config.vp8Batch
                }
            }
            container.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
        }
        container.onCallLongPressed = { config, anchor ->
            showRowMenu(config, anchor)
        }

        pendingStatus?.let { container.bindStatus(it) }
        pendingStatus = null
    }

    override fun onResume() {
        super.onResume()
        content?.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
        content?.bindHero(connected = isHostConnected(), status = hostStatus())
    }

    override fun onDestroyView() {
        tickHandler.removeCallbacks(tickRunnable)
        content?.detach()
        content = null
        super.onDestroyView()
    }

    fun onStatusChanged(status: VpnStatus) {
        val container = content
        if (container != null) {
            container.bindStatus(status)
        } else {
            pendingStatus = status
        }
        if (isHostConnected()) refreshStats()
    }

    fun onStatusTextChanged(text: String) {
        content?.bindStatusText(text)
    }

    fun onConnectedChanged(connected: Boolean) {
        content?.bindHero(connected = connected, status = hostStatus())
        if (connected) {
            if (connectedSinceMs == 0L) connectedSinceMs = System.currentTimeMillis()
            refreshStats()
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000L)
        } else {
            connectedSinceMs = 0L
            tickHandler.removeCallbacks(tickRunnable)
        }
    }

    fun onDestinationsChanged() {
        content?.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
    }

    private fun showRowMenu(config: CallConfig, anchor: View) {
        MenuActionSheet.show(
            manager = parentFragmentManager,
            title = config.name,
            subtitle = config.url,
            items = listOf(
                MenuActionSheet.MenuItem("rename", getString(R.string.destination_menu_rename), R.drawable.ic_action_pencil),
                MenuActionSheet.MenuItem("delete", getString(R.string.destination_menu_delete), R.drawable.ic_setting_trash, danger = true),
            ),
        ) { item ->
            when (item.id) {
                "rename" -> promptRename(config)
                "delete" -> confirmDelete(config)
            }
        }
    }

    private fun promptRename(config: CallConfig) {
        InputActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.destination_rename_title),
            fieldLabel = getString(R.string.sheet_field_name),
            initialValue = config.name,
        ) { newName ->
            if (newName != config.name) {
                Prefs.renameDestination(config.id, newName)
                onDestinationsChanged()
            }
        }
    }

    private fun confirmDelete(config: CallConfig) {
        ConfirmActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.destination_delete_title),
            subtitle = getString(R.string.destination_delete_confirm, config.name),
            confirmLabel = getString(R.string.confirm_delete),
            cancelLabel = getString(R.string.sheet_cancel),
            destructive = true,
        ) {
            Prefs.removeDestination(config.id)
            onDestinationsChanged()
        }
    }

    private fun refreshStats() {
        val view = content ?: return
        val uptimeMs = if (connectedSinceMs > 0L) System.currentTimeMillis() - connectedSinceMs else 0L
        val active = Prefs.activeDestination
        val effectiveMode = if (active != null) Prefs.tunnelMode.effectiveFor(active.platform) else Prefs.tunnelMode
        view.setStats(uptimeText = formatUptime(uptimeMs), mode = effectiveMode.label)
    }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0L) return "00:00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds / 60L) % 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun host(): Host? = activity as? Host

    private fun isHostConnected(): Boolean = host()?.isTunnelActive() ?: false

    private fun isHostConnecting(): Boolean = when (hostStatus()) {
        VpnStatus.STOPPING,
        VpnStatus.CONNECTING,
        VpnStatus.STARTING,
        VpnStatus.CALL_CONNECTED,
        VpnStatus.DATACHANNEL_OPEN -> true
        else -> false
    }

    private fun hostStatus(): VpnStatus? = host()?.currentStatus()

    companion object {
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
    }
}
