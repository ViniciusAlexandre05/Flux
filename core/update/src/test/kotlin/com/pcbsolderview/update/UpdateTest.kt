package com.pcbsolderview.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

class VersionTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test
    fun ordersNumericallyAndPutsTestVersionsFirst() {
        assertTrue(v("0.1.10") > v("0.1.9"))
        assertEquals(0, v("0.1").compareTo(v("0.1.0")))
        assertTrue(v("0.1.3-test1") < v("0.1.3"))
        assertTrue(v("0.1.3-test2") < v("0.1.3-test10"))
        assertTrue(v("0.1.3-test1") > v("0.1.2"))
        assertEquals("0.1.3-test1", v("v0.1.3-test1").toString())
        assertTrue(v("0.1.3-test1").isTest)
        assertNull(Version.parse("abc"))
        assertNull(Version.parse("0.1.x"))
    }
}

class ManifestTest {
    @Test
    fun parsesBothPartsAndIgnoresUnknownKeys() {
        val m = ReleaseManifest.parse("﻿" + FakeGitHub.manifest("v0.1.3", 7, "apk".toByteArray(), desktop = "0.1.3"))
        assertEquals("v0.1.3", m.tag)
        assertEquals(7, m.android!!.versionCode)
        assertEquals("0.1.3", m.android!!.versionName)
        assertEquals(3, m.android!!.apk.size)
        assertEquals("0.1.3", m.desktop!!.version)
        assertNotNull(m.desktop!!.msi)
        assertNull(m.desktop!!.portable)
        assertEquals("What is new in v0.1.3", m.notes)
    }

    @Test
    fun partsMayBeMissing() {
        val pcOnly = ReleaseManifest.parse(FakeGitHub.manifest("v0.1.4", null, desktop = "0.1.4"))
        assertNull(pcOnly.android)
        val androidOnly = ReleaseManifest.parse(FakeGitHub.manifest("v0.1.4", 8))
        assertNull(androidOnly.desktop)
        // An Android part with a bad checksum or a path in the file name is not used.
        val bad = FakeGitHub.manifest("v0.1.4", 8).replace("Flux-8.apk", "../x.apk")
        assertNull(ReleaseManifest.parse(bad).android)
    }

    @Test
    fun rejectsBrokenOrNewerFiles() {
        assertEquals(Failure.BAD_MANIFEST, assertThrows<UpdateException> { ReleaseManifest.parse("<html>") }.failure)
        assertEquals(Failure.BAD_MANIFEST, assertThrows<UpdateException> { ReleaseManifest.parse("""{"tag":"v1"}""") }.failure)
        assertEquals(Failure.NEWER_FORMAT, assertThrows<UpdateException> { ReleaseManifest.parse("""{"schema":2,"tag":"v1"}""") }.failure)
    }

    /** The manifest written by scripts/release.ps1 (PowerShell's JSON) is read like ours. */
    @Test
    fun readsTheReleaseScriptOutput() {
        val text = javaClass.getResource("/flux-release.json")!!.readText()
        val m = ReleaseManifest.parse(text)
        assertNotNull(m.android)
        assertTrue(m.notes.isNotBlank())
    }
}

class CheckerTest {
    private fun fake(vararg r: FakeGitHub.Release) = FakeGitHub().apply { releases += r }

    @Test
    fun latestFullReleaseIsOfferedWhenNewer() = fake(
        FakeGitHub.Release("v0.1.4-test1", prerelease = true, manifest = FakeGitHub.manifest("v0.1.4-test1", 9)),
        FakeGitHub.Release("v0.1.3", manifest = FakeGitHub.manifest("v0.1.3", 7)),
    ).use { gh ->
        val s = UpdateChecker(gh.source(), AndroidPlatform(installedCode = 6, sdk = 33)).check(includeTest = false)
        assertEquals(7, (s as UpdateState.Available).release.android!!.versionCode)
        // Only the plain download link was used, not the rate-limited API.
        assertTrue(gh.requests.none { it.startsWith("/api/") }, gh.requests.toString())

        val t = UpdateChecker(gh.source(), AndroidPlatform(6, 33)).check(includeTest = true)
        assertEquals(9, (t as UpdateState.Available).release.android!!.versionCode)
        assertTrue(t.release.prerelease)

        assertEquals(UpdateState.UpToDate, UpdateChecker(gh.source(), AndroidPlatform(7, 33)).check(false))
    }

    @Test
    fun skipsReleasesWithoutAnAndroidPartOrForNewerAndroid() = fake(
        FakeGitHub.Release("v0.1.5", manifest = FakeGitHub.manifest("v0.1.5", null, desktop = "0.1.5")),
        FakeGitHub.Release("v0.1.4", manifest = FakeGitHub.manifest("v0.1.4", 9, minSdk = 40)),
        FakeGitHub.Release("v0.1.3", manifest = FakeGitHub.manifest("v0.1.3", 7)),
        FakeGitHub.Release("v0.1.2"), // before the updater: no manifest
    ).use { gh ->
        val s = UpdateChecker(gh.source(), AndroidPlatform(6, 33)).check(false)
        assertEquals("v0.1.3", (s as UpdateState.Available).release.tag)
        val pc = UpdateChecker(gh.source(), DesktopPlatform(Version.parse("0.1.2")!!)).check(false)
        assertEquals("v0.1.5", (pc as UpdateState.Available).release.tag)
    }

