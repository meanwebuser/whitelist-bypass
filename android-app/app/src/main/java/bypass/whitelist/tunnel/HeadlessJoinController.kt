package bypass.whitelist.tunnel

import android.os.Handler
import android.os.Looper
import android.util.Log
import bypass.whitelist.ui.JoinFragmentHost
import bypass.whitelist.util.Prefs
import org.json.JSONObject

class HeadlessJoinController(
    nativeLibraryDir: String,
    private val host: JoinFragmentHost,
    private val platform: CallPlatform,
    private val url: String,
) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val relay: HeadlessRelayController = HeadlessRelayController(
        nativeLibraryDir,
        relayMode = "${platform.id}-headless-joiner",
        onLog = { host.appendLog(it) },
        onStatus = ::handleStatus,
    )

    fun start() {
        relay.start()
    }

    private fun handleStatus(status: VpnStatus) {
        Log.d(LOG_TAG, "status: $status")
        when (status) {
            VpnStatus.STARTING -> {
                host.onJoinStatus(status)
                relay.sendJoinParams(buildJoinParams().toString())
            }
            VpnStatus.TUNNEL_ACTIVE -> {
                host.onJoinStatusText("Relay ready, starting local VPN")
                mainHandler.post { host.requestVpn() }
            }
            else -> host.onJoinStatus(status)
        }
    }

    private fun buildJoinParams(): JSONObject = JSONObject().apply {
        put("displayName", Prefs.autofillName)
        put("vp8Fps", Prefs.vp8Fps)
        put("vp8Batch", Prefs.vp8Batch)
        put("dualTrack", Prefs.dualTrack)
        when (platform) {
            CallPlatform.TELEMOST -> put("joinLink", url)
            CallPlatform.WBSTREAM -> {
                put("roomId", CallPlatform.extractRoomId(url))
                put("tunnelMode", Prefs.tunnelMode.relayArg)
            }
            CallPlatform.DION -> put("roomId", CallPlatform.extractRoomId(url))
            CallPlatform.VK -> error("VK headless flow uses HeadlessVkFragment for captcha UI")
        }
    }

    override fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        relay.stop()
    }

    private companion object {
        const val LOG_TAG = "HeadlessJoinController"
    }
}
