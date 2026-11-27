package me.enivzoy.anilife.app.data

// components built before the language fields simply omit them
internal object ProviderServerParser {
    fun parse(value: Any?, providerName: String, requestedDub: Boolean): List<AnimeVideoSource> {
        val server = value as? Map<*, *> ?: return emptyList()
        val serverName = server["name"] as? String ?: providerName
        val dub = server["dub"] as? Boolean ?: requestedDub
        val serverLanguage = languageField(server["language"]) ?: languageField(server["languages"])
        val headers = (server["headers"] as? List<*>)?.mapNotNull { pair ->
            val parts = pair as? List<*> ?: return@mapNotNull null
            val key = parts.getOrNull(0) as? String ?: return@mapNotNull null
            val header = parts.getOrNull(1) as? String ?: return@mapNotNull null
            key to header
        }?.toMap().orEmpty()
        val streams = server["streams"] as? List<*> ?: return emptyList()
        return streams.mapNotNull { item ->
            val stream = item as? Map<*, *> ?: return@mapNotNull null
            val url = stream["url"] as? String ?: return@mapNotNull null
            val format = stream["format"] as? String ?: inferFormat(url)
            val language = languageField(stream["language"]) ?: serverLanguage.orEmpty()
            AnimeVideoSource(
                provider = providerName,
                server = serverName,
                audio = if (dub) "Dub" else "Sub",
                format = format,
                quality = stream["quality"] as? String,
                url = url,
                headers = headers,
                language = language,
            )
        }
    }

    private fun languageField(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is List<*> -> value.firstOrNull { it is String && it.isNotBlank() } as? String
        else -> null
    }

    private fun inferFormat(url: String) = when {
        ".m3u8" in url.lowercase() -> "hls"
        ".mpd" in url.lowercase() -> "dash"
        else -> "mp4"
    }
}