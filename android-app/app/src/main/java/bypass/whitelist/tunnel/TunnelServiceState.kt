package bypass.whitelist.tunnel

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

object TunnelServiceState {

    @Volatile
    var vpnStatusCallback: ((VpnStatus) -> Unit)? = null

    @Volatile
    var logCallback: ((String) -> Unit)? = null

    fun isTunnelActive(context: Context): Boolean {
        return (TunnelVpnService.instance?.isRunning == true) || (ProxyService.instance?.isRunning == true)
    }

    fun isHeadlessSessionRunning(context: Context): Boolean {
        return HeadlessSessionService.instance != null
    }

    fun isAnyTunnelComponentRunning(context: Context): Boolean {
        return isTunnelActive(context) || isHeadlessSessionRunning(context)
    }

    fun requestTileRefresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
    }
}
