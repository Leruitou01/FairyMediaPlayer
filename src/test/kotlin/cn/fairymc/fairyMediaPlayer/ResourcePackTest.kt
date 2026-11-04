package cn.fairymc.fairyMediaPlayer

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class ResourcePackTest {
    @TempDir lateinit var folder: File
    private fun settings(port: Int = 8163) = PackSettings(
        System.getenv("FMP_FFMPEG") ?: "ffmpeg", System.getenv("FMP_FFPROBE") ?: "ffprobe", 30, 75,
        true, "127.0.0.1", port, "http://127.0.0.1:$port/{sha1}.zip", false, 120)

    @Test fun `empty library produces valid zip and matching hash`() {
        ResourcePackBuilder(folder).use { builder ->
            val pack = builder.build(Library(emptyMap(), emptyMap(), settings(), null, 1))
            assertEquals(pack.sha1, ResourcePackBuilder.digest(pack.file, "SHA-1"))
            ZipFile(pack.file).use { zip ->
                val metadata = JsonParser.parseString(zip.getInputStream(zip.getEntry("pack.mcmeta")).reader().readText()).asJsonObject
                assertEquals(75, metadata.getAsJsonObject("pack").get("min_format").asInt)
                assertNotNull(zip.getEntry("assets/fairymediaplayer/sounds.json"))
            }
        }
    }
    @Test fun `http serves hash packs and rejects arbitrary paths and post`() {
        val port = ServerSocket(0).use { it.localPort }
        ResourcePackBuilder(folder).use { builder ->
            val pack = builder.build(Library(emptyMap(), emptyMap(), settings(port), null, 1))
            PackHttpServer(folder, settings(port)).use {
                HttpClient.newHttpClient().use { client ->
                    fun request(path: String, method: String = "GET") = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray())
                    val response = request("/${pack.sha1}.zip")
                    assertEquals(200, response.statusCode()); assertArrayEquals(pack.file.readBytes(), response.body())
                    val head = request("/${pack.sha1}.zip", "HEAD")
                    assertEquals(200, head.statusCode()); assertEquals(0, head.body().size)
                    assertEquals(405, request("/${pack.sha1}.zip", "POST").statusCode())
                    assertEquals(404, request("/config.yml").statusCode())
                    assertEquals(404, request("/../config.yml").statusCode())
                    assertEquals(404, request("/${"b".repeat(40)}.zip").statusCode())
                }
            }
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "FMP_FFMPEG", matches = ".+")
    fun `real mp3 converts to stereo vorbis and cache can be reused`() {
        val settings = settings()
        val songFile = File(folder, "test tone.mp3")
        val log = File(folder, "generate.log")
        val child = ProcessBuilder(settings.ffmpeg, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=6.3",
            "-c:a", "libmp3lame", songFile.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(child.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), log.readText())
        val song = Song("tone", "Tone", songFile, true, "", 1f)
        val library = Library(mapOf("tone" to song), emptyMap(), settings, null, 1)
        ResourcePackBuilder(folder).use { builder ->
            val pack = builder.build(library)
            assertTrue(pack.library.songs.getValue("tone").duration in 6.25..6.5)
            val parts = pack.segments.getValue("tone")
            assertTrue(parts.size >= 4)
            assertEquals(0.0, parts.first().startSeconds)
            assertEquals(pack.library.songs.getValue("tone").duration, parts.last().endSeconds)
            parts.zipWithNext().forEach { (a, b) -> assertEquals(a.endSeconds, b.startSeconds, 0.0001) }
            ZipFile(pack.file).use { zip ->
                val entry = zip.getEntry("assets/fairymediaplayer/sounds/music/tone.ogg")
                assertNotNull(entry)
                val bytes = zip.getInputStream(entry).readBytes()
                assertEquals("OggS", String(bytes.copyOfRange(0, 4)))
                val ogg = File(folder, "check.ogg").apply { writeBytes(bytes) }
                val probe = ProcessBuilder(settings.ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries",
                    "stream=codec_name,channels", "-of", "json", ogg.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
                assertTrue(probe.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, probe.exitValue())
                val stream = JsonParser.parseString(log.readText()).asJsonObject.getAsJsonArray("streams")[0].asJsonObject
                assertEquals("vorbis", stream.get("codec_name").asString)
                assertEquals(2, stream.get("channels").asInt)
                val sounds = JsonParser.parseString(zip.getInputStream(zip.getEntry("assets/fairymediaplayer/sounds.json")).reader().readText()).asJsonObject
                assertTrue(sounds.getAsJsonObject("tone").getAsJsonArray("sounds")[0].asJsonObject.get("stream").asBoolean)
                parts.forEachIndexed { index, part ->
                    assertNotNull(sounds.getAsJsonObject(part.key))
                    val fragment = zip.getEntry("assets/fairymediaplayer/sounds/music/${part.key}.ogg")
                    assertNotNull(fragment)
                    val audio = zip.getInputStream(fragment).readBytes()
                    assertEquals("OggS", String(audio.copyOfRange(0, 4)))
                    val check = File(folder, "fragment-$index.ogg").apply { writeBytes(audio) }
                    val checkLog = File(folder, "fragment-$index.json")
                    val decode = ProcessBuilder(settings.ffprobe, "-v", "error", "-show_entries",
                        "format=duration:stream=codec_name,channels", "-of", "json", check.absolutePath)
                        .redirectErrorStream(true).redirectOutput(checkLog).start()
                    assertTrue(decode.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, decode.exitValue())
                    val metadata = JsonParser.parseString(checkLog.readText()).asJsonObject
                    val info = metadata.getAsJsonArray("streams")[0].asJsonObject
                    assertEquals("vorbis", info.get("codec_name").asString)
                    assertEquals(2, info.get("channels").asInt)
                    assertEquals(part.endSeconds - part.startSeconds,
                        metadata.getAsJsonObject("format").get("duration").asDouble, 0.06)
                }
            }
            val cached = builder.build(library.copy(pack = settings.copy(ffmpeg = "this-executable-does-not-exist")))
            assertEquals(pack.sha1, cached.sha1)
        }
    }
}
