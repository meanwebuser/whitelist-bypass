package bypass.whitelist.tunnel

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CallConfig(
    val id: String,
    val name: String,
    val url: String,
) {
    val platform: CallPlatform get() = CallPlatform.fromUrl(url)

    val platformGlyph: String get() = when (platform) {
        CallPlatform.VK -> "VK"
        CallPlatform.TELEMOST -> "TM"
        CallPlatform.WBSTREAM -> "WB"
        CallPlatform.DION -> "DN"
    }

    val platformLabel: String get() = when (platform) {
        CallPlatform.VK -> "VK"
        CallPlatform.TELEMOST -> "Telemost"
        CallPlatform.WBSTREAM -> "WB Stream"
        CallPlatform.DION -> "DION"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
    }

    companion object {
        fun newWith(name: String, url: String): CallConfig =
            CallConfig(id = UUID.randomUUID().toString(), name = name, url = url)

        fun fromJson(obj: JSONObject): CallConfig = CallConfig(
            id = obj.getString("id"),
            name = obj.getString("name"),
            url = obj.getString("url"),
        )

        fun listToJson(items: List<CallConfig>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String): List<CallConfig> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun suggestNameFor(url: String): String {
            val platform = CallPlatform.fromUrl(url)
            val label = when (platform) {
                CallPlatform.VK -> "VK call"
                CallPlatform.TELEMOST -> "Telemost"
                CallPlatform.WBSTREAM -> "WB Stream"
                CallPlatform.DION -> "DION"
            }
            return label
        }
    }
}
