package bypass.whitelist.discovery

import android.util.Base64
import bypass.whitelist.tunnel.CallConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object VkDiscoveryScanner {
    private const val GROUP_ID = "237416141"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private val urls = listOf(
        "https://m.vk.ru/club$GROUP_ID",
        "https://m.vk.com/club$GROUP_ID",
        "https://vk.ru/club$GROUP_ID",
    )
    private val payloadRegex = Regex("wt1\\.([A-Za-z0-9_-]{24,})")
    private val roomRegex = Regex("wbstream://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")

    data class Result(val configs: List<CallConfig>, val source: String?)

    fun scan(): Result {
        val byUrl = linkedMapOf<String, CallConfig>()
        var source: String? = null
        for (url in urls) {
            val html = fetch(url) ?: continue
            source = url
            parsePayloads(html).forEach { cfg -> byUrl[cfg.url] = cfg }
            roomRegex.findAll(html).forEach { match ->
                val room = match.value
                byUrl[room] = CallConfig(
                    id = UUID.nameUUIDFromBytes(room.toByteArray()).toString(),
                    name = "VK discovery",
                    url = room,
                )
            }
            if (byUrl.isNotEmpty()) break
        }
        return Result(byUrl.values.toList(), source)
    }

    private fun parsePayloads(raw: String): List<CallConfig> {
        val html = raw
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
        return payloadRegex.findAll(html).mapNotNull { match ->
            val payload = decodeJson(match.groupValues[1]) ?: return@mapNotNull null
            val status = payload.optString("status")
            val room = payload.optString("room").takeIf { it.startsWith("wbstream://") } ?: return@mapNotNull null
            if (status.isNotBlank() && status != "free") return@mapNotNull null
            val streamId = payload.optString("stream_id").ifBlank { room }
            CallConfig(
                id = UUID.nameUUIDFromBytes(streamId.toByteArray()).toString(),
                name = payload.optString("creator").ifBlank { "VK discovery" },
                url = room,
            )
        }.toList()
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
