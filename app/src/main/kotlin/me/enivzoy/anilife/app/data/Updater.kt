package me.enivzoy.anilife.app.data

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.enivzoy.anilife.core.diagnostics.DiagnosticsPaths
import me.enivzoy.anilife.core.diagnostics.ResourceTracker
import me.enivzoy.anilife.core.model.Update
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger("AniLife.updater")

private const val DEFAULT_MANIFEST_URL =
    "https://github.com/anilifecommunity/kotlindesktop/releases/latest/download/latest.json"

private const val FALLBACK_MANIFEST_URL =
    "https://api.github.com/repos/anilifecommunity/kotlindesktop/releases/latest"

private const val PROGRESS_STEP_PERCENT = 25L
private const val PROGRESS_STEP_BYTES = 10L * 1024L * 1024L
private const val INSTALL_SUCCESS = 0
private const val INSTALL_RESTART_REQUIRED = 3010

open class UpdateException(message: String) : IOException(message)

class UpdateHttpException(val status: Int) : UpdateException("HTTP $status")

class SizeMismatchException(val expected: Long, val actual: Long) :
    UpdateException("size mismatch: expected $expected bytes, actual $actual")

class ChecksumException(val expected: String, val actual: String) :
    UpdateException("sha256 mismatch: expected $expected, actual $actual")

internal fun redactForLog(value: String): String {
    val home = System.getProperty("user.home")
    val homeRedacted = if (!home.isNullOrBlank() &&
        (value == home || value.startsWith(home + File.separator))
    ) {
        "~" + value.removePrefix(home)
    } else {
        value
    }
    return if (homeRedacted.startsWith("http://", ignoreCase = true) ||
        homeRedacted.startsWith("https://", ignoreCase = true)
    ) {
        homeRedacted.substringBefore('?')
    } else {
        homeRedacted
    }
}

internal fun installCommand(installer: Path, msiLog: Path): String =
    if (installer.fileName.toString().endsWith(".msi", ignoreCase = true)) {
        "msiexec.exe /i ${cmdQuote(installer.toString())} /passive /norestart /L*V ${cmdQuote(msiLog.toString())}"
    } else {
        "${cmdQuote(installer.toString())} /quiet /norestart"
    }

private fun cmdQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

internal fun restartCommand(executable: String, arguments: List<String>): String =
    (listOf(executable) + arguments).joinToString(" ") { cmdQuote(it) }

internal fun windowsInstallScript(
    appPid: Long,
    installer: Path,
    msiLog: Path,
    marker: Path,
    appCommand: String,
): List<String> = buildList {
    add("@echo off")
    add("setlocal")
    add("powershell.exe -NoProfile -WindowStyle Hidden -Command \"Wait-Process -Id $appPid -ErrorAction SilentlyContinue\"")
    add(installCommand(installer, msiLog))
    add("set CODE=%ERRORLEVEL%")
    add("echo %CODE%>${cmdQuote(marker.toString())}")
    add("if \"%CODE%\"==\"$INSTALL_SUCCESS\" start \"\" $appCommand")
    add("if \"%CODE%\"==\"$INSTALL_RESTART_REQUIRED\" start \"\" $appCommand")
    add("endlocal")
}

private fun rateLimitMessage(reset: String?): String {
    val retryAt = reset?.toLongOrNull()
        ?.let(Instant::ofEpochSecond)
        ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
        ?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    return if (retryAt == null) {
        "GitHub rate limit reached. Retry later."
    } else {
        "GitHub rate limit reached. Retry after $retryAt local time."
    }
}

