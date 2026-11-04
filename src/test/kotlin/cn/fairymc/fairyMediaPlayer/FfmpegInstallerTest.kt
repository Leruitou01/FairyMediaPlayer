package cn.fairymc.fairyMediaPlayer

import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FfmpegInstallerTest {
    @TempDir lateinit var folder: File
    private val windows = FfmpegInstaller.platform("Windows 11", "amd64")
    private fun settings() = PackSettings("ffmpeg", "ffprobe", 30, 75, false, "127.0.0.1", 8163,
        "https://example.com/pack.zip", false, 120)
    private fun zip(names: List<String> = listOf("build/bin/ffmpeg.exe", "build/bin/ffprobe.exe", "build/LICENSE")): File {
        val archive = File(folder, "fixture.zip")
        ZipOutputStream(archive.outputStream()).use { out -> names.forEach {
            out.putNextEntry(ZipEntry(it)); out.write("test executable".toByteArray()); out.closeEntry()
        } }
        return archive
    }
    private fun asset(file: File) = FfmpegAsset(file.name, URI("https://example.com/tools"), ResourcePackBuilder.digest(file, "SHA-256"))
    @Test fun `installs both tools and reuses offline without fetching metadata`() {
        val archive = zip()
        FfmpegInstaller(folder, platformProvider = { windows }, assetProvider = { asset(archive) },
            downloader = { _, target, _ -> archive.copyTo(target, true) }).use {
            val paths = it.resolve(settings())
            assertTrue(File(paths.first).isFile); assertTrue(File(paths.second).isFile)
            assertTrue(File(File(paths.first).parentFile, "LICENSE").isFile)
        }
        FfmpegInstaller(folder, platformProvider = { windows }, assetProvider = { error("must remain offline") }).use {
            assertTrue(File(it.resolve(settings()).first).canExecute())
        }
    }
    @Test fun `checksum failure never installs tools`() {
        val archive = zip()
        FfmpegInstaller(folder, platformProvider = { windows }, assetProvider = { asset(archive).copy(sha256 = "0".repeat(64)) },
            downloader = { _, target, _ -> archive.copyTo(target, true) }).use {
            assertThrows(IllegalStateException::class.java) { it.resolve(settings()) }
        }
        assertFalse(File(folder, "tools/ffmpeg/windows-x64/complete.txt").exists())
    }
    @Test fun `partial archive is never marked installed`() {
        val archive = zip(listOf("bin/ffmpeg.exe"))
        FfmpegInstaller(folder, platformProvider = { windows }, assetProvider = { asset(archive) },
            downloader = { _, target, _ -> archive.copyTo(target, true) }).use {
            assertThrows(IllegalStateException::class.java) { it.resolve(settings()) }
        }
        assertFalse(File(folder, "tools/ffmpeg/windows-x64/complete.txt").exists())
    }
    @Test fun `path traversal is rejected`() {
        val archive = zip(listOf("../ffmpeg.exe", "bin/ffprobe.exe"))
        FfmpegInstaller(folder, platformProvider = { windows }, assetProvider = { asset(archive) },
            downloader = { _, target, _ -> archive.copyTo(target, true) }).use {
            assertThrows(IllegalArgumentException::class.java) { it.resolve(settings()) }
        }
        assertFalse(File(folder, "tools/ffmpeg/ffmpeg.exe").exists())
    }
    @Test fun `manual paths and disabled mode do not require supported platform or network`() {
        FfmpegInstaller(folder, platformProvider = { error("no detection expected") }).use {
            assertEquals("ffmpeg" to "ffprobe", it.resolve(settings().copy(autoDownload = false)))
            assertEquals("/my/ffmpeg" to "/my/ffprobe", it.resolve(settings().copy(ffmpeg = "/my/ffmpeg", ffprobe = "/my/ffprobe")))
        }
    }
    @Test fun `platform selection rejects unsupported architectures`() {
        assertEquals("linux-x64", FfmpegInstaller.platform("Linux", "x86_64").key)
        assertEquals("linux-arm64", FfmpegInstaller.platform("Linux", "aarch64").key)
        assertThrows(IllegalStateException::class.java) { FfmpegInstaller.platform("Windows 11", "arm64") }
        assertThrows(IllegalStateException::class.java) { FfmpegInstaller.platform("Mac OS X", "x86_64") }
    }
    @Test fun `linux tar xz extraction installs executable pair`() {
        val platform = FfmpegInstaller.platform("Linux", "aarch64")
        val archive = File(folder, "fixture.tar.xz")
        TarArchiveOutputStream(XZOutputStream(archive.outputStream(), LZMA2Options())).use { tar ->
            listOf("bin/ffmpeg", "bin/ffprobe", "LICENSE.txt").forEach { name ->
                val bytes = "tool contents".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("build/$name").apply { size = bytes.size.toLong() })
                tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        FfmpegInstaller(folder, platformProvider = { platform }, assetProvider = { asset(archive) },
            downloader = { _, target, _ -> archive.copyTo(target, true) }).use {
            val paths = it.resolve(settings())
            assertEquals("tool contents", File(paths.first).readText())
            assertTrue(File(paths.second).canExecute())
        }
    }
    @Test fun `http download resumes partial bytes and follows redirect`() {
        val bytes = "complete download".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { e -> e.responseHeaders.set("Location", "/file"); e.sendResponseHeaders(302, -1); e.close() }
        server.createContext("/file") { e ->
            val start = e.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (start > 0) e.responseHeaders.set("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
            e.sendResponseHeaders(if (start > 0) 206 else 200, (bytes.size - start).toLong())
            e.responseBody.use { it.write(bytes, start, bytes.size - start) }; e.close()
        }
        server.start()
        try {
            val target = File(folder, "partial").apply { writeBytes(bytes.copyOfRange(0, 4)) }
            FfmpegInstaller(folder).use { it.download(URI("http://127.0.0.1:${server.address.port}/redirect"), target, 30) }
            assertArrayEquals(bytes, target.readBytes())
        } finally { server.stop(0) }
    }
    @Test fun `closed installer cannot download or install`() {
        val installer = FfmpegInstaller(folder)
        installer.close()
        assertThrows(IllegalStateException::class.java) { installer.resolve(settings()) }
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "FMP_FFMPEG_ARCHIVE", matches = ".+")
    fun `verified real windows archive installs and runs`() {
        val archive = File(System.getenv("FMP_FFMPEG_ARCHIVE"))
        FfmpegInstaller(folder, platformProvider = { windows }, downloader = { _, target, _ -> archive.copyTo(target, true) }).use {
            val paths = it.resolve(settings())
            for (path in listOf(paths.first, paths.second)) {
                val process = ProcessBuilder(path, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
                assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            }
        }
    }
}
