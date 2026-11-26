package com.pcbsolderview.update

sealed interface UpdateState {
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseManifest) : UpdateState
    data class Failed(val failure: Failure, val message: String) : UpdateState
}

/** How a listed release relates to the installed version. */
enum class Relation { NEWER, INSTALLED, OLDER }

data class ReleaseChoice(val entry: ReleaseEntry, val manifest: ReleaseManifest, val relation: Relation)

/** Which part of a manifest this app installs; Android and the PC differ only here. */
interface Platform {
    /** The release's version for this platform, null when the release has none for it. */
    fun versionOf(m: ReleaseManifest): Comparable<*>?
    fun relation(m: ReleaseManifest): Relation
    /** False when the release can't run here (Android minSdk). */
    fun compatible(m: ReleaseManifest): Boolean = true
}

class AndroidPlatform(private val installedCode: Int, private val sdk: Int) : Platform {
    override fun versionOf(m: ReleaseManifest) = m.android?.versionCode
    override fun relation(m: ReleaseManifest): Relation {
        val code = m.android!!.versionCode
        return when { code > installedCode -> Relation.NEWER; code == installedCode -> Relation.INSTALLED; else -> Relation.OLDER }
    }
    override fun compatible(m: ReleaseManifest) = (m.android?.minSdk ?: Int.MAX_VALUE) <= sdk
}

class DesktopPlatform(private val installed: Version) : Platform {
    override fun versionOf(m: ReleaseManifest) = m.desktop?.version?.let(Version::parse)
    override fun relation(m: ReleaseManifest): Relation {
        val v = Version.parse(m.desktop!!.version)!!
        return when { v > installed -> Relation.NEWER; v == installed -> Relation.INSTALLED; else -> Relation.OLDER }
    }
}

/**
 * Checks for updates on demand (decision D3: never in the background). Blocking calls: run them
 * off the main thread.
 */
class UpdateChecker(private val source: ReleaseSource, private val platform: Platform) {

    /** Newest release for this platform, or [UpdateState.UpToDate]. Test versions only with [includeTest]. */
    fun check(includeTest: Boolean): UpdateState = try {
        val newest = if (!includeTest) latestFull() else null
        val best = newest ?: newestFromList(includeTest)
        if (best != null && platform.relation(best) == Relation.NEWER) UpdateState.Available(best) else UpdateState.UpToDate
    } catch (e: UpdateException) {
        UpdateState.Failed(e.failure, UpdateMessages.of(e))
    }

    /** Every release with a part for this platform, newest first (fetches each manifest). */
    fun list(includeTest: Boolean, max: Int = 30): List<ReleaseChoice> =
        source.all().asSequence()
            .filter { it.manifestUrl != null && (includeTest || !it.prerelease) }
            .take(max)
            .mapNotNull { e -> runCatching { source.manifest(e) }.getOrNull()?.let { e to it } }
            .filter { (_, m) -> platform.versionOf(m) != null && platform.compatible(m) }
            .map { (e, m) -> ReleaseChoice(e, m, platform.relation(m)) }
            .toList()

    private fun latestFull(): ReleaseManifest? = try {
        source.latest().takeIf { platform.versionOf(it) != null && platform.compatible(it) }
    } catch (e: UpdateException) {
        // The newest release may predate the updater (no manifest): fall back to the list.
        if (e.failure == Failure.NO_RELEASE) null else throw e
    }

    /** The highest version among the newest releases (they are listed newest first). */
    @Suppress("UNCHECKED_CAST")
    private fun newestFromList(includeTest: Boolean): ReleaseManifest? {
        val entries = source.all().filter { it.manifestUrl != null && (includeTest || !it.prerelease) }.take(10)
        val manifests = entries.mapNotNull { e ->
            try { source.manifest(e) } catch (x: UpdateException) { if (x.failure == Failure.OFFLINE || x.failure == Failure.RATE_LIMITED) throw x else null }
        }.filter { platform.versionOf(it) != null && platform.compatible(it) }
        return manifests.maxWithOrNull { a, b -> (platform.versionOf(a) as Comparable<Any>).compareTo(platform.versionOf(b)!!) }
            ?: if (entries.isEmpty()) throw UpdateException(Failure.NO_RELEASE) else null
    }
}