class Updater(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val fallbackManifestUrl: String = FALLBACK_MANIFEST_URL,
    private val releasesUrl: String = Updater.RELEASES_URL,
) {
    /**
     * Release-list discovery only applies to the canonical repository. Custom manifest endpoints
     * (self-hosted mirrors or tests) must not silently fall through to GitHub's release list.
     */
    private val releaseListEnabled: Boolean =
        releasesUrl.isNotBlank() &&
            (manifestUrl == DEFAULT_MANIFEST_URL || fallbackManifestUrl == FALLBACK_MANIFEST_URL)

    private val mutableUpdate = MutableStateFlow<Update?>(null)
    val update: StateFlow<Update?> = mutableUpdate.asStateFlow()

    suspend fun check(
        currentVersion: String,
        updateChannel: UpdateChannel = UpdateChannel.RELEASE,
    ): Update? = withContext(Dispatchers.IO) {
        val update = fetchUpdate(currentVersion, updateChannel)
        mutableUpdate.value = update
        update
    }

    fun clear() {
        mutableUpdate.value = null
    }

    suspend fun download(
        update: Update,
        destination: Path,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        logger.info { "download start version=${update.version} url=${redactForLog(update.url)}" }
        val uri = httpUri(update.url)
            ?: throw UpdateException("invalid download URL for version ${update.version}")
        logger.info { "download expected size=${update.sizeBytes}" }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(600))
            .header("User-Agent", "AniLife-Updater")
            .header("Accept", "*/*")
            .GET()
            .build()
        val lease = ResourceTracker.networkOpened(request.uri().host)
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: Throwable) {
            lease.close()
            logger.error {
                "download request failed ${failure::class.java.simpleName}: ${redactForLog(failure.message.orEmpty())}"
            }
            throw failure
        }
        val streamBody = lease.track(response.body())
        if (response.statusCode() >= 400) {
            streamBody.close()
            val status = response.statusCode()
            logger.error { "download failed version=${update.version} HTTP $status" }
            throw UpdateHttpException(status)
        }
        logger.info { "download response status=${response.statusCode()}" }

        val parent = destination.toAbsolutePath().parent
        val temporary = try {
            Files.createDirectories(parent)
            Files.createTempFile(parent, destination.fileName.toString(), ".part")
        } catch (failure: Throwable) {
            streamBody.close()
            logger.error {
                "download temp creation failed ${failure::class.java.simpleName}: ${redactForLog(failure.message.orEmpty())}"
            }
            throw failure
        }
        logger.info { "download temp path=${redactForLog(temporary.toString())}" }

        val digest = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        val total = update.sizeBytes.takeIf { it > 0L }
        var copied = 0L
        var lastStep = 0L
        try {
            streamBody.use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                        val step = if (total != null) {
                            copied * 100 / total / PROGRESS_STEP_PERCENT
                        } else {
                            copied / PROGRESS_STEP_BYTES
                        }
                        if (step != lastStep) {
                            lastStep = step
                            if (total != null) {
                                logger.info { "download progress ${copied * 100 / total}% bytes=$copied total=$total" }
                            } else {
                                logger.info { "download progress bytes=$copied" }
                            }
                        }
                    }
                }
            }
            if (total != null && copied != total) {
                logger.error { "size verification failed expected=$total actual=$copied" }
                throw SizeMismatchException(total, copied)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            logger.info { "sha256 expected=${update.sha256.ifBlank { "n/a" }} actual=$actual" }
            if (update.sha256.isBlank()) {
                logger.warn { "sha256 missing for version ${update.version}; verification skipped (actual=$actual)" }
            } else if (!actual.equals(update.sha256, ignoreCase = true)) {
                logger.error { "sha256 mismatch expected=${update.sha256} actual=$actual" }
                throw ChecksumException(update.sha256, actual)
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            val durationMs = (System.nanoTime() - started) / 1_000_000
            logger.info { "download complete version=${update.version} bytes=$copied durationMs=$durationMs" }
            true
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    fun installWindows(installer: Path) {
        val logs = DiagnosticsPaths.logs
        Files.createDirectories(logs)
        Files.createDirectories(updatesDirectory())
        val stagedInstaller = installer.toAbsolutePath()
        if (!Files.isRegularFile(stagedInstaller)) {
            throw UpdateException("downloaded installer is missing: ${redactForLog(stagedInstaller.toString())}")
        }
        val msiLog = logs.resolve("updater-msi.log")
        val marker = logs.resolve("updater-result.txt")
        val command = installCommand(stagedInstaller, msiLog)
        val processInfo = ProcessHandle.current().info()
        val appPath = processInfo.command().orElseThrow {
            UpdateException("could not determine the AniLife executable to restart")
        }
        val appCommand = restartCommand(appPath, processInfo.arguments().orElse(emptyArray()).toList())
        val appPid = ProcessHandle.current().pid()
        val helper = updatesDirectory().resolve("run-updater.cmd")
        val lines = windowsInstallScript(appPid, stagedInstaller, msiLog, marker, appCommand)
        Files.write(helper, lines, StandardCharsets.UTF_8)
        logger.info { "install command=${redactForLog(command)}" }
        logger.info {
            "install helper launched path=${redactForLog(helper.toString())} " +
                "app=${redactForLog(appPath)} pid=$appPid"
        }
        ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/b", helper.toString()).start()
    }

    private suspend fun fetchUpdate(currentVersion: String, updateChannel: UpdateChannel): Update? {
        logger.info { "update check start current=$currentVersion channel=${updateChannel.name}" }
        val failures = mutableListOf<String>()
        val sources = buildList {
            add(manifestUrl)
            if (releaseListEnabled) add(releasesUrl)
            add(fallbackManifestUrl)
        }
        var successfulResponses = 0
        for (url in sources) {
            val safeUrl = redactForLog(url)
            logger.info { "manifest query url=$safeUrl" }
            try {
                val uri = URI.create(url)
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "AniLife-Updater")
                    .header("Accept", acceptHeaderFor(uri))
                    .GET()
                    .build()
                val response = ResourceTracker.networkOpened(uri.host).use {
                    http.send(request, HttpResponse.BodyHandlers.ofString())
                }
                val remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("n/a")
                val reset = response.headers().firstValue("X-RateLimit-Reset").orElse("n/a")
                val status = response.statusCode()
                logger.info {
                    "manifest response url=$safeUrl status=$status " +
                        "ratelimit-remaining=$remaining ratelimit-reset=$reset"
                }
                if (status >= 400) {
                    val detail = if ((status == 403 || status == 429) && remaining == "0") {
                        rateLimitMessage(reset)
                    } else {
                        "HTTP $status"
                    }
                    logger.warn { "manifest failure url=$safeUrl $detail" }
                    failures += "$safeUrl: $detail"
                    continue
                }
                successfulResponses++
                val update = if (url == releasesUrl) {
                    parseReleaseList(response.body(), currentVersion, currentPlatform(), updateChannel)
                } else {
                    parseUpdateManifest(response.body(), currentVersion, currentPlatform(), updateChannel)
                }
                if (update != null) return update
                logger.info { "manifest returned no compatible update url=$safeUrl" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = "${failure::class.java.simpleName}: ${redactForLog(failure.message.orEmpty())}"
                logger.warn { "manifest failure url=$safeUrl $detail" }
                failures += "$safeUrl: $detail"
            }
        }
        if (successfulResponses == 0) {
            val message = "Update manifest fetch failed: ${failures.joinToString("; ")}"
            logger.error { message }
            throw UpdateException(message)
        }
        logger.info { "update check complete current=$currentVersion no compatible update" }
        return null
    }

    companion object {
        const val FALLBACK_VERSION = "1.0.0"
        const val UPDATES_DIRECTORY = "updates"
        const val RELEASES_URL =
            "https://api.github.com/repos/anilifecommunity/kotlindesktop/releases?per_page=30"

        fun logFile(): Path = DiagnosticsPaths.logs.resolve("updater.log")

        fun reportPreviousInstall() {
            val marker = DiagnosticsPaths.logs.resolve("updater-result.txt")
            if (!Files.isRegularFile(marker)) return
            val code = runCatching { Files.readString(marker, StandardCharsets.UTF_8).trim() }
                .getOrNull()
                ?.toIntOrNull()
            if (code == null) {
                logger.warn { "previous installer exit code unreadable path=${redactForLog(marker.toString())}" }
            } else {
                val message = "previous installer exit code $code"
                if (code == INSTALL_SUCCESS || code == INSTALL_RESTART_REQUIRED) {
                    logger.info { message }
                } else {
                    logger.warn { message }
                }
            }
            runCatching { Files.deleteIfExists(marker) }.onFailure { failure ->
                logger.warn {
                    "updater result marker not deleted ${failure::class.java.simpleName}: " +
                        redactForLog(failure.message.orEmpty())
                }
            }
        }

        fun currentVersion(): String {
            val stream = Updater::class.java.classLoader.getResourceAsStream("version/version.properties")
            val value = stream?.bufferedReader()?.readText()?.trim()?.substringAfter('=')?.takeIf(String::isNotBlank)
            return value ?: FALLBACK_VERSION
        }

        fun updatesDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            return base.resolve("AniLifeUpdater").resolve(UPDATES_DIRECTORY)
        }

        /**
         * Legacy accessor kept for backward compatibility. Do not use for upgrade decisions;
         * [AppVersion] carries the suffix. Bare partial versions ("2.0") are padded to a triple.
         */
        fun parseVersion(version: String): List<Int> =
            parseAppVersion(version)?.let { listOf(it.major, it.minor, it.patch) }
                ?: legacyNumericVersion(version)
                ?: listOf(0)

        fun isNewer(candidate: String, current: String): Boolean {
            val left = parseAppVersion(candidate)
            val right = parseAppVersion(current)
            if (left != null && right != null) return left.isNewerThan(right)
            // Legacy tolerance for short, suffix-less versions such as "2.0" used by old manifests.
            // Anything carrying an unknown/partial suffix stays unparseable and never counts newer.
            val legacyLeft = legacyNumericVersion(candidate) ?: return false
            val legacyRight = legacyNumericVersion(current) ?: return false
            return compareTriples(legacyLeft, legacyRight) > 0
        }
    }
}

