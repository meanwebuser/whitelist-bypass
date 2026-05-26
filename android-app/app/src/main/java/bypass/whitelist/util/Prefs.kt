package bypass.whitelist.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.SplitTunnelingMode
import bypass.whitelist.tunnel.TunnelMode

object Prefs {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

        var bindSettingsToProfiles: Boolean
        get() = prefs.getBoolean(PrefsKeys.BIND_SETTINGS_TO_PROFILES, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.BIND_SETTINGS_TO_PROFILES, value) }

    var connectOnStart: Boolean
        get() = prefs.getBoolean(PrefsKeys.CONNECT_ON_START, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.CONNECT_ON_START, value) }

    var tunnelMode: TunnelMode
        get() {
            val name = prefs.getString(PrefsKeys.TUNNEL_MODE, TunnelMode.VIDEO.name)!!
            return try {
                TunnelMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                TunnelMode.VIDEO
            }
        }
        set(value) {
            prefs.edit { putString(PrefsKeys.TUNNEL_MODE, value.name) }
            val active = activeDestination
            if (active != null && bindSettingsToProfiles) updateDestination(active.copy(tunnelMode = value))
        }

    var showLogs: Boolean
        get() = prefs.getBoolean(PrefsKeys.SHOW_LOGS, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.SHOW_LOGS, value) }

    var splitTunnelingMode: SplitTunnelingMode
        get() {
            val title = prefs.getString(PrefsKeys.SPLIT_TUNNELING_MODE, SplitTunnelingMode.NONE.name)!!
            return try {
                SplitTunnelingMode.valueOf(title)
            } catch (_: IllegalArgumentException) {
                SplitTunnelingMode.NONE
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.SPLIT_TUNNELING_MODE, value.name) }

    var splitTunnelingPackages: Set<String>
        get() = prefs.getStringSet(PrefsKeys.SPLIT_TUNNELING_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(PrefsKeys.SPLIT_TUNNELING_PACKAGES, value) }

    var autofillEnabled: Boolean
        get() = prefs.getBoolean(PrefsKeys.AUTOFILL_ENABLED, true)
        set(value) = prefs.edit { putBoolean(PrefsKeys.AUTOFILL_ENABLED, value) }

    var autofillName: String
        get() = prefs.getString(PrefsKeys.AUTOFILL_NAME, "Hello")!!
        set(value) = prefs.edit { putString(PrefsKeys.AUTOFILL_NAME, value) }

    var headless: Boolean
        get() = prefs.getBoolean(PrefsKeys.HEADLESS, true)
        set(value) = prefs.edit { putBoolean(PrefsKeys.HEADLESS, value) }

    var socksHost: String
        get() = prefs.getString(PrefsKeys.SOCKS_HOST, Net.LOCALHOST) ?: Net.LOCALHOST
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_HOST, value) }

    var socksPort: Long
        get() = prefs.getLong(PrefsKeys.SOCKS_PORT, Ports.DEFAULT_SOCKS)
        set(value) = prefs.edit { putLong(PrefsKeys.SOCKS_PORT, value) }

    var socksAuthMode: SocksAuthMode
        get() {
            val name = prefs.getString(PrefsKeys.SOCKS_AUTH_MODE, SocksAuthMode.AUTO.name)!!
            return try {
                SocksAuthMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                SocksAuthMode.AUTO
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_AUTH_MODE, value.name) }

    var socksUser: String
        get() = prefs.getString(PrefsKeys.SOCKS_USER, "")!!
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_USER, value) }

    var socksPass: String
        get() = prefs.getString(PrefsKeys.SOCKS_PASS, "")!!
        set(value) = prefs.edit { putString(PrefsKeys.SOCKS_PASS, value) }

    var proxyOnly: Boolean
        get() = prefs.getBoolean(PrefsKeys.PROXY_ONLY, false)
        set(value) = prefs.edit { putBoolean(PrefsKeys.PROXY_ONLY, value) }

    var dnsMode: DnsMode
        get() {
            val name = prefs.getString(PrefsKeys.DNS_MODE, DnsMode.SYSTEM.name)!!
            return try {
                DnsMode.valueOf(name)
            } catch (_: IllegalArgumentException) {
                DnsMode.SYSTEM
            }
        }
        set(value) = prefs.edit { putString(PrefsKeys.DNS_MODE, value.name) }

    var dnsPrimary: String
        get() = prefs.getString(PrefsKeys.DNS_PRIMARY, Vpn.DNS_PRIMARY)!!
        set(value) = prefs.edit { putString(PrefsKeys.DNS_PRIMARY, value) }

    var dnsSecondary: String
        get() = prefs.getString(PrefsKeys.DNS_SECONDARY, Vpn.DNS_SECONDARY)!!
        set(value) = prefs.edit { putString(PrefsKeys.DNS_SECONDARY, value) }

    var vp8Fps: Int
        get() = prefs.getInt(PrefsKeys.VP8_FPS, VP8Defaults.FPS)
        set(value) {
            prefs.edit { putInt(PrefsKeys.VP8_FPS, value) }
            val active = activeDestination
            if (active != null && bindSettingsToProfiles) updateDestination(active.copy(vp8Fps = value))
        }

    var vp8Batch: Int
        get() = prefs.getInt(PrefsKeys.VP8_BATCH, VP8Defaults.BATCH)
        set(value) {
            prefs.edit { putInt(PrefsKeys.VP8_BATCH, value) }
            val active = activeDestination
            if (active != null && bindSettingsToProfiles) updateDestination(active.copy(vp8Batch = value))
        }

    var dualTrack: Boolean
        get() = prefs.getBoolean(PrefsKeys.DUAL_TRACK, false)
        set(value) {
            prefs.edit { putBoolean(PrefsKeys.DUAL_TRACK, value) }
            val active = activeDestination
            if (active != null && bindSettingsToProfiles) updateDestination(active.copy(dualTrack = value))
        }

    var savedDestinations: List<CallConfig>
        get() = CallConfig.listFromJson(prefs.getString(PrefsKeys.SAVED_DESTINATIONS, "") ?: "")
        set(value) = prefs.edit { putString(PrefsKeys.SAVED_DESTINATIONS, CallConfig.listToJson(value)) }

    var activeDestinationId: String
        get() = prefs.getString(PrefsKeys.ACTIVE_DESTINATION_ID, "") ?: ""
        set(value) {
            prefs.edit { putString(PrefsKeys.ACTIVE_DESTINATION_ID, value) }
            if (bindSettingsToProfiles) {
                val active = savedDestinations.firstOrNull { it.id == value }
                if (active != null) {
                    if (active.tunnelMode != null) tunnelMode = active.tunnelMode
                    if (active.vp8Fps != null) prefs.edit { putInt(PrefsKeys.VP8_FPS, active.vp8Fps) }
                    if (active.vp8Batch != null) prefs.edit { putInt(PrefsKeys.VP8_BATCH, active.vp8Batch) }
                    if (active.dualTrack != null) prefs.edit { putBoolean(PrefsKeys.DUAL_TRACK, active.dualTrack) }
                }
            }
        }

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
            return try { ThemeMode.valueOf(name) } catch (_: IllegalArgumentException) { ThemeMode.SYSTEM }
        }
        set(value) = prefs.edit { putString(PrefsKeys.THEME_MODE, value.name) }

    val activeDestination: CallConfig?
        get() {
            val id = activeDestinationId
            if (id.isEmpty()) return null
            return savedDestinations.firstOrNull { it.id == id }
        }

    fun updateDestination(config: CallConfig) {
        val list = savedDestinations.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index != -1) {
            list[index] = config
            savedDestinations = list
        }
    }

    fun addDestination(config: CallConfig) {
        val list = savedDestinations.toMutableList()
        list.removeAll { it.id == config.id }
        list.add(0, config)
        savedDestinations = list
        activeDestinationId = config.id
    }

    fun removeDestination(id: String) {
        val list = savedDestinations.filter { it.id != id }
        savedDestinations = list
        if (activeDestinationId == id) {
            activeDestinationId = list.firstOrNull()?.id ?: ""
        }
    }

    fun renameDestination(id: String, newName: String) {
        val list = savedDestinations.map { if (it.id == id) it.copy(name = newName) else it }
        savedDestinations = list
    }

    fun resetAllSettings() {
        val keepDestinations = prefs.getString(PrefsKeys.SAVED_DESTINATIONS, null)
        val keepActiveId = prefs.getString(PrefsKeys.ACTIVE_DESTINATION_ID, null)
        prefs.edit {
            clear()
            if (keepDestinations != null) putString(PrefsKeys.SAVED_DESTINATIONS, keepDestinations)
            if (keepActiveId != null) putString(PrefsKeys.ACTIVE_DESTINATION_ID, keepActiveId)
        }
    }
}