    @Test
    fun listsEveryVersionWithItsRelation() = fake(
        FakeGitHub.Release("v0.1.4-test1", prerelease = true, manifest = FakeGitHub.manifest("v0.1.4-test1", 8)),
        FakeGitHub.Release("v0.1.3", manifest = FakeGitHub.manifest("v0.1.3", 7)),
        FakeGitHub.Release("v0.1.2.1", manifest = FakeGitHub.manifest("v0.1.2.1", 6)),
        FakeGitHub.Release("v0.1.2"),
    ).use { gh ->
        val c = UpdateChecker(gh.source(), AndroidPlatform(7, 33))
        assertEquals(listOf("v0.1.3" to Relation.INSTALLED, "v0.1.2.1" to Relation.OLDER), c.list(false).map { it.entry.tag to it.relation })
        assertEquals(Relation.NEWER, c.list(true).first().relation)
    }

    @Test
    fun reportsOfflineAndRateLimits() {
        val offline = UpdateChecker(GitHubReleaseSource("o", "r", UrlConnectionHttp(connectTimeoutMs = 2000), web = "http://127.0.0.1:1", api = "http://127.0.0.1:1"), AndroidPlatform(6, 33)).check(false)
        assertEquals(Failure.OFFLINE, (offline as UpdateState.Failed).failure)

        fake(FakeGitHub.Release("v0.1.2")).use { gh ->
            gh.rateLimited = true
            val s = UpdateChecker(gh.source(), AndroidPlatform(6, 33)).check(false)
            assertEquals(Failure.RATE_LIMITED, (s as UpdateState.Failed).failure)
            assertTrue(s.message.isNotBlank())
        }
        fake().use { gh ->
            val s = UpdateChecker(gh.source(), AndroidPlatform(6, 33)).check(false)
            assertEquals(Failure.NO_RELEASE, (s as UpdateState.Failed).failure)
        }
    }
}

class DownloaderTest {
    @TempDir lateinit var dir: File
    private val apk = Random(1).nextBytes(700_000)
    private val asset = Asset("Flux-7.apk", apk.size.toLong(), FakeGitHub.sha(apk))
    private fun gh() = FakeGitHub().apply { releases += FakeGitHub.Release("v0.1.3", manifest = FakeGitHub.manifest("v0.1.3", 7, apk), files = mapOf("Flux-7.apk" to apk)) }

    @Test
    fun downloadsFollowingRedirectsAndChecksTheHash() = gh().use { gh ->
        val src = gh.source()
        val m = src.latest()
        val dest = File(dir, "Flux-7.apk")
        var last = 0L
        runBlocking { Downloader(UrlConnectionHttp()).download(src.assetUrl(m.tag, m.android!!.apk), dest, m.android!!.apk) { d, _ -> last = d } }
        assertTrue(dest.readBytes().contentEquals(apk))
        assertEquals(apk.size.toLong(), last)
        assertFalse(File(dir, "Flux-7.apk.part").exists())
    }

    @Test
    fun resumesAnInterruptedDownload() = gh().use { gh ->
        val url = gh.source().assetUrl("v0.1.3", asset)
        val dest = File(dir, "Flux-7.apk")
        gh.cutAfter = 300_000
        val first = assertThrows<UpdateException> { runBlocking { Downloader(UrlConnectionHttp()).download(url, dest, asset) } }
        assertEquals(Failure.OFFLINE, first.failure)
        assertTrue(File(dir, "Flux-7.apk.part").length() in 1..apk.size.toLong() - 1)
        runBlocking { Downloader(UrlConnectionHttp()).download(url, dest, asset) }
        assertTrue(dest.readBytes().contentEquals(apk))
        assertTrue(gh.requests.any { "Range" in it || "bytes=" in it }, gh.requests.toString())
    }

    @Test
    fun startsOverWhenTheServerIgnoresRange() = gh().use { gh ->
        gh.ignoreRange = true
        File(dir, "Flux-7.apk.part").writeBytes(apk.copyOf(1000))
        val dest = File(dir, "Flux-7.apk")
        runBlocking { Downloader(UrlConnectionHttp()).download(gh.source().assetUrl("v0.1.3", asset), dest, asset) }
        assertTrue(dest.readBytes().contentEquals(apk))
    }

    @Test
    fun deletesADamagedDownload() = gh().use { gh ->
        val wrong = asset.copy(sha256 = "0".repeat(64))
        val dest = File(dir, "Flux-7.apk")
        val e = assertThrows<UpdateException> { runBlocking { Downloader(UrlConnectionHttp()).download(gh.source().assetUrl("v0.1.3", wrong), dest, wrong) } }
        assertEquals(Failure.CHECKSUM, e.failure)
        assertFalse(dest.exists())
        assertFalse(File(dir, "Flux-7.apk.part").exists())
    }

    @Test
    fun cancellingKeepsThePartForLater() = gh().use { gh ->
        val dest = File(dir, "Flux-7.apk")
        assertThrows<CancellationException> {
            runBlocking {
                Downloader(UrlConnectionHttp()).download(gh.source().assetUrl("v0.1.3", asset), dest, asset) { d, _ ->
                    if (d > 0 && d < apk.size) throw CancellationException("user cancelled")
                }
            }
        }
        assertFalse(dest.exists())
    }
}

class ReleaseNotesTest {
    @Test
    fun joinsWrappedLinesAndDropsMarkdown() {
        val md = "Intro line.\n\n- The **Board** and\n  **Schematic** buttons.\n- Keys `1` and `2`.\n### Files\n- a"
        assertEquals("Intro line.\n\n- The Board and Schematic buttons.\n- Keys 1 and 2.\nFiles\n- a", ReleaseNotes.plain(md))
    }
}