/** Bare `1`, `1.2` or `1.2.3` padded to a triple; null when the string is anything else. */
private val LEGACY_NUMERIC_VERSION = Regex("""^\d+(?:\.\d+){0,2}$""")

private fun legacyNumericVersion(version: String): List<Int>? {
    val trimmed = version.trim()
    if (!LEGACY_NUMERIC_VERSION.matches(trimmed)) return null
    val parts = trimmed.split('.').map { it.toIntOrNull() ?: return null }
    return List(3) { parts.getOrElse(it) { 0 } }
}

private fun compareTriples(left: List<Int>, right: List<Int>): Int {
    val size = maxOf(left.size, right.size)
    for (index in 0 until size) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

internal fun parseUpdateManifest(
    body: String,
    currentVersion: String,
    platform: String,
    updateChannel: UpdateChannel = UpdateChannel.RELEASE,
): Update? {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(body) as? JsonObject ?: return null
    val tagName = root.string("tag_name")?.trim()?.takeIf { it.isNotEmpty() }
    val declaredVersion = root.string("version")?.trim()?.takeIf { it.isNotEmpty() }
    val rawVersion = declaredVersion ?: tagName ?: return null
    val candidate = parseAppVersion(rawVersion) ?: return null
    val version = candidate.normalized()
    if (tagName != null && declaredVersion != null) {
        val tagVersion = parseAppVersion(tagName) ?: return null
        if (tagVersion.normalized() != version) {
            logger.warn { "manifest tag/version mismatch tag=$tagName version=$declaredVersion" }
            return null
        }
    }
    val current = parseAppVersion(currentVersion) ?: ZERO_APP_VERSION
    if (!isUpdateAllowed(current, candidate, updateChannel)) return null
    val declaredChannel = root.string("channel")?.trim()?.lowercase()
    if (declaredChannel != null) {
        val suffixChannel = candidate.channel.name.lowercase()
        if (declaredChannel != suffixChannel && declaredChannel != "pre-release") {
            logger.warn {
                "manifest channel mismatch version=$version declared=$declaredChannel suffix=$suffixChannel"
            }
        }
    }

    val isReleasePayload = tagName != null
    val assets = (root["assets"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

    fun assetUrl(asset: JsonObject): String? =
        asset.string("browser_download_url") ?: asset.string("url")

    fun JsonObject.isUsableAsset(): Boolean {
        val name = string("name")
        if (name != null) {
            // An asset filename must name exactly this release version, not just contain it:
            // "anilife-1.1.10.base.msi" must not satisfy version 1.1.1.
            if (!assetNameNamesVersion(name, version)) return false
        } else if (isReleasePayload) {
            return false
        }
        val url = assetUrl(this) ?: return true
        return urlMatchesReleaseTag(url, tagName)
    }

    val platformAsset = assets.firstOrNull {
        it.string("platform").equals(platform, ignoreCase = true) && it.isUsableAsset()
    }
    val releaseAsset = if (isReleasePayload) {
        assets.firstOrNull {
            val name = it.string("name").orEmpty()
            (name.endsWith(".base.msi", ignoreCase = true) ||
                name.endsWith("#base.msi", ignoreCase = true)) && it.isUsableAsset()
        } ?: assets.firstOrNull {
            val name = it.string("name").orEmpty()
            (name.endsWith(".base.exe", ignoreCase = true) ||
                name.endsWith("#base.exe", ignoreCase = true)) && it.isUsableAsset()
        }
    } else {
        null
    }
    val asset = platformAsset ?: releaseAsset
    // A GitHub release object carries its own `url`, which is the API JSON endpoint, not a
    // download. The selected asset's URL is authoritative; root-level URLs are a fallback for
    // hand-written manifests and must also be real download links.
    val url = asset?.let { assetUrl(it) }?.takeIf(::isDownloadableUrl)
        ?: sequenceOf(root.string("url"), root.string("assetUrl"))
            .filterNotNull()
            .firstOrNull { it.isNotBlank() && isDownloadableUrl(it) }
        ?: return null
    if (!urlMatchesReleaseTag(url, tagName)) return null
    val downloadUrl = normalizeAssetUrl(url) ?: return null
    return Update(
        version = version,
        changelog = root.string("releaseNotes") ?: root.string("changelog") ?: root.string("body") ?: "",
        url = downloadUrl,
        sizeBytes = root.long("sizeBytes") ?: root.long("size") ?: asset?.long("sizeBytes")
            ?: asset?.long("size") ?: 0L,
        sha256 = root.string("sha256") ?: root.string("checksum")
            ?: asset?.string("sha256")
            ?: asset?.string("digest")?.removePrefix("sha256:")
            ?: "",
        publishedAt = root.long("releasedAt")
            ?: root.string("published_at")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            ?: 0L,
    )
}

/**
 * Picks the best compatible release from a GitHub release-list JSON array. Each release is parsed
 * with the same rules as a single manifest, then the highest allowed version wins.
 */
internal fun parseReleaseList(
    body: String,
    currentVersion: String,
    platform: String,
    updateChannel: UpdateChannel = UpdateChannel.RELEASE,
): Update? {
    val json = Json { ignoreUnknownKeys = true }
    val releases = json.parseToJsonElement(body) as? JsonArray ?: return null
    val current = parseAppVersion(currentVersion) ?: ZERO_APP_VERSION
    var bestVersion: AppVersion? = null
    var bestUpdate: Update? = null
    for (element in releases) {
        val release = element as? JsonObject ?: continue
        val candidate = parseAppVersion(release.string("tag_name")) ?: continue
        if (!isUpdateAllowed(current, candidate, updateChannel)) continue
        val best = bestVersion
        if (best != null && !candidate.isNewerThan(best)) continue
        val update = parseUpdateManifest(release.toString(), currentVersion, platform, updateChannel) ?: continue
        bestVersion = candidate
        bestUpdate = update
    }
    return bestUpdate
}

/**
 * True for an http(s) URL that serves file bytes. GitHub's REST release/asset endpoints return
 * JSON metadata, so they are never valid download targets for the updater.
 */
private fun isDownloadableUrl(value: String): Boolean {
    val uri = httpUri(value) ?: return false
    if (uri.host.equals("api.github.com", ignoreCase = true)) {
        val path = uri.path.orEmpty().trimEnd('/')
        if (GITHUB_RELEASE_API_PATH.matches(path)) return false
    }
    return true
}

/** `/repos/<owner>/<repo>/releases`, `.../latest`, `.../<id>` and `.../assets/<id>`. */
private val GITHUB_RELEASE_API_PATH =
    Regex("""^/repos/[^/]+/[^/]+/releases(?:/(?:latest|\d+|assets/\d+))?$""")

/**
 * True when [name] carries the exact version token, e.g. `anilife-1.1.1-HOTFIX.base.msi` for
 * `1.1.1-HOTFIX` or `anilife-1.0.3#base.exe` for `1.0.3`. A longer version that merely starts with
 * the candidate (`1.1.10` vs `1.1.1`) does not match.
 */
private fun assetNameNamesVersion(name: String, version: String): Boolean {
    val stem = "anilife-$version"
    val index = name.indexOf(stem, ignoreCase = true)
    if (index < 0) return false
    val end = index + stem.length
    return end == name.length || !name[end].isLetterOrDigit()
}

/**
 * Rejects an asset URL whose `.../releases/download/<segment>/...` segment disagrees with the
 * release tag. Applies only to github.com URLs on payloads that carry a tag.
 */
private fun urlMatchesReleaseTag(url: String, tag: String?): Boolean {
    if (tag == null) return true
    val uri = httpUri(url) ?: return false
    if (!uri.host.equals("github.com", ignoreCase = true)) return true
    val marker = "/releases/download/"
    val path = uri.path.orEmpty()
    val index = path.indexOf(marker)
    if (index < 0) return true
    val segment = path.substring(index + marker.length).substringBefore('/')
    return segment.isNotEmpty() && segment == tag
}

private fun currentPlatform(): String = when {
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true) -> "windows"
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true) -> "macos"
    else -> "linux"
}

private fun acceptHeaderFor(uri: URI): String =
    if (uri.host.equals("api.github.com", ignoreCase = true)) {
        "application/vnd.github+json"
    } else {
        "application/json"
    }

private fun httpUri(url: String): URI? = runCatching { URI.create(url) }
    .getOrNull()
    ?.takeIf { uri ->
        uri.isAbsolute &&
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }

private fun normalizeAssetUrl(url: String): String? {
    val uri = httpUri(url) ?: return null
    if (!uri.host.equals("github.com", ignoreCase = true) ||
        !uri.path.contains("/releases/download/") ||
        !uri.path.contains("/dist/") ||
        uri.rawFragment.isNullOrBlank()
    ) {
        return uri.toASCIIString()
    }

    val path = "${uri.path.substringBeforeLast("/dist/")}/${uri.path.substringAfterLast('/')}.${uri.rawFragment}"
    return runCatching { URI(uri.scheme, uri.authority, path, uri.query, null).toASCIIString() }.getOrNull()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
