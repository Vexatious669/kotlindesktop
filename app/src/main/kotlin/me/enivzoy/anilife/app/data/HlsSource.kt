package me.enivzoy.anilife.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger("AniLife.downloads")

internal data class HlsVariant(val url: URI, val height: Int?, val bandwidth: Long?)

internal data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val initSegment: URI?,
) {
    val isFragmented: Boolean get() = initSegment != null
}

internal data class HlsSegment(val url: URI, val sequence: Int, val key: HlsKey?, val explicitIv: ByteArray?)

internal data class HlsKey(val url: URI)

internal data class ValidatedSource(
    val url: String,
    val headers: Map<String, String>,
    val kind: SourceKind,
    val detail: String,
    val playlist: HlsMediaPlaylist? = null,
)

internal enum class SourceKind { DirectMedia, HlsMedia, Dash }

internal enum class SourceFailure {
    Expired,
    Forbidden,
    NotFound,
    RateLimited,
    ServerError,
    NotPlaylist,
    EmptyPlaylist,
    UnsupportedEncryption,
    Unreachable,
    ;

    fun describe(status: Int?, contentType: String?): String = when (this) {
        Expired -> "The episode link expired before the download could start."
        Forbidden -> "The provider refused the download (HTTP ${status ?: 403}); the link needs a fresh session."
        NotFound -> "The provider no longer has this episode (HTTP ${status ?: 404})."
        RateLimited -> "The provider is rate limiting downloads (HTTP ${status ?: 429})."
        ServerError -> "The provider failed to serve the episode (HTTP ${status ?: 500})."
        NotPlaylist -> "The provider sent ${contentType ?: "a non-video response"} instead of a video stream."
        EmptyPlaylist -> "The provider sent a playlist without any segments."
        UnsupportedEncryption -> "This stream uses encryption the downloader cannot handle."
        Unreachable -> "Could not reach the provider to check the episode source."
    }

    val retryWithFreshSource: Boolean
        get() = this == Expired || this == Forbidden || this == NotFound || this == RateLimited ||
            this == ServerError || this == NotPlaylist || this == EmptyPlaylist
}

internal class SourceException(
    val reason: SourceFailure,
    val status: Int? = null,
    val contentType: String? = null,
    cause: Throwable? = null,
) : IOException(reason.describe(status, contentType), cause)

internal fun Throwable.sourceException(): SourceException? {
    var current: Throwable? = this
    var guard = 0
    while (current != null && guard++ < 8) {
        (current as? SourceException)?.let { return it }
        current = current.cause
    }
    return null
}

