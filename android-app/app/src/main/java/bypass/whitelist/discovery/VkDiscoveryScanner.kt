package bypass.whitelist.discovery

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
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
import bypass.whitelist.tunnel.CallConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
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
    private val payloadRegex = Regex("wt1\\.([A-Za-z0-9_-]{24,})")
    private val roomRegex = Regex("wbstream://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")

    data class Result(val configs: List<CallConfig>, val source: String?, val method: String = "http")

    fun scan(): Result {
        val merged = linkedMapOf<String, CallConfig>()
        var source: String? = null
        for (url in urls) {
            val html = fetch(url) ?: continue
            source = url
            parseConfigs(html).forEach { cfg -> merged[cfg.url] = cfg }
            if (merged.isNotEmpty()) break
        }
        return Result(merged.values.toList(), source, "http")
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
                val configs = parseConfigs(raw)
                if (configs.isNotEmpty()) {
                    finish(Result(configs, url ?: webView.url, "webview"))
                } else {
                    handler.postDelayed({ loadNext() }, 500)
                }
            }
        }
        val timeout = Runnable { finish(Result(emptyList(), webView.url, "webview-timeout")) }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.userAgentString = UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onProgress("VK page loading…")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                handler.postDelayed({ inspect(url) }, 2500)
            }
        }
        handler.postDelayed(timeout, 30000)
        loadNext()
    }

    fun parseConfigs(raw: String): List<CallConfig> {
        val html = normalize(raw)
        val byUrl = linkedMapOf<String, CallConfig>()
        payloadRegex.findAll(html).forEach { match ->
            val payload = decodeJson(match.groupValues[1]) ?: return@forEach
            val status = payload.optString("status")
            val room = payload.optString("room").takeIf { it.startsWith("wbstream://") } ?: return@forEach
            if (status.isNotBlank() && status != "free") return@forEach
            val streamId = payload.optString("stream_id").ifBlank { room }
            byUrl[room] = CallConfig(
                id = UUID.nameUUIDFromBytes(streamId.toByteArray()).toString(),
                name = payload.optString("creator").ifBlank { "VK discovery" },
                url = room,
            )
        }
        roomRegex.findAll(html).forEach { match ->
            val room = match.value
            byUrl[room] = CallConfig(
                id = UUID.nameUUIDFromBytes(room.toByteArray()).toString(),
                name = "VK discovery",
                url = room,
            )
        }
        return byUrl.values.toList()
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
