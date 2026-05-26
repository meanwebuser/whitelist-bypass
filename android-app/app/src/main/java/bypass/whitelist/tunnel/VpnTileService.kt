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
        val isRunning = TunnelServiceState.isAnyTunnelComponentRunning(this)
        if (isRunning) {
            // Stop everything — safe to call startService with STOP action even from tile
            stopAll()
        } else {
            // Use unlockAndRun so Android lifts background-start restrictions
            // This is the correct way for TileService to start foreground services
            if (isLocked) {
                // Device is locked — just update tile, can't start
                updateTile()
                return
            }
            unlockAndRun {
                startSession()
                qsTile?.let {
                    it.state = Tile.STATE_ACTIVE
                    it.label = "Connecting…"
                    it.updateTile()
                }
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
        // Send STOP actions — these are regular startService calls (not foreground)
        // which are allowed even from background since Android handles the stop case
        try {
            startService(
                Intent(this, HeadlessSessionService::class.java).apply {
                    action = HeadlessSessionService.ACTION_STOP
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "HeadlessSessionService already stopped: ${e.message}")
        }
        try {
            startService(
                Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_STOP
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "TunnelVpnService already stopped: ${e.message}")
        }
        try {
            startService(
                Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "ProxyService already stopped: ${e.message}")
        }
        // Provide immediate visual feedback since services might take up to 2 seconds to stop
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
                tile.label = "Connecting…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "whitelistbypass"
            }
        }
        tile.updateTile()
    }
}
