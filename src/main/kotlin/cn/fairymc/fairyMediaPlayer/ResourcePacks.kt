package cn.fairymc.fairyMediaPlayer

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AudioSegment(val key: String, val startSeconds: Double, val endSeconds: Double)

data class BuiltPack(val library: Library, val file: File, val sha1: String,
                     val segments: Map<String, List<AudioSegment>> = emptyMap()) {
    val id: UUID = UUID.randomUUID()
    val url: String = java.net.URI(library.pack.publicUrl.replace("{sha1}", sha1)).toASCIIString()
}

class ResourcePackBuilder(private val folder: File, log: (String) -> Unit = {}) : AutoCloseable {
    private val installer = FfmpegInstaller(folder, log)
    @Volatile private var process: Process? = null
    @Volatile private var closed = false

    fun build(source: Library): BuiltPack {
        check(!closed) { "转换已取消" }
        val work = File(folder, "cache").apply { mkdirs() }
        val output = File(folder, "packs").apply { mkdirs() }
        val converted = linkedMapOf<String, File>()
        val segmentFiles = linkedMapOf<String, File>()
        val segments = linkedMapOf<String, List<AudioSegment>>()
        val tools = if (source.songs.isNotEmpty()) installer.resolve(source.pack) else source.pack.ffmpeg to source.pack.ffprobe
        val songs = source.songs.mapValues { (_, song) ->
            check(!closed) { "转换已取消" }
            val key = digest(song.file, "SHA-256")
            val ogg = File(work, "$key-stereo-v1.ogg")
            if (!ogg.isFile) {
                val temp = File.createTempFile("convert-", ".ogg", work)
                try {
                    run(listOf(tools.first, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-i", song.file.absolutePath, "-map", "0:a:0", "-vn", "-ac", "2", "-ar", "48000",
                        "-c:a", "libvorbis", "-q:a", "5", "-map_metadata", "-1", temp.absolutePath), source.pack.timeoutSeconds, work)
                    Files.move(temp.toPath(), ogg.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { temp.delete() }
            }
            val duration = run(listOf(tools.second, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", ogg.absolutePath), source.pack.timeoutSeconds, work).trim().toDoubleOrNull()
            require(duration != null && duration.isFinite() && duration > 0 && duration <= 86400) { "无法读取有效时长：${song.file.name}" }
            converted[song.id] = ogg
            // One encode produces short, independently playable files; storage stays linear in song length.
            val segmentFolder = File(work, "$key-sync-2s-v1")
            if (!File(segmentFolder, "segments.csv").isFile) {
                val staging = Files.createTempDirectory(work.toPath(), "segments-").toFile()
                try {
                    run(listOf(tools.first, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-i", ogg.absolutePath, "-map", "0:a:0", "-ac", "2", "-ar", "48000",
                        "-c:a", "libvorbis", "-q:a", "5", "-map_metadata", "-1", "-f", "segment",
                        "-segment_time", "2", "-reset_timestamps", "1", "-segment_format", "ogg",
                        "-segment_list", File(staging, "segments.csv").absolutePath,
                        "-segment_list_type", "csv", File(staging, "%06d.ogg").absolutePath), source.pack.timeoutSeconds, work)
                    Files.move(staging.toPath(), segmentFolder.toPath())
                } finally { staging.deleteRecursively() }
            }
            val rows = File(segmentFolder, "segments.csv").readLines().filter { it.isNotBlank() }
            segments[song.id] = rows.mapIndexed { index, row ->
                val fields = row.split(',')
                require(fields.size == 3) { "同步音频索引无效：${song.id}" }
                val file = File(segmentFolder, fields[0].trim('"'))
                require(file.isFile && file.canonicalFile.parentFile == segmentFolder.canonicalFile) { "同步音频缺失：${song.id}" }
                val soundKey = "sync/${song.id}/$index"
                segmentFiles[soundKey] = file
                val start = if (index == 0) 0.0 else fields[1].toDouble()
                val end = if (index == rows.lastIndex) duration else fields[2].toDouble().coerceAtMost(duration)
                require(start.isFinite() && end.isFinite() && start >= 0 && end > start) { "同步音频时长无效：${song.id}" }
                AudioSegment(soundKey, start, end)
            }
            require(segments.getValue(song.id).isNotEmpty()) { "同步音频为空：${song.id}" }
            song.copy(duration = duration)
        }
        val ready = source.copy(songs = songs)
        val tempZip = File.createTempFile("pack-", ".zip", output)
        try {
            ZipOutputStream(tempZip.outputStream().buffered()).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                val gson = Gson()
                entry("pack.mcmeta", gson.toJson(mapOf("pack" to mapOf(
                    "description" to "FairyMediaPlayer 音乐资源包", "min_format" to source.pack.format,
                    "max_format" to source.pack.format))))
                entry("assets/fairymediaplayer/sounds.json", gson.toJson((songs.keys + segmentFiles.keys).associateWith { id ->
                    mapOf("sounds" to listOf(mapOf("name" to "fairymediaplayer:music/$id", "stream" to true)))
                }))
                (converted + segmentFiles).forEach { (id, file) ->
                    check(!closed) { "打包已取消" }
                    zip.putNextEntry(ZipEntry("assets/fairymediaplayer/sounds/music/$id.ogg").apply { time = 0 })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            val hash = digest(tempZip, "SHA-1")
            val destination = File(output, "$hash.zip")
            // Content-addressed files remain available to clients already downloading an older pack.
            if (!destination.exists()) Files.move(tempZip.toPath(), destination.toPath())
            return BuiltPack(ready, destination, hash, segments)
        } finally { tempZip.delete() }
    }

    private fun run(arguments: List<String>, timeout: Long, work: File): String {
        check(!closed) { "转换已取消" }
        val log = File.createTempFile("process-", ".log", work)
        try {
            val child = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log).start()
            process = child
            try {
                if (closed) { child.destroyForcibly(); error("转换已取消") }
                check(child.waitFor(timeout, TimeUnit.SECONDS)) { "执行超时：${arguments.first()}" }
                val result = log.inputStream().use { String(it.readNBytes(16384), Charsets.UTF_8) }
                check(child.exitValue() == 0) { "${arguments.first()} 执行失败：$result" }
                return result
            } finally {
                if (child.isAlive) child.destroyForcibly()
                process = null
            }
        } finally { log.delete() }
    }

    override fun close() { closed = true; installer.close(); process?.destroyForcibly() }

    companion object {
        fun digest(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(65536)
                while (true) { val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Serves only generated hash-named ZIPs. No directory listing or arbitrary paths. */
class PackHttpServer(folder: File, settings: PackSettings) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "FairyMediaPlayer-HTTP").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(settings.bind, settings.port), 32)
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                if (!Regex("/[a-f0-9]{40}\\.zip").matches(path)) {
                    exchange.sendResponseHeaders(404, -1)
                } else if (exchange.requestMethod !in setOf("GET", "HEAD")) {
                    exchange.responseHeaders.set("Allow", "GET, HEAD")
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    val file = File(File(folder, "packs"), path.substring(1))
                    if (!file.isFile) exchange.sendResponseHeaders(404, -1)
                    else {
                        exchange.responseHeaders.set("Content-Type", "application/zip")
                        exchange.responseHeaders.set("Cache-Control", "public, max-age=31536000, immutable")
                        exchange.responseHeaders.set("Content-Length", file.length().toString())
                        if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
                        else {
                            exchange.sendResponseHeaders(200, file.length())
                            file.inputStream().use { it.copyTo(exchange.responseBody) }
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // The client may disconnect during download.
            } finally { exchange.close() }
        }
        server.start()
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
}
