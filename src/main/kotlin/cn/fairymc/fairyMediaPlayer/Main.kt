package cn.fairymc.fairyMediaPlayer

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.Executors

class Main : JavaPlugin() {
    lateinit var playback: PlaybackManager
        private set
    private lateinit var builder: ResourcePackBuilder
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FairyMediaPlayer-Builder").apply { isDaemon = true } }
    private var http: PackHttpServer? = null
    private var httpSettings: PackSettings? = null
    private var building = false
    @Volatile private var closing = false
    override fun onEnable() {
        saveDefaultConfig()
        File(dataFolder, "music").mkdirs()
        builder = ResourcePackBuilder(dataFolder) { logger.info(it) }
        playback = PlaybackManager(this)
        server.pluginManager.registerEvents(playback, this)
        val ui = MusicMenu(this)
        server.pluginManager.registerEvents(ui, this)
        val commands = MusicCommand(this, ui)
        getCommand("fmp")!!.setExecutor(commands)
        getCommand("fmp")!!.tabCompleter = commands
        server.servicesManager.register(FairyMediaPlayerApi::class.java, playback, this, ServicePriority.Normal)
        rebuild(null)
    }
    fun rebuild(sender: CommandSender?) {
        if (building) { sender?.sendMessage(ui("§7正在构建§b音乐资源包§7，请稍候。")); return }
        val next = try {
            Library.read(dataFolder).also { library ->
                val old = httpSettings
                require(old == null || (old.httpEnabled == library.pack.httpEnabled && old.bind == library.pack.bind && old.port == library.pack.port)) {
                    "修改 HTTP 启用状态、监听地址或端口后需要重启服务器"
                }
            }
        } catch (e: Exception) {
            logger.severe("配置加载失败：${e.message}")
            sender?.sendMessage(ui("§c配置加载失败：${e.message}\n§7当前播放配置已保留。"))
            return
        }
        building = true
        sender?.sendMessage(ui("§f正在转换音乐并生成§b音乐资源包§f，请稍候。"))
        worker.submit {
            try {
                val result = builder.build(next)
                if (!closing) Bukkit.getScheduler().runTask(this, Runnable {
                    try {
                        if (next.pack.httpEnabled && http == null) http = PackHttpServer(dataFolder, next.pack)
                        httpSettings = next.pack
                        playback.install(result)
                        logger.info("资源包就绪：${next.songs.size} 首歌曲，${next.playlists.size} 个歌单，SHA-1=${result.sha1}")
                        if (next.pack.publicUrl.contains("127.0.0.1") || next.pack.publicUrl.contains("localhost"))
                            logger.warning("resource-pack.public-url 目前是本机地址，远程玩家无法下载，请配置公网地址。")
                        sender?.sendMessage(ui("§f已加载 §b${next.songs.size} §f首歌曲、§b${next.playlists.size} §f个歌单。\n§7在线玩家已收到音乐资源包，原播放已停止。"))
                    } catch (e: Exception) {
                        logger.severe("资源包启用失败：${e.message}")
                        sender?.sendMessage(ui("§c音乐资源包启用失败：${e.message}"))
                    } finally { building = false }
                })
            } catch (e: Exception) {
                if (!closing) Bukkit.getScheduler().runTask(this, Runnable {
                    building = false
                    logger.severe("资源包构建失败：${e.message}")
                    sender?.sendMessage(ui("§c音乐资源包构建失败：${e.message}\n§7当前播放配置已保留。"))
                })
            }
        }
    }
    override fun onDisable() {
        closing = true
        if (::playback.isInitialized) {
            playback.stopAll()
            playback.pack?.let { pack -> server.onlinePlayers.forEach { it.removeResourcePacks(pack.id) } }
        }
        server.servicesManager.unregisterAll(this)
        if (::builder.isInitialized) builder.close()
        worker.shutdownNow()
        http?.close()
    }
}

