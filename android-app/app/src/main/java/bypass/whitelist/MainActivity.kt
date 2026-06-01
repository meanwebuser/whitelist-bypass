package bypass.whitelist

import android.animation.ArgbEvaluator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import bypass.whitelist.discovery.VkDiscoveryScanner
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.HeadlessJoinController
import bypass.whitelist.tunnel.HeadlessSessionService
import bypass.whitelist.tunnel.PortGuard
import bypass.whitelist.tunnel.ProxyService
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.tunnel.TunnelServiceState
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.ui.CallsListener
import bypass.whitelist.ui.HeadlessVkFragment
import bypass.whitelist.ui.JoinFragmentHost
import bypass.whitelist.ui.JoinSessionShutdown
import bypass.whitelist.ui.JsHookJoinFragment
import bypass.whitelist.ui.LogsFragment
import bypass.whitelist.ui.MainActivityHost
import bypass.whitelist.ui.MainFragment
import bypass.whitelist.ui.SettingsScreenFragment
import bypass.whitelist.update.AppUpdater
import bypass.whitelist.util.LogWriter
import bypass.whitelist.util.Net
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.UiColors
import bypass.whitelist.util.SocksAuth
import bypass.whitelist.util.maskUrl
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class MainActivity :
    AppCompatActivity(),
    JoinFragmentHost,
    MainActivityHost,
    MainFragment.Host,
    SettingsScreenFragment.Host,
    LogsFragment.Host,
    CallsListener {

    private val logWriter by lazy { LogWriter(cacheDir) }

    private lateinit var bottomNav: View
    private lateinit var navMain: LinearLayout
    private lateinit var navSettings: LinearLayout
    private lateinit var navLogs: LinearLayout
    private lateinit var navMainIcon: ImageView
    private lateinit var navSettingsIcon: ImageView
    private lateinit var navLogsIcon: ImageView
    private lateinit var navMainLabel: TextView
    private lateinit var navSettingsLabel: TextView
    private lateinit var navLogsLabel: TextView
    private lateinit var tabContainer: ViewPager2
    private lateinit var navIndicator: View
    private lateinit var subPageContainer: View
    private lateinit var joinOverlayContainer: View
    private lateinit var overlayLogs: View
    private lateinit var overlayLogsText: TextView
    private lateinit var overlayLogsScroll: ScrollView

    private var currentTabId: Int = 0
    private var lastStatus: VpnStatus? = null
    private var connected: Boolean = false
    private var activeJoinUrl: String = ""
    private var activeHeadlessController: HeadlessJoinController? = null
    private var navPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var navScrollState: Int = ViewPager2.SCROLL_STATE_IDLE
    @Volatile private var resetInProgress: Boolean = false
    @Volatile private var resetGeneration: Long = 0L
    private var pendingConnectConfig: CallConfig? = null
    private var pendingDiscoveryRescan: Boolean = false
    private val badDiscoveryRooms = linkedSetOf<String>()
    private var currentDiscoveryRoom: String? = null
    private var staleRoomRecoveryInProgress: Boolean = false
    private val navColorEvaluator = ArgbEvaluator()

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
        else appendLog("VPN permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTransparentSystemBars()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNav)
        navMain = findViewById(R.id.navMain)
        navSettings = findViewById(R.id.navSettings)
        navLogs = findViewById(R.id.navLogs)
        navMainIcon = findViewById(R.id.navMainIcon)
        navSettingsIcon = findViewById(R.id.navSettingsIcon)
        navLogsIcon = findViewById(R.id.navLogsIcon)
        navMainLabel = findViewById(R.id.navMainLabel)
        navSettingsLabel = findViewById(R.id.navSettingsLabel)
        navLogsLabel = findViewById(R.id.navLogsLabel)
        tabContainer = findViewById(R.id.tabContainer)
        navIndicator = findViewById(R.id.navIndicator)
        subPageContainer = findViewById(R.id.subPageContainer)
        joinOverlayContainer = findViewById(R.id.joinOverlayContainer)
        overlayLogs = findViewById(R.id.overlayLogs)
        overlayLogsText = findViewById(R.id.overlayLogsText)
        overlayLogsScroll = findViewById(R.id.overlayLogsScroll)

        tabContainer.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    TAB_MAIN -> MainFragment()
                    TAB_SETTINGS -> SettingsScreenFragment()
                    else -> LogsFragment()
                }
            }
        }
        tabContainer.offscreenPageLimit = 3

        navPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                moveNavIndicatorForPager(position, positionOffset)
                interpolateNavSelection(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentTabId = when (position) {
                    TAB_MAIN -> R.id.navMain
                    TAB_SETTINGS -> R.id.navSettings
                    TAB_LOGS -> R.id.navLogs
                    else -> return
                }
                if (navScrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    updateNavSelection(currentTabId)
                }
                settingsFragment()?.refresh()
            }

            override fun onPageScrollStateChanged(state: Int) {
                navScrollState = state
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    updateNavSelection(currentTabId)
                    moveNavIndicatorTo(currentTabId, animate = false)
                }
            }
        }.also(tabContainer::registerOnPageChangeCallback)

        findViewById<View>(R.id.overlayCopyButton).setOnClickListener { copyLogs() }
        findViewById<View>(R.id.overlayShareButton).setOnClickListener { shareLogs() }

        val baseTabPaddingTop = findViewById<View>(R.id.tabContainerWrap).paddingTop
        val bottomWrap = findViewById<View>(R.id.bottomWrap)
        val baseBottomWrapPaddingBottom = bottomWrap.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.tabContainerWrap).setPadding(
                bars.left,
                baseTabPaddingTop + bars.top,
                bars.right,
                0
            )
            subPageContainer.setPadding(bars.left, bars.top, bars.right, 0)
            joinOverlayContainer.setPadding(bars.left, bars.top, bars.right, 0)
            bottomWrap.setPadding(
                bars.left,
                0,
                bars.right,
                baseBottomWrapPaddingBottom + bars.bottom
            )
            insets
        }

        navMain.setOnClickListener { selectNavTab(R.id.navMain) }
        navSettings.setOnClickListener { selectNavTab(R.id.navSettings) }
        navLogs.setOnClickListener { selectNavTab(R.id.navLogs) }

        val restoredTabId =
            savedInstanceState?.getInt(STATE_CURRENT_TAB_ID, R.id.navMain) ?: R.id.navMain
        selectNavTab(restoredTabId, animatePager = false)
        findViewById<View>(R.id.navItemsRow).doOnLayout {
            moveNavIndicatorTo(currentTabId, animate = false)
        }

        TunnelVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        ProxyService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }

        if (CALL_LINK.isNotEmpty() && !TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            startJoinFor(CallConfig.newWith(name = CallConfig.suggestNameFor(CALL_LINK), url = CALL_LINK))
        } else if (Prefs.connectOnStart && !TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            Prefs.activeDestination?.let(::startJoinFor)
        }

        handleIntent(intent)
        AppUpdater.check(this, manual = false) { appendLog(it) }
    }

    override fun onResume() {
        super.onResume()
        TunnelVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        ProxyService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }

        TunnelServiceState.vpnStatusCallback = { status ->
            runOnUiThread {
                if (resetInProgress) {
                    mainFragment()?.onStatusChanged(VpnStatus.STOPPING)
                    mainFragment()?.onStatusTextChanged("Stopping previous session...")
                    return@runOnUiThread
                }
                lastStatus = status
                mainFragment()?.onStatusChanged(status)
                if (status == VpnStatus.TUNNEL_ACTIVE) {
                    if (!connected) {
                        connected = true
                        mainFragment()?.onConnectedChanged(true)
                    }
                } else if (status == VpnStatus.CALL_FAILED || status == VpnStatus.CALL_DISCONNECTED || status == VpnStatus.TUNNEL_LOST) {
                    if (connected) {
                        connected = false
                        mainFragment()?.onConnectedChanged(false)
                    }
                }
            }
        }

        TunnelServiceState.logCallback = { message ->
            runOnUiThread { appendLog(message) }
        }

        when {
            resetInProgress -> {
                connected = false
                lastStatus = VpnStatus.STOPPING
                mainFragment()?.onConnectedChanged(false)
                mainFragment()?.onStatusChanged(VpnStatus.STOPPING)
                mainFragment()?.onStatusTextChanged("Stopping previous session...")
            }
            TunnelServiceState.isTunnelActive(this) -> {
                if (!connected || lastStatus != VpnStatus.TUNNEL_ACTIVE) {
                    connected = true
                    lastStatus = VpnStatus.TUNNEL_ACTIVE
                    mainFragment()?.onStatusChanged(VpnStatus.TUNNEL_ACTIVE)
                    mainFragment()?.onConnectedChanged(true)
                }
            }
            TunnelServiceState.isHeadlessSessionRunning(this) -> {
                connected = false
                lastStatus = VpnStatus.CONNECTING
                mainFragment()?.onConnectedChanged(false)
                mainFragment()?.onStatusChanged(VpnStatus.CONNECTING)
            }
            connected && lastStatus == VpnStatus.TUNNEL_ACTIVE -> {
                onDisconnectFromService()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        TunnelServiceState.vpnStatusCallback = null
        TunnelServiceState.logCallback = null
    }

    override fun onDestroy() {
        navPageChangeCallback?.let(tabContainer::unregisterOnPageChangeCallback)
        navPageChangeCallback = null
        TunnelVpnService.onDisconnect = null
        ProxyService.onDisconnect = null
        logWriter.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_TAB_ID, currentTabId)
    }

    override fun onConnectPressed(config: CallConfig) {
        if (resetInProgress) {
            pendingConnectConfig = config
            appendLog("Queued connect after previous session stops")
            mainFragment()?.onStatusTextChanged("Stopping previous session...")
            return
        }
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.socksPort)) {
            pendingConnectConfig = config
            appendLog("Waiting for previous local tunnel to stop")
            fullReset()
            return
        }
        startJoinFor(config)
    }

    override fun onDiscoveryConnectPressed() {
        if (resetInProgress || TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            pendingDiscoveryRescan = true
            mainFragment()?.onStatusTextChanged("Остановите текущую сессию перед сканированием")
            return
        }
        mainFragment()?.onStatusTextChanged("Сканирование VK…")
        appendLog("Discovery scan started: VK group 237416141")
        VkDiscoveryScanner.scanWithWebView(
            activity = this,
            onProgress = { step ->
                runOnUiThread {
                    mainFragment()?.onStatusTextChanged(step)
                    appendLog("Discovery: $step")
                }
            },
            onDone = { result ->
                runOnUiThread {
                    val allConfigs = result.configs
                    val configs = allConfigs.filter { !badDiscoveryRooms.contains(it.url) }
                    val ignored = allConfigs.size - configs.size
                    mainFragment()?.onStatusTextChanged("Найдено свободных: ${configs.size}")
                    appendLog("Discovery scan finished: free=${configs.size}, ignored_bad=$ignored, method=${result.method}, source=${result.source ?: "none"}")
                    if (configs.isNotEmpty()) {
                        val picked = configs.first()
                        currentDiscoveryRoom = picked.url
                        Prefs.autoDestination = picked
                        Prefs.activeDestinationId = picked.id
                        mainFragment()?.onDestinationsChanged()
                        appendLog("Discovery picked auto room: slot=${picked.slotId ?: "?"} lease=${picked.leaseId ?: "?"}")
                        startJoinFor(picked)
                    } else {
                        sendPrivateBusClientEvent("request_room", null, "no_free_rooms_or_all_bad")
                    }
                }
            }
        )
    }

    override fun onDisconnectPressed() {
        pendingConnectConfig = null
        if (resetInProgress) {
            forceUnlockReset("Stopped waiting for previous session")
            return
        }
        fullReset()
    }

    override fun onCopySocksPressed() {
        val url = "socks5://${SocksAuth.user}:${SocksAuth.pass}@${Prefs.socksHost}:${Prefs.socksPort}"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SOCKS5 URL", url))
        Toast.makeText(this, R.string.copy_socks_url_toast, Toast.LENGTH_SHORT).show()
        appendLog("SOCKS5 URL copied: socks5://<auth>@${Prefs.socksHost}:${Prefs.socksPort}")
    }

    override fun onPingPressed(callback: (Boolean, Int) -> Unit) {
        thread {
            val started = System.nanoTime()
            val ok = try {
                probeHttpsViaSocks5(host = "t.me", path = "/Kuplinov_Telegram/1032")
            } catch (_: Exception) {
                false
            }
            val rtt = ((System.nanoTime() - started) / 1_000_000).toInt()
            runOnUiThread { callback(ok, rtt) }
        }
    }

    override fun onTunnelDiagnosticsPressed(callback: (String, Boolean) -> Unit, progress: (String) -> Unit) {
        thread {
            val result = try {
                runTunnelDiagnostics { text -> runOnUiThread { progress(text) } }
            } catch (e: Exception) {
                appendLog("Tunnel diagnostics failed: ${e.message}")
                "diagnostics failed: ${e.message ?: "unknown"}" to false
            }
            runOnUiThread { callback(result.first, result.second) }
        }
    }

    override fun onSpeedTestPressed(callback: (String, Boolean) -> Unit) {
        thread {
            val result = try {
                runTunnelSpeedTest()
            } catch (e: Exception) {
                appendLog("Speedtest failed: ${e.message}")
                getString(R.string.speedtest_failed) to false
            }
            runOnUiThread { callback(result.first, result.second) }
        }
    }

    override fun isTunnelActive(): Boolean = connected

    override fun currentStatus(): VpnStatus? = lastStatus

    override fun onDestinationSelected(config: CallConfig) {
        Prefs.activeDestinationId = config.id
        mainFragment()?.onDestinationsChanged()
    }

    override fun onDestinationsChanged() {
        mainFragment()?.onDestinationsChanged()
    }

    override fun onTunnelModeChanged(mode: TunnelMode) {
        fullReset()
    }

    override fun onForgetAllDestinations() {
        Prefs.savedDestinations = emptyList()
        Prefs.activeDestinationId = ""
        mainFragment()?.onDestinationsChanged()
        Toast.makeText(this, R.string.settings_toast_destinations_cleared, Toast.LENGTH_SHORT)
            .show()
    }

    override fun onCheckForUpdates() {
        AppUpdater.check(this, manual = true) { appendLog(it) }
    }

    override fun onResetAllSettings() {
        Prefs.resetAllSettings()
        App.applyTheme(Prefs.themeMode)
        App.applyLanguage(Prefs.languageMode)
        settingsFragment()?.refresh()
        Toast.makeText(this, R.string.settings_toast_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun activityLogLines(): List<String> {
        val text = logWriter.displayText()
        if (text.isEmpty()) return emptyList()
        return text.split('\n').filter { it.isNotBlank() }
    }

    override fun copyLogs() {
        val contents =
            if (logWriter.file.exists()) logWriter.file.readText() else logWriter.displayText()
        val clipboard =
            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("relay.log", contents))
        Toast.makeText(this, R.string.copy_logs_toast, Toast.LENGTH_SHORT).show()
    }

    override fun shareLogs() {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            logWriter.file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_logs)))
    }

    override fun appendLog(message: String) {
        val (line, _) = logWriter.append(message)
        runOnUiThread {
            logsFragment()?.onLineAppended(line)
            if (overlayLogs.isVisible) {
                overlayLogsText.append("$line\n")
                overlayLogsScroll.post { overlayLogsScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        if (message.contains("guest cannot create room", ignoreCase = true)) {
            scheduleBadRoomRecovery("guest_cannot_create_room")
        }
    }

    override fun onJoinStatusText(text: String) {
        if (resetInProgress) return
        runOnUiThread { mainFragment()?.onStatusTextChanged(text) }
    }

    override fun onJoinStatus(status: VpnStatus) {
        if (resetInProgress && status != VpnStatus.CALL_FAILED) return
        TunnelVpnService.instance?.updateStatus(status)
        ProxyService.instance?.updateStatus(status)
        lastStatus = status
        runOnUiThread {
            if (status == VpnStatus.CALL_FAILED) {
                fullReset()
                lastStatus = VpnStatus.CALL_FAILED
                mainFragment()?.onStatusChanged(VpnStatus.CALL_FAILED)
                return@runOnUiThread
            }
            mainFragment()?.onStatusChanged(status)
            if (status == VpnStatus.TUNNEL_ACTIVE) {
                connected = true
                mainFragment()?.onConnectedChanged(true)
            }
        }
    }

    override fun pushSubPage(fragment: Fragment) {
        subPageContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.subPageContainer, fragment, SUB_PAGE_TAG)
            .addToBackStack(SUB_PAGE_TAG)
            .commit()
    }

    override fun popSubPage() {
        supportFragmentManager.popBackStackImmediate(
            SUB_PAGE_TAG,
            FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        subPageContainer.visibility = View.GONE
    }

    override fun onJoinCancel() {
        pendingConnectConfig = null
        runOnUiThread { fullReset() }
    }

    override fun setJoinUiVisible(visible: Boolean) {
        runOnUiThread { setJoinOverlayVisible(visible) }
    }

    override fun requestVpn() {
        if (Prefs.proxyOnly) {
            appendLog("Proxy only mode, skipping VPN")
            startService(Intent(this, ProxyService::class.java))
            onJoinStatus(VpnStatus.TUNNEL_ACTIVE)
            return
        }
        if (TunnelServiceState.hasForeignVpn(this)) {
            appendLog("Another VPN is active, requesting system VPN switch")
            mainFragment()?.onStatusTextChanged("Requesting VPN replacement...")
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startVpnService()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_AUTO_START) return
        intent.action = null
        val isConnecting = lastStatus == VpnStatus.CONNECTING
        if (!connected && !isConnecting && !TunnelServiceState.isAnyTunnelComponentRunning(this)) {
            Prefs.activeDestination?.let(::onConnectPressed) ?: run {
                Toast.makeText(this, R.string.error_no_destination, Toast.LENGTH_SHORT).show()
            }
        } else if (connected) {
            onDisconnectPressed()
        }
    }

    private fun selectNavTab(itemId: Int, animatePager: Boolean = true) {
        if (currentTabId == itemId) return
        currentTabId = itemId
        dismissSubPage()
        val index = when (itemId) {
            R.id.navMain -> TAB_MAIN
            R.id.navSettings -> TAB_SETTINGS
            R.id.navLogs -> TAB_LOGS
            else -> TAB_MAIN
        }
        updateNavSelection(itemId)
        val animateIndicator = tabContainer.currentItem != index
        if (!animatePager || tabContainer.currentItem == index) {
            moveNavIndicatorTo(itemId, animate = animateIndicator)
        }
        tabContainer.setCurrentItem(index, animatePager)
    }

    private fun updateNavSelection(itemId: Int) {
        applyNavSelectionState(0f, when (itemId) {
            R.id.navMain -> TAB_MAIN
            R.id.navSettings -> TAB_SETTINGS
            R.id.navLogs -> TAB_LOGS
            else -> TAB_MAIN
        })
    }

    private fun moveNavIndicatorTo(itemId: Int, animate: Boolean) {
        val target = when (itemId) {
            R.id.navMain -> navMain
            R.id.navSettings -> navSettings
            R.id.navLogs -> navLogs
            else -> null
        } ?: return

        navIndicator.layoutParams = navIndicator.layoutParams.apply {
            width = target.width
            height = target.height
        }
        navIndicator.requestLayout()
        val targetTranslation = target.left.toFloat()
        if (animate) {
            navIndicator.animate()
                .translationX(targetTranslation)
                .setDuration(220L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            navIndicator.animate().cancel()
            navIndicator.translationX = targetTranslation
        }
    }

    private fun moveNavIndicatorForPager(position: Int, positionOffset: Float) {
        val targets = listOf(navMain, navSettings, navLogs)
        val current = targets.getOrNull(position) ?: return
        val next = targets.getOrNull(position + 1)
        val targetLeft = if (next != null) {
            current.left + ((next.left - current.left) * positionOffset)
        } else {
            current.left.toFloat()
        }
        val lp = navIndicator.layoutParams
        if (lp.width != current.width || lp.height != current.height) {
            navIndicator.layoutParams = lp.apply {
                width = current.width
                height = current.height
            }
            navIndicator.requestLayout()
        }
        navIndicator.animate().cancel()
        navIndicator.translationX = targetLeft
    }

    private fun interpolateNavSelection(position: Int, positionOffset: Float) {
        if (navScrollState == ViewPager2.SCROLL_STATE_IDLE) return
        val clampedOffset = positionOffset.coerceIn(0f, 1f)
        applyNavSelectionState(clampedOffset, position)
    }

    private fun applyNavSelectionState(positionOffset: Float, position: Int) {
        val emphasis = floatArrayOf(0f, 0f, 0f)
        val baseIndex = position.coerceIn(0, emphasis.lastIndex)
        emphasis[baseIndex] = 1f - positionOffset
        val nextIndex = (baseIndex + 1).coerceAtMost(emphasis.lastIndex)
        if (nextIndex != baseIndex) {
            emphasis[nextIndex] = positionOffset
        }

        applyNavVisual(navMainIcon, navMainLabel, emphasis[0])
        applyNavVisual(navSettingsIcon, navSettingsLabel, emphasis[1])
        applyNavVisual(navLogsIcon, navLogsLabel, emphasis[2])
    }

    private fun applyNavVisual(icon: ImageView, label: TextView, emphasis: Float) {
        val accent = UiColors.accent(this)
        val ink = getColor(R.color.ink_3)
        val blended = navColorEvaluator.evaluate(emphasis, ink, accent) as Int
        icon.setColorFilter(blended)
        icon.alpha = 0.72f + (0.28f * emphasis)
        label.setTextColor(blended)
        label.alpha = 0.74f + (0.26f * emphasis)
        label.scaleX = 1f + (0.06f * emphasis)
        label.scaleY = 1f + (0.06f * emphasis)
        label.paint.isFakeBoldText = emphasis > 0.92f
    }

    private fun applyTransparentSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun mainFragment(): MainFragment? =
        supportFragmentManager.fragments.firstOrNull { it is MainFragment } as? MainFragment

    private fun settingsFragment(): SettingsScreenFragment? =
        supportFragmentManager.fragments.firstOrNull { it is SettingsScreenFragment } as? SettingsScreenFragment

    private fun logsFragment(): LogsFragment? =
        supportFragmentManager.fragments.firstOrNull { it is LogsFragment } as? LogsFragment


    private fun runTunnelDiagnostics(progress: (String) -> Unit): Pair<String, Boolean> {
        val host = "10.255.0.1"
        val port = 18080
        progress(getString(R.string.diag_ping))
        val latencyStart = System.nanoTime()
        val ping = socksHttp(host, port, "GET /ping HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${speedUserAgent()}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII), readTimeoutMs = 7000)
        if (!String(ping, Charsets.ISO_8859_1).startsWith("HTTP/1.")) return "ping failed" to false
        val latencyMs = ((System.nanoTime() - latencyStart) / 1_000_000).toInt()

        progress("/ping: ${latencyMs} ms · ${getString(R.string.diag_external_ip)}")
        val ipResp = socksHttp("api.ipify.org", 80, "GET / HTTP/1.1\r\nHost: api.ipify.org\r\nUser-Agent: ${speedUserAgent()}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII), readTimeoutMs = 9000)
        val externalIp = String(httpBody(ipResp), Charsets.UTF_8).trim().lineSequence().firstOrNull()?.take(64).orEmpty()
        if (externalIp.isBlank()) return "/ping: ${latencyMs} ms · external IP failed" to false

        progress("/ping: ${latencyMs} ms · IP: $externalIp · ${getString(R.string.diag_telegram)}")
        val tgStart = System.nanoTime()
        val tgOk = probeHttpsViaSocks5(host = "t.me", path = "/Kuplinov_Telegram/1032")
        val tgMs = ((System.nanoTime() - tgStart) / 1_000_000).toInt()
        val text = "/ping ${latencyMs} ms · IP $externalIp · t.me ${if (tgOk) "OK" else "FAIL"} ${tgMs} ms"
        appendLog("Tunnel diagnostics $text")
        return text to tgOk
    }

    private fun runTunnelSpeedTest(): Pair<String, Boolean> {
        val host = "10.255.0.1"
        val port = 18080
        val latencyStart = System.nanoTime()
        val ping = socksHttp(host, port, "GET /ping HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${speedUserAgent()}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (!String(ping, Charsets.ISO_8859_1).startsWith("HTTP/1.")) return getString(R.string.speedtest_failed) to false
        val latencyMs = ((System.nanoTime() - latencyStart) / 1_000_000).toInt()

        val downloadBytes = 2 * 1024 * 1024
        val dlStart = System.nanoTime()
        val dl = socksHttp(host, port, "GET /download?bytes=$downloadBytes HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${speedUserAgent()}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val dlBody = httpBody(dl)
        val dlSec = (System.nanoTime() - dlStart) / 1_000_000_000.0
        val dlMbps = if (dlSec > 0) dlBody.size * 8.0 / dlSec / 1_000_000.0 else 0.0

        val uploadBytes = 1024 * 1024
        val uploadBody = ByteArray(uploadBytes) { 0x5a.toByte() }
        val header = "POST /upload HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${speedUserAgent()}\r\nContent-Length: $uploadBytes\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val upStart = System.nanoTime()
        val upResp = socksHttp(host, port, header + uploadBody)
        val upSec = (System.nanoTime() - upStart) / 1_000_000_000.0
        val upMbps = if (upSec > 0) uploadBytes * 8.0 / upSec / 1_000_000.0 else 0.0
        if (!String(upResp, Charsets.ISO_8859_1).startsWith("HTTP/1.")) return getString(R.string.speedtest_failed) to false

        val text = "${latencyMs} ms · ↓ %.1f Mbps · ↑ %.1f Mbps".format(dlMbps, upMbps)
        appendLog("Speedtest $text")
        return text to true
    }

    private fun speedUserAgent(): String {
        val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown" } catch (_: Exception) { "unknown" }
        return "BEZabotny-NET/${appVersionName()} Android/${Build.VERSION.RELEASE} ${Build.MANUFACTURER}/${Build.MODEL} client=${androidId.takeLast(8)}"
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun socksHttp(host: String, port: Int, request: ByteArray, readTimeoutMs: Int = 15000): ByteArray {
        openSocks5Tcp(host, port).use { socket ->
            socket.soTimeout = readTimeoutMs
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write(request)
            output.flush()
            val buf = ByteArray(64 * 1024)
            val out = ByteArrayOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun httpBody(response: ByteArray): ByteArray {
        val needle = byteArrayOf(13, 10, 13, 10)
        for (i in 0..response.size - needle.size) {
            if (needle.indices.all { response[i + it] == needle[it] }) {
                return response.copyOfRange(i + needle.size, response.size)
            }
        }
        return response
    }

    private fun openSocks5Tcp(host: String, port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(Net.LOCALHOST, Prefs.socksPort.toInt()), 3000)
        socket.soTimeout = 10000
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        if (input.read() != 0x05 || input.read() != 0x02) throw java.io.IOException("SOCKS auth method rejected")
        val userBytes = SocksAuth.user.toByteArray(Charsets.US_ASCII)
        val passBytes = SocksAuth.pass.toByteArray(Charsets.US_ASCII)
        val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
        authPacket[0] = 0x01
        authPacket[1] = userBytes.size.toByte()
        System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
        authPacket[2 + userBytes.size] = passBytes.size.toByte()
        System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
        output.write(authPacket)
        output.flush()
        if (input.read() != 0x01 || input.read() != 0x00) throw java.io.IOException("SOCKS auth failed")
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(4 + 1 + hostBytes.size + 2)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
        request[5 + hostBytes.size] = ((port ushr 8) and 0xff).toByte()
        request[6 + hostBytes.size] = (port and 0xff).toByte()
        output.write(request)
        output.flush()
        if (input.read() != 0x05 || input.read() != 0x00) throw java.io.IOException("SOCKS connect failed")
        if (input.read() != 0x00) throw java.io.IOException("SOCKS reserved byte failed")
        when (input.read()) {
            0x01 -> readFully(input, 4)
            0x03 -> readFully(input, input.read())
            0x04 -> readFully(input, 16)
            else -> throw java.io.IOException("SOCKS bad address type")
        }
        readFully(input, 2)
        return socket
    }

    private fun probeHttpsViaSocks5(host: String, path: String): Boolean {
        openSocks5Tcp(host, 443).use { socket ->
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, host, 443, true) as SSLSocket
            sslSocket.soTimeout = 8000
            sslSocket.startHandshake()
            val tlsOutput = sslSocket.getOutputStream()
            val tlsInput = sslSocket.getInputStream()
            val httpRequest = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: BEZabotny-NET ping\r\nAccept: text/html,*/*\r\nConnection: close\r\n\r\n"
            tlsOutput.write(httpRequest.toByteArray(Charsets.US_ASCII))
            tlsOutput.flush()
            val status = tlsInput.bufferedReader(Charsets.US_ASCII).readLine() ?: return false
            appendLog("Ping target $host$path -> $status")
            return status.contains(" 2") || status.contains(" 3")
        }
    }

    private fun readFully(input: java.io.InputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(256)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) throw java.io.EOFException()
            remaining -= n
        }
    }

    private fun dismissSubPage() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(
                SUB_PAGE_TAG,
                FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }
        subPageContainer.visibility = View.GONE
    }

    private fun setJoinOverlayVisible(visible: Boolean) {
        joinOverlayContainer.visibility = if (visible) View.VISIBLE else View.GONE
        overlayLogs.visibility = if (visible) View.VISIBLE else View.GONE
        bottomNav.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            overlayLogsText.text = logWriter.displayText()
            overlayLogsScroll.post { overlayLogsScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }


    private fun discoveryClientId(): String {
        val androidId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown" } catch (_: Exception) { "unknown" }
        return "android-${androidId.takeLast(12)}"
    }

    private fun sendPrivateBusClientEvent(type: String, room: String?, reason: String) {
        val badSnapshot = synchronized(badDiscoveryRooms) { badDiscoveryRooms.toList() }
        thread(name = "wt-client-event") {
            val ok = VkDiscoveryScanner.sendClientEvent(
                type = type,
                clientId = discoveryClientId(),
                room = room,
                reason = reason,
                badRooms = badSnapshot,
            )
            appendLog("Private-bus $type sent: $ok")
        }
    }

    private fun scheduleBadRoomRecovery(reason: String) {
        val room = currentDiscoveryRoom?.takeIf { it.startsWith("wbstream://") }
            ?: activeJoinUrl.takeIf { it.startsWith("wbstream://") }
            ?: return
        if (staleRoomRecoveryInProgress) return
        staleRoomRecoveryInProgress = true
        badDiscoveryRooms.add(room)
        appendLog("Room looks stale; blacklisted locally and requesting replacement")
        sendPrivateBusClientEvent("bad_room", room, reason)
        pendingDiscoveryRescan = true
        runOnUiThread {
            if (!resetInProgress) fullReset()
            staleRoomRecoveryInProgress = false
        }
    }

    private fun startJoinFor(config: CallConfig) {
        if (resetInProgress) {
            pendingConnectConfig = config
            appendLog("Queued connect after previous session stops")
            mainFragment()?.onStatusTextChanged("Stopping previous session...")
            return
        }
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.socksPort)) {
            pendingConnectConfig = config
            appendLog("Waiting for previous local tunnel to stop")
            fullReset()
            return
        }
        val url = config.url.trim()
        if (url.isEmpty()) return
        if (config.autoDiscovered) {
            Prefs.autoDestination = config
            appendLog("Auto room grace refreshed for 60s")
        }

        val platform = config.platform
        if (Prefs.activeTunnelMode == TunnelMode.DC &&
            (platform == CallPlatform.TELEMOST || platform == CallPlatform.DION)
        ) {
            Toast.makeText(this, R.string.dc_mode_not_supported, Toast.LENGTH_SHORT).show()
        }

        if (connected) {
            fullReset()
        }

        activeJoinUrl = url
        if (config.autoDiscovered) currentDiscoveryRoom = url
        logWriter.reset()
        runOnUiThread { logsFragment()?.refresh() }
        appendLog("Loading: ${maskUrl(url)}")
        lastStatus = VpnStatus.CONNECTING
        mainFragment()?.onStatusChanged(VpnStatus.CONNECTING)
        mainFragment()?.onConnectedChanged(false)

        val headlessMode =
            Prefs.headless || platform == CallPlatform.WBSTREAM || platform == CallPlatform.DION

        if (headlessMode && platform != CallPlatform.VK) {
            setJoinOverlayVisible(false)
            activeHeadlessController = HeadlessJoinController(
                applicationInfo.nativeLibraryDir,
                this,
                platform,
                url,
            ).also { it.start() }
            return
        }

        val joinFragment = if (headlessMode) {
            HeadlessVkFragment.newInstance(url)
        } else {
            JsHookJoinFragment.newInstance(url)
        }

        setJoinOverlayVisible(!headlessMode)

        supportFragmentManager.beginTransaction()
            .replace(R.id.joinOverlayContainer, joinFragment)
            .commit()
    }

    private fun startVpnService() {
        startService(Intent(this, TunnelVpnService::class.java))
        appendLog("VPN start requested")
        onJoinStatus(VpnStatus.STARTING)
    }

    private fun onDisconnectFromService() {
        if (resetInProgress) {
            maybeFinishReset()
            return
        }
        connected = false
        lastStatus = null
        closeActiveHeadlessController()
        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.CALL_DISCONNECTED)
        Prefs.extendAutoDestinationGrace()
    }

    private fun fullReset() {
        if (resetInProgress) return
        resetInProgress = true
        val resetId = ++resetGeneration
        connected = false
        lastStatus = VpnStatus.STOPPING
        val controller = activeHeadlessController
        activeHeadlessController = null
        activeJoinUrl = ""

        removeJoinFragment()
        TunnelVpnService.requestStop(this)
        ProxyService.requestStop(this)
        HeadlessSessionService.requestStop(this)
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.STOPPING)
        mainFragment()?.onStatusTextChanged("Stopping previous session...")
        thread(name = "full-reset-shutdown") {
            controller?.close()
            var attempts = 0
            while (
                attempts < 40 &&
                (TunnelServiceState.isAnyTunnelComponentRunning(this@MainActivity) ||
                    !PortGuard.isPortAvailable(Prefs.socksPort))
            ) {
                if (!isResetCurrent(resetId)) return@thread
                Thread.sleep(100)
                attempts++
            }
            if (!isResetCurrent(resetId)) return@thread
            if (TunnelServiceState.isAnyTunnelComponentRunning(this@MainActivity) || !PortGuard.isPortAvailable(Prefs.socksPort)) {
                TunnelVpnService.requestStop(this@MainActivity)
                ProxyService.requestStop(this@MainActivity)
                HeadlessSessionService.requestStop(this@MainActivity)
                PortGuard.ensurePortFree(Prefs.socksPort)
                Thread.sleep(150)
            }
            if (!isResetCurrent(resetId)) return@thread
            if (TunnelServiceState.isAnyTunnelComponentRunning(this@MainActivity) || !PortGuard.isPortAvailable(Prefs.socksPort)) {
                runOnUiThread {
                    if (isResetCurrent(resetId)) {
                        forceUnlockReset("Previous session is still shutting down. Try connect again.")
                    }
                }
                return@thread
            }
            Thread.sleep(400)
            runOnUiThread {
                if (isResetCurrent(resetId)) {
                    maybeFinishReset(resetId)
                }
            }
        }
    }

    private fun maybeFinishReset(expectedResetId: Long? = null) {
        if (!resetInProgress) return
        if (expectedResetId != null && expectedResetId != resetGeneration) return
        if (TunnelServiceState.isAnyTunnelComponentRunning(this) || !PortGuard.isPortAvailable(Prefs.socksPort)) return
        resetInProgress = false
        connected = false
        lastStatus = null
        activeJoinUrl = ""
        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.CALL_DISCONNECTED)
        Prefs.extendAutoDestinationGrace()
        val pendingConfig = pendingConnectConfig
        val shouldRescan = pendingDiscoveryRescan
        pendingConnectConfig = null
        pendingDiscoveryRescan = false
        if (pendingConfig != null) {
            appendLog("Previous session stopped, starting new connection")
            startJoinFor(pendingConfig)
        } else if (shouldRescan) {
            appendLog("Previous session stopped, rescanning discovery")
            onDiscoveryConnectPressed()
        }
    }

    private fun forceUnlockReset(message: String) {
        resetInProgress = false
        pendingConnectConfig = null
        connected = false
        activeJoinUrl = ""
        lastStatus = if (PortGuard.isPortAvailable(Prefs.socksPort)) VpnStatus.CALL_DISCONNECTED else VpnStatus.PORT_BUSY
        closeActiveHeadlessController()
        removeJoinFragment()
        setJoinOverlayVisible(false)
        TunnelVpnService.requestStop(this)
        ProxyService.requestStop(this)
        HeadlessSessionService.requestStop(this)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(lastStatus ?: VpnStatus.CALL_DISCONNECTED)
        mainFragment()?.onStatusTextChanged(message)
        appendLog(message)
    }

    private fun closeActiveHeadlessController() {
        val controller = activeHeadlessController
        activeHeadlessController = null
        if (controller != null) {
            thread(name = "headless-shutdown") { controller.close() }
        }
    }

    private fun isResetCurrent(resetId: Long): Boolean =
        resetInProgress && resetGeneration == resetId

    private fun shutdownJoinFragment() {
        val fragment = supportFragmentManager.findFragmentById(R.id.joinOverlayContainer)
        (fragment as? JoinSessionShutdown)?.shutdownSession()
    }

    private fun removeJoinFragment() {
        shutdownJoinFragment()
        if (isDestroyed || supportFragmentManager.isStateSaved) return
        val fragment = supportFragmentManager.findFragmentById(R.id.joinOverlayContainer)
        if (fragment != null) {
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commitAllowingStateLoss()
        }
    }

    companion object {
        const val ACTION_AUTO_START = "bypass.whitelist.AUTO_START"
        private const val SUB_PAGE_TAG = "sub_page"
        private const val STATE_CURRENT_TAB_ID = "current_tab_id"
        private const val CALL_LINK = ""
        private const val TAB_MAIN = 0
        private const val TAB_SETTINGS = 1
        private const val TAB_LOGS = 2
    }
}
