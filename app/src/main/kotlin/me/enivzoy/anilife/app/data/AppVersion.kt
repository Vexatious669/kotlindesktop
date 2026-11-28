package me.enivzoy.anilife.app.data

/**
 * Release channel derived from the version suffix. A bare MAJOR.MINOR.PATCH version is stable.
 */
internal enum class ReleaseChannel {
    STABLE,
    PRERELEASE,
}

/**
 * Allowed version suffixes, ordered by precedence inside the same MAJOR.MINOR.PATCH triple.
 * A bare version behaves like [RELEASE] for ordering purposes.
 */
internal enum class ReleaseSuffix(val rank: Int) {
    TESTING(0),
    PRE(1),
    RELEASE(2),
    HOTFIX(3),
}

enum class UpdateChannel(val label: String) {
    RELEASE("Release"),
    TESTING("Testing"),
    PRE("Pre-release"),
    HOTFIX("Hotfix"),
    ;

    internal fun accepts(version: AppVersion): Boolean = when (this) {
        RELEASE -> version.suffix == null || version.suffix == ReleaseSuffix.RELEASE
        TESTING -> version.suffix == ReleaseSuffix.TESTING
        PRE -> version.suffix == ReleaseSuffix.PRE
        HOTFIX -> version.suffix == ReleaseSuffix.HOTFIX
    }

    companion object {
        fun parse(value: String?): UpdateChannel =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: RELEASE
    }
}

/**
 * Immutable, dependency-free representation of an AniLife release version.
 *
 * Canonical form: `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-SUFFIX` where the suffix is one of
 * [ReleaseSuffix] in uppercase. A leading `v`/`V` is accepted when parsing (GitHub tags) and
 * dropped from the normalized form.
 */
internal data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: ReleaseSuffix? = null,
) : Comparable<AppVersion> {
    /** Channel inferred from the suffix; the suffix is authoritative. */
    val channel: ReleaseChannel
        get() = when (suffix) {
            ReleaseSuffix.PRE, ReleaseSuffix.TESTING -> ReleaseChannel.PRERELEASE
            else -> ReleaseChannel.STABLE
        }

    /** Rank used to order versions that share the same numeric triple (bare == RELEASE). */
    fun rank(): Int = suffix?.rank ?: ReleaseSuffix.RELEASE.rank

    override fun compareTo(other: AppVersion): Int {
        val majorDiff = major.compareTo(other.major)
        if (majorDiff != 0) return majorDiff
        val minorDiff = minor.compareTo(other.minor)
        if (minorDiff != 0) return minorDiff
        val patchDiff = patch.compareTo(other.patch)
        if (patchDiff != 0) return patchDiff
        return rank().compareTo(other.rank())
    }

    fun isNewerThan(other: AppVersion): Boolean = compareTo(other) > 0

    /** Canonical string without a leading `v`, e.g. `1.1.1-HOTFIX` or `1.1.1`. */
    fun normalized(): String {
        val triple = "$major.$minor.$patch"
        return suffix?.let { "$triple-${it.name}" } ?: triple
    }
}

private val APP_VERSION_PATTERN =
    Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(TESTING|PRE|RELEASE|HOTFIX))?$""")

private const val ZERO_MAJOR = 0
private const val ZERO_MINOR = 0
private const val ZERO_PATCH = 0

/** Version used when the locally installed version string cannot be parsed. */
internal val ZERO_APP_VERSION = AppVersion(ZERO_MAJOR, ZERO_MINOR, ZERO_PATCH)

/**
 * Parses a version string, accepting an optional single leading `v`/`V`.
 * Returns null for anything that is not exactly `MAJOR.MINOR.PATCH[-SUFFIX]`.
 */
internal fun parseAppVersion(raw: String?): AppVersion? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val withoutPrefix = if (trimmed.length > 1 &&
        (trimmed[0] == 'v' || trimmed[0] == 'V') &&
        trimmed[1].isDigit()
    ) {
        trimmed.substring(1)
    } else {
        trimmed
    }
    val match = APP_VERSION_PATTERN.matchEntire(withoutPrefix) ?: return null
    val (major, minor, patch, suffixToken) = match.destructured
    val suffix = if (suffixToken.isEmpty()) {
        null
    } else {
        ReleaseSuffix.entries.firstOrNull { it.name == suffixToken } ?: return null
    }
    return AppVersion(
        major = major.toIntOrNull() ?: return null,
        minor = minor.toIntOrNull() ?: return null,
        patch = patch.toIntOrNull() ?: return null,
        suffix = suffix,
    )
}

/** Canonical version string without a leading `v`; null when [raw] is not a valid version. */
internal fun normalizeAppVersion(raw: String?): String? = parseAppVersion(raw)?.normalized()

/**
 * True only when [candidate] is strictly newer than [current] and the current channel may accept
 * it: a stable install never accepts a PRE/TESTING build.
 */
internal fun isUpdateAllowed(current: AppVersion, candidate: AppVersion): Boolean =
    candidate.isNewerThan(current) &&
        (current.channel == ReleaseChannel.PRERELEASE || candidate.channel == ReleaseChannel.STABLE)

internal fun isUpdateAllowed(
    current: AppVersion,
    candidate: AppVersion,
    updateChannel: UpdateChannel,
): Boolean = candidate.isNewerThan(current) && updateChannel.accepts(candidate)
