package bypass.whitelist

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.HeadlessSessionService
import bypass.whitelist.tunnel.HeadlessJoinController
import bypass.whitelist.tunnel.ProxyService
import bypass.whitelist.tunnel.TunnelServiceState
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.ui.CallsListener
import bypass.whitelist.ui.HeadlessVkFragment
import bypass.whitelist.ui.JoinFragmentHost
import bypass.whitelist.ui.JsHookJoinFragment
import bypass.whitelist.ui.LogsFragment
import bypass.whitelist.ui.MainActivityHost
import bypass.whitelist.ui.MainFragment
import bypass.whitelist.ui.SettingsScreenFragment
import bypass.whitelist.util.LogWriter
import bypass.whitelist.util.Net
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.SocksAuth
import bypass.whitelist.util.maskUrl
import java.net.InetSocketAddress
import java.net.Socket
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
    private lateinit var tabContainer: ViewPager2
    private lateinit var navIndicator: View
    private lateinit var subPageContainer: View
    private lateinit var joinOverlayContainer: View
    private lateinit var overlayLogs: View
    private lateinit var overlayLogsText: TextView
    private lateinit var overlayLogsScroll: android.widget.ScrollView

    private var currentTabId: Int = 0
    private var lastStatus: VpnStatus? = null
    private var connected: Boolean = false
    private var activeJoinUrl: String = ""
    private var activeHeadlessController: HeadlessJoinController? = null
    private var navPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
        else appendLog("VPN permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNav)
        navMain = findViewById(R.id.navMain)
        navSettings = findViewById(R.id.navSettings)
        navLogs = findViewById(R.id.navLogs)
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
                    0 -> MainFragment()
                    1 -> SettingsScreenFragment()
                    else -> LogsFragment()
                }
            }
        }
        tabContainer.offscreenPageLimit = 3

        navPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                moveNavIndicatorForPager(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentTabId = when (position) {
                    0 -> R.id.navMain
                    1 -> R.id.navSettings
                    else -> R.id.navLogs
                }
                updateNavSelection(currentTabId)
                moveNavIndicatorTo(currentTabId, animate = false)
                (supportFragmentManager.findFragmentByTag("f1") as? SettingsScreenFragment)?.refresh()
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

        if (CALL_LINK.isNotEmpty()) {
            startJoinFor(CallConfig.newWith(name = CallConfig.suggestNameFor(CALL_LINK), url = CALL_LINK))
        } else if (Prefs.connectOnStart) {
            Prefs.activeDestination?.let(::startJoinFor)
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-attach disconnect callbacks 
        TunnelVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        ProxyService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        
        TunnelServiceState.vpnStatusCallback = { status ->
            runOnUiThread {
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
            TunnelServiceState.isTunnelActive(this) -> {
                // VPN service is running — sync UI without calling onJoinStatus
                // (onJoinStatus has side effects like updating notification unnecessarily)
                if (!connected || lastStatus != VpnStatus.TUNNEL_ACTIVE) {
                    connected = true
                    lastStatus = VpnStatus.TUNNEL_ACTIVE
                    mainFragment()?.onStatusChanged(VpnStatus.TUNNEL_ACTIVE)
                    mainFragment()?.onConnectedChanged(true)
                }
            }
            TunnelServiceState.isHeadlessSessionRunning(this) -> {
                // Relay is connecting but VPN not yet up — show connecting state
                connected = false
                lastStatus = VpnStatus.CONNECTING
                mainFragment()?.onConnectedChanged(false)
                mainFragment()?.onStatusChanged(VpnStatus.CONNECTING)
            }
            connected && lastStatus == VpnStatus.TUNNEL_ACTIVE -> {
                // UI thinks it's connected but no service is running — reset
                onDisconnectFromService()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_TAB_ID, currentTabId)
    }

    override fun onDestroy() {
        navPageChangeCallback?.let(tabContainer::unregisterOnPageChangeCallback)
        navPageChangeCallback = null
        TunnelVpnService.onDisconnect = null
        ProxyService.onDisconnect = null
        logWriter.close()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_AUTO_START) return
        intent.action = null
        val isConnecting = lastStatus == VpnStatus.CONNECTING
        if (!connected && !isConnecting) {
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
            R.id.navMain -> 0
            R.id.navSettings -> 1
            R.id.navLogs -> 2
            else -> 0
        }
        updateNavSelection(itemId)
        if (!animatePager || tabContainer.currentItem == index) {
            moveNavIndicatorTo(itemId, animate = false)
        }
        tabContainer.setCurrentItem(index, animatePager)
    }

    private fun updateNavSelection(itemId: Int) {
        val accent = getColor(R.color.accent_emerald)
        val ink3 = getColor(R.color.ink_3)

        findViewById<ImageView>(R.id.navMainIcon).setColorFilter(ink3)
        findViewById<ImageView>(R.id.navSettingsIcon).setColorFilter(ink3)
        findViewById<ImageView>(R.id.navLogsIcon).setColorFilter(ink3)
        findViewById<TextView>(R.id.navMainLabel).apply {
            setTextColor(ink3)
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }
        findViewById<TextView>(R.id.navSettingsLabel).apply {
            setTextColor(ink3)
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }
        findViewById<TextView>(R.id.navLogsLabel).apply {
            setTextColor(ink3)
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }

        val activeIcon = when (itemId) {
            R.id.navMain -> R.id.navMainIcon
            R.id.navSettings -> R.id.navSettingsIcon
            R.id.navLogs -> R.id.navLogsIcon
            else -> 0
        }
        val activeLabel = when (itemId) {
            R.id.navMain -> R.id.navMainLabel
            R.id.navSettings -> R.id.navSettingsLabel
            R.id.navLogs -> R.id.navLogsLabel
            else -> 0
        }
        if (activeIcon != 0) findViewById<ImageView>(activeIcon).setColorFilter(accent)
        if (activeLabel != 0) {
            findViewById<TextView>(activeLabel).apply {
                setTextColor(accent)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
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
        val targetWidth = if (next != null) {
            current.width + ((next.width - current.width) * positionOffset)
        } else {
            current.width.toFloat()
        }
        val targetLeft = if (next != null) {
            current.left + ((next.left - current.left) * positionOffset)
        } else {
            current.left.toFloat()
        }
        navIndicator.layoutParams = navIndicator.layoutParams.apply {
            width = targetWidth.toInt()
            height = current.height
        }
        navIndicator.requestLayout()
        navIndicator.animate().cancel()
        navIndicator.translationX = targetLeft
    }

    override fun onPause() {
        super.onPause()
        TunnelServiceState.vpnStatusCallback = null
        TunnelServiceState.logCallback = null
    }

    private fun mainFragment(): MainFragment? =
        supportFragmentManager.fragments.firstOrNull { it is MainFragment } as? MainFragment

    private fun logsFragment(): LogsFragment? =
        supportFragmentManager.fragments.firstOrNull { it is LogsFragment } as? LogsFragment

    override fun onConnectPressed(config: CallConfig) {
        startJoinFor(config)
    }

    override fun onDisconnectPressed() {
        fullReset()
    }

    override fun onPingPressed(callback: (Boolean, Int) -> Unit) {
        thread {
            val started = System.nanoTime()
            val ok = try {
                probeViaSocks5(host = "ya.ru", port = 443)
            } catch (_: Exception) {
                false
            }
            val rtt = ((System.nanoTime() - started) / 1_000_000).toInt()
            runOnUiThread { callback(ok, rtt) }
        }
    }

    private fun probeViaSocks5(host: String, port: Int): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(Net.LOCALHOST, Prefs.socksPort.toInt()), 5000)
            socket.soTimeout = 15000
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            if (input.read() != 0x05 || input.read() != 0x02) return false

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
            if (input.read() != 0x01 || input.read() != 0x00) return false

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(4 + 1 + hostBytes.size + 2)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x03
            request[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
            request[5 + hostBytes.size] = ((port shr 8) and 0xff).toByte()
            request[6 + hostBytes.size] = (port and 0xff).toByte()
            output.write(request)
            output.flush()

            return input.read() == 0x05 && input.read() == 0x00
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

    override fun onResetAllSettings() {
        Prefs.resetAllSettings()
        App.applyTheme(Prefs.themeMode)
        (supportFragmentManager.fragments.firstOrNull { it is SettingsScreenFragment } as? SettingsScreenFragment)?.refresh()
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
            getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("relay.log", contents))
        Toast.makeText(this, R.string.copy_logs_toast, Toast.LENGTH_SHORT).show()
    }

    override fun shareLogs() {
        val uri = androidx.core.content.FileProvider.getUriForFile(
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
    }

    override fun onJoinStatusText(text: String) {
        runOnUiThread { mainFragment()?.onStatusTextChanged(text) }
    }

    override fun onJoinStatus(status: VpnStatus) {
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
        if (supportFragmentManager.popBackStackImmediate(
                SUB_PAGE_TAG,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        ) {
            // popped
        }
        subPageContainer.visibility = View.GONE
    }

    private fun dismissSubPage() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(
                SUB_PAGE_TAG,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }
        subPageContainer.visibility = View.GONE
    }

    override fun onJoinCancel() {
        runOnUiThread { fullReset() }
    }

    override fun setJoinUiVisible(visible: Boolean) {
        runOnUiThread { setJoinOverlayVisible(visible) }
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

    override fun requestVpn() {
        if (Prefs.proxyOnly) {
            appendLog("Proxy only mode, skipping VPN")
            startService(Intent(this, ProxyService::class.java))
            onJoinStatus(VpnStatus.TUNNEL_ACTIVE)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startVpnService()
    }

    private fun startJoinFor(config: CallConfig) {
        val url = config.url.trim()
        if (url.isEmpty()) return

        val platform = config.platform
        if (Prefs.tunnelMode == TunnelMode.DC &&
            (platform == CallPlatform.TELEMOST || platform == CallPlatform.DION)
        ) {
            Toast.makeText(this, R.string.dc_mode_not_supported, Toast.LENGTH_SHORT).show()
        }

        if (connected) {
            fullReset()
        }

        activeJoinUrl = url
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
        appendLog("VPN started")
        onJoinStatus(VpnStatus.TUNNEL_ACTIVE)
    }

    private fun onDisconnectFromService() {
        connected = false
        lastStatus = null
        closeActiveHeadlessController()
        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        mainFragment()?.onStatusChanged(VpnStatus.CALL_DISCONNECTED)
    }

    private fun fullReset() {
        connected = false
        lastStatus = null
        val controller = activeHeadlessController
        activeHeadlessController = null

        startService(Intent(this, TunnelVpnService::class.java).apply { action = TunnelVpnService.ACTION_STOP })
        startService(Intent(this, ProxyService::class.java).apply { action = ProxyService.ACTION_STOP })
        startService(Intent(this, HeadlessSessionService::class.java).apply { action = HeadlessSessionService.ACTION_STOP })

        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        thread(name = "full-reset-shutdown") {
            controller?.close()
        }
    }

    private fun closeActiveHeadlessController() {
        val controller = activeHeadlessController
        activeHeadlessController = null
        if (controller != null) {
            thread(name = "headless-shutdown") { controller.close() }
        }
    }

    private fun removeJoinFragment() {
        val fragment = supportFragmentManager.findFragmentById(R.id.joinOverlayContainer)
        if (fragment != null) {
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commitNowAllowingStateLoss()
        }
    }

    companion object {
        const val ACTION_AUTO_START = "bypass.whitelist.AUTO_START"
        private const val SUB_PAGE_TAG = "sub_page"
        private const val STATE_CURRENT_TAB_ID = "current_tab_id"
        private const val CALL_LINK = "" // Open call page on app start (do not delete - I need it for debug)
    }
}
