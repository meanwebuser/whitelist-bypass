package bypass.whitelist.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import bypass.whitelist.MainActivity
import bypass.whitelist.R
import bypass.whitelist.ui.JoinFragmentHost
import bypass.whitelist.util.LogWriter
import bypass.whitelist.util.Prefs
import kotlin.concurrent.thread

class HeadlessSessionService : Service() {

    private val logWriter by lazy { LogWriter(cacheDir) }
    private var controller: HeadlessJoinController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ALWAYS call startForeground to satisfy startForegroundService requirement
        // from Android 8+, even if we're immediately stopping.
        startForegroundNotification(getString(R.string.notification_session_connecting))

        when (intent?.action) {
            ACTION_STOP -> {
                stopSession(stopDependentServices = true)
                return START_NOT_STICKY
            }
            ACTION_DEPENDENT_STOPPED -> {
                stopSession(stopDependentServices = false)
                return START_NOT_STICKY
            }
            else -> {
                startSession()
                return START_STICKY
            }
        }
    }



    private fun startSession() {
        val config = Prefs.activeDestination
        if (config == null) {
            showToast(R.string.error_no_destination)
            stopSelf()
            return
        }

        val platform = config.platform

        // VK always requires captcha UI — cannot run headlessly from tile
        if (platform == CallPlatform.VK && !Prefs.headless) {
            showToast(R.string.tile_requires_headless_destination)
            stopSelf()
            return
        }

        val headlessMode =
            Prefs.headless ||
            platform == CallPlatform.WBSTREAM ||
            platform == CallPlatform.DION ||
            platform == CallPlatform.TELEMOST
        if (!headlessMode) {
            showToast(R.string.tile_requires_headless_destination)
            stopSelf()
            return
        }

        logWriter.reset()
        logWriter.append("Loading: ${config.url.trim()}")
        controller?.close()
        controller = HeadlessJoinController(
            applicationInfo.nativeLibraryDir,
            object : JoinFragmentHost {
                override fun appendLog(message: String) {
                    logWriter.append(message)
                    android.util.Log.d(TAG, message)
                    TunnelServiceState.logCallback?.invoke(message)
                }

                override fun onJoinStatus(status: VpnStatus) {
                    updateNotification(getString(status.labelRes))
                    TunnelServiceState.vpnStatusCallback?.invoke(status)
                    if (status == VpnStatus.CALL_FAILED) {
                        stopSession(stopDependentServices = true)
                    }
                }

                override fun onJoinStatusText(text: String) {
                    updateNotification(text)
                }

                override fun requestVpn() {
                    if (Prefs.proxyOnly) {
                        logWriter.append("Proxy only mode, skipping VPN")
                        startService(Intent(this@HeadlessSessionService, ProxyService::class.java))
                        updateNotification(getString(R.string.notification_proxy_title))
                        TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.TUNNEL_ACTIVE)
                        TunnelServiceState.requestTileRefresh(this@HeadlessSessionService)
                        return
                    }

                    if (TunnelServiceState.hasForeignVpn(this@HeadlessSessionService)) {
                        logWriter.append("Another VPN is active. Turn it off first.")
                        updateNotification(getString(R.string.vpn_foreign_active))
                        TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.VPN_CONFLICT)
                        showToast(R.string.vpn_foreign_active)
                        stopSession(stopDependentServices = true)
                        return
                    }

                    if (VpnService.prepare(this@HeadlessSessionService) != null) {
                        logWriter.append("VPN permission required")
                        updateNotification(getString(R.string.tile_vpn_permission_required))
                        showToast(R.string.tile_vpn_permission_required)
                        stopSession(stopDependentServices = true)
                        return
                    }

                    logWriter.append("VPN start requested")
                    startService(Intent(this@HeadlessSessionService, TunnelVpnService::class.java))
                    updateNotification(getString(R.string.vpn_starting))
                    TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.STARTING)
                    TunnelServiceState.requestTileRefresh(this@HeadlessSessionService)
                }

                override fun setJoinUiVisible(visible: Boolean) = Unit

                override fun onJoinCancel() {
                    stopSession(stopDependentServices = true)
                }
            },
            platform,
            config.url.trim(),
        )
        controller?.start()
        TunnelServiceState.requestTileRefresh(this)
    }

    private fun startForegroundNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Headless Session",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            3,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, HeadlessSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            4,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_disconnect), stopPending).build())
            .build()
    }

    private fun stopSession(stopDependentServices: Boolean) {
        val activeController = controller
        controller = null
        if (stopDependentServices) {
            try { startService(Intent(this, TunnelVpnService::class.java).apply { action = TunnelVpnService.ACTION_STOP }) } catch (_: Exception) {}
            try { startService(Intent(this, ProxyService::class.java).apply { action = ProxyService.ACTION_STOP }) } catch (_: Exception) {}
        }
        thread(name = "headless-session-shutdown") {
            activeController?.close()
            android.os.Handler(Looper.getMainLooper()).post {
                TunnelServiceState.vpnStatusCallback?.invoke(VpnStatus.CALL_DISCONNECTED)
                @Suppress("DEPRECATION")
                stopForeground(true)
                TunnelServiceState.requestTileRefresh(this)
                stopSelf()
            }
        }
    }

    private fun showToast(messageRes: Int) {
        android.os.Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_STOP = "bypass.whitelist.STOP_HEADLESS_SESSION"
        const val ACTION_DEPENDENT_STOPPED = "bypass.whitelist.HEADLESS_DEPENDENT_STOPPED"

        private const val CHANNEL_ID = "headless_session_channel"
        private const val NOTIFICATION_ID = 3
        private const val TAG = "HeadlessSession"

        @Volatile
        var instance: HeadlessSessionService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        controller = null
        logWriter.close()
        TunnelServiceState.requestTileRefresh(this)
        super.onDestroy()
    }
}