internal object HlsSource {
    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    private const val RETRIES = 3
    private const val PARALLEL = 4
    private val restrictedHeaders = setOf(
        "host", "connection", "content-length", "expect", "upgrade", "date", "transfer-encoding", "keep-alive",
    )

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    // providers also serve playlists from extension-less paths, which validate() sniffs instead
    fun looksLikePlaylist(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    fun isDash(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase().endsWith(".mpd")

    suspend fun validate(
        url: String,
        headers: Map<String, String>,
        maxHeight: Int = Int.MAX_VALUE,
    ): ValidatedSource {
        if (isDash(url)) return ValidatedSource(url, headers, SourceKind.Dash, "dash manifest")
        if (looksLikePlaylist(url)) return validatePlaylist(URI(url), headers, maxHeight)
        val head = probeDirect(url, headers)
        // a tokenised cdn url often hides the manifest behind a path without .m3u8
        if (head.contains("#EXTM3U", ignoreCase = true)) {
            return validatePlaylist(URI(url), headers, maxHeight)
        }
        return ValidatedSource(url, headers, SourceKind.DirectMedia, "direct media")
    }
    private suspend fun validatePlaylist(uri: URI, headers: Map<String, String>, maxHeight: Int): ValidatedSource {
        val (playlist, finalUri) = resolvePlaylist(uri, headers, maxHeight)
        val detail = buildString {
            append("hls ")
            append(playlist.segments.size)
            append(" segments")
            if (playlist.isFragmented) append(" fragmented")
        }
        return ValidatedSource(finalUri.toString(), headers, SourceKind.HlsMedia, detail, playlist)
    }

    suspend fun resolvePlaylist(
        uri: URI,
        headers: Map<String, String>,
        maxHeight: Int = Int.MAX_VALUE,
    ): Pair<HlsMediaPlaylist, URI> {
        val fetched = fetchText(uri, headers)
        if (isMaster(fetched.text)) {
            val variant = pickVariant(parseMasterPlaylist(fetched.text, fetched.uri), maxHeight)
                ?: throw SourceException(SourceFailure.EmptyPlaylist)
            val media = fetchText(variant.url, headers)
            val playlist = parseMediaPlaylist(media.text, media.uri)
                ?: throw SourceException(SourceFailure.NotPlaylist, contentType = sniff(media.text))
            if (playlist.segments.isEmpty()) throw SourceException(SourceFailure.EmptyPlaylist)
            return playlist to media.uri
        }
        val playlist = parseMediaPlaylist(fetched.text, fetched.uri)
            ?: throw SourceException(SourceFailure.NotPlaylist, contentType = sniff(fetched.text))
        if (playlist.segments.isEmpty()) throw SourceException(SourceFailure.EmptyPlaylist)
        return playlist to fetched.uri
    }

    fun pickVariant(variants: List<HlsVariant>, maxHeight: Int): HlsVariant? {
        if (variants.isEmpty()) return null
        val sized = variants.filter { it.height != null }
        val withinCap = sized.filter { (it.height ?: 0) <= maxHeight }
        if (withinCap.isNotEmpty()) {
            return withinCap.maxWithOrNull(compareBy<HlsVariant> { it.height ?: 0 }.thenBy { it.bandwidth ?: 0L })
        }
        sized.minByOrNull { it.height ?: 0 }?.let { return it }
        return variants.maxByOrNull { it.bandwidth ?: 0L }
    }

    fun isMaster(body: String): Boolean = body.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    fun parseMasterPlaylist(body: String, base: URI): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        var height: Int? = null
        var bandwidth: Long? = null
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    height = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                }

                line.startsWith("#") -> Unit
                else -> {
                    if (height != null || bandwidth != null) {
                        resolve(base, line)?.let { variants += HlsVariant(it, height, bandwidth) }
                    }
                    height = null
                    bandwidth = null
                }
            }
        }
        return variants
    }

    fun parseMediaPlaylist(body: String, base: URI): HlsMediaPlaylist? {
        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.none { it.startsWith("#EXTM3U", ignoreCase = true) }) return null
        if (isMaster(body)) return null
        var sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toIntOrNull() ?: 0
        var key: HlsKey? = null
        var explicitIv: ByteArray? = null
        var initSegment: URI? = null
        val segments = mutableListOf<HlsSegment>()
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val attributes = parseAttributes(line.substringAfter(':', ""))
                    when (attributes["METHOD"]?.uppercase()) {
                        "NONE" -> {
                            key = null
                            explicitIv = null
                        }

                        "AES-128" -> {
                            key = attributes["URI"]?.let { resolve(base, it) }?.let(::HlsKey)
                            explicitIv = attributes["IV"]?.let(::parseIv)
                        }

                        else -> throw SourceException(SourceFailure.UnsupportedEncryption)
                    }
                }

                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    initSegment = parseAttributes(line.substringAfter(':', ""))["URI"]
                        ?.let { resolve(base, it) }
                }

                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    sequence = line.substringAfter(':', "").trim().toIntOrNull() ?: sequence
                }

                line.startsWith("#") -> Unit
                else -> {
                    val resolved = resolve(base, line) ?: return@forEach
                    segments += HlsSegment(resolved, sequence, key, explicitIv)
                    // an explicit iv belongs to one segment unless the key line repeats it
                    explicitIv = null
                    sequence++
                }
            }
        }
        return HlsMediaPlaylist(segments, initSegment)
    }

    suspend fun downloadMediaPlaylist(
        playlist: HlsMediaPlaylist,
        headers: Map<String, String>,
        output: Path,
        onProgress: (written: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val part = File("$output.part")
        val total = playlist.segments.size
        val startedAt = System.nanoTime()
        var written = 0L
        var index = 0
        var lastReport = 0L
        logger.info { "hls segments start count=$total parallel=$PARALLEL fragmented=${playlist.isFragmented}" }
        val keys = playlist.segments.mapNotNull { it.key }
            .distinctBy { it.url.toString() }
            .associate { it.url.toString() to fetchBytes(it.url, headers) }
        try {
            part.outputStream().buffered().use { file ->
                playlist.initSegment?.let { init ->
                    val bytes = fetchBytes(init, headers)
                    file.write(bytes)
                    written += bytes.size
                }
                while (index < total) {
                    coroutineContext.ensureActive()
                    val window = playlist.segments.subList(index, minOf(index + PARALLEL, total))
                    val fetched = coroutineScope {
                        window.map { segment ->
                            async {
                                try {
                                    val body = fetchBytes(segment.url, headers)
                                    val key = segment.key ?: return@async body
                                    decrypt(body, keys.getValue(key.url.toString()), segment)
                                } catch (error: Throwable) {
                                    logger.warn {
                                        "hls segment failed index=$index/${total} sequence=${segment.sequence} " +
                                            "url=${redactUrl(segment.url.toString())} error=${error.javaClass.simpleName}: ${error.message}"
                                    }
                                    throw error
                                }
                            }
                        }.awaitAll()
                    }
                    fetched.forEach { bytes ->
                        file.write(bytes)
                        written += bytes.size
                        onProgress(written)
                    }
                    index += window.size
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                    if (elapsedMs - lastReport >= 10_000L || index == total) {
                        lastReport = elapsedMs
                        val rate = if (elapsedMs > 0L) written / 1_048_576.0 / (elapsedMs / 1000.0) else 0.0
                        logger.info {
                            "hls segments progress $index/$total bytes=$written " +
                                "rate=${"%.2f".format(rate)}MB/s elapsedMs=$elapsedMs"
                        }
                    }
                }
            }
            move(part.toPath(), output)
            logger.info {
                "hls segments done count=$total bytes=$written " +
                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
            }
            written
        } catch (error: Throwable) {
            logger.warn {
                "hls segments aborted at $index/$total bytes=$written " +
                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} error=${error.javaClass.simpleName}: ${error.message}"
            }
            Files.deleteIfExists(part.toPath())
            throw error
        }
    }

    private fun decrypt(body: ByteArray, material: ByteArray, segment: HlsSegment): ByteArray {
        val key = normaliseKey(material) ?: throw SourceException(SourceFailure.UnsupportedEncryption)
        val iv = segment.explicitIv ?: sequenceIv(segment.sequence)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return try {
            cipher.doFinal(body)
        } catch (failure: Exception) {
            throw SourceException(SourceFailure.UnsupportedEncryption, cause = failure)
        }
    }

    private fun normaliseKey(material: ByteArray): ByteArray? {
        if (material.size == 16) return material
        if (material.size == 32) {
            val hex = material.toString(Charsets.US_ASCII)
            if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                runCatching {
                    ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                }.getOrNull()?.let { return it }
            }
        }
        return runCatching { java.util.Base64.getDecoder().decode(material) }.getOrNull()?.takeIf { it.size == 16 }
    }

    private fun sequenceIv(sequence: Int): ByteArray {
        val iv = ByteArray(16)
        val value = sequence.toLong()
        for (index in 0 until 8) iv[15 - index] = ((value ushr (8 * index)) and 0xFF).toByte()
        return iv
    }

    private fun parseIv(raw: String): ByteArray? {
        val hex = raw.trim().removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length > 32) return null
        val padded = hex.padStart(32, '0')
        return runCatching {
            ByteArray(16) { index -> padded.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var quoted = false
        raw.forEach { char ->
            when {
                char == '"' -> quoted = !quoted
                char == '=' && !inValue && !quoted -> inValue = true
                char == ',' && !quoted -> {
                    if (key.isNotBlank()) result[key.toString().trim().uppercase()] = value.toString().trim()
                    key.clear()
                    value.clear()
                    inValue = false
                }

                inValue -> value.append(char)
                else -> key.append(char)
            }
        }
        if (key.isNotBlank()) result[key.toString().trim().uppercase()] = value.toString().trim()
        return result
    }

    private fun resolve(base: URI, reference: String): URI? =
        runCatching { base.resolve(reference.trim()) }.getOrNull()

    // a direct media url can still be a hidden manifest, so the first bytes are returned to decide
    private suspend fun probeDirect(url: String, headers: Map<String, String>): String {
        var response = sendRangeProbe(url, headers, ranged = true)
        // a ranged request on a playlist is meaningless to some cdns, so retry plainly
        if (response.statusCode() == 416) {
            response.body().close()
            response = sendRangeProbe(url, headers, ranged = false)
        }
        val type = response.headers().firstValue("Content-Type").orElse(null)
        val body = response.body().use { stream -> runCatching { readLimited(stream, 2048) }.getOrDefault(ByteArray(0)) }
        val status = response.statusCode()
        if (status !in 200..299) throw SourceException(failureFor(status), status, type)
        if (type != null) {
            val lowered = type.lowercase()
            if (lowered.startsWith("application/vnd.apple.mpegurl") || lowered.startsWith("application/x-mpegurl")) {
                return body.toString(Charsets.UTF_8)
            }
            if (lowered.startsWith("text/html") || lowered.startsWith("application/json") || lowered.startsWith("text/plain")) {
                throw SourceException(SourceFailure.NotPlaylist, status, type)
            }
        }
        return body.toString(Charsets.UTF_8)
    }

    private suspend fun sendRangeProbe(
        url: String,
        headers: Map<String, String>,
        ranged: Boolean,
    ): HttpResponse<InputStream> {
        val builder = request(URI(url), headers).GET()
        if (ranged) builder.header("Range", "bytes=0-2047")
        return try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: Exception) {
            throw SourceException(SourceFailure.Unreachable, cause = failure)
        }
    }

    private suspend fun fetchText(uri: URI, headers: Map<String, String>): Fetched {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val response = client.send(request(uri, headers).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                if (status in 200..299) {
                    val body = response.body().use { readLimited(it, MAX_PLAYLIST_BYTES) }
                    return Fetched(body.toString(Charsets.UTF_8), response.uri())
                }
                val head = response.body().use { stream ->
                    runCatching { readLimited(stream, 256).toString(Charsets.UTF_8) }.getOrDefault("")
                }
                val failure = failureFor(status)
                if (failure == SourceFailure.ServerError || failure == SourceFailure.RateLimited) {
                    if (attempt < RETRIES) {
                        delay(500L * attempt)
                        continue
                    }
                }
                throw SourceException(failure, status, sniff(head))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (source: SourceException) {
                throw source
            } catch (failure: Exception) {
                if (attempt < RETRIES) {
                    delay(500L * attempt)
                    continue
                }
                throw SourceException(SourceFailure.Unreachable, cause = failure)
            }
        }
    }

    private suspend fun fetchBytes(uri: URI, headers: Map<String, String>): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val response = withContext(Dispatchers.IO) {
                    client.send(request(uri, headers).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                }
                val status = response.statusCode()
                if (status in 200..299) {
                    return withContext(Dispatchers.IO) { response.body().use { it.readBytes() } }
                }
                response.body().close()
                val failure = failureFor(status)
                logger.warn {
                    "segment http $status (${failure.name}) url=${redactUrl(uri.toString())} attempt=$attempt"
                }
                if ((failure == SourceFailure.ServerError || failure == SourceFailure.RateLimited) && attempt < RETRIES) {
                    delay(500L * attempt)
                    continue
                }
                throw SourceException(failure, status)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (source: SourceException) {
                throw source
            } catch (failure: Exception) {
                logger.warn {
                    "segment fetch error url=${redactUrl(uri.toString())} attempt=$attempt " +
                        "${failure.javaClass.simpleName}: ${failure.message}"
                }
                if (attempt < RETRIES) {
                    delay(500L * attempt)
                    continue
                }
                throw SourceException(SourceFailure.Unreachable, cause = failure)
            }
        }
    }

    private fun request(uri: URI, headers: Map<String, String>): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
        var accept = false
        var userAgent = false
        headers.forEach { (name, value) ->
            val key = name.trim()
            if (key.isEmpty() || value.isBlank() || key.lowercase() in restrictedHeaders) return@forEach
            if (key.equals("Host", ignoreCase = true)) return@forEach
            if (key.equals("Accept", ignoreCase = true)) accept = true
            if (key.equals("User-Agent", ignoreCase = true)) userAgent = true
            builder.header(key, value.replace('\n', ' ').replace('\r', ' '))
        }
        if (!accept) builder.header("Accept", "*/*")
        if (!userAgent) builder.header("User-Agent", "AniLife/1.0")
        return builder
    }

    private fun failureFor(status: Int): SourceFailure = when {
        status == 401 || status == 410 -> SourceFailure.Expired
        status == 403 -> SourceFailure.Forbidden
        status == 404 -> SourceFailure.NotFound
        status == 429 -> SourceFailure.RateLimited
        status >= 500 -> SourceFailure.ServerError
        else -> SourceFailure.Unreachable
    }

    private fun sniff(body: String): String {
        val head = body.trimStart().take(64).lowercase()
        return when {
            head.startsWith("<!doctype html") || head.startsWith("<html") -> "an html page"
            body.isBlank() -> "an empty response"
            else -> "an unsupported response"
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (buffer.size() < limit) {
            val read = stream.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class Fetched(val text: String, val uri: URI)
}

// keeps tokens out of logs; only the host and file name survive
internal fun redactUrl(url: String): String = runCatching {
    val uri = URI(url)
    val host = uri.host ?: return@runCatching "<url>"
    val name = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').ifBlank { "stream" }
    "${uri.scheme ?: "https"}://$host/.../$name"
}.getOrElse { "<url>" }