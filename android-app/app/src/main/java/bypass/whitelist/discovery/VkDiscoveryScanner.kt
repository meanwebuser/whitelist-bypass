package bypass.whitelist.discovery

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import bypass.whitelist.BuildConfig
import bypass.whitelist.tunnel.CallConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

object VkDiscoveryScanner {
    private const val GROUP_ID = "237416141"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private val urls = listOf(
        "https://m.vk.ru/club$GROUP_ID",
        "https://m.vk.com/club$GROUP_ID",
        "https://vk.ru/club$GROUP_ID",
        "https://vk.com/club$GROUP_ID",
    )
    private val encryptedPayloadRegex = Regex("(wt(?:room|bus)2)\\.([A-Za-z0-9_-]{1,16})\\.([А-Яа-я]{24,})")
    private val payloadRegex = Regex("wt1\\.([A-Za-z0-9_-]{24,})")
    private val roomRegex = Regex("wbstream://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")

    data class Stats(
        val encryptedEvents: Int = 0,
        val legacyEvents: Int = 0,
        val slots: Int = 0,
        val free: Int = 0,
        val busy: Int = 0,
        val closed: Int = 0,
        val expired: Int = 0,
        val roomLinks: Int = 0,
    ) {
        fun summary(): String = "events=${encryptedEvents + legacyEvents} encrypted=$encryptedEvents legacy=$legacyEvents slots=$slots free=$free busy=$busy closed=$closed expired=$expired raw_rooms=$roomLinks"
    }

    data class Result(
        val configs: List<CallConfig>,
        val source: String?,
        val method: String = "http",
        val stats: Stats = Stats(),
    )

    private data class ParsedConfigs(val configs: List<CallConfig>, val stats: Stats)

    private data class Event(
        val slotId: String,
        val leaseId: String?,
        val status: String,
        val room: String?,
        val creator: String?,
        val node: String?,
        val location: String?,
        val createdAt: Long,
        val expiresAt: Long,
        val seq: Long,
    ) {
        val order: Long get() = if (seq > 0) seq else createdAt
    }

