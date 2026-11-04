package cn.fairymc.fairyMediaPlayer

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI

enum class MediaType { SONG, PLAYLIST }
enum class RepeatMode { ONCE, LOOP }
data class MediaRequest(val type: MediaType, val id: String, val repeat: RepeatMode? = null, val locked: Boolean = false)
data class Song(val id: String, val name: String, val file: File, val selectable: Boolean,
                val permission: String, val volume: Float, val duration: Double = 0.0)
data class Playlist(val id: String, val name: String, val songs: List<String>, val selectable: Boolean,
                    val permission: String, val repeat: RepeatMode)
data class PackSettings(val ffmpeg: String, val ffprobe: String, val timeoutSeconds: Long,
                        val format: Int, val httpEnabled: Boolean, val bind: String, val port: Int,
                        val publicUrl: String, val required: Boolean, val loadTimeoutSeconds: Long,
                        val autoDownload: Boolean = true, val downloadTimeoutSeconds: Long = 1800)
data class Library(val songs: Map<String, Song>, val playlists: Map<String, Playlist>,
                   val pack: PackSettings, val autoPlay: MediaRequest?, val joinDelayTicks: Long) {
    fun resolve(request: MediaRequest): Pair<List<Song>, RepeatMode> = when (request.type) {
        MediaType.SONG -> {
            val song = songs[request.id] ?: error("歌曲不存在：${request.id}")
            listOf(song) to (request.repeat ?: RepeatMode.ONCE)
        }
        MediaType.PLAYLIST -> {
            val list = playlists[request.id] ?: error("歌单不存在：${request.id}")
            list.songs.map { songs.getValue(it) } to (request.repeat ?: list.repeat)
        }
    }
    companion object {
        private val idPattern = Regex("[a-z0-9_-]+")
        fun read(folder: File): Library {
            val c = YamlConfiguration()
            c.load(File(folder, "config.yml"))
            fun repeat(path: String) = when (c.getString(path, "once")!!.lowercase()) {
                "once" -> RepeatMode.ONCE
                "loop" -> RepeatMode.LOOP
                else -> error("$path 必须是 once 或 loop")
            }
            fun id(value: String) { require(idPattern.matches(value)) { "ID 仅允许小写字母、数字、下划线、横线：$value" } }
            val musicRoot = File(folder, "music").canonicalFile
            val songs = c.getConfigurationSection("songs")?.getKeys(false).orEmpty().associateWith { key ->
                id(key)
                val p = "songs.$key"
                val relative = c.getString("$p.file") ?: error("缺少 $p.file")
                val file = File(musicRoot, relative).canonicalFile
                require(file.toPath().startsWith(musicRoot.toPath()) && file.isFile) { "歌曲必须是 music 目录内存在的文件：$relative" }
                require(file.extension.equals("mp3", true)) { "歌曲源文件必须是 MP3：$relative" }
                val volume = c.getDouble("$p.volume", 1.0)
                require(volume.isFinite() && volume > 0 && volume <= 1) { "$p.volume 必须大于 0 且不超过 1" }
                Song(key, c.getString("$p.name", key)!!, file, c.getBoolean("$p.selectable", true),
                    c.getString("$p.permission", "")!!, volume.toFloat())
            }
            val playlists = c.getConfigurationSection("playlists")?.getKeys(false).orEmpty().associateWith { key ->
                id(key)
                val p = "playlists.$key"
                val tracks = c.getStringList("$p.songs")
                require(tracks.isNotEmpty()) { "歌单不能为空：$key" }
                tracks.forEach { require(it in songs) { "歌单 $key 引用了不存在的歌曲：$it" } }
                Playlist(key, c.getString("$p.name", key)!!, tracks.toList(), c.getBoolean("$p.selectable", true),
                    c.getString("$p.permission", "")!!, repeat("$p.repeat"))
            }
            val url = c.getString("resource-pack.public-url", "http://127.0.0.1:8163/{sha1}.zip")!!
            val uri = URI(url.replace("{sha1}", "0".repeat(40)))
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.fragment == null) { "resource-pack.public-url 必须是 HTTP(S) 下载直链" }
            val pack = PackSettings(c.getString("conversion.ffmpeg", "ffmpeg")!!, c.getString("conversion.ffprobe", "ffprobe")!!,
                c.getLong("conversion.timeout-seconds", 300), c.getInt("resource-pack.format", 75),
                c.getBoolean("resource-pack.http.enabled", true), c.getString("resource-pack.http.bind", "0.0.0.0")!!,
                c.getInt("resource-pack.http.port", 8163), url, c.getBoolean("resource-pack.required", false),
                c.getLong("resource-pack.load-timeout-seconds", 120),
                c.getBoolean("conversion.auto-download", true), c.getLong("conversion.download-timeout-seconds", 1800))
            require(pack.downloadTimeoutSeconds in 30..7200) { "下载超时范围为 30–7200 秒" }
            require(pack.timeoutSeconds in 1..3600 && pack.loadTimeoutSeconds in 10..3600) { "转换超时范围 1–3600 秒，加载超时范围 10–3600 秒" }
            require(pack.port in 1..65535 && pack.format > 0) { "无效的 HTTP 端口或资源包格式版本" }
            if (pack.httpEnabled) require("{sha1}" in url) { "内置 HTTP 模式的 public-url 必须包含 {sha1}" }
            val auto = if (c.getBoolean("auto-play.enabled", false)) {
                val type = when (c.getString("auto-play.type", "playlist")!!.lowercase()) {
                    "song" -> MediaType.SONG
                    "playlist" -> MediaType.PLAYLIST
                    else -> error("auto-play.type 必须是 song 或 playlist")
                }
                MediaRequest(type, c.getString("auto-play.id") ?: error("缺少 auto-play.id"), repeat("auto-play.repeat"))
            } else null
            val delay = c.getLong("auto-play.join-delay-ticks", 20)
            require(delay in 0..1200) { "auto-play.join-delay-ticks 范围为 0–1200" }
            return Library(songs, playlists, pack, auto, delay).also { if (auto != null) it.resolve(auto) }
        }
    }
}

/** Repeat belongs to a playback request or playlist, never to a song definition. */
class PlaybackQueue<T>(val tracks: List<T>, val repeat: RepeatMode) {
    init { require(tracks.isNotEmpty()) }
    var index: Int = 0
        private set
    val current: T get() = tracks[index]
    fun advance(): Boolean {
        if (index + 1 < tracks.size) { index++; return true }
        if (repeat == RepeatMode.LOOP) { index = 0; return true }
        return false
    }
}
