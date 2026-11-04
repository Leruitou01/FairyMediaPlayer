package cn.fairymc.fairyMediaPlayer

import com.google.gson.JsonParser
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

internal data class FfmpegPlatform(val key: String, val extension: String, val linuxAsset: String? = null)
internal data class FfmpegAsset(val name: String, val url: URI, val sha256: String)

/** Runs only on the builder thread. Installs both tools together after archive verification. */
internal class FfmpegInstaller(
    private val folder: File,
    private val log: (String) -> Unit = {},
    private val platformProvider: () -> FfmpegPlatform = { platform(System.getProperty("os.name"), System.getProperty("os.arch")) },
    private val assetProvider: ((FfmpegPlatform) -> FfmpegAsset)? = null,
    private val downloader: ((URI, File, Long) -> Unit)? = null
) : AutoCloseable {
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    private fun checkOpen() { check(!closed && !Thread.currentThread().isInterrupted) { "FFmpeg 下载已取消" } }

    fun resolve(settings: PackSettings): Pair<String, String> {
        checkOpen()
        val needFfmpeg = settings.ffmpeg in setOf("auto", "ffmpeg", "ffmpeg.exe", "")
        val needFfprobe = settings.ffprobe in setOf("auto", "ffprobe", "ffprobe.exe", "")
        if (!settings.autoDownload || (!needFfmpeg && !needFfprobe))
            return (settings.ffmpeg.takeUnless { it == "auto" || it.isBlank() } ?: "ffmpeg") to
                (settings.ffprobe.takeUnless { it == "auto" || it.isBlank() } ?: "ffprobe")
        val platform = platformProvider()
        val root = File(folder, "tools/ffmpeg").apply { mkdirs() }
        val installed = File(root, platform.key)
        require(installed.canonicalFile.parentFile == root.canonicalFile) { "无效的 FFmpeg 安装目录" }
        fun executable(dir: File, name: String) = File(dir, name + platform.extension)
        if (!File(installed, "complete.txt").isFile ||
            !listOf("ffmpeg", "ffprobe").all { executable(installed, it).let { f -> f.isFile && f.length() > 0 && f.canExecute() } }) {
            val asset = assetProvider?.invoke(platform) ?: asset(platform)
            require(Regex("[a-fA-F0-9]{64}").matches(asset.sha256)) { "FFmpeg 下载源没有有效的 SHA-256 校验值" }
            log("首次使用：正在下载 FFmpeg（${platform.key}），请稍候。")
            val archive = File(root, "${platform.key}-${asset.sha256.lowercase()}.download")
            val stage = Files.createTempDirectory(root.toPath(), "install-").toFile()
            try {
                if (archive.isFile && ResourcePackBuilder.digest(archive, "SHA-256") != asset.sha256.lowercase()) archive.delete()
                if (!archive.isFile) {
                    if (downloader != null) downloader.invoke(asset.url, archive, settings.downloadTimeoutSeconds)
                    else download(asset.url, archive, settings.downloadTimeoutSeconds)
                }
                checkOpen()
                check(ResourcePackBuilder.digest(archive, "SHA-256").equals(asset.sha256, true)) { "FFmpeg 下载校验失败，请执行 /fmp reload 重试" }
                extract(archive, stage, platform, asset.name)
                listOf("ffmpeg", "ffprobe").forEach { name ->
                    val file = executable(stage, name)
                    check(file.isFile && file.length() > 0) { "FFmpeg 压缩包缺少 $name" }
                    check(file.setExecutable(true, false) || file.canExecute()) { "无法设置 $name 执行权限，请检查 tools 目录权限" }
                }
                File(stage, "complete.txt").writeText("Source: ${asset.url}\nSHA-256: ${asset.sha256}\n")
                checkOpen()
                // Only a fully verified pair is marked usable; interrupted extraction is never reused.
                if (installed.exists()) installed.deleteRecursively()
                Files.move(stage.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                log("FFmpeg 已就绪：${installed.absolutePath}；以后将直接复用，无需重复下载。")
            } finally {
                stage.deleteRecursively()
                archive.delete()
            }
        }
        return (if (needFfmpeg) executable(installed, "ffmpeg").absolutePath else settings.ffmpeg) to
            (if (needFfprobe) executable(installed, "ffprobe").absolutePath else settings.ffprobe)
    }

    private fun asset(platform: FfmpegPlatform): FfmpegAsset {
        if (platform.key == "windows-x64") return FfmpegAsset("ffmpeg-9.0.2-essentials_build.zip",
            URI("https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip"),
            "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba")
        val metadata = File.createTempFile("ffmpeg-release-", ".json", folder)
        try {
            download(URI("https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest"), metadata, 60, 4L * 1024 * 1024)
            val assets = JsonParser.parseString(metadata.readText()).asJsonObject.getAsJsonArray("assets")
            val match = assets.map { it.asJsonObject }.firstOrNull { it.get("name").asString == platform.linuxAsset }
                ?: error("下载源暂未提供 ${platform.key} 的 FFmpeg，请手动配置路径")
            val digest = match.get("digest")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            check(digest.startsWith("sha256:")) { "下载源缺少 SHA-256，请稍后重试或手动配置路径" }
            val url = URI(match.get("browser_download_url").asString)
            check(url.scheme == "https" && url.host == "github.com") { "无效的 FFmpeg 下载源" }
            return FfmpegAsset(platform.linuxAsset!!, url, digest.removePrefix("sha256:"))
        } finally { metadata.delete() }
    }

    internal fun download(uri: URI, target: File, timeoutSeconds: Long, limit: Long = 512L * 1024 * 1024) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        var last: Exception? = null
        var reported = 0L
        repeat(3) { attempt ->
            checkOpen()
            try {
                check(System.nanoTime() < deadline) { "FFmpeg 下载超时" }
                val offset = target.length()
                val http = uri.toURL().openConnection() as HttpURLConnection
                connection = http
                http.connectTimeout = 15000; http.readTimeout = 20000
                http.setRequestProperty("User-Agent", "FairyMediaPlayer/1.0")
                if (offset > 0) http.setRequestProperty("Range", "bytes=$offset-")
                try {
                    val code = http.responseCode
                    check(code == 200 || code == 206) { "FFmpeg 下载返回 HTTP $code；检查网络后 /fmp reload 重试，或手动配置路径" }
                    val append = code == 206 && offset > 0
                    if (code == 206) check(http.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "下载断点不匹配" }
                    var total = if (append) offset else 0L
                    val expected = http.contentLengthLong.takeIf { it >= 0 }?.plus(total)
                    check(expected == null || expected <= limit) { "FFmpeg 下载文件过大" }
                    http.inputStream.use { input ->
                        java.io.FileOutputStream(target, append).use { output ->
                            val bytes = ByteArray(65536)
                            while (true) {
                                checkOpen(); check(System.nanoTime() < deadline) { "FFmpeg 下载超时" }
                                val n = input.read(bytes); if (n < 0) break
                                total += n; check(total <= limit) { "FFmpeg 下载文件过大" }
                                output.write(bytes, 0, n)
                                if (total - reported >= 10L * 1024 * 1024) {
                                    log("FFmpeg 已下载 ${total / 1024 / 1024} MB"); reported = total
                                }
                            }
                        }
                    }
                    check(expected == null || total == expected) { "FFmpeg 下载中断" }
                    return
                } finally { http.disconnect(); connection = null }
            } catch (e: Exception) {
                last = e; checkOpen()
                if (attempt < 2) log("FFmpeg 下载失败，将尝试续传（${attempt + 1}/2）：${e.message}")
            }
        }
        throw IllegalStateException("FFmpeg 自动下载失败：${last?.message}。可 /fmp reload 重试，或关闭 conversion.auto-download 并填写手动路径。", last)
    }

    private fun extract(archive: File, stage: File, platform: FfmpegPlatform, name: String) {
        val allowed = setOf("ffmpeg${platform.extension}", "ffprobe${platform.extension}", "LICENSE", "LICENSE.txt", "LICENSE.md")
        fun save(path: String, input: InputStream) {
            checkOpen()
            val parts = path.replace('\\', '/').split('/')
            require(".." !in parts && !path.startsWith('/') && ':' !in path) { "不安全的压缩包路径" }
            val base = parts.last()
            if (base !in allowed) return
            val target = File(stage, base)
            check(!target.exists()) { "压缩包包含重复文件：$base" }
            target.outputStream().use { output ->
                val bytes = ByteArray(65536); var total = 0L
                while (true) {
                    checkOpen()
                    val n = input.read(bytes); if (n < 0) break
                    total += n; check(total <= 512L * 1024 * 1024) { "解压文件过大" }
                    output.write(bytes, 0, n)
                }
            }
        }
        if (name.endsWith(".zip")) ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                zip.getInputStream(entry).use { save(entry.name, it) }
            }
        } else {
            require(name.endsWith(".tar.xz")) { "不支持的 FFmpeg 压缩包格式" }
            TarArchiveInputStream(XZInputStream(archive.inputStream().buffered(), 262144)).use { tar ->
                while (true) {
                    checkOpen()
                    val entry = tar.nextEntry ?: break
                    check(!entry.isSymbolicLink && !entry.isLink) { "FFmpeg 压缩包含链接文件" }
                    if (entry.isFile) save(entry.name, tar)
                }
            }
        }
    }

    override fun close() { closed = true; connection?.disconnect() }

    companion object {
        internal fun platform(os: String, arch: String): FfmpegPlatform {
            val x64 = arch.lowercase() in setOf("amd64", "x86_64", "x64")
            val arm64 = arch.lowercase() in setOf("aarch64", "arm64")
            return when {
                os.startsWith("Windows", true) && x64 -> FfmpegPlatform("windows-x64", ".exe")
                os.startsWith("Linux", true) && x64 -> FfmpegPlatform("linux-x64", "", "ffmpeg-master-latest-linux64-gpl.tar.xz")
                os.startsWith("Linux", true) && arm64 -> FfmpegPlatform("linux-arm64", "", "ffmpeg-master-latest-linuxarm64-gpl.tar.xz")
                else -> error("暂不支持自动下载 FFmpeg：$os/$arch。请关闭 conversion.auto-download 并指定 ffmpeg/ffprobe 路径。")
            }
        }
    }
}
