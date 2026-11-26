package com.pcbsolderview.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One downloadable file of a release, as named in `flux-release.json`. */
data class Asset(val name: String, val size: Long, val sha256: String)

data class AndroidRelease(val versionCode: Int, val versionName: String, val minSdk: Int, val apk: Asset)

data class DesktopRelease(val version: String, val msi: Asset?, val portable: Asset?)

/**
 * `flux-release.json`, attached to every GitHub release made by `scripts/release.ps1`. A release
 * may carry only the Android part or only the PC part. [prerelease] comes from GitHub, not the file.
 */
data class ReleaseManifest(
    val schema: Int,
    val tag: String,
    val date: String?,
    val notes: String,
    val android: AndroidRelease?,
    val desktop: DesktopRelease?,
    val prerelease: Boolean = false,
) {
    companion object {
        const val FILE_NAME = "flux-release.json"
        /** Newest manifest format this app understands. */
        const val SCHEMA = 1

        /** Parses a manifest; throws [UpdateException] ([Failure.BAD_MANIFEST]) when it can't be used. */
        fun parse(text: String, prerelease: Boolean = false): ReleaseManifest {
            val root = try {
                Json.parseToJsonElement(text.removePrefix("﻿")).jsonObject
            } catch (e: Exception) {
                throw UpdateException(Failure.BAD_MANIFEST, "not JSON: ${e.message}")
            }
            val schema = root.int("schema") ?: throw UpdateException(Failure.BAD_MANIFEST, "no schema")
            if (schema > SCHEMA) throw UpdateException(Failure.NEWER_FORMAT, "schema $schema")
            val tag = root.str("tag") ?: throw UpdateException(Failure.BAD_MANIFEST, "no tag")
            val android = (root["android"] as? JsonObject)?.let { a ->
                val code = a.int("versionCode") ?: return@let null
                val name = a.str("versionName") ?: return@let null
                val apk = asset(a.str("asset"), a) ?: return@let null
                AndroidRelease(code, name, a.int("minSdk") ?: 1, apk)
            }
            val desktop = (root["desktop"] as? JsonObject)?.let { d ->
                val version = d.str("version") ?: return@let null
                fun part(k: String) = (d[k] as? JsonObject)?.let { asset(it.str("asset"), it) }
                DesktopRelease(version, part("msi"), part("portable")).takeIf { it.msi != null || it.portable != null }
            }
            return ReleaseManifest(schema, tag, root.str("date"), root.str("notes").orEmpty(), android, desktop, prerelease)
        }

        private fun asset(name: String?, o: JsonObject): Asset? {
            val size = o.long("size") ?: return null
            val sha = o.str("sha256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            return Asset(name?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it } ?: return null, size, sha)
        }
    }
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement.objOrNull() = this as? JsonObject
