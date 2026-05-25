package bypass.whitelist

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.HeadlessJoinController
import bypass.whitelist.tunnel.ProxyService
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.ui.LogsFragment
import bypass.whitelist.ui.HeadlessVkFragment
import bypass.whitelist.ui.JoinFragmentHost
import bypass.whitelist.ui.JsHookJoinFragment
import bypass.whitelist.ui.MainActivityHost
import bypass.whitelist.ui.MainFragment
import bypass.whitelist.ui.SettingsScreenFragment
import bypass.whitelist.util.LogWriter
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.SocksAuth
import bypass.whitelist.util.maskUrl
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import bypass.whitelist.ui.CallsListener
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
    private lateinit var tabContainer: View
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

    private val vpnPrepLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

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
        subPageContainer = findViewById(R.id.subPageContainer)
        joinOverlayContainer = findViewById(R.id.joinOverlayContainer)
        overlayLogs = findViewById(R.id.overlayLogs)
        overlayLogsText = findViewById(R.id.overlayLogsText)
        overlayLogsScroll = findViewById(R.id.overlayLogsScroll)
        findViewById<View>(R.id.overlayCopyButton).setOnClickListener { copyLogs() }
        findViewById<View>(R.id.overlayShareButton).setOnClickListener { shareLogs() }

        val baseTabPaddingTop = tabContainer.paddingTop
        val bottomWrap = findViewById<View>(R.id.bottomWrap)
        val baseBottomWrapPaddingBottom = bottomWrap.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            tabContainer.setPadding(bars.left, baseTabPaddingTop + bars.top, bars.right, 0)
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
        selectNavTab(restoredTabId)

        TunnelVpnService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }
        ProxyService.onDisconnect = { runOnUiThread { onDisconnectFromService() } }

        VpnService.prepare(this)?.let { vpnPrepLauncher.launch(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        if (CALL_LINK.isNotEmpty()) {
            startJoinFor(CallConfig.newWith(name = CallConfig.suggestNameFor(CALL_LINK), url = CALL_LINK))
        } else if (Prefs.connectOnStart) {
            val active = Prefs.activeDestination
            if (active != null) startJoinFor(active)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_TAB_ID, currentTabId)
    }

    override fun onDestroy() {
        TunnelVpnService.onDisconnect = null
        TunnelVpnService.instance?.stop()
        ProxyService.onDisconnect = null
        ProxyService.instance?.stop()
        logWriter.close()
        super.onDestroy()
    }

    private fun selectNavTab(itemId: Int) {
        if (currentTabId == itemId) return
        currentTabId = itemId
        dismissSubPage()
        when (itemId) {
            R.id.navMain -> showTab(MainFragment::class.java)
            R.id.navSettings -> showTab(SettingsScreenFragment::class.java)
            R.id.navLogs -> showTab(LogsFragment::class.java)
        }
        updateNavSelection(itemId)
    }

    private fun updateNavSelection(itemId: Int) {
        val transparent = android.graphics.Color.TRANSPARENT
        val activeBg = R.drawable.bg_nav_item_active
        val accent = getColor(R.color.accent_emerald)
        val ink3 = getColor(R.color.ink_3)

        listOf(navMain, navSettings, navLogs).forEach { it.setBackgroundColor(transparent) }
        findViewById<ImageView>(R.id.navMainIcon).setColorFilter(ink3)
        findViewById<ImageView>(R.id.navSettingsIcon).setColorFilter(ink3)
        findViewById<ImageView>(R.id.navLogsIcon).setColorFilter(ink3)
        findViewById<TextView>(R.id.navMainLabel).apply {
            setTextColor(ink3); setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }
        findViewById<TextView>(R.id.navSettingsLabel).apply {
            setTextColor(ink3); setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }
        findViewById<TextView>(R.id.navLogsLabel).apply {
            setTextColor(ink3); setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }
        val activeContainer = when (itemId) {
            R.id.navMain -> navMain
            R.id.navSettings -> navSettings
            R.id.navLogs -> navLogs
            else -> null
        } ?: return
        activeContainer.setBackgroundResource(activeBg)
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
        findViewById<ImageView>(activeIcon).setColorFilter(accent)
        findViewById<TextView>(activeLabel).apply {
            setTextColor(accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun showTab(cls: Class<out Fragment>) {
        val tag = cls.name
        val existing = supportFragmentManager.findFragmentByTag(tag)
        val tx = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments
            .filter { it.id == R.id.tabContainer && it.tag != tag }
            .forEach { tx.hide(it) }
        if (existing == null) {
            tx.add(R.id.tabContainer, cls.getDeclaredConstructor().newInstance(), tag)
        } else {
            tx.show(existing)
            if (existing is LogsFragment) existing.refresh()
        }
        tx.commit()
    }

    private fun mainFragment(): MainFragment? =
        supportFragmentManager.findFragmentByTag(MainFragment::class.java.name) as? MainFragment

    private fun logsFragment(): LogsFragment? =
        supportFragmentManager.findFragmentByTag(LogsFragment::class.java.name) as? LogsFragment

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
            socket.connect(InetSocketAddress("127.0.0.1", Prefs.socksPort.toInt()), 5000)
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
            request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
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
        (supportFragmentManager.findFragmentByTag(SettingsScreenFragment::class.java.name) as? SettingsScreenFragment)?.refresh()
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
            this, "${packageName}.fileprovider", logWriter.file
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
        if (Prefs.tunnelMode == TunnelMode.DC && (platform == CallPlatform.TELEMOST || platform == CallPlatform.DION)) {
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
                applicationInfo.nativeLibraryDir, this, platform, url,
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
        val vpn = TunnelVpnService.instance
        val proxy = ProxyService.instance
        removeJoinFragment()
        setJoinOverlayVisible(false)
        mainFragment()?.onConnectedChanged(false)
        thread(name = "full-reset-shutdown") {
            controller?.close()
            vpn?.stop()
            proxy?.stop()
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
        private const val SUB_PAGE_TAG = "sub_page"
        private const val STATE_CURRENT_TAB_ID = "current_tab_id"
        private const val CALL_LINK = "" // Open call page on app start (do not delete - I need it for debug)
    }
}