    fun sendClientEvent(
        type: String,
        clientId: String,
        room: String?,
        reason: String,
        badRooms: Collection<String> = emptyList(),
    ): Boolean {
        val token = BuildConfig.VK_BOT_TOKEN.takeIf { it.isNotBlank() } ?: return false
        val peerId = BuildConfig.VK_BOT_PEER_ID.takeIf { it.isNotBlank() } ?: return false
        return try {
            val now = System.currentTimeMillis() / 1000L
            val payload = JSONObject().apply {
                put("v", 2)
                put("type", type)
                put("client_id", clientId)
                put("platform", "android")
                put("app_version", BuildConfig.VERSION_NAME)
                put("app_build", BuildConfig.VERSION_CODE)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("system_version", Build.VERSION.RELEASE ?: "")
                put("room", room ?: "")
                put("bad_rooms", JSONArray().also { arr -> badRooms.forEach { arr.put(it) } })
                put("reason", reason)
                put("created_at", now)
                put("seq", now)
                put("nonce", java.util.UUID.randomUUID().toString())
            }
            val message = WtBusCrypto.encryptEnvelope("wtclient2", payload) ?: return false
            val body = listOf(
                "peer_id" to peerId,
                "random_id" to System.currentTimeMillis().toString(),
                "message" to message,
                "access_token" to token,
                "v" to "5.199",
            ).joinToString("&") { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }
            val conn = URL("https://api.vk.com/method/messages.send").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "BEZabotny-NET Android private bus")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            !json.has("error") && json.has("response")
        } catch (_: Exception) {
            false
        }
    }

    fun scan(): Result {
        val privateHtml = fetchPrivateBus()
        if (privateHtml != null) {
            val configs = parseConfigs(privateHtml)
            if (configs.isNotEmpty()) return Result(configs, "vk-private-bus", "vk-private")
        }
        var source: String? = null
        for (url in urls) {
            val html = fetch(url) ?: continue
            source = url
            val configs = parseConfigs(html)
            if (configs.isNotEmpty()) return Result(configs, source, "http")
        }
        return Result(emptyList(), source, "http")
    }


    private fun fetchPrivateBus(): String? {
        val token = BuildConfig.VK_BOT_TOKEN.takeIf { it.isNotBlank() } ?: return null
        val peerId = BuildConfig.VK_BOT_PEER_ID.takeIf { it.isNotBlank() } ?: return null
        return try {
            val body = listOf(
                "peer_id" to peerId,
                "count" to "100",
                "access_token" to token,
                "v" to "5.199",
            ).joinToString("&") { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }
            val conn = URL("https://api.vk.com/method/messages.getHistory").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "BEZabotny-NET private bus")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val items = json.optJSONObject("response")?.optJSONArray("items") ?: return null
            buildString {
                for (i in 0 until items.length()) {
                    val text = items.optJSONObject(i)?.optString("text").orEmpty()
                    if (text.isNotBlank()) append(text).append("\n")
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun scanWithWebView(activity: Activity, onProgress: (String) -> Unit = {}, onDone: (Result) -> Unit) {
        val httpResult = scan()
        if (httpResult.configs.isNotEmpty()) {
            onDone(httpResult)
            return
        }
        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(activity)
        fun cleanup() {
            try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Exception) {}
            try { webView.stopLoading() } catch (_: Exception) {}
            try { webView.destroy() } catch (_: Exception) {}
        }
        fun finish(result: Result) {
            if (!done.compareAndSet(false, true)) return
            cleanup()
            onDone(result)
        }
        var index = 0
        fun loadNext() {
            if (done.get()) return
            if (index >= urls.size) {
                finish(Result(emptyList(), null, "webview"))
                return
            }
            val url = urls[index++]
            onProgress("WebView VK: ${url.substringAfter("://").substringBefore('/')}")
            webView.loadUrl(url)
        }
        fun inspect(url: String?) {
            if (done.get()) return
            val js = """
                (function(){
                  var h=document.documentElement?document.documentElement.outerHTML:'';
                  var t=document.body?document.body.innerText:'';
                  return h+'\n'+t;
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { encoded ->
                if (done.get()) return@evaluateJavascript
                val raw = decodeJsString(encoded)
                val parsed = parseConfigsDetailed(raw)
                if (parsed.configs.isNotEmpty()) {
                    onProgress("VK page parsed: ${parsed.configs.size} free room(s); ${parsed.stats.summary()}")
                    finish(Result(parsed.configs, url ?: webView.url, "webview", parsed.stats))
                } else {
                    handler.postDelayed({ loadNext() }, 500)
                }
            }
        }
        val timeout = Runnable { finish(Result(emptyList(), webView.url, "webview-timeout", Stats())) }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.userAgentString = UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { onProgress("VK page loading…") }
            override fun onPageFinished(view: WebView, url: String?) { handler.postDelayed({ inspect(url) }, 2500) }
        }
        handler.postDelayed(timeout, 30000)
        loadNext()
    }

    fun parseConfigs(raw: String): List<CallConfig> = parseConfigsDetailed(raw).configs

    private fun parseConfigsDetailed(raw: String): ParsedConfigs {
        val html = normalize(raw)
        val encryptedEvents = encryptedPayloadRegex.findAll(html).mapNotNull { match ->
            WtBusCrypto.decryptEnvelope(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.toEvent()
        }.toList()
        val legacyEvents = payloadRegex.findAll(html).mapNotNull { decodeJson(it.groupValues[1])?.toEvent() }.toList()
        val events = encryptedEvents + legacyEvents
        val roomLinks = roomRegex.findAll(html).map { it.value }.distinct().toList()
        if (events.isNotEmpty()) {
            val newestBySlot = events.groupBy { it.slotId }.mapValues { (_, list) -> list.maxBy { it.order } }
            val now = System.currentTimeMillis() / 1000L
            val newest = newestBySlot.values
            val stats = Stats(
                encryptedEvents = encryptedEvents.size,
                legacyEvents = legacyEvents.size,
                slots = newestBySlot.size,
                free = newest.count { it.status == "free" && it.room?.startsWith("wbstream://") == true && it.expiresAt > now },
                busy = newest.count { it.status == "busy" },
                closed = newest.count { it.status == "closed" },
                expired = newest.count { it.expiresAt <= now },
                roomLinks = roomLinks.size,
            )
            val configs = newest
                .filter { it.status == "free" && it.expiresAt > now && it.room?.startsWith("wbstream://") == true }
                .sortedByDescending { it.order }
                .map { event ->
                    val label = listOfNotNull(event.node?.ifBlank { null } ?: event.creator?.ifBlank { null }, event.location?.ifBlank { null }).joinToString(" · ").ifBlank { null }
                    CallConfig.autoWith(
                        name = label ?: event.creator?.ifBlank { null } ?: "VK discovery",
                        url = event.room!!,
                        slotId = event.slotId,
                        leaseId = event.leaseId,
                        expiresAt = event.expiresAt,
                        nodeLabel = label,
                    )
                }
            return ParsedConfigs(configs, stats)
        }
        val configs = roomLinks.map { room ->
            CallConfig.autoWith(name = "VK discovery", url = room, slotId = room, leaseId = null, expiresAt = null)
        }
        return ParsedConfigs(configs, Stats(roomLinks = roomLinks.size, free = configs.size))
    }

    private fun JSONObject.toEvent(): Event? {
        val status = optString("status").ifBlank { "free" }
        val room = optString("room").takeIf { it.isNotBlank() && it != "null" }
        val streamId = optString("stream_id").takeIf { it.isNotBlank() }
        val slotId = optString("slot_id").takeIf { it.isNotBlank() } ?: streamId ?: room ?: return null
        val leaseId = optString("lease_id").takeIf { it.isNotBlank() } ?: streamId
        val createdAt = optLong("created_at", 0L)
        val expiresAt = optLong("expires_at", Long.MAX_VALUE / 4)
        val seq = optLong("seq", 0L)
        return Event(
            slotId = slotId,
            leaseId = leaseId,
            status = status,
            room = room,
            creator = optString("creator").takeIf { it.isNotBlank() },
            node = optString("node").takeIf { it.isNotBlank() } ?: optString("node_name").takeIf { it.isNotBlank() },
            location = optString("location").takeIf { it.isNotBlank() }
                ?: listOfNotNull(optString("country").takeIf { it.isNotBlank() }, optString("city").takeIf { it.isNotBlank() }, optString("region").takeIf { it.isNotBlank() }).joinToString(" ").takeIf { it.isNotBlank() },
            createdAt = createdAt,
            expiresAt = expiresAt,
            seq = seq,
        )
    }

    private fun normalize(raw: String): String {
        return raw
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
    }

    private fun decodeJson(encoded: String): JSONObject? {
        return try {
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJsString(value: String?): String {
        if (value == null || value == "null") return ""
        return try {
            JSONObject("{\"v\":$value}").optString("v")
        } catch (_: Exception) {
            value.trim('"')
                .replace("\\n", "\n")
                .replace("\\u003C", "<")
                .replace("\\\"", "\"")
        }
    }

    private fun fetch(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
