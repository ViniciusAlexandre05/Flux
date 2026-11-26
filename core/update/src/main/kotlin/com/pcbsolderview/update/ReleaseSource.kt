package com.pcbsolderview.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A GitHub release as listed by the API; its manifest is fetched separately ([ReleaseSource.manifest]). */
data class ReleaseEntry(
    val tag: String,
    val name: String,
    val prerelease: Boolean,
    val publishedAt: String?,
    val notes: String,
    /** Download link of `flux-release.json`, null for releases made before the updater. */
    val manifestUrl: String?,
)

interface ReleaseSource {
    /** Manifest of the newest full release (no test versions). */
    fun latest(): ReleaseManifest
    /** Releases, newest first (test versions included; drafts left out). */
    fun all(): List<ReleaseEntry>
    fun manifest(entry: ReleaseEntry): ReleaseManifest
    /** Where [asset] of [tag] is downloaded from. */
    fun assetUrl(tag: String, asset: Asset): String
}

/**
 * Releases of a public GitHub repository. The newest release is read through the plain download
 * link (`releases/latest/download/…`, no rate limit); the list uses the REST API (60 requests an
 * hour per network without a token), only when the user asks for it.
 */
class GitHubReleaseSource(
    private val owner: String,
    private val repo: String,
    private val http: Http,
    private val web: String = "https://github.com",
    private val api: String = "https://api.github.com",
) : ReleaseSource {

    override fun latest(): ReleaseManifest =
        ReleaseManifest.parse(fetch("$web/$owner/$repo/releases/latest/download/${ReleaseManifest.FILE_NAME}"))

    override fun all(): List<ReleaseEntry> {
        val text = fetch("$api/repos/$owner/$repo/releases?per_page=50", mapOf("Accept" to "application/vnd.github+json"))
        val list = runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrNull()
            ?: throw UpdateException(Failure.HTTP, "unexpected release list")
        return list.mapNotNull { it as? JsonObject }.filter { it.bool("draft") != true }.mapNotNull { r ->
            val tag = r.str("tag_name") ?: return@mapNotNull null
            val manifest = (r["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name") == ReleaseManifest.FILE_NAME }?.str("browser_download_url")
            ReleaseEntry(tag, r.str("name") ?: tag, r.bool("prerelease") == true, r.str("published_at"), r.str("body").orEmpty(), manifest)
        }
    }

    override fun manifest(entry: ReleaseEntry): ReleaseManifest {
        val url = entry.manifestUrl ?: throw UpdateException(Failure.NO_RELEASE)
        return ReleaseManifest.parse(fetch(url), prerelease = entry.prerelease)
    }

    override fun assetUrl(tag: String, asset: Asset) = "$web/$owner/$repo/releases/download/$tag/${asset.name}"

    private fun fetch(url: String, headers: Map<String, String> = emptyMap()): String {
        http.get(url, headers).use { r ->
            when {
                r.code == 200 -> return r.text()
                r.code == 404 -> throw UpdateException(Failure.NO_RELEASE)
                (r.code == 403 || r.code == 429) && (r.headers["x-ratelimit-remaining"] == "0" || r.code == 429) ->
                    throw UpdateException(Failure.RATE_LIMITED)
                else -> throw UpdateException(Failure.HTTP, "HTTP ${r.code}")
            }
        }
    }
}
