package bypass.whitelist.tunnel

enum class CallPlatform(val id: String, val urlMarker: String) {
    VK("vk", ""),
    TELEMOST("telemost", "telemost.yandex"),
    WBSTREAM("wbstream", "wbstream://");

    companion object {
        fun fromUrl(url: String): CallPlatform = when {
            url.contains(WBSTREAM.urlMarker) -> WBSTREAM
            url.contains(TELEMOST.urlMarker) -> TELEMOST
            else -> VK
        }

        fun extractRoomId(url: String): String =
            url.removePrefix(WBSTREAM.urlMarker).trim()
    }
}
