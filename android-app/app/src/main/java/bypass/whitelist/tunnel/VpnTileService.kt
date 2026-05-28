package bypass.whitelist.tunnel

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    companion object {
        private const val TAG = "VpnTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            stopAll()
            return
        }

        if (isLocked) {
            updateTile()
            return
        }

        unlockAndRun {
            startSession()
            qsTile?.let {
                it.state = Tile.STATE_ACTIVE
                it.label = "Connecting..."
                it.updateTile()
            }
        }
    }

    private fun startSession() {
        try {
            val intent = Intent(this, HeadlessSessionService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "HeadlessSessionService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HeadlessSessionService: ${e.message}", e)
        }
    }

    private fun stopAll() {
        HeadlessSessionService.requestStop(this)
        TunnelVpnService.requestStop(this)
        ProxyService.requestStop(this)
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.label = "whitelistbypass"
            it.updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when {
            TunnelServiceState.isTunnelActive(this) -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Bypass ON"
            }
            TunnelServiceState.isHeadlessSessionRunning(this) -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Connecting..."
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "whitelistbypass"
            }
        }
        tile.updateTile()
    }
}
